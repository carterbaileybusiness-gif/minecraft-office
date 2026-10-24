package local.carter.office.magic.spell.wind;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Random;

import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.Vec3;

/**
 * Wind Sphere's dash and drill (pure: vectors and block positions only, no world access). The server effect and the client fx
 * both use it.
 *
 * <p><b>Dash.</b> After {@link #WINDUP} ticks forming in the hand, the sphere flies with the caster along the cast's look at
 * {@link #SPEED} blocks a tick for at most {@link #DASH_TICKS} ticks, {@link #LEAD} ahead of the eye ({@link #sphere}). The body
 * travels with it: its centre line runs {@link #BODY_DROP} under the sphere's ({@link #body}), so a knee-high wall the sphere
 * would pass over still stops the dash.
 *
 * <p><b>Drill.</b> A cone laid along the look from where the body line meets the hit ({@link #blocks}): its radius shrinks
 * linearly from {@link #BASE_RADIUS} at the impact to {@link #TIP_RADIUS} at the far end, {@link #length} blocks long. It is
 * {@code Shapes.cone}'s rule (a block is in a layer when its centre's distance from the axis is within that layer's radius),
 * measured along the look instead of +Y: rotating {@code Shapes.cone}'s cell list would leave holes and doubles, so each block is
 * tested against the turned axis. The floor under the caster's path is kept: no block whose centre is under the feet line
 * ({@link #FEET} under the axis, following its slope) is drilled, so a level drill leaves a tunnel to walk through, not a trench,
 * while a drill aimed down still digs a pit. No block is more than {@link #MAX_LENGTH} from the block the axis starts in (the
 * ULTIMATE batch's radius). Up to {@link #LAUNCH} of the drilled blocks are thrown out spinning ({@link #debris}).
 */
public final class DrillGeometry {
	public static final double SPEED = 1.8;
	public static final int DASH_TICKS = 10;
	/** Ticks the sphere spins up in the hand before the dash (the caster is held still). */
	public static final int WINDUP = 6;
	/** How far ahead of the eye the sphere flies. */
	public static final double LEAD = 1.0;
	/** The body's centre line runs this far under the eye line. */
	public static final double BODY_DROP = 1.0;
	/** The feet are this far under the body's centre line (a standing player's eye is 1.62 up). */
	public static final double FEET = 0.62;
	/** An entity whose box comes within this of the sphere sets it off. */
	public static final double TOUCH = 1.5;
	public static final double BLAST = 4;
	public static final float DAMAGE = 22;
	public static final double KNOCK = 3.5;
	public static final double BASE_RADIUS = 3;
	public static final double TIP_RADIUS = 1;
	public static final int BASE_LENGTH = 8;
	public static final int MAX_LENGTH = 12;
	/** Drilled blocks thrown out as flying debris, at most. */
	public static final int LAUNCH = 32;

	private DrillGeometry() {}

	/** Drill length at grimoire {@code level}: {@code min(12, 8 + (level − 7))}, at least 1. */
	public static int length(int level) {
		return Math.max(1, Math.min(MAX_LENGTH, BASE_LENGTH + (level - 7)));
	}

	/** The sphere's centre {@code t} dash ticks in (fractions allowed): {@code eye + look × (LEAD + SPEED × t)}. */
	public static Vec3 sphere(Vec3 eye, Vec3 look, double t) {
		return eye.add(unit(look).scale(LEAD + SPEED * Math.max(0, t)));
	}

	/**
	 * How far {@code at} is from the stretch of path {@code from} → {@code to} (its nearest point on it). A caster anywhere on the
	 * path so far is merely behind the plan (lag, bunched movement packets); far off it, they were teleported.
	 */
	public static double offPath(Vec3 at, Vec3 from, Vec3 to) {
		Vec3 d = to.subtract(from);
		double len2 = d.lengthSqr();
		double f = len2 < 1.0E-12 ? 0 : Math.max(0, Math.min(1, at.subtract(from).dot(d) / len2));
		return at.distanceTo(from.add(d.scale(f)));
	}

	/** The point on the body's centre line level with {@code p} on the sphere's (eye) line: {@link #BODY_DROP} under it. */
	public static Vec3 body(Vec3 p) {
		return p.subtract(0, BODY_DROP, 0);
	}

	/** The drill's radius {@code along} blocks down its axis, for a drill {@code length} long. */
	public static double radiusAt(double along, int length) {
		if (length <= 1) return BASE_RADIUS;
		double f = Math.max(0, Math.min(1, along / (length - 1)));
		return BASE_RADIUS + (TIP_RADIUS - BASE_RADIUS) * f;
	}

