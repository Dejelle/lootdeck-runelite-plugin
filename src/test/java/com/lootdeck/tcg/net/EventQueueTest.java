package com.lootdeck.tcg.net;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import com.lootdeck.tcg.TcgConfig;
import java.io.File;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

/**
 * Deterministic EventQueue tests: a FakeScheduler captures the drain tasks so the test runs them
 * by hand (no timing), and a FakeApi records reportDrop calls instead of doing HTTP (no Mockito).
 */
public class EventQueueTest
{
	@Rule
	public TemporaryFolder tmp = new TemporaryFolder();

	/** Captures scheduled tasks; the test runs them by hand — fully deterministic. */
	static final class FakeScheduler extends java.util.concurrent.ScheduledThreadPoolExecutor
	{
		final List<Runnable> tasks = new ArrayList<>();

		FakeScheduler()
		{
			super(1);
		}

		@Override
		public ScheduledFuture<?> schedule(Runnable command, long delay, TimeUnit unit)
		{
			tasks.add(command);
			return null; // EventQueue never uses the returned future
		}

		void runAll()
		{
			while (!tasks.isEmpty())
			{
				tasks.remove(0).run();
			}
		}

		boolean runOne()
		{
			if (tasks.isEmpty())
			{
				return false;
			}
			tasks.remove(0).run();
			return true;
		}
	}

	/**
	 * TcgApiClient that records reportDrop calls and optionally scripts responses: each call polls
	 * `responses` — an ApiException is thrown, a DropResult is returned, an empty queue yields a
	 * default success. `onReport` is an optional side-effect hook (used to enqueue mid-drain).
	 */
	static final class FakeApi extends TcgApiClient
	{
		final List<Dtos.DropReport> sent = new ArrayList<>();
		final java.util.Deque<Object> responses = new java.util.ArrayDeque<>();
		boolean dropped = false;
		java.util.function.Consumer<Dtos.DropReport> onReport;

		FakeApi(TcgConfig config)
		{
			super(new okhttp3.OkHttpClient(), new com.google.gson.Gson(), config);
		}

		@Override
		public Dtos.DropResult reportDrop(Dtos.DropReport report) throws ApiException
		{
			sent.add(report);
			if (onReport != null)
			{
				onReport.accept(report);
			}
			Object r = responses.poll();
			if (r instanceof ApiException)
			{
				throw (ApiException) r;
			}
			if (r instanceof Dtos.DropResult)
			{
				return (Dtos.DropResult) r;
			}
			Dtos.DropResult res = new Dtos.DropResult();
			res.dropped = dropped;
			return res;
		}
	}

	/** Parse the persisted queue file as the v1.4 wrapper format. */
	private static List<EventQueue.QueueEntry> readEntries(File f) throws Exception
	{
		String json = new String(java.nio.file.Files.readAllBytes(f.toPath()),
			java.nio.charset.StandardCharsets.UTF_8);
		java.lang.reflect.Type t =
			new com.google.gson.reflect.TypeToken<List<EventQueue.QueueEntry>>() {}.getType();
		List<EventQueue.QueueEntry> l = new com.google.gson.Gson().fromJson(json, t);
		return l == null ? new ArrayList<>() : l;
	}

	static TcgConfig config()
	{
		return (TcgConfig) Proxy.newProxyInstance(TcgConfig.class.getClassLoader(),
			new Class<?>[]{TcgConfig.class}, (proxy, method, args) ->
			{
				if (method.getReturnType() == boolean.class) return Boolean.TRUE;
				if (method.getReturnType() == String.class) return "";
				if (method.getReturnType() == int.class) return 0;
				return null;
			});
	}

	static Dtos.DropReport report(String key)
	{
		Dtos.DropReport r = new Dtos.DropReport();
		r.idempotencyKey = key;
		r.activity = "KILL_LOW_MONSTER";
		return r;
	}

	private EventQueue queue(File file, FakeScheduler sched, FakeApi api)
	{
		return new EventQueue(api, new com.google.gson.Gson(), file, sched);
	}

