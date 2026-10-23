package local.carter.office.client.capture;

import java.nio.ByteBuffer;

import org.jspecify.annotations.Nullable;

import local.carter.office.client.helper.HelperConnection;
import local.carter.office.client.helper.HelperProtocol.Capture;
import local.carter.office.client.helper.HelperProtocol.CaptureKind;
import local.carter.office.client.helper.HelperProtocol.Frame;
import local.carter.office.client.helper.HelperProtocol.Patch;
import local.carter.office.client.helper.HelperProtocol.PixelRect;
import local.carter.office.client.helper.HelperProtocol.Ready;
import local.carter.office.client.helper.HelperProtocol.Stop;
import local.carter.office.client.helper.HelperProtocol.WindowKey;
import local.carter.office.client.helper.MacHelper;
import local.carter.office.client.render.HologramLook;
import local.carter.office.core.Mode;

/**
 * One own hologram's screen from the Mac helper. EXTEND: makes sure the hologram's virtual display (its slot, at its
 * shape's resolution) exists, then captures it; WINDOW: captures the chosen window. Every CAPTURE gets a new surface id,
 * so late frames of an older capture are ignored. Each FRAME / PATCH is passed to the sink and acknowledged with READY
 * (the helper keeps one frame in flight per surface).
 *
 * <p>Rate: {@link #setMaxFps} 0 stops the capture (STOP) without ending the source; a new rate re-captures. A helper
 * that drops or restarts is not the end of the source: it re-captures after the next WELCOME (and DISPLAY). Only
 * "window closed" and capture failures end it ({@link FrameSink#ended}); "no permission" shows NEEDS_HELPER and waits
 * for a reconnect with permission.
 *
 * <p>Threads: start/stop/setMaxFps from the client thread; helper callbacks from the connection thread. The sink is
 * only called under {@link #lock}, so once {@link #stop} returns it is never called again.
 */
public final class MacHelperSource implements CaptureSource {
	/** A capture this long after the last display re-request counts as stable (the re-request backoff resets). */
	private static final long STABLE_NANOS = 60_000_000_000L;
	private final MacHelper helper;
	private final Runnable onStart;
	private final Object lock = new Object();

	// guarded by lock
	private @Nullable Target target;
	private @Nullable FrameSink sink;
	private int fps;
	private boolean running;
	private int surface;
	private long generation = -1;
	private int frameW;
	private int frameH;
	/** Display re-request backoff (0.5 → 8 s); only reset by a capture that has run a while without needing one. */
	private long retryDelayMs = HelperConnection.Backoff.MIN_MS;
	private int retryToken;
	private long lastRetryNanos;

	private volatile boolean ended;
	private volatile @Nullable String endedReason;
	private volatile boolean noPermission;

	public MacHelperSource(final MacHelper helper) {
		this(helper, () -> {});
	}

	/** {@code onStart} runs on every {@link #start} (MacPlatform: set up / connect the helper on first use). */
	public MacHelperSource(final MacHelper helper, final Runnable onStart) {
		this.helper = helper;
		this.onStart = onStart;
	}

	@Override
	public void start(final Target t, final int maxFps, final FrameSink s) {
		stop();
		synchronized (lock) {
			target = t;
			sink = s;
			fps = Math.max(0, maxFps);
			running = true;
			retryToken++;
			retryDelayMs = HelperConnection.Backoff.MIN_MS;
			ended = false;
			endedReason = null;
			noPermission = false;
		}
		helper.attach(this);
		onStart.run();
		update(false);
	}

	@Override
	public void setMaxFps(final int newFps) {
		synchronized (lock) {
			int f = Math.max(0, newFps);
			if (!running || f == fps) {
				return;
			}
			fps = f;
		}
		update(true);
	}

	@Override
	public void stop() {
		int old;
		synchronized (lock) {
			if (!running) {
				return;
			}
			running = false;
			retryToken++;
			old = surface;
			surface = 0;
			sink = null;
		}
		helper.detach(this);
		if (old != 0) {
			helper.releaseSurface(old);
			helper.send(new Stop(old));
		}
	}

	@Override
	public boolean isLive() {
		synchronized (lock) {
			return running && !ended;
		}
	}

	@Override
	public HologramLook.OwnSource status() {
		if (ended && "window closed".equals(endedReason)) {
			return HologramLook.OwnSource.WINDOW_CLOSED;
		}
		MacHelper.Status st = helper.status();
		if (noPermission || st == MacHelper.Status.NOT_SET_UP || st == MacHelper.Status.NO_PERMISSION) {
			return HologramLook.OwnSource.NEEDS_HELPER;
		}
		return HologramLook.OwnSource.READY;
	}

	public @Nullable Target target() {
		synchronized (lock) {
			return running ? target : null;
		}
	}

	/** The surface being captured, or 0. */
	public int surface() {
		synchronized (lock) {
			return surface;
		}
	}

	public @Nullable String endedReason() {
		return endedReason;
	}

