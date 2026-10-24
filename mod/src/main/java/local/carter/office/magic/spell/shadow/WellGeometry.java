package local.carter.office.magic.spell.shadow;

import local.carter.office.magic.core.ChargeMath;

import net.minecraft.util.Mth;
import net.minecraft.world.phys.Vec3;

/**
 * Pure geometry of Gravity Well (Vec3 math only, no world access), shared by the server effect ({@link GravityWell}) and the
 * client fx, so the motes you see falling in follow the same law that drags the mobs.
 *
 * <p><b>Pull.</b> Anything within {@link #radius} of the centre ({@link #RADIUS} at a tap, twice that at full charge) moves
 * toward it {@link #speed}{@code (d) = 0.12 + 0.03 × d}
 * blocks a tick (capped at {@link #CAP}), never more than the distance left, so it settles on the centre instead of
 * overshooting ({@link #pull}). From a distance {@code r0 ≤ 16} that law has a closed form: after {@code t} ticks the distance
 * left is {@code max(0, (r0 + 4) × 0.97^t − 4)} ({@link #radiusAt}); from 6 blocks it arrives in 31 ticks.
 *
 * <p><b>Infall.</b> The client's motes ride {@link #spiral}: the same distance law, wound round the centre faster as they near
 * it and flattened into the core's plane.
 */
public final class WellGeometry {
	/** How far ahead the well is placed, at most (the look ray). */
	public static final double RANGE = 16;
	/** The well floats this far above the ground under the ray point. */
	public static final double HEIGHT = 1;
	/** Everything pushable within this distance of a tap's centre is pulled (twice as far at full charge). */
	public static final double RADIUS = 6;
	public static final double BASE = 0.12;
	public static final double PER_BLOCK = 0.03;
	/** Most pull a tick. */
	public static final double CAP = 0.6;
	/**
	 * Creatures this close to the centre take {@link #HURT} × (1 + charge) every {@link #HURT_EVERY} ticks (4 a second at a tap:
	 * a zombie dragged in feels it within a second).
	 */
	public static final double HURT_RADIUS = 2.5;
	public static final int HURT_EVERY = 10;
	public static final float HURT = 2;
	public static final int BASE_LIFE = 80;
	public static final int LIFE_PER_CHARGE = 80;

	private WellGeometry() {}

	/** Ticks the well lasts for a charge 0..1: 80 + 80 × charge. */
	public static int life(float charge) {
		return BASE_LIFE + Math.round(LIFE_PER_CHARGE * Mth.clamp(charge, 0F, 1F));
	}

	/** The pull's reach at {@code charge}: {@code RADIUS × ChargeMath.size(charge)}, 6 at a tap and 12 at full charge. */
	public static double radius(float charge) {
		return RADIUS * ChargeMath.size(charge);
	}

	/** The core's hurting reach at {@code charge}: {@code HURT_RADIUS × ChargeMath.size(charge)}. */
	public static double hurtRadius(float charge) {
		return HURT_RADIUS * ChargeMath.size(charge);
	}

	/** The pull's speed at distance {@code d} by the formula alone: {@code min(0.6, 0.12 + 0.03 × d)}. */
	public static double speed(double d) {
		return Math.min(CAP, BASE + PER_BLOCK * Math.max(0, d));
	}

	/**
	 * The pull on something at {@code at} this tick, in blocks a tick: toward {@code centre}, {@link #speed} long but never longer
	 * than the distance left (so it lands on the centre); zero at the centre.
	 */
	public static Vec3 pull(Vec3 centre, Vec3 at) {
		Vec3 to = centre.subtract(at);
		double d = to.length();
		if (d < 1.0E-6 || !Double.isFinite(d)) return Vec3.ZERO;
		return to.scale(Math.min(speed(d), d) / d);
	}

	/** Whether {@code at} is within a tap's pull of {@code centre}. */
	public static boolean inReach(Vec3 centre, Vec3 at) {
		return inReach(centre, at, RADIUS);
	}

	/** Whether {@code at} is within {@code radius} of {@code centre}. */
	public static boolean inReach(Vec3 centre, Vec3 at, double radius) {
		return at.distanceToSqr(centre) <= radius * radius;
	}

	/**
	 * Distance left after {@code t} ticks (fractions allowed) for something pulled straight in from {@code r0} (≤ 16, where the cap
	 * never binds): {@code max(0, (r0 + 4) × 0.97^t − 4)}. At whole ticks it is exactly what repeating {@link #pull} gives.
	 */
	public static double radiusAt(double r0, double t) {
		double k = BASE / PER_BLOCK;
		return Math.max(0, (r0 + k) * Math.pow(1 - PER_BLOCK, Math.max(0, t)) - k);
	}

	/** Whole ticks until something {@code r0} (≤ 16) from the centre arrives there. */
	public static int arrival(double r0) {
		int t = 0;
		while (radiusAt(r0, t) > 0 && t < 10_000) t++;
		return t;
	}

	/**
	 * Where an infalling mote is {@code t} ticks after starting {@code r0} from {@code centre} at {@code angle0} (radians, round
	 * the vertical) and {@code lift0} above the centre's height: its distance follows {@link #radiusAt}, it winds round faster as it
	 * nears the core, and its height shrinks with its distance (the motes settle into the core's plane).
	 */
	public static Vec3 spiral(Vec3 centre, double r0, double angle0, double lift0, double t) {
		double r = radiusAt(r0, t);
		double a = angle(r0, angle0, t);
		double lift = r0 < 1.0E-6 ? 0 : lift0 * r / r0;
		return new Vec3(centre.x + Math.cos(a) * r, centre.y + lift, centre.z + Math.sin(a) * r);
	}

	/** Far-side pieces this close to the view line (as a share of the core's radius) are behind the core ({@link #behindCore}). */
	public static final double HIDDEN_SHARE = 1.1;
	/** How much light a piece behind the core keeps. */
	public static final float HIDDEN_LIGHT = 0.1F;

	/**
	 * Whether {@code p} is hidden behind the core at {@code centre} as seen from {@code eye}: farther along the view line than the
	 * centre, and within {@code within} of that line (the client dims such light, which would otherwise be added over the black).
	 */
	public static boolean behindCore(Vec3 eye, Vec3 centre, Vec3 p, double within) {
		Vec3 view = centre.subtract(eye);
		double d = view.length();
		if (d < 1.0E-6) return false;
		Vec3 axis = view.scale(1 / d);
		Vec3 rel = p.subtract(eye);
		double along = rel.dot(axis);
		if (along <= d) return false;
		return rel.subtract(axis.scale(along)).lengthSqr() <= within * within;
	}

	/** A mote's angle at {@code t} on its {@link #spiral}: a steady turn plus a wind-up as it nears the centre. */
	public static double angle(double r0, double angle0, double t) {
		double r = radiusAt(r0, t);
		return angle0 + 0.06 * Math.max(0, t) + 0.9 * Math.log((r0 + 0.5) / (r + 0.5));
	}
}
