package local.carter.office.client.capture;

import java.nio.ByteBuffer;
import java.time.Instant;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.LongSupplier;

import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;

import com.mojang.logging.LogUtils;

import local.carter.office.client.render.ScreenTextureMath.Rect;

/**
 * A synthetic screen for tests and the self-test: a grid with coloured corner markers (red top-left, green top-right,
 * blue bottom-left, yellow bottom-right, so a flipped or mirrored hologram is obvious), a centre ring (aspect check),
 * a title, large and small text lines, a live frame counter + clock, and a cyan bar sweeping left to right every
 * {@link #SWEEP_MS} ms. Size = the target shape's native pixels (1920x1080, 2560x1080 or 1080x1920).
 *
 * <p>The first delivery is a full frame; after that only the bar's old/new columns and the counter line are sent as
 * patches, so it exercises the same patch path as real capture.
 */
public final class TestPatternSource implements CaptureSource {
	public static final long SWEEP_MS = 4_000;
	private static final Logger LOGGER = LogUtils.getLogger();
	static final int BAR = 48;
	static final int LIVE_X = 140;
	static final int LIVE_SCALE = 4;
	static final int LIVE_CHARS = 22; // "FRAME 000123  12:34:56"

	private static final ScheduledExecutorService EXEC = Executors.newSingleThreadScheduledExecutor(r -> {
		Thread t = new Thread(r, "office-test-pattern");
		t.setDaemon(true);
		return t;
	});

	private final LongSupplier clock;
	private final Object lock = new Object();
	private @Nullable ScheduledFuture<?> task;
	private @Nullable FrameSink sink;
	private @Nullable State state;
	private @Nullable ByteBuffer patchBuf;
	private volatile boolean live;

	public TestPatternSource() {
		this(System::currentTimeMillis);
	}

	public TestPatternSource(final LongSupplier clock) {
		this.clock = clock;
	}

	@Override
	public void start(final Target t, final int maxFps, final FrameSink out) {
		stop();
		long period = Math.max(1, 1000L / Math.max(1, Math.min(60, maxFps)));
		synchronized (lock) {
			live = true;
			task = EXEC.scheduleAtFixedRate(() -> {
				try {
					synchronized (lock) {
						if (!live) {
							return;
						}
						if (this.sink == null) {
							begin(t, out);
						} else {
							step();
						}
					}
				} catch (Throwable e) {
					LOGGER.warn("Test pattern failed", e);
					live = false;
					out.ended("Test pattern failed: " + e);
					throw e;
				}
			}, 0, period, TimeUnit.MILLISECONDS);
		}
	}

	@Override
	public void stop() {
		ScheduledFuture<?> f;
		synchronized (lock) {
			live = false;
			f = task;
			task = null;
			sink = null;
			state = null;
			patchBuf = null;
		}
		if (f != null) {
			f.cancel(false);
		}
	}

	@Override
	public boolean isLive() {
		return live;
	}

	/** Sends the first (full) frame. Called on the pattern thread; public for tests (synchronous, no thread). */
	public void begin(final Target t, final FrameSink out) {
		synchronized (lock) {
			int w = t.shape().pxW();
			int h = t.shape().pxH();
			State s = State.at(w, h, clock.getAsLong(), 0);
			this.sink = out;
			this.state = s;
			out.full(w, h, renderFull(w, h, s));
		}
	}

	/** Advances to now and sends the changed rectangles as patches. Public for tests. */
	public void step() {
		synchronized (lock) {
			State prev = state;
			FrameSink out = sink;
			if (prev == null || out == null) {
				return;
			}
			State next = State.at(prev.w(), prev.h(), clock.getAsLong(), prev.frame() + 1);
			state = next;
			for (Rect r : changed(prev, next)) {
				ByteBuffer buf = patchBuffer(r.w() * r.h() * 4);
				render(buf, r, next);
				out.patch(r.x(), r.y(), r.w(), r.h(), buf);
			}
		}
	}

