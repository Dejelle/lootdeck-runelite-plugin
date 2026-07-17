package com.lootdeck.tcg.net;

import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;
import java.io.File;
import java.io.IOException;
import java.lang.reflect.Type;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BiConsumer;
import javax.inject.Inject;
import javax.inject.Singleton;
import net.runelite.client.RuneLite;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Off-thread drop-report pipeline. Reports are persisted to a file so a crash never loses drops;
 * a single-thread executor drains them. Idempotency keys make retries safe (no double-grant).
 */
@Singleton
public class EventQueue
{
	private static final Logger log = LoggerFactory.getLogger(EventQueue.class);
	private static final Type LIST_TYPE = new TypeToken<List<QueueEntry>>()
	{
	}.getType();
	// Pre-v1.4 files hold a bare List<DropReport>; load() migrates them (M2/L3).
	private static final Type OLD_LIST_TYPE = new TypeToken<List<Dtos.DropReport>>()
	{
	}.getType();
	private static final File DEFAULT_QUEUE_FILE = new File(RuneLite.RUNELITE_DIR, "lootdeck/queue.json");
	private static final int MAX_PENDING = 500;
	private static final int MAX_MALFORMED_ATTEMPTS = 5;

	/** Persisted queue entry. Wraps the wire-format report so bookkeeping never leaks to the API. */
	static final class QueueEntry
	{
		Dtos.DropReport report;
		int attempts;

		QueueEntry()
		{
		}

		QueueEntry(Dtos.DropReport report)
		{
			this.report = report;
		}
	}

	private final TcgApiClient api;
	private final Gson gson;
	private final File queueFile;
	private final ScheduledExecutorService executor;

	private final List<QueueEntry> pending = new ArrayList<>();
	private final java.util.Random retryJitter = new java.util.Random();
	private final AtomicBoolean draining = new AtomicBoolean(false);
	// volatile: shutDown() sets this to null on a different thread than drain() reads it (audit H4).
	private volatile BiConsumer<Dtos.DropReport, Dtos.DropResult> onDrop = (rep, res) -> {
	};
	// While paused (plugin disabled) the queue does ZERO network I/O; reports stay in `pending`
	// (persisted) and resume() restarts draining on re-enable (audit H4).
	private volatile boolean paused = false;
	// load() must only append from disk once per JVM — the in-memory list is the source of truth
	// afterwards; a second load() on plugin re-enable would double every queued report.
	private boolean loaded = false;

	@Inject
	public EventQueue(TcgApiClient api, Gson gson)
	{
		// A DEDICATED single thread for draining drops — never RuneLite's shared single-thread
		// executor. Sharing it meant a slow panel refresh (3 sequential HTTP calls) blocked the
		// drop report, so a pack could take ~a minute to appear after the kill. Draining must stay
		// single-threaded to preserve order + idempotency. Daemon so it never blocks JVM shutdown.
		this(api, gson, DEFAULT_QUEUE_FILE, Executors.newSingleThreadScheduledExecutor(r ->
		{
			Thread t = new Thread(r, "lootdeck-drops");
			t.setDaemon(true);
			return t;
		}));
	}

	// Package-private: tests inject a temp file + a deterministic executor.
	EventQueue(TcgApiClient api, Gson gson, File queueFile, ScheduledExecutorService executor)
	{
		this.api = api;
		this.gson = gson;
		this.queueFile = queueFile;
		this.executor = executor;
	}

	public void setOnDrop(BiConsumer<Dtos.DropReport, Dtos.DropResult> onDrop)
	{
		this.onDrop = onDrop;
	}

	public void pause()
	{
		paused = true;
	}

	public void resume()
	{
		paused = false;
		synchronized (this)
		{
			if (!pending.isEmpty())
			{
				scheduleDrain(0);
			}
		}
	}

	public synchronized void load()
	{
		if (loaded)
		{
			return;
		}
		loaded = true;
		try
		{
			if (queueFile.exists())
			{
				String json = new String(Files.readAllBytes(queueFile.toPath()), StandardCharsets.UTF_8);
				List<QueueEntry> saved = gson.fromJson(json, LIST_TYPE);
				if (saved != null && !saved.isEmpty() && saved.get(0) != null && saved.get(0).report == null)
				{
					// Pre-v1.4 file format: a bare list of reports. Re-read and wrap.
					List<Dtos.DropReport> old = gson.fromJson(json, OLD_LIST_TYPE);
					saved = new ArrayList<>();
					if (old != null)
					{
						for (Dtos.DropReport r : old)
						{
							saved.add(new QueueEntry(r));
						}
					}
				}
				if (saved != null)
				{
					pending.addAll(saved);
				}
			}
		}
		catch (Exception e)
		{
			log.warn("failed to load drop queue: {}", e.getMessage());
		}
		if (!pending.isEmpty())
		{
			scheduleDrain(0);
		}
	}

