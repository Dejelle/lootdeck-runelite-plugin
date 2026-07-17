package com.lootdeck.tcg.ui;

import com.lootdeck.tcg.TcgConfig;
import com.lootdeck.tcg.world.GroundPackManager;
import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.Graphics2D;
import java.awt.Polygon;
import javax.inject.Inject;
import javax.inject.Singleton;
import net.runelite.api.Client;
import net.runelite.api.Perspective;
import net.runelite.api.coords.LocalPoint;
import net.runelite.client.ui.overlay.Overlay;
import net.runelite.client.ui.overlay.OverlayLayer;
import net.runelite.client.ui.overlay.OverlayPosition;
import net.runelite.client.ui.overlay.OverlayUtil;

/**
 * Screen-space highlight for dropped booster packs: a pulsing tier-coloured tile
 * outline plus a floating label. Drawn ABOVE_SCENE, so it can never be buried
 * under real ground items — this is the "always visible" guarantee; the hovering
 * 3D mesh is just flavour. Cosmetic: no exception may escape render().
 */
@Singleton
public class GroundPackHighlightOverlay extends Overlay
{
	private final Client client;
	private final TcgConfig config;
	private final GroundPackManager groundPacks;

	@Inject
	public GroundPackHighlightOverlay(Client client, TcgConfig config, GroundPackManager groundPacks)
	{
		this.client = client;
		this.config = config;
		this.groundPacks = groundPacks;
		setPosition(OverlayPosition.DYNAMIC);
		setLayer(OverlayLayer.ABOVE_SCENE);
	}

	@Override
	public Dimension render(Graphics2D g)
	{
		if (!config.highlightGroundPacks())
		{
			return null;
		}
		// One pulse phase per frame, shared by all packs. Kept bright (floor 120) so the
		// outline is always clearly visible against a busy loot pile, not just at the peak.
		int pulse = (int) (175 + 65 * Math.sin(System.currentTimeMillis() / 300.0));
		pulse = Math.max(120, Math.min(240, pulse));

		for (GroundPackManager.Spawned p : groundPacks.spawnedPacks())
		{
			try
			{
				LocalPoint lp = LocalPoint.fromWorld(client, p.tile);
				if (lp == null)
				{
					continue; // off-scene or boat sub-worldview — best-effort only
				}
				Color base = PackArt.tierColor(p.tier);
				Polygon poly = Perspective.getCanvasTilePoly(client, lp);
				if (poly != null)
				{
					OverlayUtil.renderPolygon(g, poly,
						new Color(base.getRed(), base.getGreen(), base.getBlue(), pulse),
						new Color(base.getRed(), base.getGreen(), base.getBlue(), 60),
						new BasicStroke(2.5f));
				}
				String text = "Booster Pack (" + p.tier + ")";
				net.runelite.api.Point tp = Perspective.getCanvasTextLocation(client, g, lp, text, 160);
				if (tp != null)
				{
					OverlayUtil.renderTextLocation(g, tp, text, base);
				}
			}
			catch (Throwable ignored)
			{
				// cosmetic — never break the render loop
			}
		}
		return null;
	}
}
