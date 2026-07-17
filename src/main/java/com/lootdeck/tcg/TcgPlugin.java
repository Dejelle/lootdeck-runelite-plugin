package com.lootdeck.tcg;

import com.google.inject.Provides;
import com.lootdeck.tcg.activity.ActivityDetector;
import com.lootdeck.tcg.activity.ActivityType;
import com.lootdeck.tcg.activity.CombatState;
import com.lootdeck.tcg.net.Dtos;
import com.lootdeck.tcg.net.EventQueue;
import com.lootdeck.tcg.net.TcgApiClient;
import com.lootdeck.tcg.ui.FeedbackDialog;
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
import net.runelite.api.Tile;
import net.runelite.api.coords.WorldPoint;
import net.runelite.api.events.ChatMessage;
import net.runelite.api.events.GameStateChanged;
import net.runelite.api.events.HitsplatApplied;
import net.runelite.api.events.InteractingChanged;
import net.runelite.api.events.ItemContainerChanged;
import net.runelite.api.events.PostMenuSort;
import net.runelite.api.events.StatChanged;
import net.runelite.client.Notifier;
import net.runelite.client.callback.ClientThread;
import net.runelite.client.config.ConfigManager;
import net.runelite.client.eventbus.Subscribe;
import net.runelite.client.events.NpcLootReceived;
import net.runelite.client.plugins.loottracker.LootReceived;
import net.runelite.client.plugins.Plugin;
import net.runelite.client.plugins.PluginDescriptor;
import net.runelite.client.ui.ClientToolbar;
import net.runelite.client.ui.NavigationButton;
import net.runelite.client.ui.overlay.OverlayManager;
import net.runelite.client.util.LinkBrowser;
import net.runelite.client.config.RuneScapeProfileType;
import javax.swing.SwingUtilities;
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
	private CombatState combatState;
	@Inject
	private TcgOverlay overlay;
	@Inject
	private com.lootdeck.tcg.ui.GroundPackHighlightOverlay packHighlight;
	@Inject
	private TcgPanel panel;
	@Inject
	private com.lootdeck.tcg.world.GroundPackManager groundPacks;
	@Inject
	private com.lootdeck.tcg.net.ImageCache imageCache;
	// Last card-back URL we fetched, so we only refetch when the served URL changes (Phase 5).
	private volatile String cardBackFetchedFor;
	@Inject
	private net.runelite.client.input.MouseManager mouseManager;

	private com.lootdeck.tcg.ui.PackOpenOverlay openOverlay;
	// Held as a field so shutDown can unregister it — an anonymous listener leaks on every
	// disable/enable cycle and keeps eating reveal clicks after the plugin is gone (audit H2).
	private net.runelite.client.input.MouseAdapter revealClickListener;

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
	// True from the first LOGGED_IN of a session until LOGIN_SCREEN/HOPPING. RuneLite fires
	// LOGGED_IN after EVERY loading screen (region cross, teleport, instance exit) — only the
	// first one per session is a real login; the rest must not reset detector state (audit C1).
	private boolean sessionActive = false;

	// Per-character link status (capped 1:N linking). Starts UNKNOWN = fail-open (reporting allowed)
	// until a status check resolves it. Cached per accountHash (a hash can't change mid-session).
	private volatile LinkState linkState = LinkState.UNKNOWN;
	// Last slot counts from the server, so a late RSN resolve can re-render the panel
	// without refetching status.
	private volatile int lastLinkedCount = 0;
	private volatile int lastMaxAccounts = 0;
	/** Cached link status with an insertion timestamp so stale entries can be re-fetched (L6). */
	private static final class CachedStatus
	{
		final long at;
		final Dtos.LinkStatusResp resp;

		CachedStatus(long at, Dtos.LinkStatusResp resp)
		{
			this.at = at;
			this.resp = resp;
		}
	}

	private static final long LINK_STATUS_TTL_MS = 5 * 60_000L;
	private final Map<String, CachedStatus> linkStatusCache = new java.util.concurrent.ConcurrentHashMap<>();

	// idempotencyKey -> where to spawn the ground pack (captured on the client thread at report time).
	private final Map<String, long[]> pendingDropTiles = new java.util.concurrent.ConcurrentHashMap<>();
	// pendingPackId -> tier (for the Take menu label / claim toast).
	private final Map<String, String> pendingTiers = new java.util.concurrent.ConcurrentHashMap<>();
	// pendingPackId -> its auto-despawn timer, so claiming/taking a pack can cancel it (L7).
	private final Map<String, java.util.concurrent.Future<?>> packTtlTimers = new java.util.concurrent.ConcurrentHashMap<>();
	// Set on a LOADING scene change; onGameTick re-places any known ground packs after the load (M9).
	private boolean respawnPacksPending = false;

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
		overlayManager.add(packHighlight);
		openOverlay = new com.lootdeck.tcg.ui.PackOpenOverlay(client, imageCache, net);
		overlayManager.add(openOverlay);
		revealClickListener = new net.runelite.client.input.MouseAdapter()
		{
			@Override
			public java.awt.event.MouseEvent mousePressed(java.awt.event.MouseEvent e)
			{
				if (!e.isConsumed() && openOverlay != null && openOverlay.isActive())
				{
					openOverlay.advance();
					e.consume();
				}
				return e;
			}
		};
		mouseManager.registerMouseListener(revealClickListener);

		panel.setLinkHandler(this::doLink);
		panel.setClaimHandler(this::doClaim);
		panel.setOpenHandler(this::doOpen);
		panel.setFeedbackHandler(this::doFeedback);
		panel.setReleaseHandler(this::doSelectRelease);
		panel.setImageCache(imageCache);
		panel.setNetExecutor(net);
		overlay.setNetExecutor(net);
		queue.setOnDrop(this::onDrop);

		navButton = NavigationButton.builder()
			.tooltip("LootDeck")
			.icon(makeIcon())
			.priority(8)
			.panel(panel)
			.build();
		clientToolbar.addNavigation(navButton);

		// Not logged in yet (no accountHash), so we can't resolve real state — render optimistically
		// from token presence. onGameStateChanged runs the real status check once a character loads.
		panel.updateLinkState(
			config.token().isEmpty() ? LinkState.NOT_LINKED : LinkState.LINKED, rsn, 0, 0);
		queue.resume();   // undo a pause() from a previous disable (audit H4)
		queue.load();     // idempotent — only appends from disk once per JVM
		refreshFuture = net.scheduleWithFixedDelay(this::refreshPanel, 5, 30, TimeUnit.SECONDS);
		// Plugin enabled while already logged in: no LOGGED_IN event will ever come, so seed
		// the session from current state (audit H3). Must run on the client thread.
		clientThread.invoke(this::seedSessionFromCurrentState);
	}

	/** If the client is already logged in, start the session as if LOGGED_IN just fired (audit H3). */
	void seedSessionFromCurrentState()
	{
		if (client.getGameState() == GameState.LOGGED_IN && !sessionActive)
		{
			sessionActive = true;
			onRealLogin();
		}
	}

	@Override
	protected void shutDown()
	{
		// Ordering contract (audit H4): stop the report pipeline FIRST (no traffic while
		// disabled), then our own network pool, then UI. Reverse order of startUp.
		queue.pause();
		queue.setOnDrop(null);
		if (refreshFuture != null)
		{
			refreshFuture.cancel(true);
			refreshFuture = null;
		}
		if (net != null)
		{
			net.shutdownNow();
			net = null;
		}
		if (revealClickListener != null)
		{
			mouseManager.unregisterMouseListener(revealClickListener);
			revealClickListener = null;
		}
		if (openOverlay != null)
		{
			openOverlay.close();
			overlayManager.remove(openOverlay);
			openOverlay = null;
		}
		overlayManager.remove(overlay);
		overlayManager.remove(packHighlight);
		for (java.util.concurrent.Future<?> t : packTtlTimers.values())
		{
			t.cancel(false);
		}
		packTtlTimers.clear();
		groundPacks.clear();
		pendingTiers.clear();
		pendingDropTiles.clear();
		if (navButton != null)
		{
			clientToolbar.removeNavigation(navButton);
			navButton = null;
		}
		sessionActive = false;
		loggedIn = false;
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
		switch (e.getGameState())
		{
			case LOGGED_IN:
				if (!sessionActive)
				{
					sessionActive = true;
					onRealLogin();
				}
				// else: a scene load (teleport / region cross / instance exit) — ignore.
				break;
			case LOADING:
				// Scene reload invalidates the cosmetic objects but NOT the claims — drop the objects
				// and re-place them on the next GameTick once the new scene is loaded (audit M9).
				groundPacks.despawnObjectsKeepClaims();
				respawnPacksPending = true;
				break;
			case LOGIN_SCREEN:
			case HOPPING:
				sessionActive = false;
				loggedIn = false;
				groundPacks.clear();
				pendingTiers.clear();
				combatState.reset();
				// Reset on session END (not on scene load) so pendingMinigame and the inventory
				// snapshot survive the instance-exit loading screen (audit C1/C2).
				detector.reset();
				break;
			default:
				break;
		}
	}

	/** The once-per-session login block. Also invoked when the plugin is enabled mid-session (P1). */
	private void onRealLogin()
	{
		loggedIn = true;
		Player local = client.getLocalPlayer();
		accountHash = Long.toString(client.getAccountHash());
		rsn = local != null && local.getName() != null ? local.getName() : "";
		RuneScapeProfileType type = RuneScapeProfileType.getCurrent(client);
		profileType = type != null ? type.name() : "STANDARD";
		log.debug("[LootDeck] LOGGED_IN hash={} rsn={} profile={} tokenSet={}",
			accountHash, rsn, profileType, !config.token().isEmpty());
		if (config.token().isEmpty())
		{
			// No token → this character needs linking. Skip the status call (it would 401).
			linkState = LinkState.NOT_LINKED;
			panel.updateLinkState(LinkState.NOT_LINKED, rsn, 0, 0);
		}
		else
		{
			refreshLinkState();
		}
		refreshPanel();
	}

	@Subscribe
	public void onStatChanged(StatChanged e)
	{
		detector.onStatChanged(e);
	}

	@Subscribe
	public void onHitsplatApplied(HitsplatApplied e)
	{
		combatState.onHitsplatApplied(e);
	}

	@Subscribe
	public void onInteractingChanged(InteractingChanged e)
	{
		combatState.onInteractingChanged(e);
	}

	@Subscribe
	public void onGameTick(net.runelite.api.events.GameTick e)
	{
		// Re-place ground packs whose objects were dropped on the last scene load (M9). GameTick
		// runs on the client thread, so this is a direct call — one attempt per scene load.
		if (respawnPacksPending)
		{
			respawnPacksPending = false;
			groundPacks.respawnMissing();
		}
		// The player name often isn't loaded yet at the LOGGED_IN event — resolve it
		// lazily so the sidebar can show "Linked as <name>" instead of a placeholder.
		if (loggedIn && rsn.isEmpty())
		{
			Player local = client.getLocalPlayer();
			if (local != null && local.getName() != null && !local.getName().isEmpty())
			{
				rsn = local.getName();
				panel.updateLinkState(linkState, rsn, lastLinkedCount, lastMaxAccounts);
			}
		}
		// Slow-spin the top-tier ground packs (client thread — GameTick fires there).
		if (config.enableWorldObject())
		{
			groundPacks.tickSpin();
		}
		// Adjudicate deferred, transfer-filtered Sailing-salvage gathers and report them. Salvage
		// is deferred to GameTick because a cargo-hold withdrawal's two container events (inventory
		// gain + hold decrease) can fire in either order within the tick; the net decides the roll.
		if (canReport())
		{
			WorldPoint here = client.getLocalPlayer() != null ? client.getLocalPlayer().getWorldLocation() : null;
			// Now covers all gathers (plain skilling + salvage), adjudicated once per tick (M12).
			for (ActivityType a : detector.flushGathers())
			{
				report(a, here);
			}
			// Completion-gated minigame (Tempoross / Volcanic Mine / Fishing Trawler, DESIGN §6.2):
			// report the completion only once we're back in the overworld, so the pack spawns OUTSIDE
			// the instanced arena at the player's current tile.
			ActivityType minigame = detector.flushMinigame(client.isInInstancedRegion());
			if (minigame != null)
			{
				report(minigame, here);
			}
		}
	}

	@Subscribe
	public void onWorldViewUnloaded(net.runelite.api.events.WorldViewUnloaded e)
	{
		// A boat is a sub-worldview: when it docks/despawns, any pack object on its deck is gone.
		// The server-side PendingPack stays claimable until expiry, so surface it in the sidebar.
		if (e.getWorldView() == null)
		{
			return;
		}
		java.util.List<String> lost = groundPacks.despawnWorldView(e.getWorldView().getId());
		if (!lost.isEmpty())
		{
			for (String pid : lost)
			{
				pendingTiers.remove(pid);
			}
			panel.addFeed("Pack left behind — claim it from the sidebar before it expires.");
			refreshPanel();
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
		log.debug("[LootDeck] npc loot from {} canReport={} (loggedIn={} tokenSet={} profile={} hash={})",
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
		// Only server-originated messages may classify rewards — public/friends/clan chat
		// could otherwise spoof raid/clue/minigame completion lines (audit H1).
		ChatMessageType t = e.getType();
		if (t != ChatMessageType.GAMEMESSAGE && t != ChatMessageType.SPAM && t != ChatMessageType.MESBOX)
		{
			return;
		}
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

	@Subscribe
	public void onLootReceived(LootReceived e)
	{
		// General loot events (chests/rewards). Barrows fires type=EVENT "Barrows" once per chest —
		// that is the droppable moment (the six brothers are excluded in classifyNpcKill).
		if (!canReport())
		{
			return;
		}
		ActivityType a = detector.classifyEventLoot(e.getType() != null ? e.getType().name() : null, e.getName());
		if (a != null)
		{
			WorldPoint here = client.getLocalPlayer() != null ? client.getLocalPlayer().getWorldLocation() : null;
			report(a, here);
		}
	}

	// ---- Reporting ----

	// package-private for tests
	boolean canReport()
	{
		return loggedIn
			&& !config.token().isEmpty()
			&& "STANDARD".equals(profileType)
			&& !accountHash.isEmpty()
			// Under capped 1:N linking, only report for a character that is actually LINKED to us.
			// UNKNOWN = the status check failed → fail open (a flaky check must never block drops);
			// NOT_LINKED / MAX_REACHED / OTHER_USER would 403 and be silently dropped by EventQueue.
			&& (linkState == LinkState.LINKED || linkState == LinkState.UNKNOWN);
	}

	/**
	 * Resolve this character's link status from the server and render it in the sidebar. Runs off
	 * the client thread. FAIL-OPEN: any non-401 failure leaves us UNKNOWN (reporting still allowed) —
	 * a flaky status check must never block drops. A 401 means the token is dead → show the link UI.
	 */
	private void refreshLinkState()
	{
		final String hash = accountHash;
		if (hash.isEmpty())
		{
			return;
		}
		net.submit(() ->
		{
			CachedStatus cached = linkStatusCache.get(hash);
			if (cached != null && System.currentTimeMillis() - cached.at < LINK_STATUS_TTL_MS)
			{
				applyLinkStatus(cached.resp);
				return;
			}
			try
			{
				Dtos.LinkStatusResp resp = api.linkStatus(hash);
				linkStatusCache.put(hash, new CachedStatus(System.currentTimeMillis(), resp));
				applyLinkStatus(resp);
			}
			catch (com.lootdeck.tcg.net.ApiException ex)
			{
				if (ex.getCode() == 401)
				{
					// Token revoked/dead — a definitive signal. Drop to the link UI.
					linkState = LinkState.NOT_LINKED;
					panel.updateLinkState(LinkState.NOT_LINKED, rsn, 0, 0);
				}
				else
				{
					// Network / 5xx / unexpected 4xx (e.g. an old server) → fail open.
					linkState = LinkState.UNKNOWN;
					panel.updateLinkState(LinkState.UNKNOWN, rsn, 0, 0);
				}
			}
		});
	}

	/** Map a status response to a LinkState and push it to the panel. */
	private void applyLinkStatus(Dtos.LinkStatusResp resp)
	{
		final LinkState state;
		if (resp.thisUser)
		{
			state = LinkState.LINKED;
		}
		else if (!resp.linked)
		{
			state = resp.atMax ? LinkState.MAX_REACHED : LinkState.NOT_LINKED;
		}
		else
		{
			state = LinkState.OTHER_USER;
		}
		linkState = state;
		lastLinkedCount = resp.linkedCount;
		lastMaxAccounts = resp.maxAccounts;
		panel.updateLinkState(state, rsn, resp.linkedCount, resp.maxAccounts);
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
		if (a.getItemId() != null || a.getCombatLevel() != null)
		{
			r.context = new Dtos.DropContext(a.getItemId(), Math.max(1, a.getQuantity()), a.getCombatLevel());
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
			final String pid = result.pendingPackId;
			final String key = report.idempotencyKey;
			final String expiresAt = result.expiresAt;
			// The tile fallback reads client.getLocalPlayer(), so the whole spawn block must run on
			// the client thread (audit M11) — onDrop runs on the EventQueue drain thread.
			clientThread.invoke(() ->
			{
				long[] t = pendingDropTiles.remove(key);
				WorldPoint wp = null;
				if (t != null)
				{
					wp = new WorldPoint((int) t[0], (int) t[1], (int) t[2]);
				}
				else if (client.getLocalPlayer() != null)
				{
					wp = client.getLocalPlayer().getWorldLocation();
				}
				if (wp == null)
				{
					return;
				}
				groundPacks.spawn(pid, wp, tier);
				pendingTiers.put(pid, tier);

				// Auto-despawn when the ground timer lapses (mirrors the server sweep).
				long ttlMs = 90_000L;
				try
				{
					if (expiresAt != null)
					{
						ttlMs = Math.max(5_000L,
							java.time.Instant.parse(expiresAt).toEpochMilli() - System.currentTimeMillis());
					}
				}
				catch (Exception ignored)
				{
				}
				// net may be null if the plugin is disabling — guard the schedule (M11).
				final ScheduledExecutorService pool = net;
				if (pool != null)
				{
					java.util.concurrent.Future<?> future = pool.schedule(() ->
					{
						packTtlTimers.remove(pid);
						// isKnown (not isVisible): an off-scene pack whose object despawned is still
						// claimable, so its TTL still applies (audit M9/L7).
						if (groundPacks.isKnown(pid))
						{
							groundPacks.despawn(pid);
							pendingTiers.remove(pid);
							panel.addFeed("A pack despawned.");
						}
					}, ttlMs, java.util.concurrent.TimeUnit.MILLISECONDS);
					packTtlTimers.put(pid, future);
				}
			});
		}
		refreshPanel();
	}

	/**
	 * Inject the "Take Booster Pack" entry while the pack's tile is hovered. PostMenuSort
	 * fires once per menu build (every frame), and the menu is rebuilt from scratch each
	 * time, so a single createMenuEntry here can never stack duplicates — the failure mode
	 * that ruled out MenuEntryAdded. RUNELITE_HIGH_PRIORITY sorts above "Walk here", making
	 * the entry the left-click default; the plain RUNELITE type keeps it right-click-only.
	 */
	@Subscribe
	public void onPostMenuSort(PostMenuSort e)
	{
		if (!config.enableWorldObject() || client.getLocalPlayer() == null)
		{
			return;
		}
		// While in combat, don't inject at all — a misclick would path the player
		// toward the pack tile and interrupt the current action.
		if (config.blockPackActionsInCombat() && combatState.isInCombat())
		{
			return;
		}
		final Tile sel = client.getSelectedSceneTile();
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
					.setType(config.leftClickTakePack()
						? MenuAction.RUNELITE_HIGH_PRIORITY
						: MenuAction.RUNELITE)
					.onClick(me -> takeGroundPack(pendingId));
				return; // one pack per tile per menu build
			}
		}
	}

	/** Cancel a pack's auto-despawn timer (it was just taken/claimed) so it can't fire late (L7). */
	private void cancelPackTtl(String pendingPackId)
	{
		java.util.concurrent.Future<?> t = packTtlTimers.remove(pendingPackId);
		if (t != null)
		{
			t.cancel(false);
		}
	}

	private void takeGroundPack(String pendingPackId)
	{
		net.submit(() ->
		{
			cancelPackTtl(pendingPackId);
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
				// Re-resolve real state (LINKED + accurate slot counts) from the server.
				linkStatusCache.remove(accountHash);
				refreshLinkState();
				panel.addFeed("Linked successfully.");
				refreshPanel();
			}
			catch (com.lootdeck.tcg.net.ApiException ex)
			{
				String msg = ex.getMessage() == null ? "" : ex.getMessage();
				if (ex.getCode() == 409 && msg.contains("max_accounts_reached"))
				{
					panel.addFeed("Max linked accounts reached — manage characters at lootdeck.org/link");
					linkState = LinkState.MAX_REACHED;
					// A token exists (you can only hit the cap when already linked), so refine the
					// slot counts from the server; falls back to the plain state if that fails.
					panel.updateLinkState(LinkState.MAX_REACHED, rsn, 0, 0);
					refreshLinkState();
				}
				else if (msg.contains("osrs_account_already_linked"))
				{
					panel.addFeed("This character is already linked to a different LootDeck account.");
					linkState = LinkState.OTHER_USER;
					panel.updateLinkState(LinkState.OTHER_USER, rsn, 0, 0);
				}
				else
				{
					panel.addFeed("Link failed: " + msg);
					panel.updateLinkState(LinkState.NOT_LINKED, rsn, 0, 0);
				}
			}
			catch (Exception ex)
			{
				panel.addFeed("Link failed: " + ex.getMessage());
				panel.updateLinkState(LinkState.NOT_LINKED, rsn, 0, 0);
			}
		});
	}

	private void doClaim(String pendingId)
	{
		net.submit(() ->
		{
			cancelPackTtl(pendingId);
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
		if (config.blockPackActionsInCombat() && combatState.isInCombat())
		{
			panel.addFeed("You are in combat — pack opening is blocked.");
			// packCell disabled the button before invoking this handler; the panel refresh
			// rebuilds the pack grid (same call the API error path below uses) so it re-enables.
			refreshPanel();
			return;
		}
		if (openOverlay != null && openOverlay.isActive())
		{
			// Opening pack B would server-open it and destroy pack A's on-screen reveal (audit M8).
			panel.addFeed("Finish revealing your current pack first.");
			refreshPanel(); // re-enables the pack button the panel disabled
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
	 * Open the in-client feedback dialog (linked users) or the website form (unlinked). Bug reports
	 * are most useful at the moment something breaks in the client, so we keep the user in-client and
	 * attach version/world/rsn context; unlinked users have no token, so they fall back to the web form.
	 */
	private void doFeedback()
	{
		if (config.token().isEmpty())
		{
			LinkBrowser.browse(TcgPanel.FEEDBACK_URL);
			return;
		}
		SwingUtilities.invokeLater(() ->
		{
			FeedbackDialog dialog = new FeedbackDialog(panel, (kind, message) ->
				net.submit(() ->
				{
					try
					{
						Dtos.FeedbackReq req = new Dtos.FeedbackReq();
						req.kind = kind;
						req.message = message;
						req.context = buildFeedbackContext();
						api.submitFeedback(req);
						panel.addFeed("Feedback sent - thanks!");
					}
					catch (Exception ex)
					{
						panel.addFeed("Feedback failed: " + ex.getMessage());
					}
				}));
			dialog.setVisible(true);
		});
	}

	/** Minimal client context stamped onto a plugin feedback report. No PII beyond RSN/world. */
	private Map<String, String> buildFeedbackContext()
	{
		Map<String, String> ctx = new HashMap<>();
		ctx.put("pluginVersion", TcgApiClient.pluginVersion());
		Player local = client.getLocalPlayer();
		if (client.getGameState() == GameState.LOGGED_IN && local != null)
		{
			if (local.getName() != null)
			{
				ctx.put("rsn", local.getName());
			}
			ctx.put("world", String.valueOf(client.getWorld()));
		}
		return ctx;
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
			maybeFetchCardBack(api.cardBackUrl());
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
			// World objects on: only surface packs whose cosmetic object was LOST (e.g. the boat
			// worldview unloaded before pickup) — in-world packs keep the "Take" flow.
			try
			{
				java.util.List<Dtos.PendingPack> pend = api.listPending();
				java.util.List<Dtos.PendingPack> lost = new java.util.ArrayList<>();
				for (Dtos.PendingPack p : pend)
				{
					// off-scene packs (object despawned) show in the sidebar with their Take button (M9)
					if (p != null && p.id != null && !groundPacks.isVisible(p.id))
					{
						lost.add(p);
					}
				}
				panel.updatePending(lost);
			}
			catch (Exception ignored)
			{
				// leave pending as-is
			}
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

	/**
	 * Fetch the CDN card back off-thread when the served URL changes, and hand it to PackArt so it
	 * overrides the bundled back (Phase 5 — a new card back with no plugin release). Mirrors how
	 * PackOpenOverlay fetches pack art. Any failure clears the marker so the next poll retries, and
	 * PackArt.cardBack() keeps returning the bundled fallback until then.
	 */
	private void maybeFetchCardBack(String url)
	{
		if (url == null || url.equals(cardBackFetchedFor) || imageCache == null)
		{
			return;
		}
		final ScheduledExecutorService pool = net;
		if (pool == null)
		{
			cardBackFetchedFor = null;
			return;
		}
		cardBackFetchedFor = url;
		final String png = com.lootdeck.tcg.net.ImageCache.pngUrl(url);
		pool.submit(() ->
		{
			java.awt.image.BufferedImage img = imageCache.get(png);
			if (img != null)
			{
				com.lootdeck.tcg.ui.PackArt.setFetchedCardBack(img);
			}
			else
			{
				cardBackFetchedFor = null; // let the next poll retry on failure
			}
		});
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
