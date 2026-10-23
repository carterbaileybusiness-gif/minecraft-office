package local.carter.office.client.selftest;

import java.nio.ByteBuffer;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import org.jspecify.annotations.Nullable;

import local.carter.office.client.capture.CaptureSource;
import local.carter.office.client.capture.FrameSink;
import local.carter.office.client.capture.Target;
import local.carter.office.client.capture.TestPatternSource;
import local.carter.office.client.platform.NoPlatform;
import local.carter.office.client.platform.Platform;
import local.carter.office.client.selftest.SelfTest.Context;
import local.carter.office.client.selftest.SelfTest.Outcome;
import local.carter.office.client.stream.StbJpeg;
import local.carter.office.client.stream.StreamClient;
import local.carter.office.client.stream.StreamSender;
import local.carter.office.client.zoom.ZoomPose;
import local.carter.office.core.HologramGeometry;
import local.carter.office.core.Mode;
import local.carter.office.core.Shape;
import local.carter.office.core.TileCodec;
import local.carter.office.core.Vec3d;

/**
 * The built-in self-test steps. Captures keep only the frame size and whether it was all black: pixels are looked at
 * (a sparse sample) and dropped inside the sink, never stored or sent anywhere. Every wait is bounded and gives up when
 * the runner interrupts it.
 */
public final class SelfTestSteps {
	/** Longest wait for one captured frame (inside the 5 s step limit). */
	static final long FRAME_WAIT_MS = 4_000;
	/** Longest wait for a fresh UDP handshake. */
	static final long UDP_WAIT_MS = 4_000;
	/** Longest wait for the Mac helper to connect before reporting its state. */
	public static final long HELPER_WAIT_MS = 3_000;
	/** A zoom camera farther than this from the panel is wrong. */
	static final double MAX_ZOOM_DISTANCE = 512;
	/** A pixel with a channel above this isn't black. */
	static final int BLACK_LEVEL = 16;
	private static final int SAMPLES = 4096;

	private SelfTestSteps() {}

	static void registerDefaults() {
		SelfTest.registerStep("system", "System info", SelfTestSteps::system);
		SelfTest.registerStep("platform", Platform.isMac() ? "Screen helper and permissions" : Platform.isWindows()
				? "Window capture natives" : "Own-screen support", SelfTestSteps::platform);
		SelfTest.registerStep("test-pattern", "Test pattern capture (1 frame)", SelfTestSteps::testPattern);
		SelfTest.registerStep("real-capture", "Real window capture (1 frame)", SelfTestSteps::realCapture);
		SelfTest.registerStep("jpeg", "JPEG encode/decode", SelfTestSteps::jpeg);
		SelfTest.registerStep("udp", "Shared-screen link (UDP)", SelfTestSteps::udp);
		SelfTest.registerStep("zoom", "Zoom in/out dry run", SelfTestSteps::zoom);
	}

	static Outcome system(final Context ctx) {
		SelfTestReport r = ctx.report();
		StringBuilder missing = new StringBuilder();
		for (String key : new String[] {"OS", "CPU cores", "Max memory", "GPU"}) {
			String v = r.infoValue(key);
			if (v == null || v.isBlank() || v.startsWith("unknown")) {
				missing.append(missing.isEmpty() ? "" : ", ").append(key);
			}
		}
		return missing.isEmpty() ? Outcome.pass("OS, CPU, memory and GPU read") : Outcome.fail("couldn't read: " + missing);
	}

	static Outcome platform(final Context ctx) throws InterruptedException {
		Platform.Readiness r = ctx.platform().readiness();
		if (!r.supported()) {
			return Outcome.skip(r.detail());
		}
		return r.ok() ? Outcome.pass(r.detail()) : Outcome.fail(r.detail());
	}

	static Outcome testPattern(final Context ctx) throws InterruptedException {
		Frame f = captureOne(new TestPatternSource(), new Target(Mode.EXTEND, 1, null, Shape.WIDE_16_9), FRAME_WAIT_MS);
		if (f.size == null) {
			return Outcome.fail(f.ended != null ? "ended: " + f.ended : "no frame within " + FRAME_WAIT_MS / 1000 + " s");
		}
		String what = f.size[0] + "x" + f.size[1] + " frame in " + f.millis + " ms";
		if (f.size[0] != Shape.WIDE_16_9.pxW() || f.size[1] != Shape.WIDE_16_9.pxH()) {
			return Outcome.fail(what + ", expected " + Shape.WIDE_16_9.pxW() + "x" + Shape.WIDE_16_9.pxH());
		}
		return f.nonBlack ? Outcome.pass(what) : Outcome.fail(what + " but it was all black");
	}