	private static List<Dtos.DropReport> readReports(File f) throws Exception
	{
		String json = new String(java.nio.file.Files.readAllBytes(f.toPath()),
			java.nio.charset.StandardCharsets.UTF_8);
		java.lang.reflect.Type t =
			new com.google.gson.reflect.TypeToken<List<Dtos.DropReport>>() {}.getType();
		List<Dtos.DropReport> l = new com.google.gson.Gson().fromJson(json, t);
		return l == null ? new ArrayList<>() : l;
	}

	@Test
	public void pausedQueueSchedulesNothing() throws Exception
	{
		File file = tmp.newFile("queue.json");
		FakeScheduler sched = new FakeScheduler();
		FakeApi api = new FakeApi(config());
		EventQueue q = queue(file, sched, api);
		q.pause();
		q.enqueue(report("a"));
		assertTrue("paused → no drain scheduled", sched.tasks.isEmpty());
		q.resume();
		assertEquals(1, sched.tasks.size());
		sched.runAll();
		assertEquals(1, api.sent.size());
		assertTrue("queue file drained to empty", readReports(file).isEmpty());
	}

	@Test
	public void resumeAfterPauseSendsEachReportOnce() throws Exception
	{
		File file = tmp.newFile("queue.json");
		FakeScheduler sched = new FakeScheduler();
		FakeApi api = new FakeApi(config());
		EventQueue q = queue(file, sched, api);
		q.enqueue(report("a"));
		q.enqueue(report("b"));
		q.pause();
		sched.runAll(); // the scheduled drain returns immediately (paused)
		assertTrue("paused drain sends nothing", api.sent.isEmpty());
		q.resume();
		sched.runAll();
		assertEquals("each report sent exactly once", 2, api.sent.size());
	}

	@Test
	public void loadIsIdempotent() throws Exception
	{
		File file = tmp.newFile("queue.json");
		// Instance A persists one report to the file (paused so it never drains it away).
		EventQueue a = queue(file, new FakeScheduler(), new FakeApi(config()));
		a.pause();
		a.enqueue(report("k"));
		// Instance B loads the same file twice, then drains.
		FakeScheduler sched = new FakeScheduler();
		FakeApi apiB = new FakeApi(config());
		EventQueue b = queue(file, sched, apiB);
		b.load();
		b.load();
		sched.runAll();
		assertEquals("double load must not duplicate the report", 1, apiB.sent.size());
	}

	@Test
	public void nullCallbackDoesNotThrow() throws Exception
	{
		File file = tmp.newFile("queue.json");
		FakeScheduler sched = new FakeScheduler();
		FakeApi api = new FakeApi(config());
		api.dropped = true;
		EventQueue q = queue(file, sched, api);
		q.setOnDrop(null);
		q.enqueue(report("a"));
		sched.runAll(); // must not throw despite a null callback + a dropped result
		assertEquals(1, api.sent.size());
		assertTrue("report removed after delivery", readReports(file).isEmpty());
	}

	// ---- P3: retry taxonomy, bounded retries, robustness ----

	@Test
	public void transient429Retries() throws Exception
	{
		File file = tmp.newFile("queue.json");
		FakeScheduler sched = new FakeScheduler();
		FakeApi api = new FakeApi(config());
		api.responses.add(new ApiException(429, "slow down", 7));
		api.responses.add(new Dtos.DropResult()); // success on retry
		EventQueue q = queue(file, sched, api);
		q.enqueue(report("a"));
		sched.runOne(); // attempt 1: 429 → transient → reschedule
		assertEquals(1, api.sent.size());
		assertEquals("still queued after a 429", 1, readEntries(file).size());
		assertEquals("429 reschedules exactly one drain", 1, sched.tasks.size());
		sched.runOne(); // attempt 2: success
		assertEquals(2, api.sent.size());
		assertTrue(readEntries(file).isEmpty());
	}

	@Test
	public void permanent403Drops() throws Exception
	{
		File file = tmp.newFile("queue.json");
		FakeScheduler sched = new FakeScheduler();
		FakeApi api = new FakeApi(config());
		api.responses.add(new ApiException(403, "mismatch"));
		EventQueue q = queue(file, sched, api);
		q.enqueue(report("a"));
		sched.runOne();
		assertEquals("attempted once", 1, api.sent.size());
		assertTrue("permanent failure drops the report", readEntries(file).isEmpty());
		assertTrue("permanent failure does not reschedule", sched.tasks.isEmpty());
	}

