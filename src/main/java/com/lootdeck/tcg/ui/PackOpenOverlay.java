package com.lootdeck.tcg.ui;

import com.lootdeck.tcg.net.Dtos;
import com.lootdeck.tcg.net.ImageCache;
import java.awt.AlphaComposite;
import java.awt.Color;
import java.awt.Composite;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.GradientPaint;
import java.awt.Graphics2D;
import java.awt.RadialGradientPaint;
import java.awt.RenderingHints;
import java.awt.Shape;
import java.awt.geom.Point2D;
import java.awt.geom.RoundRectangle2D;
import java.awt.image.BufferedImage;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import net.runelite.api.Client;
import net.runelite.client.ui.overlay.Overlay;
import net.runelite.client.ui.overlay.OverlayLayer;
import net.runelite.client.ui.overlay.OverlayPosition;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Full-screen, client-side pack-open reveal. Data (cards + their rarities) is fetched off-thread
 * BEFORE this overlay is shown; here we only draw already-fetched data + cached images. Advance
 * is driven by click (see TcgPlugin's mouse handling). All motion is wall-clock timed; render()
 * never does I/O or mutates network state.
 */
public class PackOpenOverlay extends Overlay
{
	private static final long INTRO_MS = 650L;
	private static final long FLIP_MS = 300L; // 150ms back-collapse + 150ms face-expand

	private static final Logger log = LoggerFactory.getLogger(PackOpenOverlay.class);

	// Hoisted per-frame allocations (audit L10). Colors with a variable alpha (the bg dim) can't
	// be hoisted and stay inline.
	private static final Font PROMPT_FONT = new Font("SansSerif", Font.BOLD, 18);
	private static final Font BACK_FONT = new Font("Serif", Font.BOLD, 12);
	private static final Font PLACEHOLDER_FONT = new Font("SansSerif", Font.PLAIN, 11);
	private static final Color CREAM = new Color(0xf3, 0xe6, 0xc3);
	private static final Color CARD_BG = new Color(0x19, 0x13, 0x09);

	private final Client client;
	private final ImageCache images;
	// The plugin's net pool: all art fetches run here (never ad-hoc threads) so they die with the
	// plugin and never touch the render thread (audit C3/L11).
	private final java.util.concurrent.Executor netExec;

	private volatile boolean active = false;
	private volatile List<Dtos.OpenedCard> cards = null;
	private volatile int revealed = 0;
	private volatile String tier = "";
	private volatile long shownAt = 0L;
	private volatile long[] revealedAt = new long[0];
	// Release-specific booster art, fetched off-thread in show(); null until it lands (then the
	// intro uses it in place of the bundled tier art). render() never blocks on I/O.
	private volatile BufferedImage packImg = null;
	// Bumped per show(); async art fetches capture it and only publish if still current (audit L11).
	private volatile int generation = 0;

	// Card faces pre-scaled to their on-screen size with a high-quality multi-step downscale, so the
	// reveal draws them ~1:1 (crisp). Otherwise every frame squashes the full 512px+ bake down to
	// ~150px in a single bilinear pass, which under-samples on a >2x reduction and looks soft — the
	// hero reveal ends up blurrier than the sidebar thumbnails (which already use SCALE_SMOOTH).
	// Keyed by url@WxH; computed off the render thread; cleared per pack open.
	private final Map<String, BufferedImage> faceCache = new ConcurrentHashMap<>();
	private final Set<String> facePending = ConcurrentHashMap.newKeySet();

	public PackOpenOverlay(Client client, ImageCache images, java.util.concurrent.Executor netExec)
	{
		this.client = client;
		this.images = images;
		this.netExec = netExec;
		setLayer(OverlayLayer.ABOVE_WIDGETS);
		setPosition(OverlayPosition.DYNAMIC);
	}

	public void show(String tier, String packArtUrl, List<Dtos.OpenedCard> cards)
	{
		if (active)
		{
			// doOpen() already refuses while active (M8); this is the belt for that suspender.
			log.warn("[LootDeck] show() called while a reveal is active — ignoring");
			return;
		}
		generation++;
		this.tier = tier == null ? "" : tier;
		this.cards = cards;
		this.revealed = 0;
		this.revealedAt = new long[cards != null ? cards.size() : 0];
		this.shownAt = System.currentTimeMillis();
		this.packImg = null;
		this.active = true;
		faceCache.clear();
		facePending.clear();
		// Fetch the release's booster art off the render thread; drawIntro falls back to the
		// bundled tier art until this resolves. Guard on generation so a stale fetch from a
		// previous open never paints onto a newer one.
		if (packArtUrl != null && images != null)
		{
			final String url = ImageCache.pngUrl(packArtUrl);
			final int gen = generation;
			runAsync(() ->
			{
				BufferedImage img = images.get(url);
				if (img != null && gen == generation)
				{
					this.packImg = img;
				}
			});
		}
		// Pre-fetch every face now, so each flip finds its art in memory (audit C3).
		if (cards != null)
		{
			for (Dtos.OpenedCard c : cards)
			{
				if (c.definition != null)
				{
					images.prefetch(ImageCache.pngUrl(c.definition.baseImageUrl), netExec);
				}
			}
		}
	}

	/** Run on the plugin's net pool; swallow rejection if the pool is shutting down. */
	private void runAsync(Runnable r)
	{
		try
		{
			netExec.execute(r);
		}
		catch (RuntimeException ignored)
		{
		}
	}

	public boolean isActive()
	{
		return active;
	}

	/** Advance one reveal; when all revealed, a further click closes. Returns true if it consumed. */
	public boolean advance()
	{
		if (!active || cards == null)
		{
			return false;
		}
		// Ignore clicks during the intro so the pack-tear reads clearly.
		if (System.currentTimeMillis() - shownAt < INTRO_MS)
		{
			return true;
		}
		if (revealed >= cards.size())
		{
			active = false;
			return true;
		}
		if (revealed < revealedAt.length)
		{
			revealedAt[revealed] = System.currentTimeMillis();
		}
		revealed++;
		return true;
	}

	public void close()
	{
		active = false;
	}

	@Override
	public Dimension render(Graphics2D g)
	{
		if (!active)
		{
			return null;
		}
		// Snapshot the volatile fields once so a concurrent advance()/close() can't tear this frame
		// (audit M8). Everything below reads these locals, not the fields.
		final List<Dtos.OpenedCard> cards = this.cards;
		final long[] revealedAt = this.revealedAt;
		final int revealed = Math.min(this.revealed, cards != null ? cards.size() : 0);
		if (cards == null || cards.isEmpty())
		{
			return null;
		}
		// Smoothly downscale the bundled pack/back/face art.
		g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
		// Lay out against the game's real (unstretched) render dimensions — the same space
		// RuneLite's OverlayRenderer positions overlays in. getCanvas().getSize() returns the
		// post-stretch canvas size, so under Stretched Mode / DPI scaling it is wider than the
		// buffer we actually draw into, which pushed the centred card row off to the right.
		final Dimension real = client.getRealDimensions();
		final int W = real != null ? real.width : 765;
		final int H = real != null ? real.height : 503;
		final long now = System.currentTimeMillis();
		final long sinceShown = now - shownAt;

		// Dim background (fades in over the intro).
		int dim = (int) Math.min(180, 180 * Math.max(0.15, Math.min(1.0, sinceShown / (double) INTRO_MS)));
		g.setColor(new Color(0, 0, 0, dim));
		g.fillRect(0, 0, W, H);

		if (sinceShown < INTRO_MS)
		{
			drawIntro(g, W, H, sinceShown);
			return new Dimension(W, H);
		}

		// Title / prompt.
		g.setFont(PROMPT_FONT);
		g.setColor(CREAM);
		String prompt = revealed >= cards.size()
			? "Click to close" : "Click to reveal (" + revealed + "/" + cards.size() + ")";
		g.drawString(prompt, W / 2 - g.getFontMetrics().stringWidth(prompt) / 2, 40);

		// Card row layout. Size each card so the whole row fits the viewport in BOTH axes — including
		// the final "best-last" card, which is drawn at 1.25x once revealed. Width alone isn't enough:
		// on a short viewport a width-sized card would clip top/bottom (and crowd the title).
		final int n = cards.size();
		final int gap = 12;
		// Vertical budget: reserve room for the title (top) plus matching bottom breathing room. The
		// row is centered, so the tallest (emphasized) card — height cardW * 1.4 (aspect) * 1.25
		// (emphasis) — must fit within H minus that symmetric reserve.
		final int vReserve = 56;
		final int cardWByHeight = (int) ((H - 2 * vReserve) / (1.4 * 1.25));
		final int cardWByWidth = (W - 40) / Math.max(1, n) - gap;
		final int cardW = Math.max(24, Math.min(150, Math.min(cardWByWidth, cardWByHeight)));
		final int cardH = (int) (cardW * 1.4);
		final int totalW = n * cardW + (n - 1) * gap;
		int x = (W - totalW) / 2;
		final int y = (H - cardH) / 2;

		final int lastRevealedIdx = revealed - 1;

		for (int i = 0; i < n; i++)
		{
			Dtos.OpenedCard c = cards.get(i);
			final boolean isLastCard = i == n - 1;
			final boolean emphasize = i == lastRevealedIdx && isLastCard;
			// Best-last emphasis: the final card, once revealed, sits ~1.25x larger.
			final int ew = emphasize ? (int) (cardW * 1.25) : cardW;
			final int eh = emphasize ? (int) (cardH * 1.25) : cardH;
			final int ex = x - (ew - cardW) / 2;
			final int ey = y - (eh - cardH) / 2;

			Color glow = Rarity.color(c.definition != null ? c.definition.rarity : "common");

			// Rarity glow beneath every card (the "hint"), stronger for the emphasized card.
			float radius = ew * (emphasize ? 1.15f : 0.9f);
			int cx = ex + ew / 2;
			int cy = ey + eh - 6;
			int glowAlpha = emphasize ? 210 : 150;
			RadialGradientPaint gp = new RadialGradientPaint(
				new Point2D.Float(cx, cy), radius,
				new float[]{0f, 1f},
				new Color[]{new Color(glow.getRed(), glow.getGreen(), glow.getBlue(), glowAlpha),
					new Color(glow.getRed(), glow.getGreen(), glow.getBlue(), 0)});
			g.setPaint(gp);
			g.fillOval((int) (cx - radius), (int) (cy - radius), (int) (radius * 2), (int) (radius * 2));

			if (i < revealed)
			{
				long flipElapsed = i < revealedAt.length ? now - revealedAt[i] : FLIP_MS;
				drawRevealing(g, c, ex, ey, ew, eh, glow, flipElapsed, now);
			}
			else
			{
				drawBack(g, x, y, cardW, cardH, 1.0);
			}
			x += cardW + gap;
		}
		return new Dimension(W, H);
	}

	/** Intro: the pack art scales up from the center, then a tier-colored burst. */
	private void drawIntro(Graphics2D g, int W, int H, long elapsed)
	{
		double p = Math.min(1.0, elapsed / (double) INTRO_MS);
		int baseW = 150;
		int baseH = 210;
		double scale = 0.4 + 0.6 * easeOut(Math.min(1.0, p / 0.7));
		int w = (int) (baseW * scale);
		int h = (int) (baseH * scale);
		int x = W / 2 - w / 2;
		int y = H / 2 - h / 2;

		BufferedImage art = packImg != null ? packImg : PackArt.image(tier);
		if (art != null)
		{
			g.drawImage(art, x, y, w, h, null);
		}
		else
		{
			g.drawImage(PackArt.render(tier, Math.max(1, w), Math.max(1, h)), x, y, null);
		}

		// Burst near the end of the intro.
		if (p > 0.7)
		{
			double bp = (p - 0.7) / 0.3;
			Color c = PackArt.tierColor(tier);
			float r = (float) (baseW * (0.4 + bp * 1.6));
			int cx = W / 2;
			int cy = H / 2;
			RadialGradientPaint gp = new RadialGradientPaint(
				new Point2D.Float(cx, cy), Math.max(1f, r),
				new float[]{0f, 1f},
				new Color[]{new Color(c.getRed(), c.getGreen(), c.getBlue(), (int) (200 * (1 - bp))),
					new Color(c.getRed(), c.getGreen(), c.getBlue(), 0)});
			g.setPaint(gp);
			g.fillOval((int) (cx - r), (int) (cy - r), (int) (r * 2), (int) (r * 2));
		}
	}

	/** Draw a card that is (or just became) revealed, animating a fake Y-flip. */
	private void drawRevealing(Graphics2D g, Dtos.OpenedCard c, int x, int y, int w, int h,
		Color glow, long flipElapsed, long now)
	{
		if (flipElapsed < FLIP_MS / 2)
		{
			// First half: the back collapses horizontally to 0.
			double t = flipElapsed / (double) (FLIP_MS / 2);
			int cw = (int) (w * (1 - t));
			drawBack(g, x + (w - cw) / 2, y, Math.max(1, cw), h, 1.0);
		}
		else if (flipElapsed < FLIP_MS)
		{
			// Second half: the face expands horizontally from 0.
			double t = (flipElapsed - FLIP_MS / 2) / (double) (FLIP_MS / 2);
			int cw = (int) (w * t);
			drawFace(g, c, x + (w - cw) / 2, y, Math.max(1, cw), h, glow, now);
		}
		else
		{
			drawFace(g, c, x, y, w, h, glow, now);
		}
	}

	private void drawBack(Graphics2D g, int x, int y, int w, int h, double alpha)
	{
		BufferedImage back = PackArt.cardBack();
		if (back != null)
		{
			Composite old = null;
			if (alpha < 1.0)
			{
				old = g.getComposite();
				g.setComposite(AlphaComposite.getInstance(AlphaComposite.SRC_OVER, (float) alpha));
			}
			g.drawImage(back, x, y, w, h, null);
			if (old != null)
			{
				g.setComposite(old);
			}
			return;
		}
		// Fallback: procedural card back.
		g.setColor(new Color(0x2a, 0x25, 0x1c));
		g.fillRoundRect(x, y, w, h, 12, 12);
		g.setColor(new Color(0x7c, 0x5a, 0x22));
		g.setStroke(new java.awt.BasicStroke(2f));
		g.drawRoundRect(x, y, w, h, 12, 12);
		if (w > 40)
		{
			g.setFont(BACK_FONT);
			g.setColor(new Color(0xc9, 0xa3, 0x4e));
			String s = "LootDeck";
			g.drawString(s, x + w / 2 - g.getFontMetrics().stringWidth(s) / 2, y + h / 2);
		}
	}

	private void drawFace(Graphics2D g, Dtos.OpenedCard c, int x, int y, int w, int h, Color glow, long now)
	{
		String url = c.definition != null ? ImageCache.pngUrl(c.definition.baseImageUrl) : null;
		// peek() never blocks (render() is on the client thread); prefetch() re-requests a face
		// whose earlier fetch failed, rate-limited by the negative cache (audit C3).
		BufferedImage img = url != null ? images.peek(url) : null;
		if (img == null && url != null)
		{
			images.prefetch(url, netExec);
		}
		if (img != null)
		{
			// Draw a copy pre-scaled to the card's resting size (h / 1.4 wide — the full width the flip
			// expands to) so at rest it's ~1:1 and crisp. Fall back to the raw bake until the off-thread
			// scale lands (a frame or two), so the card is never missing.
			int restW = Math.max(1, Math.round(h / 1.4f));
			BufferedImage face = faceFor(url, img, restW, h);
			g.drawImage(face != null ? face : img, x, y, w, h, null);
		}
		else
		{
			// Placeholder while art loads.
			g.setColor(CARD_BG);
			g.fillRoundRect(x, y, w, h, 12, 12);
			g.setColor(glow);
			g.setStroke(new java.awt.BasicStroke(2f));
			g.drawRoundRect(x, y, w, h, 12, 12);
			if (c.definition != null && c.definition.name != null && w > 40)
			{
				g.setFont(PLACEHOLDER_FONT);
				g.setColor(CREAM);
				g.drawString(c.definition.name, x + 6, y + h / 2);
			}
		}

		// Foil shimmer: a translucent diagonal band sweeping across the face over time.
		if (c.isFoil && w > 20)
		{
			drawFoilShimmer(g, x, y, w, h, now);
		}

		// Rarity border + foil marker.
		g.setColor(glow);
		g.setStroke(new java.awt.BasicStroke(c.isFoil ? 4f : 2f));
		g.drawRoundRect(x, y, w, h, 12, 12);
	}

	private void drawFoilShimmer(Graphics2D g, int x, int y, int w, int h, long now)
	{
		Shape oldClip = g.getClip();
		Composite oldComposite = g.getComposite();
		try
		{
			g.setClip(new RoundRectangle2D.Float(x, y, w, h, 12, 12));
			g.setComposite(AlphaComposite.getInstance(AlphaComposite.SRC_OVER, 0.35f));
			// Sweep offset cycles every ~2.4s across the card width + band.
			double phase = ((now % 2400L) / 2400.0);
			int band = w / 2;
			int sweep = (int) (phase * (w + band)) - band;
			int gx = x + sweep;
			GradientPaint gp = new GradientPaint(
				gx, y, new Color(255, 255, 255, 0),
				gx + band / 2f, y + h, new Color(255, 255, 255, 180), true);
			g.setPaint(gp);
			g.fillRect(x, y, w, h);
		}
		finally
		{
			g.setComposite(oldComposite);
			g.setClip(oldClip);
		}
	}

	/**
	 * The card face pre-scaled to w×h (cached), or null until the off-thread scale lands (caller then
	 * draws the raw bake for a frame or two). render() stays allocation-light: the actual downscale
	 * runs on a worker thread; here we only do a concurrent-map lookup.
	 */
	private BufferedImage faceFor(String url, BufferedImage raw, int w, int h)
	{
		final String key = url + "@" + w + "x" + h;
		BufferedImage hit = faceCache.get(key);
		if (hit != null)
		{
			return hit;
		}
		// Source already at (or below) the target — no downscale needed, use it directly.
		if (raw.getWidth() <= w && raw.getHeight() <= h)
		{
			faceCache.put(key, raw);
			return raw;
		}
		if (facePending.add(key))
		{
			runAsync(() ->
			{
				try
				{
					faceCache.put(key, highQualityDownscale(raw, w, h));
				}
				catch (Exception ignored)
				{
					// Leave uncached → the raw-bake fallback keeps drawing; just not as crisp.
				}
				finally
				{
					facePending.remove(key);
				}
			});
		}
		return null;
	}

	/**
	 * Progressive (halving) bilinear downscale to w×h. Each step reduces by at most 2x, where bilinear
	 * samples accurately — a single-pass bilinear over a >2x reduction under-samples and looks soft.
	 */
	private static BufferedImage highQualityDownscale(BufferedImage src, int w, int h)
	{
		int cw = src.getWidth();
		int ch = src.getHeight();
		BufferedImage cur = src;
		do
		{
			cw = Math.max(w, cw / 2);
			ch = Math.max(h, ch / 2);
			BufferedImage next = new BufferedImage(cw, ch, BufferedImage.TYPE_INT_ARGB);
			Graphics2D g = next.createGraphics();
			g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
			g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
			g.drawImage(cur, 0, 0, cw, ch, null);
			g.dispose();
			cur = next;
		}
		while (cw > w || ch > h);
		return cur;
	}

	private static double easeOut(double t)
	{
		return 1 - Math.pow(1 - t, 3);
	}
}
