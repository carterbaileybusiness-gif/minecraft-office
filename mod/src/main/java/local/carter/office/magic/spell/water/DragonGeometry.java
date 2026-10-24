package local.carter.office.magic.spell.water;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.function.Predicate;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.util.Mth;
import net.minecraft.util.RandomSource;
import net.minecraft.world.phys.Vec3;

/**
 * Water Dragon's pure geometry (Vec3 and BlockPos math, no world access), shared by the server spell and the client fx.
 *
 * <p><b>Flight.</b> The dragon leaves the caster's circle ({@link #start}) and reaches the target at tick {@link #FLIGHT}. Its
 * path is a Catmull-Rom spline through five keys built from the cast's seed: out of the circle, up and to one side, over a
 * high crest swinging to the other side, rearing above the target, then straight down onto it. The head moves along the
 * spline by arc length, slow as it pours out of the circle and fastest in the dive ({@link #ease}). The same seed gives the
 * same path on every side.
 *
 * <p><b>Flood.</b> {@link #flood} picks the air cells the crash fills with water: within {@link #floodRadius} of the impact
 * cell, at or below its level, reachable from it through air (so a cave sealed off by rock stays dry), each standing on
 * something solid, on water, or on another flooded cell (filled bottom up, so no water floats), at most {@link #MAX_SOURCES}.
 * {@link #mud} picks the grass and dirt tops within {@link #mudRadius} that turn to mud, {@link #MUD_CHANCE} of them, by a
 * seeded hash of their position.
 */
public final class DragonGeometry {
	/** Ticks from the cast to the impact. */
	public static final int FLIGHT = 30;
	/** How far the target ray reaches. */
	public static final double RANGE = 48;
	/** The circle the dragon pours out of: this far along the look from the eye, a little below it. */
	public static final double START_AHEAD = 1.6;
	public static final double START_DROP = 0.25;
	/** Impact: {@link #DAMAGE} × P to everything within {@link #DAMAGE_RADIUS}, foes knocked {@link #KNOCK} + {@link #KNOCK_UP}. */
	public static final double DAMAGE_RADIUS = 6;
	public static final float DAMAGE = 18;
	public static final double KNOCK = 2.0;
	public static final double KNOCK_UP = 0.5;
	/** Most water sources one crash places. */
	public static final int MAX_SOURCES = 600;
	/** Share of the grass and dirt tops that turn to mud. */
	public static final double MUD_CHANCE = 0.4;
	/** Mud reaches this much past the flood. */
	public static final int MUD_EXTRA = 3;
	/** No edit is farther than this from the impact cell (the ultimate batch's radius). */
	public static final int MAX_REACH = 12;
	/** Arc-length samples along the spline. */
	private static final int SAMPLES = 240;

	private final Vec3 start;
	private final Vec3 target;
	/** Keys with a phantom at each end: P(-1), K0..K4, P(5). */
	private final Vec3[] keys;
	/** Cumulative arc length at each sample. */
	private final double[] length = new double[SAMPLES + 1];

	public DragonGeometry(Vec3 start, Vec3 target, long seed) {
		this.start = start;
		this.target = target;
		RandomSource r = RandomSource.create(seed);
		Vec3 flat = new Vec3(target.x - start.x, 0, target.z - start.z);
		double l = flat.length();
		Vec3 f;
		if (l < 1) {
			double a = r.nextDouble() * Mth.TWO_PI; // straight down: the dragon still loops out to one side first
			f = new Vec3(Math.cos(a), 0, Math.sin(a));
		} else {
			f = flat.scale(1 / l);
		}
		double span = Math.max(l, 6);
		Vec3 side = new Vec3(-f.z, 0, f.x);
		double s = r.nextBoolean() ? 1 : -1;
		double top = Math.max(start.y, target.y);
		Vec3 k1 = start.add(f.scale(0.14 * span)).add(side.scale(s * (2.0 + 0.08 * span) * jit(r)));
		k1 = new Vec3(k1.x, start.y + (2.5 + 0.12 * span) * jit(r), k1.z);
		Vec3 k2 = start.add(f.scale(0.5 * span)).add(side.scale(-s * (2.5 + 0.1 * span) * jit(r)));
		k2 = new Vec3(k2.x, top + (5 + 0.22 * span) * jit(r), k2.z);
		Vec3 k3 = target.subtract(f.scale(0.12 * span)).add(side.scale(s * 1.2 * jit(r)));
		k3 = new Vec3(k3.x, target.y + 6 + 0.1 * span, k3.z);
		this.keys = new Vec3[] {start.scale(2).subtract(k1), start, k1, k2, k3, target, target.scale(2).subtract(k3)};
		double total = 0;
		Vec3 prev = spline(0);
		for (int i = 1; i <= SAMPLES; i++) {
			Vec3 p = spline(4.0 * i / SAMPLES);
			total += p.distanceTo(prev);
			length[i] = total;
			prev = p;
		}
	}

