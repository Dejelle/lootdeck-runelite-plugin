package com.lootdeck.tcg.world;

import com.lootdeck.tcg.ui.PackArt;
import java.awt.Color;
import java.util.concurrent.ConcurrentHashMap;
import java.util.Map;
import javax.inject.Inject;
import javax.inject.Singleton;
import net.runelite.api.Client;
import net.runelite.api.ItemComposition;
import net.runelite.api.JagexColor;
import net.runelite.api.Model;
import net.runelite.api.ModelData;
import net.runelite.api.RuneLiteObject;
import net.runelite.api.coords.LocalPoint;
import net.runelite.api.coords.WorldPoint;
import net.runelite.client.callback.ClientThread;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Spawns cosmetic, client-side pack objects on the drop tile. 100% local — other players
 * never see them and they never affect gameplay. All scene mutation runs on the client thread.
 *
 * <p>The pack mesh is the "Primed bar" (item 9727, an obscure quest ingot) recolored per tier.
 * We can't ship a custom mesh — RuneLite only renders cache geometry — so we take the bar's
 * inventory model and remap every face colour to the tier hue/saturation while keeping each
 * face's original luminance, so the mesh's shading (highlights/shadows) survives the recolour.
 */
@Singleton
public class GroundPackManager
{
	private static final Logger log = LoggerFactory.getLogger(GroundPackManager.class);

	// The "Primed bar" quest item; we render its inventory model, recoloured per tier.
	private static final int PRIMED_BAR_ITEM_ID = 9727;
	// Fallback cache model if the primed bar can't be resolved/recoloured (casket-like present).
	private static final int FALLBACK_MODEL_ID = 2426;
	// Mesh scale in 1/128 units (128 = original). A bar is small on a tile, so enlarge it a bit.
	private static final int PACK_SCALE = 160;
	// Orientation step per tick. 2048 = full turn. Dragon spins faster than the rest.
	private static final int SPIN_STEP = 32;
	private static final int SPIN_STEP_SLOW = 12;
	private static final String TOP_TIER = "dragon";

	private final Client client;
	private final ClientThread clientThread;

	// pendingPackId -> spawned object (so Take/despawn can find it).
	private final Map<String, RuneLiteObject> objects = new ConcurrentHashMap<>();
	// pendingPackId -> world tile it was spawned on (RuneLiteObject has no getLocation() in this API).
	private final Map<String, WorldPoint> tiles = new ConcurrentHashMap<>();
	// pendingPackId -> tier, so per-tick effects (spin) know which objects to animate.
	private final Map<String, String> tierById = new ConcurrentHashMap<>();
	// pendingPackId -> worldview id the object was registered into (boats are sub-worldviews).
	private final Map<String, Integer> wvById = new ConcurrentHashMap<>();
	// tier -> built+lit model, cached (geometry is read-only and safe to share across objects).
	private final Map<String, Model> modelCache = new ConcurrentHashMap<>();
	// Resolved once: the bar's inventory model id (-1 = unresolved, -2 = resolution failed).
	private volatile int barModelId = -1;

	@Inject
	public GroundPackManager(Client client, ClientThread clientThread)
	{
		this.client = client;
		this.clientThread = clientThread;
	}

	/** Spawn a pack object at a world tile. No-op (logged) if the scene can't place it. */
	public void spawn(String pendingPackId, WorldPoint wp, String tier)
	{
		if (pendingPackId == null || wp == null)
		{
			return;
		}
		clientThread.invoke(() -> spawnOnClientThread(pendingPackId, wp, tier));
	}