	/** One frame of the window the player picked (none picked: skipped). Mac needs Screen Recording first. */
	static Outcome realCapture(final Context ctx) throws InterruptedException {
		Platform.WindowInfo w = ctx.window();
		if (ctx.platform() instanceof NoPlatform) {
			return Outcome.skip("this computer can't show its own screens");
		}
		if (w == null) {
			return Outcome.skip("no window picked (pick one at the top to test real capture)");
		}
		String app = w.app() == null || w.app().isBlank() ? "the picked window" : w.app();
		Platform.Readiness r = ctx.platform().readiness();
		if (!r.canCapture()) {
			return Outcome.skip("can't capture yet: " + r.detail());
		}
		CaptureSource src = ctx.platform().newOwnSource();
		if (src == null) {
			return Outcome.skip("this computer can't capture windows");
		}
		Frame f = captureOne(src, new Target(Mode.WINDOW, 0, w.key(), Shape.WIDE_16_9), FRAME_WAIT_MS);
		if (f.size == null) {
			return Outcome.fail(app + ": " + (f.ended != null ? "capture ended: " + f.ended : "no frame within " + FRAME_WAIT_MS / 1000 + " s"));
		}
		String what = "1 frame of " + app + ": " + f.size[0] + "x" + f.size[1] + " in " + f.millis + " ms";
		return f.nonBlack ? Outcome.pass(what + ", not black")
				: Outcome.fail(what + ", but all black (minimised window, or capture permission missing?)");
	}