	/** Where the dragon starts: the caster's circle, {@link #START_AHEAD} along the look from the eye. */
	public static Vec3 start(Vec3 eye, Vec3 look) {
		double n = look.length();
		Vec3 d = n < 1.0E-6 ? new Vec3(0, 0, 1) : look.scale(1 / n);
		return eye.add(d.scale(START_AHEAD)).add(0, -START_DROP, 0);
	}

	public Vec3 start() {
		return start;
	}

	public Vec3 target() {
		return target;
	}

	/** The head at {@code tick} (0..{@link #FLIGHT}; clamped): the start at 0, the target at {@link #FLIGHT}. */
	public Vec3 at(double tick) {
		if (!(tick > 0)) return start;
		if (tick >= FLIGHT) return target;
		return along(ease(tick / FLIGHT) * length[SAMPLES]);
	}

	/** How far along the path the head is at {@code tick}: 0 at the start, {@link #length()} at {@link #FLIGHT}, and on past it at the dive's speed (the body pours in after the head). */
	public double headDistance(double tick) {
		if (!(tick > 0)) return 0;
		double l = length[SAMPLES];
		if (tick <= FLIGHT) return ease(tick / FLIGHT) * l;
		return l + (tick - FLIGHT) * l / FLIGHT * 1.65; // ease'(1) = 1.65
	}

	/** The point {@code s} blocks along the path from the start (clamped to the start and the target). */
	public Vec3 along(double s) {
		if (!(s > 0)) return start;
		if (s >= length[SAMPLES]) return target;
		double want = s;
		int lo = 0;
		int hi = SAMPLES;
		while (hi - lo > 1) {
			int mid = (lo + hi) >>> 1;
			if (length[mid] <= want) lo = mid;
			else hi = mid;
		}
		double seg = length[hi] - length[lo];
		double f = seg <= 1.0E-9 ? 0 : (want - length[lo]) / seg;
		return spline(4.0 * (lo + f) / SAMPLES);
	}

	/** The direction the head moves at {@code tick} (unit; along the dive past the end). */
	public Vec3 tangent(double tick) {
		double t = Mth.clamp(tick, 0, FLIGHT);
		Vec3 a = at(Math.max(0, t - 0.25));
		Vec3 b = at(Math.min(FLIGHT, t + 0.25));
		Vec3 d = b.subtract(a);
		if (d.lengthSqr() < 1.0E-10) d = target.subtract(keys[4]);
		double n = d.length();
		return n < 1.0E-9 ? new Vec3(0, -1, 0) : d.scale(1 / n);
	}

	/** The path's length in blocks. */
	public double length() {
		return length[SAMPLES];
	}

	/** Share of the path's length covered at time share {@code x} (0..1): slow out of the circle, fastest in the dive. */
	public static double ease(double x) {
		double c = Mth.clamp(x, 0, 1);
		return 0.35 * c + 0.65 * c * c;
	}

	/** The spline at {@code u} in 0..4 (one unit per key-to-key segment). */
	private Vec3 spline(double u) {
		int i = Mth.clamp((int) Math.floor(u), 0, 3);
		double t = u - i;
		if (u >= 4) {
			i = 3;
			t = 1;
		}
		Vec3 p0 = keys[i];
		Vec3 p1 = keys[i + 1];
		Vec3 p2 = keys[i + 2];
		Vec3 p3 = keys[i + 3];
		if (t <= 0) return p1;
		if (t >= 1) return p2;
		double t2 = t * t;
		double t3 = t2 * t;
		return new Vec3(cr(p0.x, p1.x, p2.x, p3.x, t, t2, t3), cr(p0.y, p1.y, p2.y, p3.y, t, t2, t3), cr(p0.z, p1.z, p2.z, p3.z, t, t2, t3));
	}

	private static double cr(double a, double b, double c, double d, double t, double t2, double t3) {
		return 0.5 * (2 * b + (-a + c) * t + (2 * a - 5 * b + 4 * c - d) * t2 + (-a + 3 * b - 3 * c + d) * t3);
	}