	/** The state of the newest frame sent (tests). */
	public @Nullable State stateForTest() {
		synchronized (lock) {
			return state;
		}
	}

	private ByteBuffer patchBuffer(final int bytes) {
		ByteBuffer b = patchBuf;
		if (b == null || b.capacity() < bytes) {
			b = ByteBuffer.allocate(bytes);
			patchBuf = b;
		}
		return b.clear().limit(bytes);
	}

	// ---- the picture (pure) ----

	/** What one frame shows. */
	public record State(int w, int h, long ms, long frame, int barX, String liveText) {
		public static State at(final int w, final int h, final long ms, final long frame) {
			long phase = Math.floorMod(ms, SWEEP_MS);
			int barX = (int) (phase * (w + BAR) / SWEEP_MS) - BAR;
			LocalTime time = LocalTime.ofInstant(Instant.ofEpochMilli(ms), ZoneId.systemDefault());
			String text = String.format("FRAME %06d  %02d:%02d:%02d", frame % 1_000_000, time.getHour(), time.getMinute(), time.getSecond());
			return new State(w, h, ms, frame, barX, text);
		}
	}

	/** A whole w x h frame (tightly packed RGBA, position 0). */
	public static ByteBuffer renderFull(final int w, final int h, final State s) {
		ByteBuffer buf = ByteBuffer.allocate(w * h * 4);
		render(buf, new Rect(0, 0, w, h), s);
		return buf;
	}

	/** Renders rect {@code r} of frame {@code s} into {@code out} (from index 0, tightly packed). */
	static void render(final ByteBuffer out, final Rect r, final State s) {
		List<Text> lines = lines(s);
		int i = 0;
		for (int y = r.y(); y < r.bottom(); y++) {
			for (int x = r.x(); x < r.right(); x++, i += 4) {
				out.putInt(i, pixel(x, y, s, lines)); // RGBA bytes in order (big-endian int)
			}
		}
	}

	/** Rectangles that differ between two consecutive frames. */
	static List<Rect> changed(final State a, final State b) {
		List<Rect> out = new ArrayList<>();
		int lo = Math.min(a.barX(), b.barX());
		int hi = Math.max(a.barX(), b.barX()) + BAR;
		if (Math.abs(a.barX() - b.barX()) < a.w() / 2) {
			add(out, lo, 0, hi - lo, a.h(), a);
		} else {
			add(out, a.barX(), 0, BAR, a.h(), a); // wrapped around: old and new place separately
			add(out, b.barX(), 0, BAR, a.h(), a);
		}
		if (!a.liveText().equals(b.liveText())) {
			Text live = liveLine(b);
			add(out, live.x, live.y, live.width(), live.height(), a);
		}
		return out;
	}

	private static void add(final List<Rect> out, final int x, final int y, final int w, final int h, final State s) {
		Rect r = Rect.clip(x, y, w, h, s.w(), s.h());
		if (r != null) {
			out.add(r);
		}
	}

	private record Text(int x, int y, int scale, String text, int rgba) {
		int width() {
			return text.length() * 6 * scale;
		}

		int height() {
			return 7 * scale;
		}
	}

	private static Text liveLine(final State s) {
		return new Text(LIVE_X, LIVE_X + 120, LIVE_SCALE, s.liveText(), 0xFFE07AFF);
	}

	private static List<Text> lines(final State s) {
		int m = LIVE_X;
		String small = "THE QUICK BROWN FOX JUMPS OVER THE LAZY DOG 0123456789";
		return List.of(
				new Text(m, m, 6, "MINECRAFT OFFICE", 0xFFFFFFFF),
				new Text(m, m + 60, 3, "TEST PATTERN " + s.w() + "X" + s.h(), 0x9FE8F7FF),
				liveLine(s),
				new Text(m, s.h() - m - 60, 2, "14PX: " + small, 0xE6EEF2FF),
				new Text(m, s.h() - m - 30, 1, "7PX: " + small, 0xE6EEF2FF));
	}