	/** (Re)issues CAPTURE when wanted: connected, rate above 0, and (EXTEND) the display online. */
	private void update(final boolean restart) {
		int stopSurface = 0;
		Capture capture = null;
		synchronized (lock) {
			if (!running || ended || target == null) {
				return;
			}
			if (!helper.isConnected()) {
				surface = 0;
				return;
			}
			if (fps <= 0) {
				stopSurface = surface;
				surface = 0;
			} else if (surface == 0 || restart) {
				Target t = target;
				boolean extend = t.mode() == Mode.EXTEND;
				if (!extend && (t.windowKey() == null || WindowKey.parse(t.windowKey()) == null)) {
					stopSurface = surface; // no window picked yet: the look says "Pick a window"
					surface = 0;
				} else if (extend && !helper.ensureDisplay(t.displaySlot(), t.shape().pxW(), t.shape().pxH())) {
					stopSurface = surface; // wait for DISPLAY
					surface = 0;
				} else {
					stopSurface = surface;
					surface = helper.newSurface(this);
					generation = -1;
					capture = extend
							? new Capture(surface, CaptureKind.DISPLAY, t.displaySlot(), null, t.shape().pxW(), fps)
							: new Capture(surface, CaptureKind.WINDOW, null, t.windowKey(), t.shape().pxW(), fps);
				}
			}
		}
		if (stopSurface != 0) {
			helper.releaseSurface(stopSurface);
			helper.send(new Stop(stopSurface));
		}
		if (capture != null) {
			helper.send(capture);
		}
	}

	// ---------------------------------------------------------------- helper callbacks (connection thread)

	public void helperConnected() {
		synchronized (lock) {
			surface = 0;
			noPermission = false;
		}
		update(false);
	}

	public void helperDisconnected() {
		synchronized (lock) {
			surface = 0;
		}
	}

	public void displayOnline(final int slot, final int w, final int h) {
		synchronized (lock) {
			Target t = target;
			if (t == null || t.mode() != Mode.EXTEND || t.displaySlot() != slot || t.shape().pxW() != w || t.shape().pxH() != h
					|| surface != 0) {
				return;
			}
		}
		update(false);
	}

	public void onFrame(final Frame f) {
		synchronized (lock) {
			if (!running || surface != f.surface() || sink == null) {
				return;
			}
			generation = f.generation();
			if (System.nanoTime() - lastRetryNanos > STABLE_NANOS) {
				retryDelayMs = HelperConnection.Backoff.MIN_MS;
			}
			frameW = f.width();
			frameH = f.height();
			sink.full(f.width(), f.height(), f.rgba());
		}
		helper.send(new Ready(f.surface()));
	}

	public void onPatch(final Patch p) {
		synchronized (lock) {
			if (!running || surface != p.surface() || sink == null) {
				return;
			}
			if (p.generation() == generation && p.width() == frameW && p.height() == frameH) {
				ByteBuffer all = p.rgba();
				int offset = all.position();
				for (PixelRect r : p.rects()) {
					int len = r.w() * r.h() * 4;
					sink.patch(r.x(), r.y(), r.w(), r.h(), all.slice(offset, len));
					offset += len;
				}
			}
		}
		helper.send(new Ready(p.surface()));
	}

	/** Under {@link #lock}. */
	private void scheduleRetry() {
		long delay = retryDelayMs;
		retryDelayMs = Math.min(HelperConnection.Backoff.MAX_MS, delay * 2);
		lastRetryNanos = System.nanoTime();
		int token = ++retryToken;
		helper.schedule(() -> retryDisplay(token), delay);
	}

	/** Re-requests the display until a capture is running again (DISPLAY may never come if the helper failed). */
	private void retryDisplay(final int token) {
		synchronized (lock) {
			if (token != retryToken || !running || ended || surface != 0 || target == null || target.mode() != Mode.EXTEND) {
				return;
			}
			helper.displayOffline(target.displaySlot());
		}
		update(false);
		synchronized (lock) {
			if (token == retryToken && running && !ended && surface == 0) {
				scheduleRetry();
			}
		}
	}

	public void onEnded(final int endedSurface, final String reason) {
		FrameSink endSink = null;
		synchronized (lock) {
			if (!running || surface != endedSurface) {
				return;
			}
			surface = 0;
			helper.releaseSurface(endedSurface);
			switch (reason) {
				case "no permission" -> {
					noPermission = true;
					helper.permissionDenied();
				}
				case "no display", "display removed" -> {
					// The display went away under us (removed, or the helper lost it): ask for it again, with backoff,
					// so two holograms that disagree about a slot's shape swap at most every 8 s instead of fighting.
					if (target != null) {
						helper.displayOffline(target.displaySlot());
					}
					scheduleRetry();
				}
				default -> {
					ended = true;
					endedReason = reason;
					endSink = sink;
				}
			}
			if (endSink != null) {
				endSink.ended(reason);
			}
		}
	}
}
