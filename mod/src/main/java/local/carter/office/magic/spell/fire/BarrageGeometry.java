package local.carter.office.magic.spell.fire;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

import local.carter.office.magic.spell.ProjectileSpec;
import local.carter.office.magic.spell.Projectiles;

import net.minecraft.util.Mth;
import net.minecraft.world.phys.Vec3;

/**
 * Where a Fireball Barrage's fireballs go (pure: vector math only, no world access). The server and the client fx both build the
 * volley from what the fx event carries: the aim point ({@code target}), the caster's flat facing ({@code dir}), the cast's
 * {@code seed}, the fireball count and, per fireball, how much of its sky drop is clear ({@code lift}).
 *
 * <p>Each fireball is aimed at the aim point plus a seeded offset of at most {@link #SPREAD} (a disc in the horizontal plane).
 * It is thrown from the sky, {@link #HEIGHT} above its aim and {@link #BEYOND} beyond it, away from the caster, so the caster sees the circle it leaves from above the spot (that offset scaled by its
 * {@code lift}, shorter under a roof), at {@link #SPEED} blocks a tick with {@link #GRAVITY}, on the low arc that passes exactly
 * through its aim. One leaves every {@link #INTERVAL} ticks, the first at the cast.
 */
public final class BarrageGeometry {
	/** How far the caster can point the barrage. */
	public static final double RANGE = 40;
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

	private BarrageGeometry() {}

	/** One fireball: where it is aimed, where it starts, its flight, and the tick (from the cast) it leaves on. */
	public record Ball(Vec3 aim, Vec3 origin, ProjectileSpec spec, int launchTick) {
		/** Its position {@code t} ticks after it left ({@link Projectiles#step}). */
		public Vec3 at(int t) {
			return Projectiles.step(spec, t);
		}
	}

	/** Fireballs for a cast at {@code charge}: 3, up to 5 at full charge. */
	public static int count(float charge) {
		return 3 + Math.round(2 * Mth.clamp(charge, 0F, 1F));
	}

	/** The aim of every fireball: {@code point} plus a seeded offset within {@link #SPREAD}, the same for the same seed. */
	public static List<Vec3> aims(Vec3 point, long seed, int count) {
		Random r = new Random(seed);
		List<Vec3> out = new ArrayList<>(count);
		for (int i = 0; i < count; i++) {
			double a = r.nextDouble() * Math.PI * 2;
			double d = SPREAD * Math.sqrt(r.nextDouble());
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
		List<Vec3> aims = aims(point, seed, count);
		Vec3 drop = drop(dir);
		List<Ball> out = new ArrayList<>(count);
		for (int i = 0; i < count; i++) {
			double lift = i < lifts.length ? Mth.clamp(lifts[i], MIN_LIFT, 1) : 1;
			Vec3 aim = aims.get(i);
			Vec3 origin = aim.add(drop.scale(lift));
			out.add(new Ball(aim, origin, spec(origin, aim), i * INTERVAL));
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
		return Mth.clamp(1 - distance / BLAST, 0, 1);
	}

	/** The flat unit direction of {@code v}, or +Z if it has none. */
	static Vec3 flat(Vec3 v) {
		double l = Math.sqrt(v.x * v.x + v.z * v.z);
		if (l < 1.0E-6 || !Double.isFinite(l)) return new Vec3(0, 0, 1);
		return new Vec3(v.x / l, 0, v.z / l);
	}
}
