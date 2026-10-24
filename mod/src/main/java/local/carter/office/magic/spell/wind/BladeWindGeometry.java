package local.carter.office.magic.spell.wind;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

import local.carter.office.magic.core.ChargeMath;
import local.carter.office.magic.spell.ProjectileSpec;
import local.carter.office.magic.spell.Projectiles;

import net.minecraft.core.BlockPos;
import net.minecraft.util.Mth;
import net.minecraft.world.phys.Vec3;

/**
 * Blade Wind's shared math (pure: vectors only, no world). The server effect ({@link BladeWind}) and the client fx both call it,
 * so the blades they move and draw are the same blades:
 * <ul>
 *   <li>the fan: {@link #blades} blades (3 at a tap, 5 from charge 0.25, 7 from 0.75) evenly over ±{@link #spread} (15° at a tap,
 *       30° at full charge: twice as wide), turned about the look's own "up";</li>
 *   <li>each blade's path: a {@link ProjectileSpec} (speed 2.0, radius 0.6, pierce, 14 ticks, no gravity) walked with
 *       {@link Projectiles#step};</li>
 *   <li>the swath a blade cuts: cells whose centre lies within 1.1 sideways of its centre line, from 0.7 above it down to the
 *       caster's feet (1.62 below, the eye height);</li>
 *   <li>the 6-block stretches of a blade, each with its own edit-batch origin (the stretch's middle).</li>
 * </ul>
 */
public final class BladeWindGeometry {
	/** Blocks per tick. */
	public static final double SPEED = 2.0;
	/** Entity sweep radius. */
	public static final double RADIUS = 0.6;
	public static final int MAX_TICKS = 14;
	/** Blades leave this far ahead of the eye (clear of the caster's own hitbox). */
	public static final double START = 0.5;
	/** Length of the stretch of path that shares one edit batch. */
	public static final double SEGMENT = 6;
	/** The cut swath, from the blade's centre line to a cell's centre: sideways, above, and below (down to the caster's feet). */
	public static final double CUT_HALF_WIDTH = 1.1;
	public static final double CUT_UP = 0.7;
	public static final double CUT_DOWN = 1.62;
	/** Most blocks one cast cuts, over all its blades. */
	public static final int MAX_CUTS = 64;
	/** Half the fan's spread at a tap, in degrees (twice that at full charge). */
	public static final double SPREAD = 15;

	private BladeWindGeometry() {}

	/** A blade's own axes: {@code forward} along its flight, {@code side} level and to its right, {@code up} completing them. */
	public record Frame(Vec3 forward, Vec3 side, Vec3 up) {}

	/** Blades in the fan: {@code 3 + 2 × round(2 × charge)}, so 3 at a tap, 5 from charge 0.25 and 7 from 0.75 (always one along the look). */
	public static int blades(float charge) {
		return 3 + 2 * Math.round(2 * Mth.clamp(charge, 0F, 1F));
	}

	/** Half the fan's spread, in degrees: {@code 15 × ChargeMath.size(charge)}, 15 at a tap and 30 at full charge. */
	public static double spread(float charge) {
		return SPREAD * ChargeMath.size(charge);
	}

	/** Each blade's turn from the look, in degrees, left to right, evenly over ±{@link #spread}. */
	public static double[] angles(float charge) {
		int n = blades(charge);
		double s = spread(charge);
		double[] out = new double[n];
		for (int i = 0; i < n; i++) out[i] = -s + 2 * s * i / (n - 1);
		return out;
	}

	/**
	 * Axes for flying along {@code dir}: {@code side} is level (looking straight up or down it is +X), {@code up} leans toward
	 * world up. All three are unit length and at right angles.
	 */
	public static Frame frame(Vec3 dir) {
		Vec3 f = unit(dir);
		Vec3 side = f.cross(new Vec3(0, 1, 0));
		side = side.lengthSqr() < 1.0E-8 ? new Vec3(1, 0, 0) : side.normalize();
		Vec3 up = side.cross(f).normalize();
		return new Frame(f, side, up);
	}

