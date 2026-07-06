package com.lootdeck.tcg;

import com.google.inject.Provides;
import com.lootdeck.tcg.activity.ActivityDetector;
import com.lootdeck.tcg.activity.ActivityType;
import com.lootdeck.tcg.net.Dtos;
import com.lootdeck.tcg.net.EventQueue;
import com.lootdeck.tcg.net.TcgApiClient;
import com.lootdeck.tcg.ui.TcgOverlay;
import com.lootdeck.tcg.ui.TcgPanel;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import javax.inject.Inject;
import net.runelite.api.ChatMessageType;
import net.runelite.api.Client;
import net.runelite.api.GameState;
import net.runelite.api.MenuAction;
import net.runelite.api.Player;
import net.runelite.api.coords.WorldPoint;
import net.runelite.api.events.ChatMessage;
import net.runelite.api.events.GameStateChanged;
import net.runelite.api.events.ItemContainerChanged;
import net.runelite.api.events.MenuOpened;
import net.runelite.api.events.StatChanged;
import net.runelite.client.Notifier;
import net.runelite.client.callback.ClientThread;
import net.runelite.client.config.ConfigManager;
import net.runelite.client.eventbus.Subscribe;
import net.runelite.client.events.NpcLootReceived;
import net.runelite.client.plugins.Plugin;
import net.runelite.client.plugins.PluginDescriptor;
import net.runelite.client.ui.ClientToolbar;
import net.runelite.client.ui.NavigationButton;
import net.runelite.client.ui.overlay.OverlayManager;
import net.runelite.client.config.RuneScapeProfileType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

@PluginDescriptor(
	name = "LootDeck",
	description = "Earn collectible cards while you play. Cosmetic; reports gameplay (opt-in).",
	tags = {"collection", "cards", "cosmetic", "external"}
)
public class TcgPlugin extends Plugin
{
	private static final Logger log = LoggerFactory.getLogger(TcgPlugin.class);

	@Inject
	private Client client;
	@Inject
	private ClientThread clientThread;
	@Inject
	private TcgConfig config;
	@Inject
	private ClientToolbar clientToolbar;
	@Inject
	private OverlayManager overlayManager;
	@Inject
	private Notifier notifier;
	@Inject
	private TcgApiClient api;
	@Inject
	private EventQueue queue;
	@Inject
	private ActivityDetector detector;
	@Inject
	private TcgOverlay overlay;
	@Inject
	private TcgPanel panel;
	@Inject
	private com.lootdeck.tcg.world.GroundPackManager groundPacks;
	@Inject
	private com.lootdeck.tcg.net.ImageCache imageCache;
	@Inject
	private net.runelite.client.input.MouseManager mouseManager;

	private com.lootdeck.tcg.ui.PackOpenOverlay openOverlay;

	private NavigationButton navButton;
	private ScheduledFuture<?> refreshFuture;
	// Our OWN pool for all HTTP/panel work — never RuneLite's shared single-thread executor.
	// 3 threads so a slow refresh can't block Take/Open (and vice-versa).
	private ScheduledExecutorService net;
	// Coalesce refreshes: at most one running + one pending, so bursts of triggers (login,
	// drop, take, open, 30s timer) never pile up sequential HTTP round-trips.
	private final AtomicBoolean refreshing = new AtomicBoolean(false);
	private final AtomicBoolean refreshQueued = new AtomicBoolean(false);

	// Cached account state (refreshed on login).
	private String accountHash = "";
	private String rsn = "";
	private String profileType = "STANDARD";
	private boolean loggedIn = false;

	// idempotencyKey -> where to spawn the ground pack (captured on the client thread at report time).
	private final Map<String, long[]> pendingDropTiles = new java.util.concurrent.ConcurrentHashMap<>();
	// pendingPackId -> tier (for the Take menu label / claim toast).
	private final Map<String, String> pendingTiers = new java.util.concurrent.ConcurrentHashMap<>();