	private static int pixel(final int x, final int y, final State s, final List<Text> lines) {
		int w = s.w();
		int h = s.h();
		// moving bar (on top of everything), white core
		int bx = x - s.barX();
		if (bx >= 0 && bx < BAR) {
			return bx >= BAR / 2 - 4 && bx < BAR / 2 + 4 ? 0xFFFFFFFF : 0x4FE3FFFF;
		}
		for (Text t : lines) {
			if (y >= t.y && y < t.y + t.height() && x >= t.x && x < t.x + t.width()) {
				int col = (x - t.x) / t.scale;
				int gx = col % 6;
				int gy = (y - t.y) / t.scale;
				if (gx < 5 && glyph(t.text.charAt(col / 6), gx, gy)) {
					return t.rgba;
				}
			}
		}
		// corner markers
		int c = 100;
		int in = 24;
		boolean left = x >= in && x < in + c;
		boolean right = x >= w - in - c && x < w - in;
		boolean top = y >= in && y < in + c;
		boolean bottom = y >= h - in - c && y < h - in;
		if (left && top) {
			return 0xE63C3CFF;
		}
		if (right && top) {
			return 0x3CC85AFF;
		}
		if (left && bottom) {
			return 0x3C6EF0FF;
		}
		if (right && bottom) {
			return 0xF0C828FF;
		}
		// centre ring, radius h/4, 4 px
		long dx = x - w / 2;
		long dy = y - h / 2;
		long rr = Math.min(w, h) / 4;
		long d2 = dx * dx + dy * dy;
		if (d2 >= (rr - 2) * (rr - 2) && d2 < (rr + 2) * (rr + 2)) {
			return 0x7FA8C0FF;
		}
		// grid: minor every 120 px (1 px), major every 480 px (3 px)
		if (x % 480 < 3 || y % 480 < 3) {
			return 0x3E6E8CFF;
		}
		if (x % 120 == 0 || y % 120 == 0) {
			return 0x24425AFF;
		}
		int g = 18 + 20 * y / Math.max(1, h);
		return (10 << 24) | (g << 16) | ((30 + g / 2) << 8) | 0xFF;
	}

