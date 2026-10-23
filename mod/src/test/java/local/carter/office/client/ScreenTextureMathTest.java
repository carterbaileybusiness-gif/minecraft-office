package local.carter.office.client;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.ByteBuffer;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.Test;

import local.carter.office.client.capture.FrameSink;
import local.carter.office.client.capture.Target;
import local.carter.office.client.capture.TestPatternSource;
import local.carter.office.client.render.ScreenTexture;
import local.carter.office.client.render.ScreenTextureMath;
import local.carter.office.client.render.ScreenTextureMath.Rect;
import local.carter.office.core.Mode;
import local.carter.office.core.Shape;

import net.minecraft.resources.Identifier;

class ScreenTextureMathTest {
	/** Records what would reach the GPU. */
	static final class FakeGpu implements ScreenTexture.Gpu {
		final List<int[]> allocations = new ArrayList<>();
		final List<Write> writes = new ArrayList<>();
		int frees;
		boolean onRenderThread = true;
		final List<Runnable> queued = new ArrayList<>();

		@Override
		public boolean canFreeNow() {
			return onRenderThread;
		}

		@Override
		public void runOnRenderThread(final Runnable task) {
			queued.add(task);
		}

		void runQueued() {
			List<Runnable> tasks = new ArrayList<>(queued);
			queued.clear();
			tasks.forEach(Runnable::run);
		}

		record Write(int level, int x, int y, int w, int h, byte[] data) {}

		@Override
		public void allocate(final int width, final int height, final int levels) {
			allocations.add(new int[] {width, height, levels});
		}

		@Override
		public void write(final int level, final int x, final int y, final int w, final int h, final ByteBuffer rgba) {
			assertEquals((long) w * h * 4, rgba.remaining(), "upload buffer holds exactly the rectangle");
			byte[] copy = new byte[rgba.remaining()];
			rgba.duplicate().get(copy);
			writes.add(new Write(level, x, y, w, h, copy));
		}

		@Override
		public void free() {
			frees++;
		}

		List<Write> level0() {
			return writes.stream().filter(w -> w.level == 0).toList();
		}
	}

	private static ScreenTexture texture(final FakeGpu gpu) {
		return new ScreenTexture(Identifier.fromNamespaceAndPath("office", "screen/test"), gpu);
	}

	/** w*h RGBA where pixel (x, y) = (x, y, seed, 255). */
	private static ByteBuffer image(final int w, final int h, final int seed) {
		ByteBuffer b = ByteBuffer.allocate(w * h * 4);
		for (int y = 0; y < h; y++) {
			for (int x = 0; x < w; x++) {
				b.put((byte) x).put((byte) y).put((byte) seed).put((byte) 255);
			}
		}
		return b.flip();
	}

	private static ByteBuffer solid(final int w, final int h, final int r, final int g, final int b) {
		ByteBuffer buf = ByteBuffer.allocate(w * h * 4);
		for (int i = 0; i < w * h; i++) {
			buf.put((byte) r).put((byte) g).put((byte) b).put((byte) 255);
		}
		return buf.flip();
	}

	private static int[] px(final FakeGpu.Write w, final int x, final int y) {
		int i = (y * w.w() + x) * 4;
		return new int[] {w.data()[i] & 0xFF, w.data()[i + 1] & 0xFF, w.data()[i + 2] & 0xFF, w.data()[i + 3] & 0xFF};
	}

	// ---- pure math ----

	@Test
	void rectClipsToBounds() {
		assertEquals(new Rect(6, 4, 2, 2), Rect.clip(6, 4, 4, 4, 8, 6));
		assertEquals(new Rect(0, 0, 2, 2), Rect.clip(-2, -1, 4, 3, 8, 6));
		assertEquals(new Rect(0, 0, 8, 6), Rect.clip(-100, -100, 1000, 1000, 8, 6));
		assertNull(Rect.clip(8, 0, 4, 4, 8, 6), "entirely right of the texture");
		assertNull(Rect.clip(0, -4, 4, 4, 8, 6), "entirely above the texture");
		assertNull(Rect.clip(1, 1, 0, 3, 8, 6), "empty");
		assertNull(Rect.clip(Integer.MAX_VALUE - 1, 0, Integer.MAX_VALUE, 1, 8, 6), "no int overflow");
	}