	@Override
	protected void startUp()
	{
		net = Executors.newScheduledThreadPool(3, r ->
		{
			Thread t = new Thread(r, "lootdeck-net");
			t.setDaemon(true);
			return t;
		});
		overlayManager.add(overlay);
		openOverlay = new com.lootdeck.tcg.ui.PackOpenOverlay(client, imageCache);
		overlayManager.add(openOverlay);
		mouseManager.registerMouseListener(new net.runelite.client.input.MouseAdapter()
		{
			@Override
			public java.awt.event.MouseEvent mousePressed(java.awt.event.MouseEvent e)
			{
				if (openOverlay != null && openOverlay.isActive())
				{
					openOverlay.advance();
					e.consume();
				}
				return e;
			}
		});

		panel.setLinkHandler(this::doLink);
		panel.setClaimHandler(this::doClaim);
		panel.setRefreshHandler(this::refreshPanel);
		panel.setOpenHandler(this::doOpen);
		panel.setReleaseHandler(this::doSelectRelease);
		panel.setImageCache(imageCache);
		queue.setOnDrop(this::onDrop);

		navButton = NavigationButton.builder()
			.tooltip("LootDeck")
			.icon(makeIcon())
			.priority(8)
			.panel(panel)
			.build();
		clientToolbar.addNavigation(navButton);

		panel.updateLinked(!config.token().isEmpty(), rsn);
		queue.load();
		refreshFuture = net.scheduleWithFixedDelay(this::refreshPanel, 5, 30, TimeUnit.SECONDS);
	}

	@Override
	protected void shutDown()
	{
		overlayManager.remove(overlay);
		if (openOverlay != null)
		{
			overlayManager.remove(openOverlay);
		}
		groundPacks.clear();
		if (navButton != null)
		{
			clientToolbar.removeNavigation(navButton);
		}
		if (refreshFuture != null)
		{
			refreshFuture.cancel(true);
		}
		if (net != null)
		{
			net.shutdownNow();
		}
	}

	@Provides
	TcgConfig provideConfig(ConfigManager configManager)
	{
		return configManager.getConfig(TcgConfig.class);
	}

	// ---- Events ----

	@Subscribe
	public void onGameStateChanged(GameStateChanged e)
	{
		if (e.getGameState() == GameState.LOGGED_IN)
		{
			loggedIn = true;
			Player local = client.getLocalPlayer();
			accountHash = Long.toString(client.getAccountHash());
			rsn = local != null && local.getName() != null ? local.getName() : "";
			RuneScapeProfileType type = RuneScapeProfileType.getCurrent(client);
			profileType = type != null ? type.name() : "STANDARD";
			detector.reset();
			log.info("[LootDeck] LOGGED_IN hash={} rsn={} profile={} tokenSet={}",
				accountHash, rsn, profileType, !config.token().isEmpty());
			panel.updateLinked(!config.token().isEmpty(), rsn);
			refreshPanel();
		}
		else if (e.getGameState() == GameState.LOGIN_SCREEN || e.getGameState() == GameState.HOPPING)
		{
			loggedIn = false;
			groundPacks.clear();
			pendingTiers.clear();
		}
	}

	@Subscribe
	public void onStatChanged(StatChanged e)
	{
		detector.onStatChanged(e);
	}

	@Subscribe
	public void onGameTick(net.runelite.api.events.GameTick e)
	{
		// Slow-spin the top-tier ground packs (client thread — GameTick fires there).
		if (config.enableWorldObject())
		{
			groundPacks.tickSpin();
		}
	}

	@Subscribe
	public void onItemContainerChanged(ItemContainerChanged e)
	{
		if (!canReport())
		{
			return;
		}
		WorldPoint here = client.getLocalPlayer() != null ? client.getLocalPlayer().getWorldLocation() : null;
		for (ActivityType a : detector.onItemContainerChanged(e))
		{
			report(a, here);
		}
	}