	private static double jit(RandomSource r) {
		return 0.85 + 0.3 * r.nextDouble();
	}

	// ---- the flood ---------------------------------------------------------------------------------------------------------

	/** Radius of the flood at grimoire {@code level}: {@code min(12, 6 + (level − 7))}, at least 1. */
	public static int floodRadius(int level) {
		return Math.max(1, Math.min(MAX_REACH, 6 + (level - 7)));
	}

	/** Radius of the mud: the flood's plus {@link #MUD_EXTRA}, never past {@link #MAX_REACH}. */
	public static int mudRadius(int level) {
		return Math.min(MAX_REACH, floodRadius(level) + MUD_EXTRA);
	}

	/**
	 * The cells the crash at {@code origin} fills with water sources, bottom layer first, at most {@code max}: air cells
	 * ({@code air}) within {@code radius} of {@code origin} (block distance), no higher than it, reachable from it through such
	 * cells, each above a cell that {@code holds} water (solid or water) or above one already picked. {@code origin} itself must be air.
	 */
	public static List<BlockPos> flood(BlockPos origin, int radius, Predicate<BlockPos> air, Predicate<BlockPos> holds, int max) {
		List<BlockPos> out = new ArrayList<>();
		if (max <= 0 || !air.test(origin)) return out;
		double r2 = (double) radius * radius;
		// Reachable air: from the origin, sideways and down (water never climbs above the impact).
		Set<BlockPos> reach = new HashSet<>();
		ArrayDeque<BlockPos> open = new ArrayDeque<>();
		reach.add(origin);
		open.add(origin);
		while (!open.isEmpty()) {
			BlockPos c = open.poll();
			for (Direction d : Direction.values()) {
				BlockPos n = c.relative(d);
				if (n.getY() > origin.getY() || n.distSqr(origin) > r2 || reach.contains(n) || !air.test(n)) continue;
				reach.add(n);
				open.add(n);
			}
		}
		List<BlockPos> cells = new ArrayList<>(reach);
		cells.sort(Comparator.<BlockPos>comparingInt(BlockPos::getY)
				.thenComparingDouble(p -> horizontal(p, origin))
				.thenComparingInt(BlockPos::getX)
				.thenComparingInt(BlockPos::getZ));
		Set<BlockPos> picked = new HashSet<>();
		for (BlockPos c : cells) {
			if (out.size() >= max) break;
			BlockPos below = c.below();
			if (picked.contains(below) || (!reach.contains(below) && holds.test(below))) {
				out.add(c);
				picked.add(c);
			}
		}
		return out;
	}

	/**
	 * The cells within {@code radius} of {@code origin} (block distance, never past {@link #MAX_REACH}) for which {@code top}
	 * holds (grass or dirt with open air or water above) and the seeded pick says mud ({@link #mudPick}), nearest first.
	 */
	public static List<BlockPos> mud(BlockPos origin, int radius, Predicate<BlockPos> top, long seed) {
		int r = Math.min(radius, MAX_REACH);
		double r2 = (double) r * r;
		List<BlockPos> out = new ArrayList<>();
		for (BlockPos p : BlockPos.betweenClosed(origin.offset(-r, -r, -r), origin.offset(r, r, r))) {
			if (p.distSqr(origin) > r2 || !mudPick(seed, p) || !top.test(p)) continue;
			out.add(p.immutable());
		}
		out.sort(Comparator.<BlockPos>comparingDouble(p -> p.distSqr(origin)).thenComparingInt(BlockPos::getY)
				.thenComparingInt(BlockPos::getX).thenComparingInt(BlockPos::getZ));
		return out;
	}

	/** Whether the cast with {@code seed} turns the dirt at {@code p} to mud: {@link #MUD_CHANCE} of positions, the same on every call. */
	public static boolean mudPick(long seed, BlockPos p) {
		long h = seed ^ p.asLong() * 0x9E3779B97F4A7C15L;
		h ^= h >>> 33;
		h *= 0xFF51AFD7ED558CCDL;
		h ^= h >>> 33;
		h *= 0xC4CEB9FE1A85EC53L;
		h ^= h >>> 33;
		return (h >>> 11) * 0x1.0p-53 < MUD_CHANCE;
	}

	private static double horizontal(BlockPos p, BlockPos o) {
		double dx = p.getX() - o.getX();
		double dz = p.getZ() - o.getZ();
		return dx * dx + dz * dz;
	}
}