	@Test
	void mipLevelsStopAtSixtyFourPixels() {
		assertEquals(5, ScreenTextureMath.mipLevels(1920, 1080)); // 1920, 960, 480, 240, 120
		assertEquals(5, ScreenTextureMath.mipLevels(1080, 1920));
		assertEquals(ScreenTextureMath.MAX_MIP_LEVELS, ScreenTextureMath.mipLevels(2880, 1620));
		assertEquals(1, ScreenTextureMath.mipLevels(64, 64));
		assertEquals(1, ScreenTextureMath.mipLevels(8, 6));
		assertEquals(2, ScreenTextureMath.mipLevels(128, 2)); // never a 0-high level
		assertEquals(1, ScreenTextureMath.mipLevels(128, 1));
	}

	@Test
	void rectAtLevelCoversEveryTouchedTexel() {
		Rect r = new Rect(5, 3, 4, 1); // x 5..8, y 3
		assertEquals(new Rect(2, 1, 3, 1), r.atLevel(1, 960, 540)); // 2..4
		assertEquals(new Rect(1, 0, 2, 1), r.atLevel(2, 480, 270)); // 1..2
		assertTrue(new Rect(1918, 1078, 2, 2).atLevel(5, 60, 33).isEmpty(), "rows past the last whole texel of a level");
		assertEquals(new Rect(59, 33, 1, 1), new Rect(1918, 1078, 2, 2).atLevel(5, 60, 34)); // clamped to the level
	}

	@Test
	void downsampleAveragesTwoByTwo() {
		ByteBuffer src = ByteBuffer.allocate(2 * 2 * 4);
		src.put(new byte[] {0, 0, 0, (byte) 255, 100, 0, 0, (byte) 255, 0, (byte) 200, 0, (byte) 255, 0, 0, (byte) 255, (byte) 255}).flip();
		ByteBuffer dst = ByteBuffer.allocate(4);
		ScreenTextureMath.downsample(src, 2, 2, dst, 1, 1, new Rect(0, 0, 1, 1));
		assertArrayEquals(new byte[] {25, 50, 64, (byte) 255}, dst.array()); // 255/4 rounds to 64
	}

	// ---- ScreenTexture pipeline (fake GPU) ----

	@Test
	void patchRectIsClippedToTextureBounds() {
		FakeGpu gpu = new FakeGpu();
		ScreenTexture t = texture(gpu);
		t.full(8, 6, solid(8, 6, 0, 0, 0));
		assertTrue(t.prepareForRender());
		gpu.writes.clear();

		t.patch(6, 4, 4, 4, image(4, 4, 7)); // hangs 2 px off the right and bottom
		t.patch(-2, -1, 4, 3, image(4, 3, 9)); // hangs off the left and top
		t.patch(20, 20, 4, 4, image(4, 4, 1)); // fully outside: ignored
		assertTrue(t.prepareForRender());

		List<FakeGpu.Write> l0 = gpu.level0();
		assertEquals(2, l0.size());
		FakeGpu.Write a = l0.get(0);
		assertEquals(List.of(6, 4, 2, 2), List.of(a.x(), a.y(), a.w(), a.h()));
		assertArrayEquals(new int[] {0, 0, 7, 255}, px(a, 0, 0)); // source pixel (0,0)
		assertArrayEquals(new int[] {1, 1, 7, 255}, px(a, 1, 1));
		FakeGpu.Write b = l0.get(1);
		assertEquals(List.of(0, 0, 2, 2), List.of(b.x(), b.y(), b.w(), b.h()));
		assertArrayEquals(new int[] {2, 1, 9, 255}, px(b, 0, 0)); // source pixel (2,1): the part inside the texture
		assertArrayEquals(new int[] {3, 2, 9, 255}, px(b, 1, 1));
	}

