package local.carter.office.client.capture;

import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Supplier;

import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;

import com.mojang.logging.LogUtils;

import dev.onvoid.webrtc.media.FourCC;
import dev.onvoid.webrtc.media.video.VideoBufferConverter;
import dev.onvoid.webrtc.media.video.VideoFrame;
import dev.onvoid.webrtc.media.video.VideoFrameBuffer;
import dev.onvoid.webrtc.media.video.desktop.DesktopCapturer;
import dev.onvoid.webrtc.media.video.desktop.DesktopSource;
import dev.onvoid.webrtc.media.video.desktop.ScreenCapturer;
import dev.onvoid.webrtc.media.video.desktop.WindowCapturer;

import local.carter.office.client.platform.Win32;
import local.carter.office.client.platform.WindowKey;
import local.carter.office.client.platform.WindowsPlatform;
import local.carter.office.client.render.HologramLook;
import local.carter.office.core.Mode;

/**
 * Windows capture (no helper install) with webrtc-java: one app window ({@link WindowCapturer}, Window mode) or the
 * primary monitor ({@link ScreenCapturer}, Mirror mode; Minecraft's own window is kept out of it with
 * {@code WDA_EXCLUDEFROMCAPTURE}, see {@link #setMirrorGuard}). An Extend target ends at once with {@link #REASON_EXTEND}. One dedicated thread per running source calls
 * {@code captureFrame()} at 10 fps (30 while the player is working on that window, see {@link #setWorkingKey}), converts
 * each I420 frame straight to RGBA natively (libyuv), and delivers it as a {@link FrameSink#full} frame (no dirty
 * rectangles in v1). When the window goes away ({@code IsWindow} false, or a capture error once it is gone) the sink
 * gets {@code ended("window closed")}; any other way it stops ends with a short, human-readable reason (also logged).
 */
public final class JavaDesktopSource implements CaptureSource {
	public static final String REASON_WINDOW_CLOSED = "window closed";
	public static final String REASON_EXTEND = "Whole display is coming soon on Windows";
	public static final String REASON_ARM = "Window capture isn't supported on Windows on ARM yet";
	public static final String REASON_NOT_WINDOWS = "Window capture needs Windows";
	public static final String REASON_NO_LIBRARY = "Capture library didn't load";
	public static final String REASON_CANT_CAPTURE = "Can't capture this window";
	public static final String REASON_FAILED = "Window capture failed";
	/** Mirror: Minecraft couldn't be left out of the capture (needs Windows 10 2004+), so it would show inside itself. */
	public static final String REASON_MIRROR_EXCLUDE = "Needs Windows 10 2004+ to hide Minecraft";
	public static final String REASON_NO_SCREEN = "Can't capture your screen";
	public static final String REASON_SCREEN_FAILED = "Screen capture failed";
	public static final int IDLE_FPS = 10;
	public static final int WORKING_FPS = 30;
	/** Consecutive failed captures of a window that still exists before giving up (~5 s at 10 fps). */
	static final int MAX_FAILURES = 50;
	private static final Logger LOGGER = LogUtils.getLogger();

	private static volatile @Nullable String workingKey;
	/** Mirror pre-check (Windows platform: keep Minecraft out of the capture); returns why not, or null. Null = none. */
	private static volatile @Nullable Supplier<@Nullable String> mirrorGuard;

	/** One screen capture for every Mirror hologram on this computer. */
	private static final SharedFeed MIRROR_FEED = new SharedFeed("win-mirror", ScreenProducer::new);

	private final Object lock = new Object();
	private @Nullable Run current;
	private @Nullable MirrorSub mirror;
	/** Why the current session ended (null while running or not started), for {@link #status()}. */
	private volatile @Nullable String endedReason;

	/** The window the player is zoomed into (captured faster), or null. Set by the Windows platform on zoom in/out. */
	public static void setWorkingKey(final @Nullable String windowKey) {
		workingKey = windowKey;
	}

	/**
	 * Runs on every Mirror start (the caller's thread: OwnScreens, the client thread) before the screen is captured; a
	 * non-null answer ends the source with that reason instead (shown on the hologram as "Screens unavailable").
	 */
	public static void setMirrorGuard(final @Nullable Supplier<@Nullable String> guard) {
		mirrorGuard = guard;
	}