	@Test
	public void malformedSuccessDropsAfterFiveAttempts() throws Exception
	{
		File file = tmp.newFile("queue.json");
		FakeScheduler sched = new FakeScheduler();
		FakeApi api = new FakeApi(config());
		for (int i = 0; i < 5; i++)
		{
			api.responses.add(ApiException.malformedSuccess("bad_response", null));
		}
		EventQueue q = queue(file, sched, api);
		q.enqueue(report("a"));
		sched.runAll();
		assertEquals("exactly five attempts", 5, api.sent.size());
		assertTrue("dropped after the fifth malformed success", readEntries(file).isEmpty());
	}

	@Test
	public void attemptsSurviveRestart() throws Exception
	{
		File file = tmp.newFile("queue.json");
		FakeScheduler sched = new FakeScheduler();
		FakeApi api = new FakeApi(config());
		api.responses.add(ApiException.malformedSuccess("bad_response", null));
		api.responses.add(ApiException.malformedSuccess("bad_response", null));
		EventQueue q = queue(file, sched, api);
		q.enqueue(report("a"));
		sched.runOne(); // attempt 1 → attempts = 1
		sched.runOne(); // attempt 2 → attempts = 2
		// The persisted file (what a restart's load() reads) carries the attempt count.
		List<EventQueue.QueueEntry> entries = readEntries(file);
		assertEquals(1, entries.size());
		assertEquals(2, entries.get(0).attempts);
	}

	@Test
	public void oldQueueFileMigrates() throws Exception
	{
		File file = tmp.newFile("queue.json");
		java.nio.file.Files.write(file.toPath(),
			"[{\"idempotencyKey\":\"k\",\"activity\":\"KILL_LOW_MONSTER\"}]"
				.getBytes(java.nio.charset.StandardCharsets.UTF_8));
		FakeScheduler sched = new FakeScheduler();
		FakeApi api = new FakeApi(config());
		EventQueue q = queue(file, sched, api);
		q.load();
		sched.runAll();
		assertEquals(1, api.sent.size());
		assertEquals("KILL_LOW_MONSTER", api.sent.get(0).activity);
	}

	@Test
	public void enqueueDuringDrainExitIsNotLost() throws Exception
	{
		File file = tmp.newFile("queue.json");
		FakeScheduler sched = new FakeScheduler();
		FakeApi api = new FakeApi(config());
		EventQueue q = queue(file, sched, api);
		final boolean[] enqueuedB = {false};
		api.onReport = r ->
		{
			if (!enqueuedB[0])
			{
				enqueuedB[0] = true;
				q.enqueue(report("b")); // races in mid-drain; scheduleDrain's CAS fails
			}
		};
		q.enqueue(report("a"));
		sched.runAll();
		assertEquals("both A and B delivered", 2, api.sent.size());
		assertTrue(readEntries(file).isEmpty());
	}

	@Test
	public void callbackExceptionDoesNotWedgeQueue() throws Exception
	{
		File file = tmp.newFile("queue.json");
		FakeScheduler sched = new FakeScheduler();
		FakeApi api = new FakeApi(config());
		api.dropped = true;
		EventQueue q = queue(file, sched, api);
		q.setOnDrop((r, res) ->
		{
			throw new RuntimeException("boom");
		});
		q.enqueue(report("a"));
		q.enqueue(report("b"));
		sched.runAll(); // the callback throws on each — must not escape or wedge the queue
		assertEquals(2, api.sent.size());
		assertTrue(readEntries(file).isEmpty());
	}

	@Test
	public void queueCapsAt500DroppingOldest() throws Exception
	{
		File file = tmp.newFile("queue.json");
		FakeScheduler sched = new FakeScheduler();
		FakeApi api = new FakeApi(config());
		EventQueue q = queue(file, sched, api);
		q.pause();
		for (int i = 0; i < 501; i++)
		{
			q.enqueue(report("k" + i));
		}
		List<EventQueue.QueueEntry> entries = readEntries(file);
		assertEquals(500, entries.size());
		assertEquals("oldest dropped", "k1", entries.get(0).report.idempotencyKey);
		assertEquals("newest kept", "k500", entries.get(entries.size() - 1).report.idempotencyKey);
	}
}
