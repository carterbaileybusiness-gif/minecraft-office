package local.carter.office.client.pointer;

import local.carter.office.core.Vec3d;

/**
 * Work mode, Carter's ruling (round 2 fix): the view follows the real cursor — each frame the camera turns so the
 * crosshair sits on the cursor's point on whichever own hologram it is on (also when it crosses from the Mirror onto an
 * Extend screen), lightly smoothed: an exponential approach with time constant {@link #TAU_MS}, so a jump settles
 * within about 100 ms (3 τ ≈ 95 %) without jitter. The cursor itself is never moved by this; the body doesn't move. Pure.
 */
public final class ViewFollow {
	/** Smoothing time constant. */
	public static final double TAU_MS = 30;
	/** Closer than this (degrees) the view simply lands on the target. */
	static final float SNAP = 0.01f;

	private ViewFollow() {}

	/** {yaw, pitch} (Minecraft's degrees: yaw 0 = south, pitch + = down) for looking from {@code eye} at {@code target}. */
	public static float[] aim(final Vec3d eye, final Vec3d target) {
		double dx = target.x() - eye.x();
		double dy = target.y() - eye.y();
		double dz = target.z() - eye.z();
		float yaw = (float) Math.toDegrees(Math.atan2(-dx, dz));
		float pitch = (float) -Math.toDegrees(Math.atan2(dy, Math.hypot(dx, dz)));
		return new float[] {yaw, pitch};
	}

	/** One frame of smoothing toward {targetYaw, targetPitch}, {@code dtMs} after the last; yaw takes the short way round. */
	public static float[] step(final float yaw, final float pitch, final float targetYaw, final float targetPitch, final double dtMs) {
		float dYaw = wrap(targetYaw - yaw);
		float dPitch = targetPitch - pitch;
		if (Math.abs(dYaw) < SNAP && Math.abs(dPitch) < SNAP) {
			return new float[] {yaw + dYaw, targetPitch};
		}
		double a = 1 - Math.exp(-Math.max(0, dtMs) / TAU_MS);
		return new float[] {(float) (yaw + a * dYaw), (float) (pitch + a * dPitch)};
	}

	/** Degrees into (-180, 180]. */
	static float wrap(final float degrees) {
		float d = degrees % 360f;
		if (d > 180f) {
			d -= 360f;
		} else if (d <= -180f) {
			d += 360f;
		}
		return d;
	}
}
