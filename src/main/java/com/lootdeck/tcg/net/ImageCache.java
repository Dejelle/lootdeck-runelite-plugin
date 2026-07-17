package com.lootdeck.tcg.net;

import java.awt.image.BufferedImage;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.security.MessageDigest;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import javax.imageio.ImageIO;
import javax.inject.Inject;
import javax.inject.Singleton;
import net.runelite.client.RuneLite;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Downloads + disk-caches card PNGs and decodes them via ImageIO. All network happens on the
 * caller's (executor) thread — NEVER call get(...) from the client thread. Decoded images are
 * held in a small in-memory LRU. Server bakes the art (DESIGN §8.6); we only fetch + draw.
 */
@Singleton
public class ImageCache
{
	private static final Logger log = LoggerFactory.getLogger(ImageCache.class);
	private static final File DEFAULT_DIR = new File(RuneLite.RUNELITE_DIR, "lootdeck/imgcache");
	// ~64MB of decoded pixels (w*h*4). A 512x768 card face is ~1.5MB → ~40 faces resident.
	private static final long MEM_BUDGET_BYTES = 64L * 1024 * 1024;
	// Largest legitimate asset (1024² source PNG) is well under this.
	private static final long MAX_DOWNLOAD_BYTES = 4L * 1024 * 1024;
	private static final long DISK_SWEEP_TRIGGER = 256L * 1024 * 1024;
	private static final long DISK_SWEEP_TARGET = 192L * 1024 * 1024;

	private final OkHttpClient http;
	private final File dir;

	// Access-ordered LRU bounded by BYTES (not entry count — a few 1024² sources would blow a
	// count-based bound's assumptions, audit M5). Guarded by synchronized(mem); memBytes too.
	private final LinkedHashMap<String, BufferedImage> mem = new LinkedHashMap<>(16, 0.75f, true);
	private long memBytes = 0;
	// De-dupe concurrent downloads of the same url. Never removed: the lock objects are tiny and
	// bounded by the URL working set (audit L1).
	private final Map<String, Object> locks = new ConcurrentHashMap<>();
	// url -> failedAtMillis. A failed fetch is not retried for NEG_TTL_MS so a dead CDN can't be
	// hammered once per frame from prefetch (audit C3); get() clears the entry on success.
	private final Map<String, Long> negative = new ConcurrentHashMap<>();
	// urls currently being fetched by prefetch(), to de-dupe submissions.
	private final java.util.Set<String> inFlight = ConcurrentHashMap.newKeySet();
	private static final long NEG_TTL_MS = 15_000L;

	@Inject
	public ImageCache(OkHttpClient http)
	{
		this(http, DEFAULT_DIR);
	}

	// Package-private: tests inject a temp dir so they never touch the real ~/.runelite cache.
	ImageCache(OkHttpClient http, File dir)
	{
		this.http = http;
		this.dir = dir;
		//noinspection ResultOfMethodCallIgnored
		dir.mkdirs();
		Thread sweeper = new Thread(this::sweepDisk, "lootdeck-imgsweep");
		sweeper.setDaemon(true);
		sweeper.start();
	}

	/**
	 * Turn a baked .webp URL into its .png sibling. Safe if already .png. Query-string-safe:
	 * baked URLs may carry a cache-busting query (…/base.webp?v=abcd123456), so we split the
	 * query off, swap the extension on the path only, then re-attach it. Without this, ".webp?v=…"
	 * fails an endsWith(".webp") check and the plugin fetches a WebP that Java ImageIO can't decode.
	 */
	public static String pngUrl(String webpUrl)
	{
		if (webpUrl == null)
		{
			return null;
		}
		int q = webpUrl.indexOf('?');
		String path = q < 0 ? webpUrl : webpUrl.substring(0, q);
		String query = q < 0 ? "" : webpUrl.substring(q);
		if (path.endsWith(".webp"))
		{
			path = path.substring(0, path.length() - 5) + ".png";
		}
		return path + query;
	}

	/**
	 * Memory-cache hit or null. NEVER touches disk or network — safe on the client thread.
	 */
	public BufferedImage peek(String pngUrl)
	{
		if (pngUrl == null || pngUrl.isEmpty())
		{
			return null;
		}
		synchronized (mem)
		{
			return mem.get(pngUrl);
		}
	}

	/**
	 * Kick off a background get() unless the image is cached, already in flight, or failed within
	 * the last NEG_TTL_MS. Cheap; safe to call every frame from a render path (audit C3).
	 */
	public void prefetch(String pngUrl, java.util.concurrent.Executor ex)
	{
		if (pngUrl == null || pngUrl.isEmpty() || ex == null)
		{
			return;
		}
		synchronized (mem)
		{
			if (mem.get(pngUrl) != null)
			{
				return;
			}
		}
		Long failedAt = negative.get(pngUrl);
		if (failedAt != null && System.currentTimeMillis() - failedAt < NEG_TTL_MS)
		{
			return;
		}
		if (!inFlight.add(pngUrl))
		{
			return;
		}
		try
		{
			ex.execute(() ->
			{
				try
				{
					get(pngUrl);
				}
				finally
				{
					inFlight.remove(pngUrl);
				}
			});
		}
		catch (RuntimeException e)
		{
			// Executor already shut down (plugin disabling) — never let this reach render().
			inFlight.remove(pngUrl);
		}
	}