	@Test
	void sizeChangeReallocates() {
		FakeGpu gpu = new FakeGpu();
		ScreenTexture t = texture(gpu);
		t.full(1920, 1080, solid(1920, 1080, 10, 20, 30));
		assertTrue(t.prepareForRender());
		assertEquals(1, gpu.allocations.size());
		assertArrayEquals(new int[] {1920, 1080, 5}, gpu.allocations.get(0));

		t.full(1920, 1080, solid(1920, 1080, 11, 21, 31)); // same size: no new allocation
		t.prepareForRender();
		assertEquals(1, gpu.allocations.size());

		gpu.writes.clear();
		t.full(2880, 1620, solid(2880, 1620, 40, 50, 60));
		assertEquals(2880, t.width());
		assertEquals(1620, t.height());
		assertTrue(t.prepareForRender());
		assertEquals(2, gpu.allocations.size());
		assertArrayEquals(new int[] {2880, 1620, ScreenTextureMath.mipLevels(2880, 1620)}, gpu.allocations.get(1));
		// the whole new frame (every mip level) is uploaded at the new size
		assertEquals(ScreenTextureMath.mipLevels(2880, 1620), gpu.writes.size());
		FakeGpu.Write l0 = gpu.level0().get(0);
		assertEquals(List.of(0, 0, 2880, 1620), List.of(l0.x(), l0.y(), l0.w(), l0.h()));
		assertArrayEquals(new int[] {40, 50, 60, 255}, px(l0, 2879, 1619));
	}

	@Test
	void clearDropsPendingPatches() {
		FakeGpu gpu = new FakeGpu();
		ScreenTexture t = texture(gpu);
		t.full(8, 6, solid(8, 6, 1, 2, 3));
		assertTrue(t.prepareForRender());
		t.patch(0, 0, 2, 2, image(2, 2, 5));
		t.patch(4, 4, 2, 2, image(2, 2, 6));
		gpu.writes.clear();

		t.clear();
		assertEquals(1, gpu.frees, "GPU texture freed");
		assertEquals(0, t.width());
		assertFalse(t.hasFrame());
		assertFalse(t.prepareForRender(), "nothing to draw after clear");
		assertTrue(gpu.writes.isEmpty(), "pending patches were dropped");
		assertTrue(t.needsFullFrame());

		// a patch after clear cannot apply (no frame) and is dropped as well
		t.patch(0, 0, 2, 2, image(2, 2, 5));
		assertFalse(t.prepareForRender());
		assertTrue(gpu.writes.isEmpty());

		// the texture is reusable: a new full frame allocates again
		t.full(8, 6, solid(8, 6, 7, 7, 7));
		assertTrue(t.prepareForRender());
		assertEquals(2, gpu.allocations.size());
	}

	@Test
	void newestFullFrameWins() {
		FakeGpu gpu = new FakeGpu();
		ScreenTexture t = texture(gpu);
		t.full(8, 6, solid(8, 6, 1, 1, 1));
		t.patch(0, 0, 2, 2, image(2, 2, 5));
		t.full(8, 6, solid(8, 6, 9, 9, 9));
		assertTrue(t.prepareForRender());
		List<FakeGpu.Write> l0 = gpu.level0();
		assertEquals(1, l0.size(), "one upload for everything that queued up");
		assertArrayEquals(new int[] {9, 9, 9, 255}, px(l0.get(0), 0, 0));
		gpu.writes.clear();
		assertTrue(t.prepareForRender());
		assertTrue(gpu.writes.isEmpty(), "nothing re-uploaded when nothing changed");
	}

	@Test
	void patchesCoalesceAndOverflowBecomesOneFullUpload() {
		FakeGpu gpu = new FakeGpu();
		ScreenTexture t = texture(gpu);
		t.full(256, 256, solid(256, 256, 0, 0, 0));
		t.prepareForRender();
		gpu.writes.clear();

		t.patch(10, 10, 4, 4, image(4, 4, 1));
		t.patch(10, 10, 4, 4, image(4, 4, 2)); // same place again: newest wins, one upload
		t.patch(11, 11, 2, 2, image(2, 2, 3)); // inside the first: no extra upload
		t.prepareForRender();
		List<FakeGpu.Write> l0 = gpu.level0();
		assertEquals(1, l0.size());
		assertArrayEquals(new int[] {0, 0, 2, 255}, px(l0.get(0), 0, 0));
		assertArrayEquals(new int[] {0, 0, 3, 255}, px(l0.get(0), 1, 1));

		gpu.writes.clear();
		for (int i = 0; i < ScreenTexture.MAX_PENDING_RECTS + 5; i++) {
			t.patch(i * 3 % 250, i * 7 % 250, 2, 2, image(2, 2, i));
		}
		t.prepareForRender();
		l0 = gpu.level0();
		assertEquals(1, l0.size(), "overflowing the patch queue falls back to one whole-texture upload");
		assertEquals(List.of(0, 0, 256, 256), List.of(l0.get(0).x(), l0.get(0).y(), l0.get(0).w(), l0.get(0).h()));
	}

