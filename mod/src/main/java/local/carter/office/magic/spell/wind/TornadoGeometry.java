package local.carter.office.magic.spell.wind;

import net.minecraft.world.phys.Vec3;

/**
 * Tornado's path and wind (pure: vectors only, no world access). The server effect and the client funnel both use it.
 *
 * <p>The whirlwind starts at {@code origin} (on the ground) and travels along the level direction {@code dir} at
 * {@link #SPEED} blocks a tick for {@link #life} ticks: {@code at(t) = origin + dir × SPEED × t}. Its reach is a column of
 * radius {@link #RADIUS} round that point, from {@link #BELOW} under it to {@link #HEIGHT} over it. Anything caught gets the
 * velocity {@link #push}: {@link #TANGENTIAL} round the axis, {@link #INWARD} toward it, {@link #UP} up, plus the tornado's own
 * travel. It turns anticlockwise seen from above ({@link #tangent}).
 */
public record TornadoGeometry(Vec3 origin, Vec3 dir, int life) {
	/** How far ahead the ray looks for the start. */
	public static final double RANGE = 20;
	public static final double SPEED = 0.15;
	public static final int LIFE = 120;
	public static final int LIFE_PER_CHARGE = 40;
	public static final double RADIUS = 4;
	public static final double HEIGHT = 9;
	public static final double BELOW = 1;
	public static final double TANGENTIAL = 0.35;
	public static final double INWARD = 0.15;
	public static final double UP = 0.25;
	/** Inside this distance of the axis nothing is pulled further in (it would only jitter across the axis). */
	public static final double CORE = 0.5;
	public static final float HURT = 1;
	public static final int HURT_EVERY = 10;

	public TornadoGeometry {
		dir = flatUnit(dir);
	}

	/** Ticks the tornado lasts at {@code charge}: {@code 120 + 40 × charge}. */
	public static int life(float charge) {
		return LIFE + Math.round(LIFE_PER_CHARGE * Math.max(0F, Math.min(1F, charge)));
	}

	/** Where the tornado's base is after {@code t} ticks (fractions allowed; held at the ends of its life). */
	public Vec3 at(double t) {
		double s = Math.max(0, Math.min(life, t));
		return origin.add(dir.scale(SPEED * s));
	}

	/** Whether {@code p} is inside the tornado whose base is at {@code base}. */
	public static boolean inReach(Vec3 base, Vec3 p) {
		double dy = p.y - base.y;
		if (dy < -BELOW || dy > HEIGHT) return false;
		double dx = p.x - base.x;
		double dz = p.z - base.z;
		return dx * dx + dz * dz <= RADIUS * RADIUS;
	}

	/** The unit tangent of the spin at level offset {@code (dx, dz)} from the axis (anticlockwise seen from above). */
	public static Vec3 tangent(double dx, double dz) {
		double len = Math.sqrt(dx * dx + dz * dz);
		if (len < 1.0E-6) return new Vec3(1, 0, 0);
		return new Vec3(dz / len, 0, -dx / len);
	}

	/**
	 * The velocity the tornado based at {@code base} gives something at {@code p}: {@link #TANGENTIAL} along {@link #tangent},
	 * {@link #INWARD} toward the axis (none inside {@link #CORE}), {@link #UP} up, plus this tornado's travel.
	 */
	public Vec3 push(Vec3 base, Vec3 p) {
		double dx = p.x - base.x;
		double dz = p.z - base.z;
		double len = Math.sqrt(dx * dx + dz * dz);
		Vec3 t = tangent(dx, dz).scale(TANGENTIAL);
		Vec3 in = len > CORE ? new Vec3(-dx / len, 0, -dz / len).scale(INWARD) : Vec3.ZERO;
		return t.add(in).add(0, UP, 0).add(dir.scale(SPEED));
	}

	/** The level part of {@code v} at unit length ({@code +Z} if it has none). */
	static Vec3 flatUnit(Vec3 v) {
		Vec3 f = new Vec3(v.x, 0, v.z);
		return f.lengthSqr() < 1.0E-10 ? new Vec3(0, 0, 1) : f.normalize();
	}
}
