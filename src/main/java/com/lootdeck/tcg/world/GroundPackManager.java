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
	// Orientation step per tick for the top ("dragon") tier's slow spin. 2048 = full turn.
	private static final int SPIN_STEP = 32;
	private static final String TOP_TIER = "dragon";

	private final Client client;
	private final ClientThread clientThread;

	// pendingPackId -> spawned object (so Take/despawn can find it).
	private final Map<String, RuneLiteObject> objects = new ConcurrentHashMap<>();
	// pendingPackId -> world tile it was spawned on (RuneLiteObject has no getLocation() in this API).
	private final Map<String, WorldPoint> tiles = new ConcurrentHashMap<>();
	// pendingPackId -> tier, so per-tick effects (spin) know which objects to animate.
	private final Map<String, String> tierById = new ConcurrentHashMap<>();
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
		clientThread.invoke(() ->
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
				o.setLocation(lp, client.getPlane());
				o.setActive(true);
				objects.put(pendingPackId, o);
				tiles.put(pendingPackId, wp);
				tierById.put(pendingPackId, tier == null ? "" : tier);
			}
			catch (Throwable t)
			{
				// Cosmetic only — never let a scene error escape.
				log.warn("[LootDeck] world object spawn failed: {}", t.toString());
			}
		});
	}

	/** Rotate top-tier ground packs a touch each tick. Must be called on the client thread. */
	public void tickSpin()
	{
		if (objects.isEmpty())
		{
			return;
		}
		for (Map.Entry<String, RuneLiteObject> e : objects.entrySet())
		{
			if (TOP_TIER.equals(tierById.get(e.getKey())))
			{
				RuneLiteObject o = e.getValue();
				try
				{
					o.setOrientation((o.getOrientation() + SPIN_STEP) & 2047);
				}
				catch (Throwable ignored)
				{
					// cosmetic — ignore
				}
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

	public WorldPoint tileOf(String pendingPackId)
	{
		return tiles.get(pendingPackId);
	}

	/** Remove a specific spawned pack. */
	public void despawn(String pendingPackId)
	{
		tiles.remove(pendingPackId);
		tierById.remove(pendingPackId);
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
}