	@Test
	void patchBeforeFirstFrameAsksForAFullFrame() {
		FakeGpu gpu = new FakeGpu();
		ScreenTexture t = texture(gpu);
		assertTrue(t.needsFullFrame());
		t.patch(0, 0, 2, 2, image(2, 2, 1));
		assertFalse(t.hasFrame());
		assertFalse(t.prepareForRender());
		assertTrue(gpu.allocations.isEmpty());
		t.full(8, 6, solid(8, 6, 1, 1, 1));
		assertFalse(t.needsFullFrame());
		assertTrue(t.hasFrame());
	}

	@Test
	void patchUpdatesTheMipLevelsUnderIt() {
		FakeGpu gpu = new FakeGpu();
		ScreenTexture t = texture(gpu);
		t.full(256, 128, solid(256, 128, 0, 0, 0));
		t.prepareForRender();
		gpu.writes.clear();
		t.patch(4, 4, 4, 4, solid(4, 4, 200, 100, 40));
		t.prepareForRender();
		assertEquals(3, gpu.writes.size()); // 256x128 has three levels (256, 128, 64 wide)
		FakeGpu.Write l1 = gpu.writes.stream().filter(w -> w.level() == 1).findFirst().orElseThrow();
		assertEquals(List.of(2, 2, 2, 2), List.of(l1.x(), l1.y(), l1.w(), l1.h()));
		assertArrayEquals(new int[] {200, 100, 40, 255}, px(l1, 0, 0));
	}

	@Test
	void shortBufferIsIgnoredAndAsksForAFullFrame() {
		FakeGpu gpu = new FakeGpu();
		ScreenTexture t = texture(gpu);
		t.full(8, 6, ByteBuffer.allocate(10));
		assertFalse(t.hasFrame());
		t.full(8, 6, solid(8, 6, 1, 1, 1));
		t.patch(0, 0, 4, 4, ByteBuffer.allocate(4));
		assertTrue(t.needsFullFrame());
	}

	// ---- fix round 1: no render-thread stalls, stable ids, off-thread clear ----

	@Test
	void prepareForRenderNeverWaitsForAProducerMidFrame() throws Exception {
		FakeGpu gpu = new FakeGpu();
		ScreenTexture t = texture(gpu);
		t.full(1920, 1080, solid(1920, 1080, 1, 1, 1));
		assertTrue(t.prepareForRender());
		gpu.writes.clear();

		CountDownLatch inside = new CountDownLatch(1);
		CountDownLatch release = new CountDownLatch(1);
		t.setMidFullFrameHookForTest(() -> {
			inside.countDown();
			try {
				release.await(10, TimeUnit.SECONDS);
			} catch (InterruptedException e) {
				Thread.currentThread().interrupt();
			}
		});
		Thread producer = new Thread(() -> t.full(1920, 1080, solid(1920, 1080, 2, 2, 2)), "test-producer");
		producer.start();
		try {
			assertTrue(inside.await(10, TimeUnit.SECONDS), "producer reached the middle of its frame");
			long start = System.nanoTime();
			boolean drawable = assertTimeoutPreemptively(Duration.ofMillis(500), t::prepareForRender);
			long ms = (System.nanoTime() - start) / 1_000_000;
			assertTrue(drawable, "keeps drawing the previous frame (no flicker to CONNECTING)");
			assertTrue(ms < 100, "prepareForRender returned in " + ms + " ms while the producer held the frame");
			assertTrue(gpu.writes.isEmpty(), "nothing half-written was uploaded");
		} finally {
			release.countDown();
			producer.join(10_000);
		}
		t.setMidFullFrameHookForTest(null);
		assertTrue(t.prepareForRender());
		assertArrayEquals(new int[] {2, 2, 2, 255}, px(gpu.level0().get(0), 0, 0), "the finished frame is uploaded next time");
	}

