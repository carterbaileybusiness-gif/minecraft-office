package local.carter.office.client.capture;

import local.carter.office.core.HologramGeometry.Frame;
import local.carter.office.core.Vec3d;

/** How often to capture one of the player's own screens (pure logic for {@link OwnScreens}). */
public final class CaptureRate {
	/** While the hologram is on screen. */
	public static final int VISIBLE_FPS = 5;
	/** While the player works on it (zoomed in). */
	public static final int ZOOMED_FPS = 30;
	/** Same as the hologram renderer's view distance. */
	public static final double VIEW_DISTANCE = 64.0;

	private CaptureRate() {}

	public static int fps(final boolean visible, final boolean zoomed) {
		if (zoomed) {
			return ZOOMED_FPS;
		}
		return visible ? VISIBLE_FPS : 0;
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