	// 5x7 glyphs, one int per row (bit 4 = left column)
	private static final Map<Character, int[]> FONT = Map.ofEntries(
			Map.entry('A', new int[] {0x0E, 0x11, 0x11, 0x1F, 0x11, 0x11, 0x11}),
			Map.entry('B', new int[] {0x1E, 0x11, 0x11, 0x1E, 0x11, 0x11, 0x1E}),
			Map.entry('C', new int[] {0x0E, 0x11, 0x10, 0x10, 0x10, 0x11, 0x0E}),
			Map.entry('D', new int[] {0x1C, 0x12, 0x11, 0x11, 0x11, 0x12, 0x1C}),
			Map.entry('E', new int[] {0x1F, 0x10, 0x10, 0x1E, 0x10, 0x10, 0x1F}),
			Map.entry('F', new int[] {0x1F, 0x10, 0x10, 0x1E, 0x10, 0x10, 0x10}),
			Map.entry('G', new int[] {0x0E, 0x11, 0x10, 0x17, 0x11, 0x11, 0x0F}),
			Map.entry('H', new int[] {0x11, 0x11, 0x11, 0x1F, 0x11, 0x11, 0x11}),
			Map.entry('I', new int[] {0x0E, 0x04, 0x04, 0x04, 0x04, 0x04, 0x0E}),
			Map.entry('J', new int[] {0x07, 0x02, 0x02, 0x02, 0x02, 0x12, 0x0C}),
			Map.entry('K', new int[] {0x11, 0x12, 0x14, 0x18, 0x14, 0x12, 0x11}),
			Map.entry('L', new int[] {0x10, 0x10, 0x10, 0x10, 0x10, 0x10, 0x1F}),
			Map.entry('M', new int[] {0x11, 0x1B, 0x15, 0x15, 0x11, 0x11, 0x11}),
			Map.entry('N', new int[] {0x11, 0x11, 0x19, 0x15, 0x13, 0x11, 0x11}),
			Map.entry('O', new int[] {0x0E, 0x11, 0x11, 0x11, 0x11, 0x11, 0x0E}),
			Map.entry('P', new int[] {0x1E, 0x11, 0x11, 0x1E, 0x10, 0x10, 0x10}),
			Map.entry('Q', new int[] {0x0E, 0x11, 0x11, 0x11, 0x15, 0x12, 0x0D}),
			Map.entry('R', new int[] {0x1E, 0x11, 0x11, 0x1E, 0x14, 0x12, 0x11}),
			Map.entry('S', new int[] {0x0F, 0x10, 0x10, 0x0E, 0x01, 0x01, 0x1E}),
			Map.entry('T', new int[] {0x1F, 0x04, 0x04, 0x04, 0x04, 0x04, 0x04}),
			Map.entry('U', new int[] {0x11, 0x11, 0x11, 0x11, 0x11, 0x11, 0x0E}),
			Map.entry('V', new int[] {0x11, 0x11, 0x11, 0x11, 0x11, 0x0A, 0x04}),
			Map.entry('W', new int[] {0x11, 0x11, 0x11, 0x15, 0x15, 0x15, 0x0A}),
			Map.entry('X', new int[] {0x11, 0x11, 0x0A, 0x04, 0x0A, 0x11, 0x11}),
			Map.entry('Y', new int[] {0x11, 0x11, 0x11, 0x0A, 0x04, 0x04, 0x04}),
			Map.entry('Z', new int[] {0x1F, 0x01, 0x02, 0x04, 0x08, 0x10, 0x1F}),
			Map.entry('0', new int[] {0x0E, 0x11, 0x13, 0x15, 0x19, 0x11, 0x0E}),
			Map.entry('1', new int[] {0x04, 0x0C, 0x04, 0x04, 0x04, 0x04, 0x0E}),
			Map.entry('2', new int[] {0x0E, 0x11, 0x01, 0x02, 0x04, 0x08, 0x1F}),
			Map.entry('3', new int[] {0x1F, 0x02, 0x04, 0x02, 0x01, 0x11, 0x0E}),
			Map.entry('4', new int[] {0x02, 0x06, 0x0A, 0x12, 0x1F, 0x02, 0x02}),
			Map.entry('5', new int[] {0x1F, 0x10, 0x1E, 0x01, 0x01, 0x11, 0x0E}),
			Map.entry('6', new int[] {0x06, 0x08, 0x10, 0x1E, 0x11, 0x11, 0x0E}),
			Map.entry('7', new int[] {0x1F, 0x01, 0x02, 0x04, 0x08, 0x08, 0x08}),
			Map.entry('8', new int[] {0x0E, 0x11, 0x11, 0x0E, 0x11, 0x11, 0x0E}),
			Map.entry('9', new int[] {0x0E, 0x11, 0x11, 0x0F, 0x01, 0x02, 0x0C}),
			Map.entry(':', new int[] {0x00, 0x0C, 0x0C, 0x00, 0x0C, 0x0C, 0x00}),
			Map.entry('.', new int[] {0x00, 0x00, 0x00, 0x00, 0x00, 0x0C, 0x0C}),
			Map.entry('-', new int[] {0x00, 0x00, 0x00, 0x1F, 0x00, 0x00, 0x00}),
			Map.entry('/', new int[] {0x00, 0x01, 0x02, 0x04, 0x08, 0x10, 0x00}));

	private static boolean glyph(final char ch, final int gx, final int gy) {
		int[] rows = FONT.get(ch);
		return rows != null && (rows[gy] & (0x10 >> gx)) != 0;
	}
}