	/**
	 * Return a decoded image for the given PNG url, downloading + caching if needed.
	 * Blocking; returns null on any failure (caller should draw a placeholder).
	 * MUST be called off the client thread.
	 */
	public BufferedImage get(String pngUrl)
	{
		if (pngUrl == null || pngUrl.isEmpty())
		{
			return null;
		}
		synchronized (mem)
		{
			BufferedImage hit = mem.get(pngUrl);
			if (hit != null)
			{
				return hit;
			}
		}
		Object lock = locks.computeIfAbsent(pngUrl, k -> new Object());
		synchronized (lock)
		{
			synchronized (mem)
			{
				BufferedImage hit = mem.get(pngUrl);
				if (hit != null)
				{
					return hit;
				}
			}
			try
			{
				File f = new File(dir, keyFor(pngUrl) + ".png");
				BufferedImage img = null;
				if (f.exists())
				{
					img = ImageIO.read(f);
					if (img == null)
					{
						// Poison purge (M3): a cached file that no longer decodes is deleted + refetched.
						//noinspection ResultOfMethodCallIgnored
						f.delete();
					}
					else
					{
						// LRU signal for the disk sweep (M6).
						//noinspection ResultOfMethodCallIgnored
						f.setLastModified(System.currentTimeMillis());
					}
				}
				if (img == null)
				{
					byte[] bytes = download(pngUrl);
					// Validate BEFORE persisting (M3): only bytes that decode get written to disk.
					img = bytes != null ? ImageIO.read(new java.io.ByteArrayInputStream(bytes)) : null;
					if (img != null)
					{
						Files.write(f.toPath(), bytes);
					}
				}
				if (img == null)
				{
					negative.put(pngUrl, System.currentTimeMillis());
					return null;
				}
				negative.remove(pngUrl);
				putMem(pngUrl, img);
				return img;
			}
			catch (Exception e)
			{
				log.warn("[LootDeck] image fetch failed {}: {}", pngUrl, e.getMessage());
				negative.put(pngUrl, System.currentTimeMillis());
				return null;
			}
		}
	}

	private static long sizeOf(BufferedImage img)
	{
		return 4L * img.getWidth() * img.getHeight();
	}

	/** Insert into the byte-budgeted LRU, evicting oldest until under MEM_BUDGET_BYTES (M5). */
	private void putMem(String url, BufferedImage img)
	{
		synchronized (mem)
		{
			BufferedImage prev = mem.put(url, img);
			if (prev != null)
			{
				memBytes -= sizeOf(prev);
			}
			memBytes += sizeOf(img);
			java.util.Iterator<Map.Entry<String, BufferedImage>> it = mem.entrySet().iterator();
			while (memBytes > MEM_BUDGET_BYTES && it.hasNext())
			{
				Map.Entry<String, BufferedImage> eldest = it.next();
				if (eldest.getValue() == img)
				{
					break; // never evict the entry we just added
				}
				memBytes -= sizeOf(eldest.getValue());
				it.remove();
			}
		}
	}

	private byte[] download(String url) throws IOException
	{
		Request req = new Request.Builder().url(url).get().build();
		try (Response resp = http.newCall(req).execute())
		{
			if (!resp.isSuccessful() || resp.body() == null)
			{
				return null;
			}
			String ct = resp.header("Content-Type");
			if (ct != null && !ct.toLowerCase(java.util.Locale.ROOT).startsWith("image/"))
			{
				log.warn("[LootDeck] non-image content-type '{}' for {}", ct, url);
				return null;
			}
			java.io.InputStream in = resp.body().byteStream();
			java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream(64 * 1024);
			byte[] buf = new byte[8192];
			long total = 0;
			int n;
			while ((n = in.read(buf)) != -1)
			{
				total += n;
				if (total > MAX_DOWNLOAD_BYTES)
				{
					log.warn("[LootDeck] image over {} bytes, aborting: {}", MAX_DOWNLOAD_BYTES, url);
					return null;
				}
				out.write(buf, 0, n);
			}
			return out.toByteArray();
		}
	}

	/** If the disk cache is over 256MB, delete oldest-mtime files down to 192MB (M6). */
	private void sweepDisk()
	{
		try
		{
			File[] files = dir.listFiles();
			if (files == null)
			{
				return;
			}
			long total = 0;
			for (File f : files)
			{
				total += f.length();
			}
			if (total <= DISK_SWEEP_TRIGGER)
			{
				return;
			}
			java.util.Arrays.sort(files, java.util.Comparator.comparingLong(File::lastModified));
			long freed = 0;
			for (File f : files)
			{
				if (total - freed <= DISK_SWEEP_TARGET)
				{
					break;
				}
				long len = f.length();
				if (f.delete())
				{
					freed += len;
				}
			}
			log.info("[LootDeck] imgcache sweep freed {} bytes", freed);
		}
		catch (Exception e)
		{
			log.warn("[LootDeck] imgcache sweep failed: {}", e.toString());
		}
	}

	// package-private for tests (they compute the expected cache filename).
	static String keyFor(String url)
	{
		try
		{
			MessageDigest md = MessageDigest.getInstance("SHA-1");
			byte[] d = md.digest(url.getBytes(java.nio.charset.StandardCharsets.UTF_8));
			StringBuilder sb = new StringBuilder();
			for (byte b : d)
			{
				sb.append(String.format("%02x", b));
			}
			return sb.toString();
		}
		catch (Exception e)
		{
			return Integer.toHexString(url.hashCode());
		}
	}
}