	/**
	 * The webrtc screen source to mirror: the primary monitor ({@code primaryIndex}, EnumDisplayDevices order = webrtc's
	 * screen ids on Windows), else the first screen; null when there is none. Pure.
	 */
	public static @Nullable Long pickScreen(final List<Long> screenIds, final int primaryIndex) {
		if (screenIds.isEmpty()) {
			return null;
		}
		return screenIds.contains((long) primaryIndex) ? Long.valueOf(primaryIndex) : screenIds.get(0);
	}

	/**
	 * Frames per second for {@code t}: 10, or 30 while working on that window (Mirror: always 10, nobody looks at the
	 * hologram while working on the real desktop); never above {@code maxFps}, at least 1.
	 */
	public static int fpsFor(final Target t, final int maxFps, final @Nullable String working) {
		int want = t.mode() == Mode.WINDOW && working != null && working.equals(t.windowKey()) ? WORKING_FPS : IDLE_FPS;
		return Math.max(1, Math.min(maxFps, want));
	}

	@Override
	public void start(final Target t, final int maxFps, final FrameSink sink) {
		stop();
		endedReason = null;
		if (t.mode() == Mode.MIRROR) {
			startMirror(t, maxFps, sink);
			return;
		}
		if (t.mode() != Mode.WINDOW) {
			endedReason = REASON_EXTEND;
			sink.ended(REASON_EXTEND);
			return;
		}
		WindowKey key = WindowKey.parse(t.windowKey());
		if (key == null) {
			endedReason = REASON_WINDOW_CLOSED;
			sink.ended(REASON_WINDOW_CLOSED);
			return;
		}
		if (!WebRtcNatives.supported()) {
			String why = WebRtcNatives.isWindows() ? REASON_ARM : REASON_NOT_WINDOWS;
			LOGGER.info("Windows capture unavailable: {} ({})", why, System.getProperty("os.arch"));
			endedReason = why;
			sink.ended(why);
			return;
		}
		Run run = new Run(t, key, maxFps, sink);
		synchronized (lock) {
			current = run;
		}
		run.thread.start();
	}

	private void startMirror(final Target t, final int maxFps, final FrameSink sink) {
		if (!WebRtcNatives.supported()) {
			String why = WebRtcNatives.isWindows() ? REASON_ARM : REASON_NOT_WINDOWS;
			endedReason = why;
			sink.ended(why);
			return;
		}
		Supplier<@Nullable String> guard = mirrorGuard;
		String problem = null;
		try {
			problem = guard == null ? null : guard.get();
		} catch (RuntimeException e) {
			LOGGER.warn("Mirror pre-check failed", e);
			problem = REASON_MIRROR_EXCLUDE;
		}
		if (problem != null) {
			LOGGER.info("Windows mirror unavailable: {}", problem);
			endedReason = problem;
			sink.ended(problem);
			return;
		}
		MirrorSub sub = new MirrorSub(sink);
		synchronized (lock) {
			mirror = sub;
		}
		MIRROR_FEED.subscribe(sub); // every Mirror hologram shares one screen capture
	}

	@Override
	public void stop() {
		Run run;
		MirrorSub sub;
		synchronized (lock) {
			run = current;
			current = null;
			sub = mirror;
			mirror = null;
		}
		if (run != null) {
			run.cancel();
		}
		if (sub != null) {
			MIRROR_FEED.unsubscribe(sub); // returns once the feed no longer calls it
		}
	}

	@Override
	public boolean isLive() {
		Run run;
		MirrorSub sub;
		synchronized (lock) {
			run = current;
			sub = mirror;
		}
		return (run != null && run.live) || (sub != null && !sub.ended);
	}

	/** This source's subscription to the shared Mirror capture; records why it ended for {@link #status()}. */
	private final class MirrorSub implements FrameSink {
		private final FrameSink sink;
		volatile boolean ended;

		MirrorSub(final FrameSink sink) {
			this.sink = sink;
		}

		@Override
		public void full(final int w, final int h, final ByteBuffer rgba) {
			sink.full(w, h, rgba);
		}

		@Override
		public void patch(final int x, final int y, final int w, final int h, final ByteBuffer rgba) {
			sink.patch(x, y, w, h, rgba);
		}

		@Override
		public void ended(final String reason) {
			ended = true;
			endedReason = reason;
			sink.ended(reason);
		}
	}

	/** Number of Mirror holograms currently fed by the one shared screen capture (tests, logs). */
	public static int mirrorSubscribers() {
		return MIRROR_FEED.subscribers();
	}

