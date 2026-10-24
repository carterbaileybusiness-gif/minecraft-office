package local.carter.office.magic.spell.fire;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

import local.carter.office.magic.core.ChargeMath;
import local.carter.office.magic.spell.ProjectileSpec;
import local.carter.office.magic.spell.Projectiles;
import local.carter.office.magic.tree.Knob;
import local.carter.office.magic.tree.SpellRank;

import net.minecraft.util.Mth;
import net.minecraft.world.phys.Vec3;

/**
 * Where a Fireball Barrage's fireballs go (pure: vector math only, no world access). The server and the client fx both build the
 * volley from what the fx event carries: the aim point ({@code target}), the caster's flat facing ({@code dir}), the cast's
 * {@code seed}, the fireball count and, per fireball, how much of its sky drop is clear ({@code lift}).
 *
 * <p>Each fireball is aimed at the aim point plus a seeded offset of at most {@link #spread} (a disc in the horizontal plane:
 * {@link #SPREAD} for a tap's three fireballs, twice that for a full charge's six).
 * It is thrown from the sky, {@link #HEIGHT} above its aim and {@link #BEYOND} beyond it, away from the caster, so the caster sees the circle it leaves from above the spot (that offset scaled by its
 * {@code lift}, shorter under a roof), at {@link #SPEED} blocks a tick with {@link #GRAVITY}, on the low arc that passes exactly
 * through its aim. One leaves every {@link #INTERVAL} ticks, the first at the cast.
 */
public final class BarrageGeometry {
	/** How far the caster can point the barrage. */
	public static final double RANGE = 40;
	/** A tap's fireballs and how far from the aim they land. */
	public static final int TAP_COUNT = 3;
	public static final double SPREAD = 1.5;
	public static final int INTERVAL = 4;
	public static final double SPEED = 1.8;
	public static final double GRAVITY = 0.03;
	/** Where the fireballs drop from: above their aim and beyond it. */
	public static final double HEIGHT = 12;
	public static final double BEYOND = 6;
	/** A fireball's own size (its entity sweep). */
	public static final double RADIUS = 0.4;
	/** A fireball flies at most this long (it keeps falling past an aim in the air until something stops it). */
	public static final int MAX_TICKS = 60;
	/** The blast round each impact. */
	public static final double BLAST = 2.5;
	/** Least share of the sky drop kept under a roof. */
	public static final double MIN_LIFT = 0.15;
	/** Most fireballs the ranks' size knob gives (before a fork's extra), and most a forked volley throws (Carpet's +2). */
	public static final int MAX_COUNT = 9;
	public static final int MAX_FORKED = 11;
	/** Carpet's spread, Guided Shells' reach past the spread, Heavy Shells' blast and crater. */
	public static final double CARPET_SPREAD = 1.6;
	public static final double GUIDED_REACH = 3;
	public static final double HEAVY_BLAST = 3.5;
	public static final double HEAVY_CRATER = 2.0;

	private BarrageGeometry() {}

	/** One fireball: where it is aimed, where it starts, its flight, and the tick (from the cast) it leaves on. */
	public record Ball(Vec3 aim, Vec3 origin, ProjectileSpec spec, int launchTick) {
		/** Its position {@code t} ticks after it left ({@link Projectiles#step}). */
		public Vec3 at(int t) {
			return Projectiles.step(spec, t);
		}
	}

	/** Fireballs for a cast at {@code charge}: {@code round(3 × ChargeMath.size(charge))}, 3 at a tap and 6 at full charge. */
	public static int count(float charge) {
		return Math.round(TAP_COUNT * ChargeMath.size(charge));
	}

	/**
	 * Fireballs at {@code charge} and {@code rank}: {@code round(3 × ChargeMath.size(charge) × the size knob)} capped at
	 * {@link #MAX_COUNT} first, then the forks' extra (Carpet's +2, Heavy Shells' −1), 1 to {@link #MAX_FORKED}. The cap comes
	 * before the fork, so Carpet's two extra fireballs still land at full charge.
	 */
	public static int count(float charge, SpellRank rank) {
		int base = Math.min(MAX_COUNT, Math.round(TAP_COUNT * ChargeMath.size(charge) * rank.mul(Knob.SIZE)));
		return Math.clamp(base + Math.round(rank.add(Knob.COUNT)), 1, MAX_FORKED);
	}

	/** The spread factor at {@code rank}: {@link #CARPET_SPREAD} for Carpet, else 1. */
	public static double spreadMul(SpellRank rank) {
		return rank.is(FireballBarrage.CARPET) ? CARPET_SPREAD : 1;
	}

	/** How far from the aim point Guided Shells look for creatures: the volley's spread plus {@link #GUIDED_REACH}. */
	public static double guidedReach(int count, SpellRank rank) {
		return spread(count) * spreadMul(rank) + GUIDED_REACH;
	}

	/** {@code aims} with the first ones moved onto {@code targets} (Guided Shells: one fireball per creature, the rest as seeded). */
	public static List<Vec3> guide(List<Vec3> aims, List<Vec3> targets) {
		List<Vec3> out = new ArrayList<>(aims);
		for (int i = 0; i < Math.min(out.size(), targets.size()); i++) out.set(i, targets.get(i));
		return out;
	}

	/** Each impact's blast radius at {@code rank}. */
	public static double blast(SpellRank rank) {
		return rank.is(FireballBarrage.HEAVY) ? HEAVY_BLAST : BLAST;
	}

	/** How far from the aim point a volley of {@code count} lands: {@link #SPREAD} for a tap's 3, in step with the count after. */
	public static double spread(int count) {
		return SPREAD * Math.max(1, count) / TAP_COUNT;
	}

