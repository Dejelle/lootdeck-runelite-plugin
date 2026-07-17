package com.lootdeck.tcg.ui;

import com.lootdeck.tcg.TcgConfig;
import com.lootdeck.tcg.net.ImageCache;
import java.awt.AlphaComposite;
import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Composite;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.Graphics2D;
import java.awt.RadialGradientPaint;
import java.awt.RenderingHints;
import java.awt.geom.Point2D;
import java.awt.image.BufferedImage;
import javax.inject.Inject;
import net.runelite.client.ui.overlay.Overlay;
import net.runelite.client.ui.overlay.OverlayLayer;
import net.runelite.client.ui.overlay.OverlayPosition;

/** Draws a ~2.5s "a pack dropped" animation in the tier colour. Driven by wall-clock time. */
public class TcgOverlay extends Overlay
{
	private static final long DURATION_MS = 2500;

	private final TcgConfig config;
	private final ImageCache images;

	/** One drop animation: all fields for one trigger, published atomically (audit M7). */
	private static final class DropAnim
	{
		final long startedAt;
		final String tier;
		final String label;
		final Color color;
		volatile BufferedImage packImg; // arrives later from the fetch; tied to THIS anim only

		DropAnim(long startedAt, String tier, String label, Color color)
		{
			this.startedAt = startedAt;
			this.tier = tier;
			this.label = label;
			this.color = color;
		}
	}

	private final java.util.concurrent.atomic.AtomicReference<DropAnim> anim =
		new java.util.concurrent.atomic.AtomicReference<>();

	private java.util.concurrent.Executor netExec;

	/** Route the pack-art fetch through the plugin's net pool instead of an ad-hoc thread (L9). */
	public void setNetExecutor(java.util.concurrent.Executor ex)
	{
		this.netExec = ex;
	}

	@Inject
	public TcgOverlay(TcgConfig config, ImageCache images)
	{
		this.config = config;
		this.images = images;
		setLayer(OverlayLayer.ABOVE_WIDGETS);
		setPosition(OverlayPosition.DYNAMIC);
	}

	/**
	 * Start the drop animation for a tier. {@code packArtUrl} is the dropped pack's release-specific
	 * booster art (CDN .png); when non-null it's fetched off-thread and shown in place of the bundled
	 * tier art once it lands. Null (older server / no CDN) keeps the bundled art.
	 */
	public void trigger(String tier, String packArtUrl)
	{
		final String t = tier == null ? "" : tier;
		final DropAnim a = new DropAnim(System.currentTimeMillis(), t, t.toUpperCase(), colorForTier(tier));
		anim.set(a);
		if (packArtUrl != null && images != null)
		{
			final String url = ImageCache.pngUrl(packArtUrl);
			runAsync(() ->
			{
				BufferedImage img = images.get(url);
				if (img != null)
				{
					// Publishes onto THIS trigger's anim only — a newer trigger's object is
					// untouched, so no cross-drop art/label mixing.
					a.packImg = img;
				}
			});
		}
	}

	private void runAsync(Runnable r)
	{
		java.util.concurrent.Executor ex = netExec;
		try
		{
			if (ex != null)
			{
				ex.execute(r);
			}
			else
			{
				new Thread(r, "lootdeck-droppackart").start(); // pre-startUp fallback
			}
		}
		catch (RuntimeException ignored)
		{
			// pool shutting down — skip the fetch
		}
	}

	private static Color colorForTier(String tier)
	{
		if (tier == null)
		{
			return new Color(0xC3, 0x96, 0x40);
		}
		switch (tier)
		{
			case "bronze":
				return new Color(0xA5, 0x72, 0x3D);
			case "steel":
				return new Color(0x85, 0x93, 0xA0);
			case "rune":
				return new Color(0x51, 0x64, 0xC6);
			case "dragon":
				return new Color(0xB3, 0x23, 0x1B);
			default:
				return new Color(0xC3, 0x96, 0x40);
		}
	}

	@Override
	public Dimension render(Graphics2D g)
	{
		final DropAnim a = anim.get();
		if (!config.enableAnimation() || a == null)
		{
			return null;
		}
		long elapsed = System.currentTimeMillis() - a.startedAt;
		if (elapsed > DURATION_MS)
		{
			// Only clear the anim we just rendered — never a newer trigger that raced in.
			anim.compareAndSet(a, null);
			return null;
		}
		final Color tierColor = a.color;

		float t = elapsed / (float) DURATION_MS; // 0..1
		float grow = Math.min(1f, t * 2f); // grows in first half
		float fade = t < 0.7f ? 1f : 1f - (t - 0.7f) / 0.3f; // fades in last 30%
		int alpha = Math.max(0, Math.min(255, (int) (fade * 255)));

		int w = (int) (70 * grow);
		int h = (int) (96 * grow);
		int cx = 120;
		int cy = 120;

		// glow
		float radius = Math.max(1f, 90f * grow);
		RadialGradientPaint glow = new RadialGradientPaint(
			new Point2D.Float(cx, cy), radius,
			new float[]{0f, 1f},
			new Color[]{
				new Color(tierColor.getRed(), tierColor.getGreen(), tierColor.getBlue(), (int) (alpha * 0.5f)),
				new Color(tierColor.getRed(), tierColor.getGreen(), tierColor.getBlue(), 0)
			});
		g.setPaint(glow);
		g.fillOval((int) (cx - radius), (int) (cy - radius), (int) (radius * 2), (int) (radius * 2));

		// pack body — the dropped pack's release art once fetched, else the bundled tier art, else a
		// procedural rounded card.
		BufferedImage art = null;
		if (w > 0 && h > 0)
		{
			art = a.packImg != null ? a.packImg : PackArt.image(a.tier);
		}
		if (art != null)
		{
			Composite oldComposite = g.getComposite();
			g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
			g.setComposite(AlphaComposite.getInstance(AlphaComposite.SRC_OVER, alpha / 255f));
			g.drawImage(art, cx - w / 2, cy - h / 2, w, h, null);
			g.setComposite(oldComposite);
		}
		else
		{
			g.setColor(new Color(0x1a, 0x13, 0x0a, alpha));
			g.fillRoundRect(cx - w / 2, cy - h / 2, w, h, 12, 12);
			g.setColor(new Color(tierColor.getRed(), tierColor.getGreen(), tierColor.getBlue(), alpha));
			g.setStroke(new BasicStroke(3f));
			g.drawRoundRect(cx - w / 2, cy - h / 2, w, h, 12, 12);
		}

		// label
		g.setFont(new Font("SansSerif", Font.BOLD, 14));
		g.setColor(new Color(0xf3, 0xe6, 0xc3, alpha));
		g.drawString(a.label + " PACK!", cx - 34, cy + h / 2 + 20);

		return new Dimension(240, 240);
	}
}