	static Outcome jpeg(final Context ctx) {
		int w = StreamSender.TILE;
		int h = StreamSender.TILE;
		ByteBuffer px = ByteBuffer.allocate(w * h * 4);
		for (int y = 0; y < h; y++) {
			for (int x = 0; x < w; x++) {
				boolean text = (x / 4 + y / 8) % 5 == 0; // some sharp edges, like text on a screen
				px.put((byte) (text ? 20 : x * 4)).put((byte) (text ? 20 : y * 4)).put((byte) (text ? 20 : 160)).put((byte) 255);
			}
		}
		px.flip();
		try (StbJpeg jpeg = new StbJpeg()) {
			long t0 = System.nanoTime();
			byte[] enc = jpeg.encode(new TileCodec.Tile(0, 0, w, h, px), StreamSender.QUALITY);
			ByteBuffer out = ByteBuffer.allocateDirect(w * h * 4);
			jpeg.decodeInto(enc, out, w, h);
			long ms = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - t0);
			int worst = 0;
			long sum = 0;
			for (int i = 0; i < w * h * 4; i++) {
				if (i % 4 != 3) {
					int d = Math.abs((px.get(i) & 0xFF) - (out.get(i) & 0xFF));
					worst = Math.max(worst, d);
					sum += d;
				}
			}
			double mean = sum / (double) (w * h * 3);
			String what = String.format(java.util.Locale.ROOT, "%dx%d tile -> %d bytes at quality %d and back in %d ms, mean error %.1f",
					w, h, enc.length, StreamSender.QUALITY, ms, mean);
			return mean < 12 ? Outcome.pass(what) : Outcome.fail(what + " (too blurry, worst " + worst + ")");
		}
	}

	/**
	 * The shared-screen transport: was a relay token received (it is never printed), the relay's UDP port, a fresh
	 * HELLO → challenge → bound handshake and its round trip, or why screens use the game-connection fallback.
	 */
	static Outcome udp(final Context ctx) throws InterruptedException {
		StreamClient c = ctx.stream();
		if (c == null) {
			return Outcome.fail("shared screens aren't running (join the server first)");
		}
		StreamClient.UdpDiag d = c.udpDiag();
		if (!d.tokenReceived()) {
			return Outcome.fail("no stream token from the server yet (is this the Office server? rejoin and try again)");
		}
		String head = "token received, udpPort " + d.udpPort();
		if (d.udpPort() <= 0) {
			return Outcome.fail(head + ": the server has no UDP relay, so screens use the slower game-connection fallback");
		}
		if (!d.linkOpen() || d.stats() == null) {
			return Outcome.fail(head + ", but UDP to the server couldn't be opened: using the game-connection fallback");
		}
		int binds = d.stats().binds();
		int challenges = d.stats().challenges();
		c.probeUdp();
		long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(UDP_WAIT_MS);
		StreamClient.UdpDiag now = d;
		while (System.nanoTime() < deadline) {
			now = c.udpDiag();
			if (now.stats() != null && now.stats().binds() > binds) {
				break;
			}
			Thread.sleep(25);
		}
		var st = now.stats();
		if (st != null && st.binds() > binds) {
			return Outcome.pass(head + ", HELLO -> challenge -> bound, round trip " + Math.max(0, TimeUnit.NANOSECONDS.toMillis(st.rttNanos())) + " ms");
		}
		String was = d.bound() ? " (it was bound earlier)" : "";
		if (st == null || st.challenges() <= challenges) {
			return Outcome.fail(head + ", HELLO sent but no challenge came back within " + UDP_WAIT_MS / 1000
					+ " s (UDP blocked by a firewall or router?)" + was + ": using the game-connection fallback");
		}
		return Outcome.fail(head + ", challenge received but the relay didn't confirm the bind within " + UDP_WAIT_MS / 1000 + " s" + was
				+ ": using the game-connection fallback");
	}

	/**
	 * Zoom dry run on the player's first own hologram, without zooming: would {@link Platform#workProblem} let them work
	 * on it, and is the zoom camera sane (finite, in front of the panel, a sensible distance, the letterboxed view inside
	 * the game window at the panel's aspect)?
	 */
	static Outcome zoom(final Context ctx) {
		SelfTest.ZoomTarget z = ctx.zoom();
		if (z == null) {
			return Outcome.skip("no hologram of yours nearby (place one, or stand near it, to test zooming)");
		}
		HologramGeometry.Frame f = z.frame();
		int sw = z.windowW();
		int sh = z.windowH();
		double aspect = sw > 0 && sh > 0 ? (double) sw / sh : 16.0 / 9.0;
		ZoomPose.Pose pose = ZoomPose.framing(f, z.fovDeg(), aspect);
		Vec3d off = pose.eye().sub(f.center());
		double dist = off.length();
		Vec3d normal = f.right().cross(f.up());
		boolean finite = Double.isFinite(pose.eye().x()) && Double.isFinite(pose.eye().y()) && Double.isFinite(pose.eye().z())
				&& Float.isFinite(pose.yaw()) && Float.isFinite(pose.pitch());
		boolean poseOk = finite && dist > 0 && dist < MAX_ZOOM_DISTANCE && off.dot(normal) > 0;
		double panelAspect = f.h() > 0 ? f.w() / f.h() : 0;
		ZoomPose.Rect box = ZoomPose.letterbox(sw, sh, panelAspect);
		boolean boxOk = box.w() > 0 && box.h() > 0 && box.x() >= 0 && box.y() >= 0 && box.x() + box.w() <= sw && box.y() + box.h() <= sh
				&& panelAspect > 0 && Math.abs((double) box.w() / box.h() - panelAspect) / panelAspect < 0.02;
		String what = String.format(java.util.Locale.ROOT, "%s hologram %.1fx%.1f blocks, camera %.1f blocks in front, view %dx%d of %dx%d",
				z.spec().mode() == Mode.WINDOW ? "window" : "whole-display", f.w(), f.h(), dist, box.w(), box.h(), sw, sh);
		if (!poseOk || !boxOk) {
			return Outcome.fail("zoom camera math is off: " + what);
		}
		String problem = ctx.platform().workProblem(z.spec());
		if (problem != null) {
			return Outcome.fail("can't work on it yet: " + problem + " (" + what + ")");
		}
		return Outcome.pass("could work on it (no real zoom done): " + what);
	}

	// ------------------------------------------------------------------ capture one frame

	/** What one capture showed: size (null if none), all-black or not, or why it ended. No pixels. */
	static final class Frame {
		int @Nullable [] size;
		boolean nonBlack;
		@Nullable String ended;
		long millis;
	}

	static Frame captureOne(final CaptureSource src, final Target t, final long waitMs) throws InterruptedException {
		CountDownLatch got = new CountDownLatch(1);
		AtomicReference<Frame> first = new AtomicReference<>();
		long t0 = System.nanoTime();
		FrameSink sink = new FrameSink() {
			@Override
			public void full(final int w, final int h, final ByteBuffer rgba) {
				if (first.get() != null) {
					return;
				}
				Frame f = new Frame();
				f.size = new int[] {w, h};
				f.nonBlack = nonBlack(w, h, rgba);
				f.millis = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - t0);
				if (first.compareAndSet(null, f)) {
					got.countDown();
				}
			}

			@Override
			public void patch(final int x, final int y, final int w, final int h, final ByteBuffer rgba) {}

			@Override
			public void ended(final String reason) {
				Frame f = new Frame();
				f.ended = reason == null || reason.isBlank() ? "stopped" : reason;
				if (first.compareAndSet(null, f)) {
					got.countDown();
				}
			}
		};
		try {
			src.start(t, 5, sink);
			got.await(waitMs, TimeUnit.MILLISECONDS);
		} finally {
			src.stop();
		}
		Frame f = first.get();
		return f == null ? new Frame() : f;
	}

	/** Whether any of ~4096 evenly spread pixels has a colour channel above {@link #BLACK_LEVEL}. */
	static boolean nonBlack(final int w, final int h, final ByteBuffer rgba) {
		long pixels = (long) w * h;
		if (pixels <= 0) {
			return false;
		}
		int base = rgba.position();
		long n = Math.min(SAMPLES, pixels);
		for (long i = 0; i < n; i++) {
			long p = i * pixels / n;
			int at = base + (int) (p * 4);
			if (at + 3 >= rgba.limit()) {
				break;
			}
			if ((rgba.get(at) & 0xFF) > BLACK_LEVEL || (rgba.get(at + 1) & 0xFF) > BLACK_LEVEL || (rgba.get(at + 2) & 0xFF) > BLACK_LEVEL) {
				return true;
			}
		}
		return false;
	}
}