	/**
	 * The drilled blocks for an axis from {@code start} (on the body's centre line) along {@code look}, {@code length} long: every
	 * block whose centre is ahead of the start ({@code 0 ≤ along < length}), within {@link #radiusAt} of the axis there, not under
	 * the feet line, and within {@link #MAX_LENGTH} of {@code BlockPos.containing(start)}. Ordered from the mouth inward (then nearest
	 * the axis first).
	 */
	public static List<BlockPos> blocks(Vec3 start, Vec3 look, int length) {
		Vec3 u = unit(look);
		BlockPos origin = BlockPos.containing(start);
		int n = Math.min(MAX_LENGTH, length) + (int) Math.ceil(BASE_RADIUS) + 1;
		record Scored(BlockPos p, double along, double radial) {}
		List<Scored> out = new ArrayList<>();
		for (int x = -n; x <= n; x++) {
			for (int y = -n; y <= n; y++) {
				for (int z = -n; z <= n; z++) {
					BlockPos p = origin.offset(x, y, z);
					if (p.distSqr(origin) > MAX_LENGTH * MAX_LENGTH) continue;
					double dx = p.getX() + 0.5 - start.x;
					double dy = p.getY() + 0.5 - start.y;
					double dz = p.getZ() + 0.5 - start.z;
					double a = dx * u.x + dy * u.y + dz * u.z;
					if (a < 0 || a >= length) continue;
					if (dy < u.y * a - FEET) continue;
					double rx = dx - u.x * a;
					double ry = dy - u.y * a;
					double rz = dz - u.z * a;
					double radial = Math.sqrt(rx * rx + ry * ry + rz * rz);
					if (radial <= radiusAt(a, length) + 1.0E-9) out.add(new Scored(p, a, radial));
				}
			}
		}
		out.sort(Comparator.comparingDouble(Scored::along).thenComparingDouble(Scored::radial));
		List<BlockPos> blocks = new ArrayList<>(out.size());
		for (Scored s : out) blocks.add(s.p());
		return blocks;
	}

	/** Distance along the axis from {@code start} to the centre of {@code p}. */
	public static double along(BlockPos p, Vec3 start, Vec3 look) {
		return Vec3.atCenterOf(p).subtract(start).dot(unit(look));
	}

	/** Distance of the centre of {@code p} from the axis through {@code start} along {@code look}. */
	public static double radial(BlockPos p, Vec3 start, Vec3 look) {
		Vec3 u = unit(look);
		Vec3 d = Vec3.atCenterOf(p).subtract(start);
		return d.subtract(u.scale(d.dot(u))).length();
	}

	/**
	 * Which drilled blocks are thrown (indices into {@code blocks}): the mouth's blocks off the axis, spread round it, at most
	 * {@code max}. Picked evenly from the blocks less than 3 down the axis that lie at least 1 from it.
	 */
	public static List<Integer> thrown(List<BlockPos> blocks, Vec3 start, Vec3 look, int max) {
		List<Integer> pool = new ArrayList<>();
		for (int i = 0; i < blocks.size(); i++) {
			BlockPos p = blocks.get(i);
			if (along(p, start, look) < 3 && radial(p, start, look) >= 1) pool.add(i);
		}
		if (pool.size() <= max) return pool;
		List<Integer> out = new ArrayList<>(max);
		for (int k = 0; k < max; k++) out.add(pool.get((int) ((long) k * pool.size() / max)));
		return out;
	}

	/**
	 * A thrown block's velocity: spun round the drill's axis ({@code look × outward}, the way the sphere turns), flung out from
	 * the axis, up, and on along the look, away from the caster: never back toward them. Seeded jitter from {@code r}.
	 */
	public static Vec3 debris(BlockPos p, Vec3 start, Vec3 look, Random r) {
		Vec3 u = unit(look);
		Vec3 d = Vec3.atCenterOf(p).subtract(start);
		Vec3 out = d.subtract(u.scale(d.dot(u)));
		if (out.lengthSqr() < 1.0E-6) out = BladeWindGeometry.frame(u).side();
		out = out.normalize();
		Vec3 spin = u.cross(out).normalize();
		double k = 0.85 + 0.3 * r.nextDouble();
		Vec3 v = spin.scale(0.45 * k).add(out.scale(0.35 * k)).add(u.scale(0.15)).add(0, 0.35 + 0.2 * r.nextDouble(), 0);
		// Whatever the look, nothing comes back at the caster.
		double back = v.dot(u);
		return back < 0 ? v.subtract(u.scale(back)) : v;
	}

	/** {@code v} at unit length ({@code +Z} if it has none). */
	static Vec3 unit(Vec3 v) {
		double len = v.length();
		return len < 1.0E-9 ? new Vec3(0, 0, 1) : v.scale(1 / len);
	}
}