	@Subscribe
	public void onNpcLootReceived(NpcLootReceived e)
	{
		final String npcName = e.getNpc() != null ? e.getNpc().getName() : "?";
		log.info("[LootDeck] npc loot from {} canReport={} (loggedIn={} tokenSet={} profile={} hash={})",
			npcName, canReport(), loggedIn, !config.token().isEmpty(), profileType, accountHash);
		if (!canReport())
		{
			return;
		}
		ActivityType a = detector.onNpcKill(e.getNpc());
		if (a != null)
		{
			WorldPoint tile = e.getNpc() != null ? e.getNpc().getWorldLocation() : null;
			report(a, tile);
		}
	}

	@Subscribe
	public void onChatMessage(ChatMessage e)
	{
		if (!canReport())
		{
			return;
		}
		ActivityType a = detector.onChatMessage(e);
		if (a != null)
		{
			WorldPoint here = client.getLocalPlayer() != null ? client.getLocalPlayer().getWorldLocation() : null;
			report(a, here);
		}
	}

	// ---- Reporting ----

	private boolean canReport()
	{
		return loggedIn
			&& !config.token().isEmpty()
			&& "STANDARD".equals(profileType)
			&& !accountHash.isEmpty();
	}

	private void report(ActivityType a)
	{
		report(a, null);
	}

	private void report(ActivityType a, WorldPoint tile)
	{
		Dtos.DropReport r = new Dtos.DropReport();
		r.idempotencyKey = UUID.randomUUID().toString();
		r.accountHash = accountHash;
		r.rsn = rsn;
		r.profileType = profileType;
		r.activity = a.getId();
		r.clientTs = System.currentTimeMillis();
		if (a.getItemId() != null)
		{
			r.context = new Dtos.DropContext(a.getItemId(), Math.max(1, a.getQuantity()));
		}
		if (tile != null)
		{
			pendingDropTiles.put(r.idempotencyKey,
				new long[]{tile.getX(), tile.getY(), tile.getPlane(), System.currentTimeMillis()});
		}
		// Bound the map: drop anything older than 60s (network never takes that long).
		final long cutoff = System.currentTimeMillis() - 60_000;
		pendingDropTiles.values().removeIf(v -> v[3] < cutoff);
		log.info("[LootDeck] enqueue report activity={} item={}", a.getId(), a.getItemId());
		queue.enqueue(r);
	}

	private void onDrop(Dtos.DropReport report, Dtos.DropResult result)
	{
		final String tier = result.tier != null ? result.tier : "";
		overlay.trigger(tier, result.packArtUrl);
		final String line = "You found a Booster Pack (" + capitalize(tier)
			+ " tier)! Take it before it despawns.";
		clientThread.invoke(() ->
			client.addChatMessage(ChatMessageType.GAMEMESSAGE, "", line, null));
		notifier.notify(line);
		panel.addFeed("Pack dropped: " + tier);

		// Spawn the cosmetic ground object at the captured tile (fallback: beside the player).
		if (config.enableWorldObject() && result.pendingPackId != null)
		{
			long[] t = pendingDropTiles.remove(report.idempotencyKey);
			WorldPoint wp = null;
			if (t != null)
			{
				wp = new WorldPoint((int) t[0], (int) t[1], (int) t[2]);
			}
			else if (client.getLocalPlayer() != null)
			{
				wp = client.getLocalPlayer().getWorldLocation();
			}
			if (wp != null)
			{
				groundPacks.spawn(result.pendingPackId, wp, tier);
				pendingTiers.put(result.pendingPackId, tier);

				// Auto-despawn when the ground timer lapses (mirrors the server sweep).
				final String pid = result.pendingPackId;
				long ttlMs = 90_000L;
				try
				{
					if (result.expiresAt != null)
					{
						ttlMs = Math.max(5_000L,
							java.time.Instant.parse(result.expiresAt).toEpochMilli() - System.currentTimeMillis());
					}
				}
				catch (Exception ignored)
				{
				}
				net.schedule(() ->
				{
					if (groundPacks.has(pid))
					{
						groundPacks.despawn(pid);
						pendingTiers.remove(pid);
						panel.addFeed("A pack despawned.");
					}
				}, ttlMs, java.util.concurrent.TimeUnit.MILLISECONDS);
			}
		}
		refreshPanel();
	}