	/** The actual scene mutation. MUST run on the client thread (spawn / respawnMissing call it). */
	private void spawnOnClientThread(String pendingPackId, WorldPoint wp, String tier)
	{
		try
		{
			LocalPoint lp = LocalPoint.fromWorld(client, wp);
			if (lp == null)
			{
				log.debug("[LootDeck] drop tile off-scene; skipping world object");
				return;
			}
			Model model = modelFor(tier);
			if (model == null)
			{
				log.debug("[LootDeck] pack model not loaded; skipping world object");
				return;
			}
			RuneLiteObject o = client.createRuneLiteObject();
			o.setModel(model);
			// Use the WorldPoint's plane, NOT client.getPlane() (the top-level plane): when lp
			// resolves into a boat sub-worldview, LocalPoint.fromWorld already matched wp's plane,
			// and the top-level plane would be wrong — the object rides the boat's worldview.
			o.setLocation(lp, wp.getPlane());
			// Sits flush on the ground tile — setLocation already put z at the tile's ground
			// height. We deliberately DON'T lift it into the air: it reads as a real ground drop
			// and stays visually anchored to its highlight. Visibility among other loot comes
			// from the GroundPackHighlightOverlay (pulsing tile + label) and the per-tick spin.
			o.setActive(true);
			objects.put(pendingPackId, o);
			tiles.put(pendingPackId, wp);
			tierById.put(pendingPackId, tier == null ? "" : tier);
			wvById.put(pendingPackId, lp.getWorldView());
		}
		catch (Throwable t)
		{
			// Cosmetic only — never let a scene error escape.
			log.warn("[LootDeck] world object spawn failed: {}", t.toString());
		}
	}

	/** Rotate ground packs a touch each tick — dragon faster. Must be called on the client thread. */
	public void tickSpin()
	{
		if (objects.isEmpty())
		{
			return;
		}
		for (Map.Entry<String, RuneLiteObject> e : objects.entrySet())
		{
			int step = TOP_TIER.equals(tierById.get(e.getKey())) ? SPIN_STEP : SPIN_STEP_SLOW;
			RuneLiteObject o = e.getValue();
			try
			{
				o.setOrientation((o.getOrientation() + step) & 2047);
			}
			catch (Throwable ignored)
			{
				// cosmetic — ignore
			}
		}
	}

	/** Build (or fetch cached) the lit, recoloured model for a tier. Client thread only. */
	private Model modelFor(String tier)
	{
		final String key = tier == null ? "" : tier;
		Model cached = modelCache.get(key);
		if (cached != null)
		{
			return cached;
		}

		Model built = buildTierModel(key);
		if (built == null)
		{
			// Last-ditch fallback: the plain placeholder model, uncached so a later success can win.
			return client.loadModel(FALLBACK_MODEL_ID);
		}
		modelCache.put(key, built);
		return built;
	}

	private Model buildTierModel(String tier)
	{
		try
		{
			int modelId = resolveBarModelId();
			if (modelId < 0)
			{
				return null;
			}
			ModelData data = client.loadModelData(modelId);
			if (data == null)
			{
				return null;
			}
			data = data.cloneColors();

			// Remap every distinct face colour to the tier hue/sat, preserving per-face luminance.
			Color tierColor = PackArt.tierColor(tier);
			short tierHsl = JagexColor.rgbToHSL(tierColor.getRGB() & 0xFFFFFF, 1.0d);
			int hue = JagexColor.unpackHue(tierHsl);
			int sat = JagexColor.unpackSaturation(tierHsl);

			short[] faces = data.getFaceColors();
			java.util.Set<Short> seen = new java.util.HashSet<>();
			if (faces != null)
			{
				for (short src : faces)
				{
					if (seen.add(src))
					{
						int lum = JagexColor.unpackLuminance(src);
						data.recolor(src, JagexColor.packHSL(hue, sat, lum));
					}
				}
			}

			data.cloneVertices().scale(PACK_SCALE, PACK_SCALE, PACK_SCALE);
			return data.light();
		}
		catch (Throwable t)
		{
			log.warn("[LootDeck] tier model build failed for {}: {}", tier, t.toString());
			return null;
		}
	}

	/** Resolve the primed bar's inventory model id once. Client thread only. */
	private int resolveBarModelId()
	{
		int id = barModelId;
		if (id != -1)
		{
			return id; // resolved (>=0) or previously failed (-2)
		}
		try
		{
			ItemComposition comp = client.getItemDefinition(PRIMED_BAR_ITEM_ID);
			int model = comp != null ? comp.getInventoryModel() : -1;
			barModelId = model >= 0 ? model : -2;
		}
		catch (Throwable t)
		{
			log.warn("[LootDeck] could not resolve primed bar model: {}", t.toString());
			barModelId = -2;
		}
		return barModelId >= 0 ? barModelId : -2;
	}