	/** The cast's blades, left to right: from {@code eye} looking along {@code look} at {@code charge}. */
	public static List<ProjectileSpec> specs(Vec3 eye, Vec3 look, float charge) {
		Frame fr = frame(look);
		List<ProjectileSpec> out = new ArrayList<>();
		for (double deg : angles(charge)) {
			double a = Math.toRadians(deg);
			Vec3 dir = fr.forward().scale(Math.cos(a)).add(fr.side().scale(Math.sin(a))).normalize();
			out.add(new ProjectileSpec(eye.add(dir.scale(START)), dir.scale(SPEED), 0, RADIUS, MAX_TICKS, true));
		}
		return out;
	}

	/** Where a blade is after {@code t} ticks (fractions allowed): {@link Projectiles#step} at the ticks on either side, blended. */
	public static Vec3 at(ProjectileSpec s, double t) {
		int a = Mth.floor(t);
		Vec3 p = Projectiles.step(s, a);
		double f = t - a;
		return f < 1.0E-9 ? p : p.lerp(Projectiles.step(s, a + 1), f);
	}

	/** A blade's flight direction (unit). */
	public static Vec3 direction(ProjectileSpec s) {
		return unit(s.velocity());
	}

	/** How far along the blade (from its start) {@code p} lies. */
	public static double along(ProjectileSpec s, Vec3 p) {
		return p.subtract(s.origin()).dot(direction(s));
	}

	/** The stretch a distance along the blade falls in (0 for the first 6 blocks, also for anything before the start). */
	public static int segment(double distance) {
		return Math.max(0, Mth.floor(distance / SEGMENT));
	}

	/** Edit-batch origin of stretch {@code k}: its middle, on the blade's line. */
	public static Vec3 segmentOrigin(ProjectileSpec s, int k) {
		return s.origin().add(direction(s).scale(k * SEGMENT + SEGMENT / 2));
	}

	/**
	 * The cells a blade sweeping {@code from} → {@code to} cuts, nearest {@code from} first: those whose centre projects onto
	 * the segment ({@code [from, to)}, so back-to-back segments never share a cell) and lies within the swath: at most
	 * {@link #CUT_HALF_WIDTH} along {@code frame.side}, from {@link #CUT_UP} above to {@link #CUT_DOWN} below along {@code frame.up}.
	 */
	public static List<BlockPos> cuts(Vec3 from, Vec3 to, Frame frame) {
		Vec3 seg = to.subtract(from);
		double len2 = seg.lengthSqr();
		List<BlockPos> out = new ArrayList<>();
		if (len2 < 1.0E-10) return out;
		double reach = Math.max(CUT_HALF_WIDTH, Math.max(CUT_UP, CUT_DOWN)) + 0.5;
		int x0 = Mth.floor(Math.min(from.x, to.x) - reach);
		int y0 = Mth.floor(Math.min(from.y, to.y) - reach);
		int z0 = Mth.floor(Math.min(from.z, to.z) - reach);
		int x1 = Mth.floor(Math.max(from.x, to.x) + reach);
		int y1 = Mth.floor(Math.max(from.y, to.y) + reach);
		int z1 = Mth.floor(Math.max(from.z, to.z) + reach);
		List<double[]> keyed = new ArrayList<>();
		for (int x = x0; x <= x1; x++) {
			for (int y = y0; y <= y1; y++) {
				for (int z = z0; z <= z1; z++) {
					Vec3 c = new Vec3(x + 0.5, y + 0.5, z + 0.5);
					Vec3 rel = c.subtract(from);
					double t = rel.dot(seg) / len2;
					if (t < 0 || t >= 1) continue;
					Vec3 off = rel.subtract(seg.scale(t));
					double s = off.dot(frame.side());
					double u = off.dot(frame.up());
					if (Math.abs(s) <= CUT_HALF_WIDTH && u <= CUT_UP && u >= -CUT_DOWN) keyed.add(new double[] {t, x, y, z});
				}
			}
		}
		keyed.sort(Comparator.comparingDouble(k -> k[0]));
		for (double[] k : keyed) out.add(new BlockPos((int) k[1], (int) k[2], (int) k[3]));
		return out;
	}

	private static Vec3 unit(Vec3 v) {
		double l = v.length();
		return l < 1.0E-9 || !Double.isFinite(l) ? new Vec3(0, 0, 1) : v.scale(1 / l);
	}
}
