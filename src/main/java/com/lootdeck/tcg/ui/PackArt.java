package com.lootdeck.tcg.ui;

import java.awt.Color;
import java.awt.Font;
import java.awt.GradientPaint;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.io.InputStream;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import javax.imageio.ImageIO;

/**
 * Booster-pack + card-back art. Prefers bundled PNG assets shipped on the classpath
 * (src/main/resources/com/lootdeck/tcg/ui), falling back to a procedural render when a
 * resource is missing. Loaded lazily and cached; safe to call from the render thread.
 */
public final class PackArt
{
	private PackArt()
	{
	}

	// Lazily loaded from the classpath and cached. An empty Optional caches a miss so we
	// only probe the classpath once per key.
	private static final Map<String, Optional<BufferedImage>> PACKS = new ConcurrentHashMap<>();
	private static volatile Optional<BufferedImage> cardBack;
	// CDN-fetched card back (Phase 5). When set, overrides the bundled back so a new card back
	// propagates without a plugin release. Null = fall back to the bundled classpath resource.
	private static volatile BufferedImage fetchedCardBack;

	public static Color tierColor(String tier)
	{
		if (tier == null)
		{
			return new Color(0x8a, 0x65, 0x26);
		}
		switch (tier)
		{
			case "bronze":
				return new Color(0xa5, 0x72, 0x3d);
			case "steel":
				return new Color(0x85, 0x93, 0xa0);
			case "rune":
				return new Color(0x51, 0x64, 0xc6);
			case "dragon":
				return new Color(0xb3, 0x23, 0x1b);
			default:
				return new Color(0x8a, 0x65, 0x26);
		}
	}

	/** Bundled pack art for a tier (full-res, cached). Null if the resource is missing. */
	public static BufferedImage image(String tier)
	{
		if (tier == null)
		{
			return null;
		}
		return PACKS.computeIfAbsent(tier, t -> Optional.ofNullable(load("packs/" + t + ".png"))).orElse(null);
	}

	/** Override the bundled card back with a CDN-fetched image (null = clear, use bundled). */
	public static void setFetchedCardBack(BufferedImage img)
	{
		fetchedCardBack = img;
	}

	/**
	 * Universal card back. Prefers a CDN-fetched image (Phase 5, so a new back propagates without a
	 * plugin release), else the bundled classpath resource (full-res, cached). Null if both miss.
	 */
	public static BufferedImage cardBack()
	{
		BufferedImage fetched = fetchedCardBack;
		if (fetched != null)
		{
			return fetched;
		}
		Optional<BufferedImage> c = cardBack;
		if (c == null)
		{
			c = Optional.ofNullable(load("card-back.png"));
			cardBack = c;
		}
		return c.orElse(null);
	}

	/** Load a PNG resource relative to this class's package. Null on any failure. */
	private static BufferedImage load(String path)
	{
		try (InputStream in = PackArt.class.getResourceAsStream(path))
		{
			return in == null ? null : ImageIO.read(in);
		}
		catch (IOException e)
		{
			return null;
		}
	}

	/**
	 * Procedural fallback pack "back": tier gradient, gold border, tier label. Used only when the
	 * bundled pack art can't be loaded.
	 */
	public static BufferedImage render(String tier, int w, int h)
	{
		BufferedImage img = new BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB);
		Graphics2D g = img.createGraphics();
		g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
		g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);

		Color c = tierColor(tier);
		int arc = Math.max(10, w / 8);
		g.setPaint(new GradientPaint(0, 0, c, w * 0.6f, h, new Color(0x1a, 0x13, 0x0a)));
		g.fillRoundRect(0, 0, w - 1, h - 1, arc, arc);

		// Gold frame.
		g.setColor(new Color(0xc9, 0xa3, 0x4e));
		g.setStroke(new java.awt.BasicStroke(Math.max(2f, w / 60f)));
		g.drawRoundRect(1, 1, w - 3, h - 3, arc, arc);

		// A central "LD" seal.
		int seal = Math.min(w, h) / 3;
		g.setColor(new Color(0, 0, 0, 90));
		g.fillOval(w / 2 - seal / 2, h / 2 - seal / 2 - h / 12, seal, seal);
		g.setColor(new Color(0xf3, 0xe6, 0xc3));
		g.setFont(new Font("SansSerif", Font.BOLD, seal / 2));
		drawCentered(g, "LD", w / 2, h / 2 - h / 12 + seal / 6);

		// Tier label near the bottom.
		g.setFont(new Font("SansSerif", Font.BOLD, Math.max(9, h / 18)));
		g.setColor(new Color(0xf3, 0xe6, 0xc3));
		drawCentered(g, tier == null ? "PACK" : tier.toUpperCase(), w / 2, h - h / 10);

		g.dispose();
		return img;
	}

	private static void drawCentered(Graphics2D g, String s, int cx, int baselineY)
	{
		int tw = g.getFontMetrics().stringWidth(s);
		g.drawString(s, cx - tw / 2, baselineY);
	}
}
