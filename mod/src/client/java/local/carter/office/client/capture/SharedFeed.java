package local.carter.office.client.capture;

import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;

import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;

import com.mojang.logging.LogUtils;

/**
 * One capture shared by many sinks (Windows Mirror: every Mirror hologram shows the same primary screen, so one
 * {@code ScreenCapturer} feeds them all). The producer runs on its own daemon thread while at least one sink is
 * subscribed and stops with the last {@link #unsubscribe}. Frames go to every sink, in subscription order, under the
 * feed's lock, so once {@link #unsubscribe} returns that sink is never called again. When the producer ends (or
 * throws) every subscriber gets {@link FrameSink#ended} once and the feed is idle until the next subscribe.
 */
public final class SharedFeed {
	private static final Logger LOGGER = LogUtils.getLogger();

	/** The capture loop: runs until {@link Output#running()} turns false, or ends itself with {@link Output#end}. */
	public interface Producer {
		void run(Output out) throws Exception;
	}

	public interface Output {
		boolean running();

		/** Delivers a full frame to every subscriber (the buffer is only read during this call). */
		void frame(int w, int h, ByteBuffer rgba);

		/** The capture can't go on: every subscriber is ended with {@code reason}. */
		void end(String reason);

		/** Sleeps until {@code nanoDeadline} ({@link System#nanoTime}) or until the feed stops. */
		void sleepUntil(long nanoDeadline) throws InterruptedException;
	}

	private final String name;
	private final Supplier<Producer> producers;
	private final Object lock = new Object();
	private final List<FrameSink> sinks = new ArrayList<>();
	private @Nullable Run run;

	public SharedFeed(final String name, final Supplier<Producer> producers) {
		this.name = name;
		this.producers = producers;
	}

	public void subscribe(final FrameSink sink) {
		synchronized (lock) {
			if (!sinks.contains(sink)) {
				sinks.add(sink);
			}
			if (run == null) {
				Run r = new Run();
				run = r;
				r.thread.start();
			}
		}
	}

	/** Removes {@code sink}; the last one out stops the capture and waits (3 s at most) for its thread. */
	public void unsubscribe(final FrameSink sink) {
		Run stopping = null;
		synchronized (lock) {
			sinks.remove(sink);
			if (sinks.isEmpty() && run != null) {
				stopping = run;
				stopping.alive = false;
				run = null;
				lock.notifyAll();
			}
		}
		if (stopping != null && stopping.thread != Thread.currentThread()) {
			try {
				stopping.thread.join(3_000);
				if (stopping.thread.isAlive()) {
					LOGGER.warn("{} capture thread did not stop within 3 s", name);
				}
			} catch (InterruptedException e) {
				Thread.currentThread().interrupt();
			}
		}
	}

	/** True while a capture thread runs for current subscribers. */
	public boolean active() {
		synchronized (lock) {
			return run != null;
		}
	}

	public int subscribers() {
		synchronized (lock) {
			return sinks.size();
		}
	}

	/** One producer run; stale once the feed stopped or restarted (its frames are then dropped). */
	private final class Run implements Output {
		volatile boolean alive = true;
		final Thread thread = new Thread(this::loop, "office-" + "shared-" + SharedFeed.this.name);

		Run() {
			thread.setDaemon(true);
		}

		private void loop() {
			try {
				producers.get().run(this);
			} catch (Throwable e) {
				if (alive) {
					LOGGER.warn("{} capture failed", name, e);
					end("Screen capture failed");
				}
			} finally {
				synchronized (lock) {
					if (run == this) {
						run = null; // ended on its own
					}
				}
			}
		}

		@Override
		public boolean running() {
			return alive;
		}

		@Override
		public void frame(final int w, final int h, final ByteBuffer rgba) {
			synchronized (lock) {
				if (!alive) {
					return;
				}
				for (FrameSink s : new ArrayList<>(sinks)) { // a copy: a sink may unsubscribe inside full()
					if (!alive || !sinks.contains(s)) {
						continue; // unsubscribed (or the feed stopped) during this delivery
					}
					try {
						s.full(w, h, rgba.duplicate());
					} catch (RuntimeException e) {
						LOGGER.warn("{}: a sink failed", name, e);
					}
				}
			}
		}

		@Override
		public void end(final String reason) {
			List<FrameSink> ended;
			synchronized (lock) {
				if (!alive) {
					return;
				}
				alive = false;
				if (run == this) {
					run = null;
				}
				ended = new ArrayList<>(sinks);
				sinks.clear();
				for (FrameSink s : ended) {
					try {
						s.ended(reason);
					} catch (RuntimeException e) {
						LOGGER.warn("{}: a sink failed on end", name, e);
					}
				}
			}
			LOGGER.info("{} capture ended: {}", name, reason);
		}

		@Override
		public void sleepUntil(final long nanoDeadline) throws InterruptedException {
			synchronized (lock) {
				while (alive) {
					long ms = (nanoDeadline - System.nanoTime()) / 1_000_000;
					if (ms <= 0) {
						return;
					}
					lock.wait(ms);
				}
			}
		}
	}
}