	@Subscribe
	public void onMenuOpened(MenuOpened e)
	{
		if (!config.enableWorldObject() || client.getLocalPlayer() == null)
		{
			return;
		}
		// Add the "Take" exactly ONCE per right-click. The old handler used MenuEntryAdded, which
		// fires once for EVERY entry the game already put on the tile (Take rune, Take bones, Walk
		// here, Examine…), so we appended a duplicate "Take Booster Pack" for each one — hence the
		// stack of copies. MenuOpened fires once when the menu opens, so we add a single entry.
		final net.runelite.api.Tile sel = client.getSelectedSceneTile();
		if (sel == null)
		{
			return;
		}
		final WorldPoint hover = sel.getWorldLocation();
		for (Map.Entry<String, String> entry : pendingTiers.entrySet())
		{
			final String pendingId = entry.getKey();
			final WorldPoint packTile = groundPacks.tileOf(pendingId);
			if (packTile != null && packTile.equals(hover))
			{
				client.createMenuEntry(-1)
					.setOption("Take")
					.setTarget("<col=ffde9b>Booster Pack (" + entry.getValue() + ")</col>")
					.setType(MenuAction.RUNELITE)
					.onClick(me -> takeGroundPack(pendingId));
				return; // one pack per tile is enough; take it, then the next reveals on re-click
			}
		}
	}

	private void takeGroundPack(String pendingPackId)
	{
		net.submit(() ->
		{
			try
			{
				api.claim(pendingPackId);
				groundPacks.despawn(pendingPackId);
				pendingTiers.remove(pendingPackId);
				panel.addFeed("Picked up a pack!");
				refreshPanel();
			}
			catch (com.lootdeck.tcg.net.ApiException ex)
			{
				if (ex.getCode() == 410)
				{
					panel.addFeed("The pack despawned.");
				}
				else
				{
					panel.addFeed("Take failed: " + ex.getMessage());
				}
				groundPacks.despawn(pendingPackId);
				pendingTiers.remove(pendingPackId);
				refreshPanel();
			}
		});
	}

	// ---- Panel actions (off-thread) ----

	private void doLink(String code)
	{
		net.submit(() ->
		{
			try
			{
				Dtos.TokenResp resp = api.redeem(code, accountHash, rsn, profileType);
				config.setToken(resp.token);
				panel.updateLinked(true, rsn);
				panel.addFeed("Linked successfully.");
				refreshPanel();
			}
			catch (Exception ex)
			{
				panel.addFeed("Link failed: " + ex.getMessage());
				panel.updateLinked(false, rsn);
			}
		});
	}

	private void doClaim(String pendingId)
	{
		net.submit(() ->
		{
			try
			{
				api.claim(pendingId);
				panel.addFeed("Claimed a pack!");
				refreshPanel();
			}
			catch (com.lootdeck.tcg.net.ApiException ex)
			{
				if (ex.getCode() == 410)
				{
					panel.addFeed("The pack despawned.");
				}
				else
				{
					panel.addFeed("Claim failed: " + ex.getMessage());
				}
				refreshPanel();
			}
		});
	}

	private void doOpen(Dtos.UserPack pack)
	{
		if (!config.enableInClientOpen())
		{
			panel.addFeed("Open packs on the website (in-client open is off).");
			return;
		}
		net.submit(() ->
		{
			try
			{
				Dtos.OpenResp resp = api.openPack(pack.id);
				List<Dtos.OpenedCard> items = resp != null ? resp.items : null;
				if (items == null || items.isEmpty())
				{
					panel.addFeed("Open returned no cards.");
					return;
				}
				// Show the reveal immediately; the intro animation covers the art fetch and each
				// face falls back to a placeholder until its image lands — so we never block here.
				openOverlay.show(pack.tier, pack.packArtUrl, items);
				panel.addFeed("Opened a " + pack.tier + " pack.");
				// Pre-warm art in the background (one task per card) so reveals are instant.
				for (Dtos.OpenedCard c : items)
				{
					if (c.definition != null)
					{
						final String url = com.lootdeck.tcg.net.ImageCache.pngUrl(c.definition.baseImageUrl);
						net.submit(() -> imageCache.get(url));
					}
				}
				refreshPanel();
			}
			catch (com.lootdeck.tcg.net.ApiException ex)
			{
				// 409 already-opened → show the stored result instead of erroring.
				panel.addFeed("Open failed: " + ex.getMessage());
				refreshPanel();
			}
		});
	}

