package local.carter.office.client.zoom;

import local.carter.office.core.HologramGeometry.Frame;
import local.carter.office.core.Vec3d;

/**
 * Camera math for zooming into a hologram (pure).
 *
 * <p>The working pose looks straight at the hologram's front from its centre normal, at the distance where the panel
 * exactly fills the view in its tighter dimension (vertical FOV {@code fov}, window aspect {@code aspect}); the other
 * dimension is letterboxed. {@link #letterbox} is the same rectangle in window pixels, so the 2D overlay drawn while
 * working lines up with the 3D view it replaces.
 *
 * <p>Angles use Minecraft's conventions: {@code yaw} 0 looks south (+Z), 90 west; look vector = (-sin yaw, 0, cos yaw)
 * at pitch 0. A hologram whose spec yaw is {@code y} faces (-sin y, 0, cos y), so the camera looks along the opposite
 * direction, yaw {@code y + 180}.
 */
public final class ZoomPose {
	public record Pose(Vec3d eye, float yaw, float pitch) {}

	public record Rect(int x, int y, int w, int h) {}

	private ZoomPose() {}

	/** Distance from the panel at which a w×h panel fills a view of vertical FOV {@code fovDeg} and {@code aspect}. */
	public static double distance(final double w, final double h, final double fovDeg, final double aspect) {
		double t = Math.tan(Math.toRadians(fovDeg) / 2);
		return Math.max((h / 2) / t, (w / 2) / (t * aspect));
	}

	public static Pose framing(final Frame f, final double fovDeg, final double aspect) {
		Vec3d normal = f.right().cross(f.up()); // the front (see CaptureRate.seesFront)
		double len = normal.length();
		Vec3d n = len == 0 ? new Vec3d(0, 0, 1) : normal.scale(1 / len);
		Vec3d eye = f.center().add(n.scale(distance(f.w(), f.h(), fovDeg, aspect)));
		// look = -n = (-sin yaw, 0, cos yaw)  ->  yaw = atan2(n.x, -n.z)
		float yaw = (float) Math.toDegrees(Math.atan2(n.x(), -n.z()));
		return new Pose(eye, wrap(yaw), 0f);
	}

	/** The centred rectangle of aspect {@code contentAspect} that fits a {@code screenW}×{@code screenH} window. */
	public static Rect letterbox(final int screenW, final int screenH, final double contentAspect) {
		if (screenW <= 0 || screenH <= 0 || !(contentAspect > 0)) {
			return new Rect(0, 0, Math.max(0, screenW), Math.max(0, screenH));
		}
		int w;
		int h;
		if ((double) screenW / screenH > contentAspect) {
			h = screenH;
			w = (int) Math.round(screenH * contentAspect);
		} else {
			w = screenW;
			h = (int) Math.round(screenW / contentAspect);
		}
		return new Rect((screenW - w) / 2, (screenH - h) / 2, w, h);
	}

	public static Vec3d lerp(final Vec3d a, final Vec3d b, final double t) {
		return a.add(b.sub(a).scale(t));
	}

	/** Interpolates angles in degrees the short way round. */
	public static float lerpDegrees(final float from, final float to, final double t) {
		float delta = wrap(to - from);
		return (float) (from + delta * t);
	}

	/** Wraps degrees to [-180, 180). */
	public static float wrap(final float degrees) {
		float d = degrees % 360f;
		if (d >= 180f) {
			d -= 360f;
		}
		if (d < -180f) {
			d += 360f;
		}
		return d;
	}
}