	public void enqueue(Dtos.DropReport report)
	{
		synchronized (this)
		{
			pending.add(new QueueEntry(report));
			while (pending.size() > MAX_PENDING)
			{
				// Oldest first: the newest reports are the ones the player is watching for.
				QueueEntry dropped = pending.remove(0);
				log.warn("[LootDeck] queue over {} entries — dropping oldest report activity={}",
					MAX_PENDING, dropped.report != null ? dropped.report.activity : "?");
			}
			persist();
		}
		scheduleDrain(0);
	}

	private synchronized void persist()
	{
		try
		{
			//noinspection ResultOfMethodCallIgnored
			queueFile.getParentFile().mkdirs();
			File tmp = new File(queueFile.getParentFile(), queueFile.getName() + ".tmp");
			Files.write(tmp.toPath(), gson.toJson(pending, LIST_TYPE).getBytes(StandardCharsets.UTF_8));
			try
			{
				Files.move(tmp.toPath(), queueFile.toPath(),
					java.nio.file.StandardCopyOption.ATOMIC_MOVE,
					java.nio.file.StandardCopyOption.REPLACE_EXISTING);
			}
			catch (java.nio.file.AtomicMoveNotSupportedException e)
			{
				Files.move(tmp.toPath(), queueFile.toPath(),
					java.nio.file.StandardCopyOption.REPLACE_EXISTING);
			}
		}
		catch (IOException e)
		{
			log.warn("failed to persist drop queue: {}", e.getMessage());
		}
	}

	private void scheduleDrain(long delaySeconds)
	{
		if (paused)
		{
			return; // resume() reschedules
		}
		if (draining.compareAndSet(false, true))
		{
			executor.schedule(this::drain, delaySeconds, TimeUnit.SECONDS);
		}
	}

	/** Flat 15s retry with ±20% jitter, so a fleet of clients doesn't retry in lockstep (L14). */
	private long jitteredRetrySeconds()
	{
		return Math.max(1, Math.round(15 * (0.8 + retryJitter.nextDouble() * 0.4)));
	}

	private void drain()
	{
		boolean reschedule = false;
		long delaySeconds = 0;
		try
		{
			while (true)
			{
				if (paused)
				{
					return;
				}
				QueueEntry entry;
				synchronized (this)
				{
					if (pending.isEmpty())
					{
						return;
					}
					entry = pending.get(0);
				}
				try
				{
					Dtos.DropResult result = api.reportDrop(entry.report);
					synchronized (this)
					{
						pending.remove(entry);
						persist();
					}
					log.debug("[LootDeck] report sent activity={} -> dropped={}",
						entry.report.activity, result != null && result.dropped);
					if (result != null && result.dropped)
					{
						BiConsumer<Dtos.DropReport, Dtos.DropResult> cb = onDrop;
						if (cb != null)
						{
							try
							{
								cb.accept(entry.report, result);
							}
							catch (RuntimeException ex)
							{
								// A listener bug must never wedge the queue (L2).
								log.warn("[LootDeck] onDrop callback failed: {}", ex.toString());
							}
						}
					}
				}
				catch (ApiException e)
				{
					log.warn("[LootDeck] report failed activity={} code={} msg={}",
						entry.report.activity, e.getCode(), e.getMessage());
					switch (e.classify())
					{
						case TRANSIENT:
							reschedule = true;
							delaySeconds = e.getRetryAfterSeconds() > 0
								? e.getRetryAfterSeconds() : jitteredRetrySeconds();
							return;
						case MALFORMED_SUCCESS:
							// The grant already happened server-side; bounded retries only
							// chase the client-side toast (M2).
							entry.attempts++;
							if (entry.attempts >= MAX_MALFORMED_ATTEMPTS)
							{
								log.warn("dropping report after {} malformed-success retries", entry.attempts);
								synchronized (this)
								{
									pending.remove(entry);
									persist();
								}
							}
							else
							{
								synchronized (this)
								{
									persist(); // keep the attempt count across restarts
								}
								reschedule = true;
								delaySeconds = jitteredRetrySeconds();
								return;
							}
							break;
						case PERMANENT:
						default:
							log.warn("dropping un-retryable report: {}", e.getMessage());
							synchronized (this)
							{
								pending.remove(entry);
								persist();
							}
							break;
					}
				}
			}
		}
		finally
		{
			draining.set(false);
			if (reschedule)
			{
				scheduleDrain(delaySeconds);
			}
			else
			{
				// Lost-wakeup double check (L2): an enqueue() that raced our exit found
				// draining == true and scheduled nothing — pick its report up now.
				boolean nonEmpty;
				synchronized (this)
				{
					nonEmpty = !pending.isEmpty();
				}
				if (nonEmpty)
				{
					scheduleDrain(0);
				}
			}
		}
	}
}
