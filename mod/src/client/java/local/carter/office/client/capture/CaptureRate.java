package local.carter.office.client.capture;

import local.carter.office.core.HologramGeometry.Frame;
import local.carter.office.core.Vec3d;

/** How often to capture one of the player's own screens (pure logic for {@link OwnScreens}). */
public final class CaptureRate {
	/**
	 * While the hologram is on screen. Measured through the real helper (inputprobe --latency, laptop-round2-probe.log):
	 * at 5 fps a change took 143 ms (median, p90 209) to arrive; at 30 fps 32 ms (p90 45); at 60 fps 33 ms (p90 40).
	 */
	public static final int VISIBLE_FPS = 30;
	/** The screen the real cursor is on while working (work mode), or the one zoomed into (Windows). */
	public static final int ZOOMED_FPS = 60;
	/** Same as the hologram renderer's view distance. */
	public static final double VIEW_DISTANCE = 64.0;

	/** A visibility change only counts once it has lasted this long (no capture churn when the camera sweeps by). */
	public static final long VISIBILITY_HOLD_MS = 1_000;

	private CaptureRate() {}

	/** Debounced visibility of one hologram: the first observation counts at once, later changes after {@link #VISIBILITY_HOLD_MS}. */
	public static final class Visibility {
		private boolean known;
		private boolean shown;
		private long pendingSinceMs = -1;

		public boolean update(final boolean raw, final long nowMs) {
			if (!known) {
				known = true;
				shown = raw;
				return shown;
			}
			if (raw == shown) {
				pendingSinceMs = -1;
				return shown;
			}
			if (pendingSinceMs < 0) {
				pendingSinceMs = nowMs;
			}
			if (nowMs - pendingSinceMs >= VISIBILITY_HOLD_MS) {
				shown = raw;
				pendingSinceMs = -1;
			}
			return shown;
		}
	}

	public static int fps(final boolean visible, final boolean zoomed) {
		if (zoomed) {
			return ZOOMED_FPS;
		}
		return visible ? VISIBLE_FPS : 0;
	}

	/**
	 * Own screens: zoomed in (Windows) → 60; visible → 60 when the real cursor is on it while working, else 30; not
	 * visible → 0 (also with the cursor on it: nobody sees it). Only one screen at a time runs at 60.
	 */
	public static int fps(final boolean visible, final boolean zoomed, final boolean cursorHere) {
		if (zoomed) {
			return ZOOMED_FPS;
		}
		if (!visible) {
			return 0;
		}
		return cursorHere ? ZOOMED_FPS : VISIBLE_FPS;
	}

	/**
	 * The viewer at {@code eye} is in front of the panel (the content quad is front-only; from behind only a faint
	 * sheet shows) and within {@link #VIEW_DISTANCE}. The frustum test is done separately by the caller.
	 */
	public static boolean seesFront(final Frame f, final Vec3d eye) {
		Vec3d rel = eye.sub(f.center());
		Vec3d normal = f.right().cross(f.up());
		if (rel.dot(normal) <= 0) {
			return false;
		}
		double x = Math.max(-f.w() / 2, Math.min(f.w() / 2, rel.dot(f.right())));
		double y = Math.max(-f.h() / 2, Math.min(f.h() / 2, rel.dot(f.up())));
		Vec3d nearest = f.center().add(f.right().scale(x)).add(f.up().scale(y));
		return eye.sub(nearest).length() <= VIEW_DISTANCE;
	}
}
