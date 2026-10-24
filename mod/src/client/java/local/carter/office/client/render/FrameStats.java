package local.carter.office.client.render;

import java.lang.reflect.Method;
import java.util.Arrays;

import org.slf4j.Logger;

import com.mojang.logging.LogUtils;

/**
 * In-game timing of own screens (measurements, off by default): from a picture reaching a {@link ScreenTexture}
 * ({@code full} / {@code patch}, right after the helper's FRAME / PATCH is read off the socket) to its GPU upload, and to
 * the end of the frame that drew it (the next frame's start), plus rendered frames per second and Dynamic FPS's state.
 * The helper's own part (screen change → FRAME sent) is measured by {@code inputprobe.sh --latency}.
 * {@code -Doffice.frameStats=true} logs a line every 5 s; tests read {@link #snapshot()}. Allocation-free per frame.
 */
public final class FrameStats {
	private static final Logger LOGGER = LogUtils.getLogger();
	public static final String PROPERTY = "office.frameStats";
	private static final boolean LOG = Boolean.getBoolean(PROPERTY);
	private static final int RING = 1024;
	private static final long LOG_EVERY_NANOS = 5_000_000_000L;

	private static volatile boolean recording = LOG;
	private static volatile boolean phase; // a test is recording: no periodic log / reset in between
	// render thread only
	private static final long[] uploadNanos = new long[RING];
	private static final long[] presentNanos = new long[RING];
	private static int uploads;
	private static int presents;
	private static long oldestUploadedArrival; // 0 = nothing uploaded since the last frame start
	private static int frames;
	private static long windowStart;
	private static long lastFrame;
	private static long worstGapNanos;
	// work-mode cursor (round 3): a test says when each cursor position was set; drawn -> on screen at the next frame
	private static volatile java.util.function.@org.jspecify.annotations.Nullable LongUnaryOperator cursorStamps;
	private static final long[] cursorNanos = new long[RING];
	private static final long[] cursorDrawNanos = new long[RING];
	private static int cursors;
	private static int cursorDraws;
	private static long lastCursorKey = Long.MIN_VALUE;
	private static long cursorSetAt; // when the cursor drawn this frame was set (0 = no new one)

	private FrameStats() {}

	/** Tests: start (true) or stop recording; starting clears what was recorded. */
	public static void record(final boolean on) {
		if (on) {
			reset();
		}
		phase = on;
		recording = on || LOG;
	}

	static boolean recording() {
		return recording;
	}

	/** Render thread: a picture that arrived at {@code arrivalNanos} was just uploaded. */
	static void uploaded(final long arrivalNanos) {
		long now = System.nanoTime();
		uploadNanos[uploads++ % RING] = now - arrivalNanos;
		if (oldestUploadedArrival == 0 || arrivalNanos < oldestUploadedArrival) {
			oldestUploadedArrival = arrivalNanos;
		}
	}

	/**
	 * Measurements: {@code stamps} maps a cursor position ({@code x * 100_000 + y}) to the System.nanoTime it was set
	 * (0 = unknown); null stops. From then on each newly drawn position's set → on-screen time is recorded.
	 */
	public static void setCursorStamps(final java.util.function.@org.jspecify.annotations.Nullable LongUnaryOperator stamps) {
		cursorStamps = stamps;
		lastCursorKey = Long.MIN_VALUE;
	}

	/** Render thread: the work-mode cursor is drawn at (x, y) of its display this frame. */
	public static void cursorDrawn(final int x, final int y) {
		java.util.function.LongUnaryOperator stamps = cursorStamps;
		if (stamps == null || !recording) {
			return;
		}
		long key = x * 100_000L + y;
		if (key == lastCursorKey) {
			return;
		}
		lastCursorKey = key;
		long set = stamps.applyAsLong(key);
		if (set > 0) {
			cursorDrawNanos[cursorDraws++ % RING] = System.nanoTime() - set; // set → drawn (in this frame)
			if (cursorSetAt == 0 || set < cursorSetAt) {
				cursorSetAt = set;
			}
		}
	}