	/**
	 * The primary monitor, captured once for every Mirror hologram (Minecraft's window is excluded from capture by its
	 * display affinity), at {@link #IDLE_FPS}, converted I420 → RGBA natively like the window path.
	 */
	static final class ScreenProducer implements SharedFeed.Producer {
		private @Nullable ByteBuffer rgba;
		private DesktopCapturer.@Nullable Result lastResult;
		private int failures;

		@Override
		public void run(final SharedFeed.Output out) throws Exception {
			try {
				WebRtcNatives.load();
			} catch (RuntimeException e) {
				LOGGER.warn("Windows capture: {}", e.getMessage());
				out.end(REASON_NO_LIBRARY);
				return;
			}
			ScreenCapturer capturer = new ScreenCapturer();
			try {
				List<DesktopSource> screens = capturer.getDesktopSources();
				List<Long> ids = new ArrayList<>();
				for (DesktopSource s : screens) {
					ids.add(s.id);
				}
				int primary = Win32.get().primaryDisplayIndex();
				Long pick = pickScreen(ids, primary);
				if (pick == null) {
					out.end(REASON_NO_SCREEN);
					return;
				}
				LOGGER.info("Windows mirror: screen {} of {} (primary index {})", pick, ids, primary);
				capturer.selectSource(screens.get(ids.indexOf(pick)));
				capturer.setMaxFrameRate(IDLE_FPS);
				capturer.start((result, frame) -> onResult(out, result, frame));
				long next = System.nanoTime();
				while (out.running()) {
					lastResult = null;
					capturer.captureFrame(); // calls onResult on this thread
					if (lastResult != null && lastResult != DesktopCapturer.Result.SUCCESS && ++failures >= MAX_FAILURES) {
						out.end(REASON_NO_SCREEN);
						return;
					}
					next += 1_000_000_000L / IDLE_FPS;
					long now = System.nanoTime();
					if (next < now - 1_000_000_000L) {
						next = now;
					}
					out.sleepUntil(next);
				}
			} finally {
				try {
					capturer.dispose();
				} catch (Throwable e) {
					LOGGER.warn("Disposing the screen capturer failed", e);
				}
			}
		}

		private void onResult(final SharedFeed.Output out, final DesktopCapturer.Result result, final @Nullable VideoFrame frame) {
			lastResult = result;
			if (frame == null) {
				return;
			}
			try {
				if (result != DesktopCapturer.Result.SUCCESS || !out.running()) {
					return;
				}
				VideoFrameBuffer buf = frame.buffer;
				int w = buf.getWidth();
				int h = buf.getHeight();
				if (w <= 0 || h <= 0) {
					return;
				}
				ByteBuffer o = rgba;
				if (o == null || o.capacity() != w * h * 4) {
					o = ByteBuffer.allocateDirect(w * h * 4);
					rgba = o;
				}
				toRgba(buf, o);
				failures = 0;
				out.frame(w, h, o.duplicate().clear());
			} catch (Throwable e) {
				lastResult = DesktopCapturer.Result.ERROR_TEMPORARY; // never let anything escape into the native call frame
				LOGGER.debug("Frame conversion failed", e);
			} finally {
				frame.release();
			}
		}
	}

	/** The owner's look for this source: "Window closed", "Pick a window" (Extend) or "Screens unavailable". */
	@Override
	public HologramLook.OwnSource status() {
		return WindowsPlatform.ownSourceFor(endedReason);
	}

	/**
	 * Converts a captured I420 frame into tightly packed, opaque RGBA in {@code direct} (capacity at least w*h*4,
	 * written from index 0), in one native pass. Natives must be loaded. Public for the Windows tests.
	 */
	public static void toRgba(final VideoFrameBuffer frame, final ByteBuffer direct) throws Exception {
		int w = frame.getWidth();
		int h = frame.getHeight();
		ByteBuffer out = direct.duplicate().clear();
		out.limit(w * h * 4);
		// libyuv names formats by little-endian word: "ABGR" is the bytes R,G,B,A in memory (and "ARGB" is B,G,R,A).
		VideoBufferConverter.convertFromI420(frame, out, FourCC.ABGR);
	}

	/** One window's capture session on its own thread. */
	private final class Run {
		private final Target target;
		private final WindowKey key;
		private final int maxFps;
		private final FrameSink sink;
		private final AtomicBoolean endedSent = new AtomicBoolean();
		final Thread thread;
		volatile boolean running = true;
		volatile boolean live = true;
		private @Nullable ByteBuffer rgba;
		private DesktopCapturer.@Nullable Result lastResult;
		private int failures;