	public boolean has(String pendingPackId)
	{
		return objects.containsKey(pendingPackId);
	}

	/** True if the cosmetic object is currently spawned in the scene (audit M9). */
	public boolean isVisible(String pendingPackId)
	{
		return objects.containsKey(pendingPackId);
	}

	/** True if we still track this pack's claim (its tile is known), even if its object despawned. */
	public boolean isKnown(String pendingPackId)
	{
		return tiles.containsKey(pendingPackId);
	}

	/**
	 * Drop the cosmetic objects (a scene reload invalidated them) but KEEP the claim bookkeeping
	 * (tiles/tierById/wvById) so the pack is still claimable and respawnMissing can re-place it
	 * once the new scene is loaded (audit M9). Must be paired with respawnMissing() on the next tick.
	 */
	public void despawnObjectsKeepClaims()
	{
		for (Map.Entry<String, RuneLiteObject> e : objects.entrySet())
		{
			final RuneLiteObject o = e.getValue();
			clientThread.invoke(() ->
			{
				try
				{
					o.setActive(false);
				}
				catch (Throwable ignored)
				{
				}
			});
		}
		objects.clear();
	}

	/**
	 * Re-place any known pack whose object is missing (e.g. after despawnObjectsKeepClaims on a
	 * scene load). Client thread only. A pack whose tile is off the new scene stays claimable via
	 * the sidebar; it simply isn't re-spawned this time.
	 */
	public void respawnMissing()
	{
		for (Map.Entry<String, WorldPoint> e : tiles.entrySet())
		{
			final String id = e.getKey();
			if (!objects.containsKey(id))
			{
				String tier = tierById.get(id);
				spawnOnClientThread(id, e.getValue(), tier == null ? "" : tier);
			}
		}
	}

	public WorldPoint tileOf(String pendingPackId)
	{
		return tiles.get(pendingPackId);
	}

	/**
	 * Despawn every pack whose object was registered in the given worldview (e.g. the player's
	 * boat docked/despawned). Returns the affected pending ids so the caller can surface a
	 * sidebar-claim fallback — the server-side pack stays claimable until expiry.
	 */
	public java.util.List<String> despawnWorldView(int worldViewId)
	{
		java.util.List<String> lost = new java.util.ArrayList<>();
		for (Map.Entry<String, Integer> e : wvById.entrySet())
		{
			if (e.getValue() != null && e.getValue() == worldViewId)
			{
				lost.add(e.getKey());
			}
		}
		for (String id : lost)
		{
			despawn(id);
		}
		return lost;
	}

	/** Remove a specific spawned pack. */
	public void despawn(String pendingPackId)
	{
		tiles.remove(pendingPackId);
		tierById.remove(pendingPackId);
		wvById.remove(pendingPackId);
		RuneLiteObject o = objects.remove(pendingPackId);
		if (o != null)
		{
			clientThread.invoke(() -> {
				try
				{
					o.setActive(false);
				}
				catch (Throwable ignored)
				{
				}
			});
		}
	}

	/** Remove everything (plugin shutdown / logout). */
	public void clear()
	{
		for (String id : objects.keySet().toArray(new String[0]))
		{
			despawn(id);
		}
	}

	/** Immutable snapshot row for the highlight overlay. */
	public static final class Spawned
	{
		public final String id;
		public final WorldPoint tile;
		public final String tier;

		Spawned(String id, WorldPoint tile, String tier)
		{
			this.id = id;
			this.tile = tile;
			this.tier = tier;
		}
	}

	/** Per-frame snapshot of spawned packs. Safe to call from the render thread. */
	public java.util.List<Spawned> spawnedPacks()
	{
		java.util.List<Spawned> out = new java.util.ArrayList<>(tiles.size());
		for (Map.Entry<String, WorldPoint> e : tiles.entrySet())
		{
			String tier = tierById.get(e.getKey());
			out.add(new Spawned(e.getKey(), e.getValue(), tier == null ? "" : tier));
		}
		return out;
	}
}
