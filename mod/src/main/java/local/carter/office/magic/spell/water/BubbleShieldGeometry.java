package local.carter.office.magic.spell.water;

import org.jspecify.annotations.Nullable;

import net.minecraft.util.Mth;
import net.minecraft.world.phys.Vec3;

/**
 * Bubble Shield's pure geometry (no world access), shared by the server spell and the client fx: a sphere of
 * {@link #RADIUS} about the caster's middle, lasting {@link #duration}. A projectile is stopped when it is inside the bubble
 * or its next move ({@code pos → pos + velocity}) would cross into it, so even a fast arrow never reaches the caster.
 */
public final class BubbleShieldGeometry {
	public static final double RADIUS = 2.5;
	/** Projectiles are looked for this far past the bubble (the fastest ones move about 4 blocks a tick). */
	public static final double SEARCH = 5;

	private BubbleShieldGeometry() {}

	/** How long the bubble lasts: 120 + 60 × charge ticks. */
	public static int duration(float charge) {
		return 120 + Math.round(60 * Mth.clamp(charge, 0F, 1F));
	}

	/** The bubble's centre for a caster standing at {@code feet}, {@code height} tall. */
	public static Vec3 centre(Vec3 feet, double height) {
		return feet.add(0, height / 2, 0);
	}

	/** Whether a projectile at {@code pos} moving {@code velocity} a tick is inside the bubble at {@code centre} or about to enter it. */
	public static boolean threatens(Vec3 centre, Vec3 pos, Vec3 velocity) {
		return entry(centre, pos, velocity) != null;
	}

	/**
	 * Where a projectile at {@code pos} moving {@code velocity} meets the bubble at {@code centre} on its next move: {@code pos}
	 * when it is already inside, the first point on the surface it crosses, or null when it misses the bubble this tick.
	 */
	public static @Nullable Vec3 entry(Vec3 centre, Vec3 pos, Vec3 velocity) {
		double r2 = RADIUS * RADIUS;
		Vec3 m = pos.subtract(centre);
		double c = m.lengthSqr() - r2;
		if (c <= 0) return pos;
		double a = velocity.lengthSqr();
		if (a < 1.0E-12) return null;
		double b = m.dot(velocity);
		if (b >= 0) return null; // moving away
		double disc = b * b - a * c;
		if (disc < 0) return null;
		double t = (-b - Math.sqrt(disc)) / a;
		return t <= 1 ? pos.add(velocity.scale(t)) : null;
	}
}