		Run(final Target target, final WindowKey key, final int maxFps, final FrameSink sink) {
			this.target = target;
			this.key = key;
			this.maxFps = maxFps;
			this.sink = sink;
			this.thread = new Thread(this::loop, "office-win-capture");
			this.thread.setDaemon(true);
		}

		/** Stops the loop; returns once it no longer calls the sink (unless called from the capture thread itself). */
		void cancel() {
			synchronized (this) {
				running = false;
				live = false;
				notifyAll();
			}
			if (thread != Thread.currentThread()) {
				try {
					thread.join(3_000);
					if (thread.isAlive()) {
						LOGGER.warn("Windows capture thread did not stop within 3 s");
					}
				} catch (InterruptedException e) {
					Thread.currentThread().interrupt();
				}
			}
		}

		void loop() {
			WindowCapturer capturer = null;
			Win32 w32 = null;
			try {
				try {
					WebRtcNatives.load();
				} catch (RuntimeException e) {
					LOGGER.warn("Windows capture: {}", e.getMessage());
					end(REASON_NO_LIBRARY);
					return;
				}
				w32 = Win32.get();
				String exe = w32.isWindow(key.hwnd()) ? w32.exeName(key.hwnd()) : null;
				if (exe == null || !exe.equalsIgnoreCase(key.exe())) {
					end(REASON_WINDOW_CLOSED); // gone, or the handle now belongs to another program
					return;
				}
				capturer = new WindowCapturer();
				capturer.selectSource(new DesktopSource(key.exe(), key.hwnd()));
				int fps = fpsFor(target, maxFps, workingKey);
				capturer.setMaxFrameRate(fps);
				capturer.start(this::onResult);
				long next = System.nanoTime();
				while (running) {
					if (!w32.isWindow(key.hwnd())) {
						end(REASON_WINDOW_CLOSED);
						return;
					}
					lastResult = null;
					capturer.captureFrame(); // calls onResult on this thread
					if (lastResult != null && lastResult != DesktopCapturer.Result.SUCCESS) {
						if (!w32.isWindow(key.hwnd())) {
							end(REASON_WINDOW_CLOSED);
							return;
						}
						if (++failures >= MAX_FAILURES) {
							end(REASON_CANT_CAPTURE);
							return;
						}
					}
					fps = fpsFor(target, maxFps, workingKey);
					next += 1_000_000_000L / fps;
					long now = System.nanoTime();
					if (next < now - 1_000_000_000L) {
						next = now; // fell far behind (slow capture): don't burst
					}
					sleepUntil(next);
				}
			} catch (Throwable e) {
				if (running) {
					boolean gone = w32 != null && !w32.isWindow(key.hwnd());
					LOGGER.warn("Windows capture of {} failed", key.exe(), e);
					end(gone ? REASON_WINDOW_CLOSED : REASON_FAILED);
				}
			} finally {
				if (capturer != null) {
					try {
						capturer.dispose();
					} catch (Throwable e) {
						LOGGER.warn("Disposing the window capturer failed", e);
					}
				}
			}
		}

		private void onResult(final DesktopCapturer.Result result, final @Nullable VideoFrame frame) {
			lastResult = result;
			if (frame == null) {
				return;
			}
			try {
				if (result != DesktopCapturer.Result.SUCCESS || !running) {
					return;
				}
				VideoFrameBuffer buf = frame.buffer;
				int w = buf.getWidth();
				int h = buf.getHeight();
				if (w <= 0 || h <= 0) {
					return;
				}
				ByteBuffer out = rgba;
				if (out == null || out.capacity() != w * h * 4) {
					out = ByteBuffer.allocateDirect(w * h * 4);
					rgba = out;
				}
				toRgba(buf, out);
				failures = 0;
				if (running) {
					sink.full(w, h, out.duplicate().clear());
				}
			} catch (Throwable e) {
				// never let anything escape into the native capturer's call frame
				lastResult = DesktopCapturer.Result.ERROR_TEMPORARY;
				LOGGER.debug("Frame conversion failed", e);
			} finally {
				frame.release();
			}
		}

		private void end(final String reason) {
			live = false;
			if (running && endedSent.compareAndSet(false, true)) {
				endedReason = reason;
				LOGGER.info("Windows capture of {} ended: {}", key.exe(), reason);
				sink.ended(reason);
			}
		}

		private synchronized void sleepUntil(final long deadline) throws InterruptedException {
			while (running) {
				long ms = (deadline - System.nanoTime()) / 1_000_000;
				if (ms <= 0) {
					return;
				}
				wait(ms);
			}
		}
	}
}
