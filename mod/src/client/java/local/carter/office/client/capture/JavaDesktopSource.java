package local.carter.office.client.capture;

import java.nio.ByteBuffer;
import java.util.concurrent.atomic.AtomicBoolean;

import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;

import com.mojang.logging.LogUtils;

import dev.onvoid.webrtc.media.FourCC;
import dev.onvoid.webrtc.media.video.VideoBufferConverter;
import dev.onvoid.webrtc.media.video.VideoFrame;
import dev.onvoid.webrtc.media.video.VideoFrameBuffer;
import dev.onvoid.webrtc.media.video.desktop.DesktopCapturer;
import dev.onvoid.webrtc.media.video.desktop.DesktopSource;
import dev.onvoid.webrtc.media.video.desktop.WindowCapturer;

import local.carter.office.client.platform.Win32;
import local.carter.office.client.platform.WindowKey;
import local.carter.office.core.Mode;

/**
 * Windows capture of one app window with webrtc-java's {@link WindowCapturer} (no helper install). Window mode only:
 * an Extend target ends at once with {@link #REASON_EXTEND}. One dedicated thread per running source calls
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
	public static final int IDLE_FPS = 10;
	public static final int WORKING_FPS = 30;
	/** Consecutive failed captures of a window that still exists before giving up (~5 s at 10 fps). */
	static final int MAX_FAILURES = 50;
	private static final Logger LOGGER = LogUtils.getLogger();

	private static volatile @Nullable String workingKey;

	private final Object lock = new Object();
	private @Nullable Run current;

	/** The window the player is zoomed into (captured faster), or null. Set by the Windows platform on zoom in/out. */
	public static void setWorkingKey(final @Nullable String windowKey) {
		workingKey = windowKey;
	}

	/** Frames per second for {@code t}: 10, or 30 while working on that window; never above {@code maxFps}, at least 1. */
	public static int fpsFor(final Target t, final int maxFps, final @Nullable String working) {
		int want = working != null && working.equals(t.windowKey()) ? WORKING_FPS : IDLE_FPS;
		return Math.max(1, Math.min(maxFps, want));
	}

	@Override
	public void start(final Target t, final int maxFps, final FrameSink sink) {
		stop();
		if (t.mode() != Mode.WINDOW) {
			sink.ended(REASON_EXTEND);
			return;
		}
		WindowKey key = WindowKey.parse(t.windowKey());
		if (key == null) {
			sink.ended(REASON_WINDOW_CLOSED);
			return;
		}
		if (!WebRtcNatives.supported()) {
			String why = WebRtcNatives.isWindows() ? REASON_ARM : REASON_NOT_WINDOWS;
			LOGGER.info("Windows capture unavailable: {} ({})", why, System.getProperty("os.arch"));
			sink.ended(why);
			return;
		}
		Run run = new Run(t, key, maxFps, sink);
		synchronized (lock) {
			current = run;
		}
		run.thread.start();
	}

	@Override
	public void stop() {
		Run run;
		synchronized (lock) {
			run = current;
			current = null;
		}
		if (run != null) {
			run.cancel();
		}
	}

	@Override
	public boolean isLive() {
		Run run;
		synchronized (lock) {
			run = current;
		}
		return run != null && run.live;
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

	/** One capture session on its own thread. */
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