	/** Render thread, each frame's start (MinecraftFrameMixin): the previous frame, with its uploads, is on screen. */
	public static void frameStart() {
		if (!recording) {
			return;
		}
		long now = System.nanoTime();
		if (cursorSetAt != 0) {
			cursorNanos[cursors++ % RING] = now - cursorSetAt;
			cursorSetAt = 0;
		}
		if (oldestUploadedArrival != 0) {
			presentNanos[presents++ % RING] = now - oldestUploadedArrival;
			oldestUploadedArrival = 0;
		}
		if (lastFrame != 0) {
			worstGapNanos = Math.max(worstGapNanos, now - lastFrame);
		}
		lastFrame = now;
		frames++;
		if (windowStart == 0) {
			windowStart = now;
		}
		if (LOG && !phase && now - windowStart >= LOG_EVERY_NANOS) {
			Snapshot s = snapshot();
			LOGGER.info("FRAMESTATS fps={} worstFrameMs={} arrival->upload p50={}ms p90={}ms arrival->onScreen p50={}ms p90={}ms n={}"
					+ " working={} dynamicFps={}", String.format("%.1f", s.fps()), String.format("%.1f", s.worstFrameMs()),
					ms(s.uploadP50()), ms(s.uploadP90()), ms(s.presentP50()), ms(s.presentP90()), s.presented(),
					local.carter.office.client.pointer.PointerClient.working(), dynamicFps());
			reset();
		}
	}

	private static String ms(final double v) {
		return String.format("%.1f", v);
	}

	/** What was recorded since the last reset: rendered fps, worst frame gap, and the two latencies in ms (median, p90). */
	public record Snapshot(double fps, double worstFrameMs, int presented, double uploadP50, double uploadP90, double presentP50,
			double presentP90, int cursorsShown, double cursorP50, double cursorP90, double cursorDrawP50, double cursorDrawP90) {}

	/** Render thread (or a test via computeOnClient). */
	public static Snapshot snapshot() {
		long now = System.nanoTime();
		double secs = windowStart == 0 ? 0 : (now - windowStart) / 1e9;
		long[] up = Arrays.copyOf(uploadNanos, Math.min(uploads, RING));
		long[] pr = Arrays.copyOf(presentNanos, Math.min(presents, RING));
		long[] cu = Arrays.copyOf(cursorNanos, Math.min(cursors, RING));
		long[] cd = Arrays.copyOf(cursorDrawNanos, Math.min(cursorDraws, RING));
		Arrays.sort(cd);
		Arrays.sort(up);
		Arrays.sort(pr);
		Arrays.sort(cu);
		return new Snapshot(secs > 0 ? frames / secs : 0, worstGapNanos / 1e6, pr.length, pct(up, 50), pct(up, 90), pct(pr, 50), pct(pr, 90),
				cu.length, pct(cu, 50), pct(cu, 90), pct(cd, 50), pct(cd, 90));
	}

	private static double pct(final long[] sorted, final int p) {
		if (sorted.length == 0) {
			return Double.NaN;
		}
		return sorted[Math.min(sorted.length - 1, (int) Math.floor(sorted.length * p / 100.0))] / 1e6;
	}

	private static void reset() {
		uploads = 0;
		presents = 0;
		oldestUploadedArrival = 0;
		frames = 0;
		windowStart = 0;
		lastFrame = 0;
		worstGapNanos = 0;
		cursors = 0;
		cursorDraws = 0;
		cursorSetAt = 0;
	}

	private static Method powerState;
	private static Method targetFrameRate;
	private static boolean looked;

	/** Dynamic FPS's power state and frame-rate target ("STATE/target"), or "absent". */
	public static String dynamicFps() {
		try {
			if (!looked) {
				looked = true;
				Class<?> mod = Class.forName("dynamic_fps.impl.DynamicFPSMod");
				powerState = mod.getMethod("powerState");
				targetFrameRate = mod.getMethod("targetFrameRate");
			}
			if (powerState == null) {
				return "absent";
			}
			return powerState.invoke(null) + "/" + targetFrameRate.invoke(null);
		} catch (ReflectiveOperationException | RuntimeException | LinkageError e) {
			powerState = null;
			return "absent";
		}
	}
}
