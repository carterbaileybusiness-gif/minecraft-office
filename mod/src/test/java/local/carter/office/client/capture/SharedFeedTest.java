package local.carter.office.client.capture;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.ByteBuffer;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;

import org.junit.jupiter.api.Test;

/** Windows Mirror: every Mirror hologram shares one screen capture; it runs while anyone subscribes, then stops. */
class SharedFeedTest {
	static final class Sink implements FrameSink {
		final AtomicInteger fulls = new AtomicInteger();
		final List<String> ended = new CopyOnWriteArrayList<>();

		@Override
		public void full(final int w, final int h, final ByteBuffer rgba) {
			fulls.incrementAndGet();
		}

		@Override
		public void patch(final int x, final int y, final int w, final int h, final ByteBuffer rgba) {}

		@Override
		public void ended(final String reason) {
			ended.add(reason);
		}
	}

	/** A producer that sends a 1x1 frame every 5 ms until the feed stops it (or ends after {@code endAfter} frames). */
	static final class Fake implements SharedFeed.Producer {
		final AtomicInteger runs = new AtomicInteger();
		final AtomicInteger live = new AtomicInteger();
		volatile int endAfter = -1;

		@Override
		public void run(final SharedFeed.Output out) throws InterruptedException {
			runs.incrementAndGet();
			live.incrementAndGet();
			try {
				int n = 0;
				while (out.running()) {
					out.frame(1, 1, ByteBuffer.allocateDirect(4));
					if (++n == endAfter) {
						out.end("screen gone");
						return;
					}
					out.sleepUntil(System.nanoTime() + 5_000_000L);
				}
			} finally {
				live.decrementAndGet();
			}
		}
	}

	private static boolean waitUntil(final BooleanSupplier c) throws InterruptedException {
		long end = System.currentTimeMillis() + 3_000;
		while (System.currentTimeMillis() < end) {
			if (c.getAsBoolean()) {
				return true;
			}
			Thread.sleep(5);
		}
		return c.getAsBoolean();
	}

	@Test
	void twoHologramsShareOneCaptureThatStopsWithTheLast() throws Exception {
		Fake fake = new Fake();
		SharedFeed feed = new SharedFeed("test", () -> fake);
		Sink a = new Sink();
		Sink b = new Sink();
		feed.subscribe(a);
		feed.subscribe(b);
		assertTrue(waitUntil(() -> a.fulls.get() > 3 && b.fulls.get() > 3), "both get frames");
		assertEquals(1, fake.runs.get(), "one capture for both");

		feed.unsubscribe(a);
		int seen = a.fulls.get();
		assertTrue(waitUntil(() -> b.fulls.get() > 10));
		assertEquals(seen, a.fulls.get(), "nothing reaches a sink after unsubscribe returns");
		assertEquals(1, fake.live.get(), "still running for b");

		feed.unsubscribe(b);
		assertEquals(0, fake.live.get(), "the last unsubscribe stops (and waits for) the capture");
		assertFalse(feed.active());

		feed.subscribe(a);
		assertTrue(waitUntil(() -> fake.runs.get() == 2 && a.fulls.get() > seen), "a new subscriber starts it again");
		feed.unsubscribe(a);
	}

	@Test
	void anEndReachesEverySubscriberOnce() throws Exception {
		Fake fake = new Fake();
		fake.endAfter = 3;
		SharedFeed feed = new SharedFeed("test", () -> fake);
		Sink a = new Sink();
		Sink b = new Sink();
		feed.subscribe(a);
		feed.subscribe(b);
		assertTrue(waitUntil(() -> !a.ended.isEmpty() && !b.ended.isEmpty()));
		assertEquals(List.of("screen gone"), a.ended);
		assertEquals(List.of("screen gone"), b.ended);
		assertTrue(waitUntil(() -> !feed.active()));
		feed.unsubscribe(a); // harmless after the end
		feed.unsubscribe(b);
	}
}
