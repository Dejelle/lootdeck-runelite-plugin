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
	private static final File DIR = new File(RuneLite.RUNELITE_DIR, "lootdeck/imgcache");
	private static final int MEM_MAX = 128;

	private final OkHttpClient http;

	// Access-ordered LRU, bounded, synchronized on itself.
	private final Map<String, BufferedImage> mem =
		new LinkedHashMap<String, BufferedImage>(16, 0.75f, true)
		{
			@Override
			protected boolean removeEldestEntry(Map.Entry<String, BufferedImage> e)
			{
				return size() > MEM_MAX;
			}
		};
	// De-dupe concurrent downloads of the same url.
	private final Map<String, Object> locks = new ConcurrentHashMap<>();

	@Inject
	public ImageCache(OkHttpClient http)
	{
		this.http = http;
		//noinspection ResultOfMethodCallIgnored
		DIR.mkdirs();
	}

	/** Turn a baked .webp URL into its .png sibling. Safe if already .png. */
	public static String pngUrl(String webpUrl)
	{
		if (webpUrl == null)
		{
			return null;
		}
		return webpUrl.endsWith(".webp") ? webpUrl.substring(0, webpUrl.length() - 5) + ".png" : webpUrl;
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
				File f = new File(DIR, keyFor(pngUrl) + ".png");
				BufferedImage img;
				if (f.exists())
				{
					img = ImageIO.read(f);
				}
				else
				{
					byte[] bytes = download(pngUrl);
					if (bytes == null)
					{
						return null;
					}
					Files.write(f.toPath(), bytes);
					img = ImageIO.read(f);
				}
				if (img != null)
				{
					synchronized (mem)
					{
						mem.put(pngUrl, img);
					}
				}
				return img;
			}
			catch (Exception e)
			{
				log.warn("[LootDeck] image fetch failed {}: {}", pngUrl, e.getMessage());
				return null;
			}
			finally
			{
				locks.remove(pngUrl);
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
			return resp.body().bytes();
		}
	}

	private static String keyFor(String url)
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
