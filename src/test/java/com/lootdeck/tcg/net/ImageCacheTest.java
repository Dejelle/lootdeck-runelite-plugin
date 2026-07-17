package com.lootdeck.tcg.net;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import com.sun.net.httpserver.HttpServer;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.util.concurrent.Executor;
import java.util.concurrent.TimeUnit;
import javax.imageio.ImageIO;
import okhttp3.OkHttpClient;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

public class ImageCacheTest
{
	@Rule
	public TemporaryFolder tmp = new TemporaryFolder();

	private static byte[] pngBytes() throws Exception
	{
		BufferedImage img = new BufferedImage(4, 4, BufferedImage.TYPE_INT_ARGB);
		ByteArrayOutputStream baos = new ByteArrayOutputStream();
		ImageIO.write(img, "png", baos);
		return baos.toByteArray();
	}

	private static HttpServer serverWith(String path, String contentType, byte[] body) throws Exception
	{
		HttpServer s = HttpServer.create(new InetSocketAddress(0), 0);
		s.createContext(path, ex ->
		{
			ex.getResponseHeaders().add("Content-Type", contentType);
			ex.sendResponseHeaders(200, body.length);
			ex.getResponseBody().write(body);
			ex.close();
		});
		s.start();
		return s;
	}

	private static String base(HttpServer s)
	{
		return "http://127.0.0.1:" + s.getAddress().getPort();
	}

	@Test
	public void peekNeverBlocksAndPrefetchDedupes() throws Exception
	{
		// Short connect timeout so an unreachable host fails fast rather than hanging the test.
		OkHttpClient http = new OkHttpClient.Builder()
			.connectTimeout(300, TimeUnit.MILLISECONDS)
			.build();
		ImageCache ic = new ImageCache(http, tmp.newFolder());
		String url = "http://127.0.0.1:1/nope.png"; // port 1 → connection refused, fast

		// peek() only reads the in-memory map — never disk or network — so it returns instantly.
		assertNull(ic.peek(url));

		// First prefetch runs get() inline (direct executor) → the fetch fails → url is negative-cached.
		ic.prefetch(url, Runnable::run);

		// Second prefetch must be suppressed by the negative cache: the executor is never invoked.
		final int[] count = {0};
		Executor counting = r ->
		{
			count[0]++;
			r.run();
		};
		ic.prefetch(url, counting);
		assertEquals("negative cache suppresses the re-fetch", 0, count[0]);
	}

	@Test
	public void rejectsNonImageAndNegativeCaches() throws Exception
	{
		HttpServer s = serverWith("/html.png", "text/html", "<html>cdn error page</html>".getBytes());
		try
		{
			String url = base(s) + "/html.png";
			File dir = tmp.newFolder();
			ImageCache ic = new ImageCache(new OkHttpClient(), dir);
			assertNull("non-image content-type must be rejected", ic.get(url));
			assertEquals("nothing persisted for a rejected fetch", 0, dir.listFiles().length);
			// Negative-cached → an immediate prefetch submits nothing.
			final int[] count = {0};
			ic.prefetch(url, r ->
			{
				count[0]++;
				r.run();
			});
			assertEquals(0, count[0]);
		}
		finally
		{
			s.stop(0);
		}
	}

	@Test
	public void purgesPoisonOnRead() throws Exception
	{
		HttpServer s = serverWith("/good.png", "image/png", pngBytes());
		try
		{
			String url = base(s) + "/good.png";
			File dir = tmp.newFolder();
			// Pre-plant garbage at the cache key — an old truncated/corrupt download.
			File poison = new File(dir, ImageCache.keyFor(url) + ".png");
			java.nio.file.Files.write(poison.toPath(), "not a png".getBytes());
			ImageCache ic = new ImageCache(new OkHttpClient(), dir);
			BufferedImage img = ic.get(url);
			assertNotNull("poison deleted and the good png refetched", img);
		}
		finally
		{
			s.stop(0);
		}
	}

	@Test
	public void downloadsValidatesAndCaches() throws Exception
	{
		HttpServer s = serverWith("/good.png", "image/png", pngBytes());
		try
		{
			String url = base(s) + "/good.png";
			File dir = tmp.newFolder();
			ImageCache ic = new ImageCache(new OkHttpClient(), dir);
			assertNotNull(ic.get(url));
			assertTrue("validated bytes are persisted to disk",
				new File(dir, ImageCache.keyFor(url) + ".png").exists());
			// Second get hits memory (short-circuits before any network); a fresh cache on the same
			// dir hits disk. Neither needs the server, but leaving it up keeps the test simple.
			assertNotNull(ic.get(url));
			ImageCache ic2 = new ImageCache(new OkHttpClient(), dir);
			assertNotNull(ic2.get(url));
		}
		finally
		{
			s.stop(0);
		}
	}

	@Test
	public void overCapDownloadAborts() throws Exception
	{
		HttpServer s = HttpServer.create(new InetSocketAddress(0), 0);
		s.createContext("/big.png", ex ->
		{
			ex.getResponseHeaders().add("Content-Type", "image/png");
			long total = 5L * 1024 * 1024; // > the 4MB cap
			ex.sendResponseHeaders(200, total);
			OutputStream os = ex.getResponseBody();
			byte[] chunk = new byte[64 * 1024];
			long sent = 0;
			try
			{
				while (sent < total)
				{
					int n = (int) Math.min(chunk.length, total - sent);
					os.write(chunk, 0, n);
					sent += n;
				}
			}
			catch (java.io.IOException ignored)
			{
				// client aborted after the cap — expected
			}
			ex.close();
		});
		s.start();
		try
		{
			String url = base(s) + "/big.png";
			File dir = tmp.newFolder();
			ImageCache ic = new ImageCache(new OkHttpClient(), dir);
			assertNull("over-cap download is aborted", ic.get(url));
			assertEquals("nothing persisted for an aborted download", 0, dir.listFiles().length);
		}
		finally
		{
			s.stop(0);
		}
	}

	@Test
	public void swapsWebpToPngPreservingCacheBustQuery()
	{
		// Plain webp → png.
		assertEquals(
			"https://cdn.lootdeck.org/set01/002-cow/base.png",
			ImageCache.pngUrl("https://cdn.lootdeck.org/set01/002-cow/base.webp"));

		// Versioned webp → png, query preserved (the whole point).
		assertEquals(
			"https://cdn.lootdeck.org/set01/002-cow/base.png?v=abcd123456",
			ImageCache.pngUrl("https://cdn.lootdeck.org/set01/002-cow/base.webp?v=abcd123456"));

		// Already a versioned .png (e.g. pack art) → unchanged.
		assertEquals(
			"https://cdn.lootdeck.org/set01/pack/rune.png?v=2",
			ImageCache.pngUrl("https://cdn.lootdeck.org/set01/pack/rune.png?v=2"));

		// Null in → null out.
		assertNull(ImageCache.pngUrl(null));
	}
}