	/**
	 * Refresh the panel from the server. Coalesced: if a refresh is already running we just mark
	 * that another is wanted, so a burst of triggers (login, drop, take, open, 30s timer) collapses
	 * into at most one extra pass. Runs on our own pool — never the client thread.
	 */
	private void refreshPanel()
	{
		if (config.token().isEmpty() || net == null)
		{
			return;
		}
		refreshQueued.set(true);
		if (refreshing.compareAndSet(false, true))
		{
			net.submit(this::refreshLoop);
		}
	}

	private void refreshLoop()
	{
		try
		{
			while (refreshQueued.compareAndSet(true, false))
			{
				refreshOnce();
			}
		}
		finally
		{
			refreshing.set(false);
			// Service a trigger that raced in after the last drain.
			if (refreshQueued.get() && refreshing.compareAndSet(false, true))
			{
				net.submit(this::refreshLoop);
			}
		}
	}

	private void refreshOnce()
	{
		try
		{
			List<Dtos.UserPack> packs = api.listPacks();
			Map<String, Integer> counts = new HashMap<>();
			for (Dtos.UserPack p : packs)
			{
				counts.merge(p.tier, 1, Integer::sum);
			}
			panel.updatePacks(counts);
			panel.updatePackList(packs);
		}
		catch (Exception ignored)
		{
			// leave counts as-is
		}
		// Ground packs live in the game world only — don't also list them as a "Take" row in the
		// sidebar. Only fall back to the sidebar list when the world object is turned off.
		if (config.enableWorldObject())
		{
			panel.updatePending(java.util.Collections.emptyList());
		}
		else
		{
			try
			{
				panel.updatePending(api.listPending());
			}
			catch (Exception ignored)
			{
				// leave pending as-is
			}
		}
		try
		{
			panel.updateOpenings(api.listOpenings(3));
		}
		catch (Exception ignored)
		{
			// leave openings as-is
		}
		try
		{
			Dtos.ReleasesResp rel = api.listReleases();
			if (rel != null)
			{
				panel.updateReleases(rel.releases, rel.selected);
			}
		}
		catch (Exception ignored)
		{
			// leave the release picker as-is
		}
	}

	/** Push the chosen pack release to the API; re-sync the picker with the server's answer. */
	private void doSelectRelease(String setCode)
	{
		net.execute(() ->
		{
			try
			{
				api.selectRelease(setCode);
			}
			catch (Exception e)
			{
				panel.addFeed("Release change failed");
			}
			try
			{
				Dtos.ReleasesResp rel = api.listReleases();
				if (rel != null)
				{
					panel.updateReleases(rel.releases, rel.selected);
				}
			}
			catch (Exception ignored)
			{
				// next 30s refresh will re-sync
			}
		});
	}

	// ---- Misc ----

	private static String capitalize(String s)
	{
		return s == null || s.isEmpty() ? s : Character.toUpperCase(s.charAt(0)) + s.substring(1);
	}

	private static BufferedImage makeIcon()
	{
		BufferedImage img = new BufferedImage(24, 24, BufferedImage.TYPE_INT_ARGB);
		Graphics2D g = img.createGraphics();
		g.setColor(new Color(0xC3, 0x96, 0x40));
		g.fillRoundRect(2, 1, 20, 22, 6, 6);
		g.setColor(new Color(0x1a, 0x13, 0x0a));
		g.setFont(g.getFont().deriveFont(java.awt.Font.BOLD, 14f));
		g.drawString("L", 8, 17);
		g.dispose();
		return img;
	}
}