	/** The aim of every fireball: {@code point} plus a seeded offset within {@link #spread}, the same for the same seed. */
	public static List<Vec3> aims(Vec3 point, long seed, int count) {
		return aims(point, seed, count, 1);
	}

	/** As {@link #aims(Vec3, long, int)} with the spread times {@code spreadMul}. */
	public static List<Vec3> aims(Vec3 point, long seed, int count, double spreadMul) {
		Random r = new Random(seed);
		double spread = spread(count) * spreadMul;
		List<Vec3> out = new ArrayList<>(count);
		for (int i = 0; i < count; i++) {
			double a = r.nextDouble() * Math.PI * 2;
			double d = spread * Math.sqrt(r.nextDouble());
			out.add(point.add(Math.cos(a) * d, 0, Math.sin(a) * d));
		}
		return out;
	}

	/** The sky drop of a fireball with all of it clear: {@link #HEIGHT} up and {@link #BEYOND} on along {@code dir}. */
	public static Vec3 drop(Vec3 dir) {
		Vec3 f = flat(dir);
		return new Vec3(f.x * BEYOND, HEIGHT, f.z * BEYOND);
	}

	/** The whole volley; {@code lifts[i]} (missing → 1) shortens fireball {@code i}'s drop, at least {@link #MIN_LIFT}. */
	public static List<Ball> volley(Vec3 point, Vec3 dir, long seed, int count, float[] lifts) {
		return volley(point, dir, seed, count, lifts, 1, INTERVAL);
	}

	/** As {@link #volley(Vec3, Vec3, long, int, float[])} with the spread times {@code spreadMul}, one fireball every {@code interval}. */
	public static List<Ball> volley(Vec3 point, Vec3 dir, long seed, int count, float[] lifts, double spreadMul, int interval) {
		return volley(aims(point, seed, count, spreadMul), dir, lifts, interval);
	}

	/** The volley onto the given {@code aims} (Guided Shells move some onto creatures, {@link #guide}). */
	public static List<Ball> volley(List<Vec3> aims, Vec3 dir, float[] lifts, int interval) {
		int count = aims.size();
		Vec3 drop = drop(dir);
		List<Ball> out = new ArrayList<>(count);
		for (int i = 0; i < count; i++) {

			double lift = i < lifts.length ? Mth.clamp(lifts[i], MIN_LIFT, 1) : 1;
			Vec3 aim = aims.get(i);
			Vec3 origin = aim.add(drop.scale(lift));
			out.add(new Ball(aim, origin, spec(origin, aim), i * interval));
		}
		return out;
	}

	/** A fireball thrown from {@code origin} through {@code aim}. */
	public static ProjectileSpec spec(Vec3 origin, Vec3 aim) {
		return new ProjectileSpec(origin, velocity(origin, aim), GRAVITY, RADIUS, MAX_TICKS, false);
	}

	/**
	 * The launch velocity, {@link #SPEED} long, on the low arc from {@code origin} through {@code aim} under {@link #GRAVITY}
	 * (straight at it when no arc reaches). {@link Projectiles#step} drops {@code g·t(t−1)/2}, which is the smooth fall
	 * {@code g·t²/2} of a throw half a tick's gravity faster upward, so that half is taken off: the stepped path then passes
	 * exactly through {@code aim} ({@link #flightTicks} after leaving).
	 */
	public static Vec3 velocity(Vec3 origin, Vec3 aim) {
		Vec3 d = aim.subtract(origin);
		double r = Math.sqrt(d.x * d.x + d.z * d.z);
		double v2 = SPEED * SPEED;
		double disc = v2 * v2 - GRAVITY * (GRAVITY * r * r + 2 * d.y * v2);
		if (r < 1.0E-6 || disc < 0) {
			Vec3 straight = d.lengthSqr() < 1.0E-12 ? new Vec3(0, -1, 0) : d.normalize();
			return straight.scale(SPEED);
		}
		double theta = Math.atan((v2 - Math.sqrt(disc)) / (GRAVITY * r));
		double h = SPEED * Math.cos(theta);
		return new Vec3(d.x / r * h, SPEED * Math.sin(theta) - GRAVITY / 2, d.z / r * h);
	}

	/** Ticks (fractional) a fireball from {@code origin} takes to pass through {@code aim}. */
	public static double flightTicks(Vec3 origin, Vec3 aim) {
		Vec3 v = velocity(origin, aim);
		Vec3 d = aim.subtract(origin);
		double h = Math.sqrt(v.x * v.x + v.z * v.z);
		double r = Math.sqrt(d.x * d.x + d.z * d.z);
		return h < 1.0E-9 ? d.length() / SPEED : r / h;
	}

	/** The stepped path at a fractional tick ({@link Projectiles#step} for whole ones). */
	public static Vec3 at(ProjectileSpec spec, double t) {
		Vec3 o = spec.origin();
		Vec3 v = spec.velocity();
		return new Vec3(o.x + v.x * t, o.y + v.y * t - spec.gravity() * t * (t - 1) / 2, o.z + v.z * t);
	}

	/** Linear falloff of the blast: 1 at the impact, 0 at {@link #BLAST}. */
	public static double falloff(double distance) {
		return falloff(distance, BLAST);
	}

	/** Linear falloff of a blast of {@code radius}. */
	public static double falloff(double distance, double radius) {
		return Mth.clamp(1 - distance / radius, 0, 1);
	}

	/** The flat unit direction of {@code v}, or +Z if it has none. */
	static Vec3 flat(Vec3 v) {
		double l = Math.sqrt(v.x * v.x + v.z * v.z);
		if (l < 1.0E-6 || !Double.isFinite(l)) return new Vec3(0, 0, 1);
		return new Vec3(v.x / l, 0, v.z / l);
	}
}
