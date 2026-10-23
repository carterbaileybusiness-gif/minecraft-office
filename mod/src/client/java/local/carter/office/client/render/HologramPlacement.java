package local.carter.office.client.render;

import local.carter.office.core.HologramGeometry.Frame;
import local.carter.office.core.HologramSpec;
import local.carter.office.core.Vec3d;

/**
 * Where a hologram is in the world (pure math, shared by the renderer, picking and dragging).
 *
 * <p>Conventions (Task 5): offsets are blocks from the emitter block's centre; {@code yaw} is the Minecraft yaw the front
 * faces (0 = south/+Z, 90 = west). In the hologram's local frame the front faces +Z, +X is right and +Y is up; the
 * renderer gets there with {@code rotate(Y, -yaw)}.
 */
public final class HologramPlacement {
	/** Top of the emitter base (it is 4 px tall), in blocks relative to the emitter block's centre. */
	public static final double EMITTER_TOP = -0.25;
	/** Gap kept between the emitter top and a hologram's bottom edge when a resize would push it lower. */
	public static final double EMITTER_CLEARANCE = 0.05;

	private HologramPlacement() {}

	/** Unit "right" vector of a hologram whose front faces {@code yaw}. */
	public static Vec3d right(final float yaw) {
		double r = Math.toRadians(yaw);
		return new Vec3d(Math.cos(r), 0, Math.sin(r));
	}

	/** World-space frame of {@code spec} on the emitter whose block's minimum corner is (ex, ey, ez). */
	public static Frame frame(final double ex, final double ey, final double ez, final HologramSpec spec) {
		Vec3d center = new Vec3d(ex + 0.5 + spec.offX(), ey + 0.5 + spec.offY(), ez + 0.5 + spec.offZ());
		return new Frame(center, right(spec.yaw()), new Vec3d(0, 1, 0), spec.width(), spec.height());
	}

	/** Distance from {@code p} to the nearest point of the (finite) panel. */
	public static double distanceToPanel(final Frame f, final Vec3d p) {
		Vec3d rel = p.sub(f.center());
		double x = clamp(rel.dot(f.right()), -f.w() / 2, f.w() / 2);
		double y = clamp(rel.dot(f.up()), -f.h() / 2, f.h() / 2);
		Vec3d nearest = f.center().add(f.right().scale(x)).add(f.up().scale(y));
		return p.sub(nearest).length();
	}

	public static double clamp(final double v, final double lo, final double hi) {
		return Math.max(lo, Math.min(hi, v));
	}
}