	@Test
	void hologramTextureIdsAreStable() {
		UUID id = UUID.fromString("12345678-1234-1234-1234-123456789abc");
		assertEquals(Identifier.fromNamespaceAndPath("office", "screen/12345678-1234-1234-1234-123456789abc"), ScreenTexture.idFor(id));
		assertEquals(ScreenTexture.idFor(id), ScreenTexture.idFor(UUID.fromString(id.toString())));
	}

	@Test
	void offThreadClearQueuesTheFreeOnTheRenderThread() {
		FakeGpu gpu = new FakeGpu();
		ScreenTexture t = texture(gpu);
		t.full(8, 6, solid(8, 6, 1, 1, 1));
		assertTrue(t.prepareForRender());

		gpu.onRenderThread = false; // e.g. a network thread
		t.clear();
		assertEquals(0, gpu.frees, "not freed off the render thread");
		assertEquals(1, gpu.queued.size(), "free queued onto the render thread");
		gpu.onRenderThread = true;
		gpu.runQueued();
		assertEquals(1, gpu.frees, "freed without any further prepareForRender");
		gpu.runQueued();
		assertEquals(1, gpu.frees);
	}

	@Test
	void queuedFreeNeverFreesANewerAllocation() {
		FakeGpu gpu = new FakeGpu();
		ScreenTexture t = texture(gpu);
		t.full(8, 6, solid(8, 6, 1, 1, 1));
		assertTrue(t.prepareForRender());

		gpu.onRenderThread = false;
		t.clear();
		t.full(16, 12, solid(16, 12, 3, 3, 3)); // the source reconnects before the queued free ran
		gpu.onRenderThread = true;
		assertTrue(t.prepareForRender()); // frees the old texture first, then allocates the new one
		assertEquals(1, gpu.frees);
		assertEquals(2, gpu.allocations.size());
		gpu.runQueued();
		assertEquals(1, gpu.frees, "the late queued free is a no-op");
		assertTrue(t.prepareForRender());
	}

	// ---- test pattern ----

	@Test
	void testPatternPatchesMatchAFreshFullFrame() {
		Target target = new Target(Mode.EXTEND, 1, null, Shape.WIDE_16_9);
		long[] clock = {1_000L};
		List<String> events = new ArrayList<>();
		ScreenTexture fromSource = texture(new FakeGpu());
		FrameSink recorder = new FrameSink() {
			@Override
			public void full(final int w, final int h, final ByteBuffer rgba) {
				events.add("full " + w + "x" + h);
				fromSource.full(w, h, rgba);
			}

			@Override
			public void patch(final int x, final int y, final int w, final int h, final ByteBuffer rgba) {
				events.add("patch");
				fromSource.patch(x, y, w, h, rgba);
			}

			@Override
			public void ended(final String reason) {
				events.add("ended " + reason);
			}
		};
		TestPatternSource src = new TestPatternSource(() -> clock[0]);
		src.begin(target, recorder);
		clock[0] += 700;
		src.step();
		clock[0] += 1_300;
		src.step();
		assertEquals("full 1920x1080", events.get(0));
		assertTrue(events.stream().skip(1).allMatch("patch"::equals), events.toString());
		assertTrue(events.size() > 1);

		// the patched texture equals a full frame rendered directly at the same moment
		FakeGpu a = new FakeGpu();
		ScreenTexture patched = texture(a);
		patched.full(1920, 1080, fromSource.snapshot());
		patched.prepareForRender();
		FakeGpu b = new FakeGpu();
		ScreenTexture direct = texture(b);
		direct.full(1920, 1080, TestPatternSource.renderFull(1920, 1080, src.stateForTest()));
		direct.prepareForRender();
		assertArrayEquals(b.level0().get(0).data(), a.level0().get(0).data());
	}

	@Test
	void testPatternMovesOverTime() {
		ByteBuffer t0 = TestPatternSource.renderFull(1920, 1080, TestPatternSource.State.at(1920, 1080, 0, 0));
		ByteBuffer t1 = TestPatternSource.renderFull(1920, 1080, TestPatternSource.State.at(1920, 1080, 500, 15));
		assertNotEquals(t0, t1, "the bar and the frame counter move");
		ByteBuffer tall = TestPatternSource.renderFull(1080, 1920, TestPatternSource.State.at(1080, 1920, 0, 0));
		assertEquals(1080 * 1920 * 4, tall.remaining());
	}
}
