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
	private static final Type LIST_TYPE = new TypeToken<List<Dtos.DropReport>>()
	{
	}.getType();
	private static final File QUEUE_FILE = new File(RuneLite.RUNELITE_DIR, "lootdeck/queue.json");

	private final TcgApiClient api;
	private final Gson gson;

	// A DEDICATED single thread for draining drops — never RuneLite's shared single-thread
	// executor. Sharing it meant a slow panel refresh (3 sequential HTTP calls) blocked the
	// drop report, so a pack could take ~a minute to appear after the kill. Draining must stay
	// single-threaded to preserve order + idempotency. Daemon so it never blocks JVM shutdown.
	private final ScheduledExecutorService executor =
		Executors.newSingleThreadScheduledExecutor(r ->
		{
			Thread t = new Thread(r, "lootdeck-drops");
			t.setDaemon(true);
			return t;
		});

	private final List<Dtos.DropReport> pending = new ArrayList<>();
	private final AtomicBoolean draining = new AtomicBoolean(false);
	private BiConsumer<Dtos.DropReport, Dtos.DropResult> onDrop = (rep, res) -> {
	};

	@Inject
	public EventQueue(TcgApiClient api, Gson gson)
	{
		this.api = api;
		this.gson = gson;
	}

	public void setOnDrop(BiConsumer<Dtos.DropReport, Dtos.DropResult> onDrop)
	{
		this.onDrop = onDrop;
	}

	public synchronized void load()
	{
		try
		{
			if (QUEUE_FILE.exists())
			{
				String json = new String(Files.readAllBytes(QUEUE_FILE.toPath()), StandardCharsets.UTF_8);
				List<Dtos.DropReport> saved = gson.fromJson(json, LIST_TYPE);
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
			pending.add(report);
			persist();
		}
		scheduleDrain(0);
	}

	private synchronized void persist()
	{
		try
		{
			//noinspection ResultOfMethodCallIgnored
			QUEUE_FILE.getParentFile().mkdirs();
			Files.write(QUEUE_FILE.toPath(), gson.toJson(pending, LIST_TYPE).getBytes(StandardCharsets.UTF_8));
		}
		catch (IOException e)
		{
			log.warn("failed to persist drop queue: {}", e.getMessage());
		}
	}

	private void scheduleDrain(long delaySeconds)
	{
		if (draining.compareAndSet(false, true))
		{
			executor.schedule(this::drain, delaySeconds, TimeUnit.SECONDS);
		}
	}

	private void drain()
	{
		boolean reschedule = false;
		try
		{
			while (true)
			{
				Dtos.DropReport next;
				synchronized (this)
				{
					if (pending.isEmpty())
					{
						return;
					}
					next = pending.get(0);
				}
				try
				{
					Dtos.DropResult result = api.reportDrop(next);
					synchronized (this)
					{
						pending.remove(next);
						persist();
					}
					log.info("[LootDeck] report sent activity={} -> dropped={}",
						next.activity, result != null && result.dropped);
					if (result != null && result.dropped)
					{
						onDrop.accept(next, result);
					}
				}
				catch (ApiException e)
				{
					log.warn("[LootDeck] report failed activity={} code={} msg={}",
						next.activity, e.getCode(), e.getMessage());
					if (e.isRetryable())
					{
						// Leave it queued; retry after backoff.
						reschedule = true;
						return;
					}
					// Non-retryable (e.g. 400 bad report, 403 mismatch): drop it to avoid a poison pill.
					log.warn("dropping un-retryable report: {}", e.getMessage());
					synchronized (this)
					{
						pending.remove(next);
						persist();
					}
				}
			}
		}
		finally
		{
			draining.set(false);
			if (reschedule)
			{
				scheduleDrain(15);
			}
		}
	}
}
