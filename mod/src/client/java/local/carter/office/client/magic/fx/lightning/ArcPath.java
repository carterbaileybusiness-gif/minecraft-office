package local.carter.office.client.magic.fx.lightning;

import net.minecraft.util.RandomSource;
import net.minecraft.world.phys.Vec3;

/**
 * Pure geometry of one {@link ArcParticle} (no rendering, so unit tests can use it): its jagged paths, struck by midpoint
 * displacement from a seed, and how many quads drawing them takes. {@link #quads} counts the pieces exactly as
 * {@code ArcParticle} lays them ({@link #pieces} per segment), so the fx budget is charged for what is really drawn.
 */
final class ArcPath {
	/** Points on the main path (2^k + 1) and on each fork. */
	static final int PATH = 17;
	static final int FORK = 5;
	/** Most a midpoint is pushed sideways (blocks), whatever the span. */
	static final double MAX_OFFSET = 0.6;
	/** Line pieces are this share of their width long; halo blobs ({@link #HALO} × the width) are spaced this share of their size. */
	static final float BAND = 0.75F;
	static final float HALO = 3.5F;
	static final float HALO_STEP = 0.55F;
	/** Core, fork and fork-core widths as shares of the arc's width. */
	static final float CORE = 0.45F;
	static final float FORK_WIDTH = 0.5F;
	static final float FORK_CORE = 0.22F;
	/** Pieces are never closer than this (blocks), however thin the line: hair-thin arcs stay cheap. */
	static final float MIN_STEP = 0.05F;
	/** Quads one fx budget particle pays for (as {@code WindRibbon} does). */
	static final int QUADS_PER_PARTICLE = 50;

	/** One strike: the main path and its forks. */
	record Strike(Vec3[] path, Vec3[][] forks) {}

	private ArcPath() {}

	/** Strike {@code n} of an arc (it is re-struck every two ticks): the same on every client for one seed. */
	static Strike strike(Vec3 from, Vec3 to, long seed, int n, double roughness, int forks) {
		RandomSource r = RandomSource.create(seed * 31 + n);
		Vec3[] b = basis(to.subtract(from));
		Vec3[] path = new Vec3[PATH];
		displace(path, from, to, b, r, roughness);
		Vec3 d = to.subtract(from);
		double length = d.length();
		Vec3[][] out = new Vec3[Math.clamp(forks, 0, 3)][FORK];
		for (Vec3[] fork : out) {
			Vec3 start = path[2 + r.nextInt(PATH - 4)];
			double sx = r.nextDouble() * 2 - 1;
			double sy = r.nextDouble() * 2 - 1;
			Vec3 side = b[0].scale(sx).add(b[1].scale(sy));
			Vec3 end = start.add(d.scale(0.1 + 0.1 * r.nextDouble())).add(side.scale(Math.min(1.2, length * 0.12)));
			displace(fork, start, end, basis(end.subtract(start)), r, 0.25);
		}
		return new Strike(path, out);
	}

	/** Fills {@code pts} (length 2^k + 1) from {@code a} to {@code b}, each middle pushed off by up to {@code rough} of its span (at most {@link #MAX_OFFSET}). */
	private static void displace(Vec3[] pts, Vec3 a, Vec3 b, Vec3[] basis, RandomSource r, double rough) {
		int last = pts.length - 1;
		pts[0] = a;
		pts[last] = b;
		for (int span = last; span > 1; span /= 2) {
			for (int i = 0; i + span <= last; i += span) {
				Vec3 p = pts[i];
				Vec3 q = pts[i + span];
				double off = Math.min(p.distanceTo(q) * rough, MAX_OFFSET);
				double ox = (r.nextDouble() * 2 - 1) * off;
				double oy = (r.nextDouble() * 2 - 1) * off;
				pts[i + span / 2] = p.add(q).scale(0.5).add(basis[0].scale(ox)).add(basis[1].scale(oy));
			}
		}
	}

	/** Two unit vectors square to {@code d} and each other (right, up); upright arcs use world X for "right". */
	static Vec3[] basis(Vec3 d) {
		double l = d.length();
		Vec3 n = l < 1.0E-9 ? new Vec3(0, 0, 1) : d.scale(1 / l);
		Vec3 up = Math.abs(n.y) > 0.95 ? new Vec3(1, 0, 0) : new Vec3(0, 1, 0);
		Vec3 right = n.cross(up).normalize();
		return new Vec3[] {right, right.cross(n).normalize()};
	}

	/** A segment's length as {@code ArcParticle} measures it. */
	static float segment(Vec3 p0, Vec3 p1) {
		float dx = (float) (p1.x - p0.x);
		float dy = (float) (p1.y - p0.y);
		float dz = (float) (p1.z - p0.z);
		return (float) Math.sqrt(dx * dx + dy * dy + dz * dz);
	}

	/** Pieces laid on a segment of {@code length}, one every {@code step}: none on a degenerate one, else at least one. */
	static int pieces(float length, float step) {
		if (length < 1.0E-4F) return 0;
		return Math.max(1, (int) Math.ceil(length / step));
	}

	/** Pieces along all of {@code pts}, one every {@code step}. */
	static int pieces(Vec3[] pts, float step) {
		int n = 0;
		for (int i = 0; i + 1 < pts.length; i++) n += pieces(segment(pts[i], pts[i + 1]), step);
		return n;
	}

	/** Spacing of halo blobs for an arc of {@code width}. */
	static float haloStep(float width) {
		return Math.max(MIN_STEP, width * HALO * HALO_STEP);
	}

	/** Spacing of line pieces {@code w} wide. */
	static float lineStep(float w) {
		return Math.max(MIN_STEP, w / BAND);
	}

	/**
	 * Quads one frame of {@code s} takes, drawn {@code width} thick: halo and line on the path and line on each fork, plus (with
	 * {@code core}) a white core on the path and on each fork.
	 */
	static int quads(Strike s, float width, boolean core) {
		int n = pieces(s.path(), haloStep(width)) + pieces(s.path(), lineStep(width));
		if (core) n += pieces(s.path(), lineStep(width * CORE));
		for (Vec3[] fork : s.forks()) {
			n += pieces(fork, lineStep(width * FORK_WIDTH));
			if (core) n += pieces(fork, lineStep(width * FORK_CORE));
		}
		return n;
	}

	/** The strikes an arc living {@code life} ticks shows (one per two ticks). */
	static Strike[] strikes(Vec3 from, Vec3 to, long seed, double roughness, int forks, int life) {
		Strike[] out = new Strike[(Math.max(2, life) - 1) / 2 + 1];
		for (int n = 0; n < out.length; n++) out[n] = strike(from, to, seed, n, roughness, forks);
		return out;
	}

	/** The most quads any of {@code strikes} takes in a frame. */
	static int quads(Strike[] strikes, float width, boolean core) {
		int max = 0;
		for (Strike s : strikes) max = Math.max(max, quads(s, width, core));
		return max;
	}

	/** Budget particles that pay for {@code quads} quads a frame. */
	static int cost(int quads) {
		return (quads + QUADS_PER_PARTICLE - 1) / QUADS_PER_PARTICLE;
	}

	/** Budget cost of the arc {@code from} → {@code to} (its heaviest strike). */
	static int cost(Vec3 from, Vec3 to, long seed, double roughness, int forks, float width, int life, boolean core) {
		return cost(quads(strikes(from, to, seed, roughness, forks, life), width, core));
	}
}
