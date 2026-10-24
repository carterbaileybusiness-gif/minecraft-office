package local.carter.office.magic.spell.lightning;

import java.util.Arrays;
import java.util.List;
import java.util.function.IntPredicate;

import local.carter.office.magic.core.ChargeMath;

import net.minecraft.world.phys.Vec3;

/**
 * Pure hop selection for lightning that leaps between targets (Chain Lightning, and Spark's arcs), on points only: no world
 * access, so the server and a unit test run the same rules. A chain starts at one point and each hop goes to the nearest point not
 * yet hit within {@code range} of the last one hit (centre to centre), if the {@link Link} allows it (the server's line of sight).
 * Ties go to the lower index, so a chain is the same on every run. It stops when no point qualifies, and never revisits a point.
 */
public final class ChainHops {
	/** Chain Lightning: the most hops at charge 0 ({@code round(4 × ChargeMath.size(charge))} in all), each at most 6 from the last target. */
	public static final int BASE_HOPS = 4;
	public static final double HOP_RANGE = 6;
	/** Chain Lightning: damage {@code 6 × P × 0.8^hop}, hop 0 being the first target. */
	public static final float DAMAGE = 6;
	public static final float FALLOFF = 0.8F;

	/** Whether lightning may leap from point {@code from} to point {@code to} (indices into the point list). */
	@FunctionalInterface
	public interface Link {
		boolean allows(int from, int to);
	}

	private ChainHops() {}

	/** Chain Lightning's hop count after the first target: {@code round(4 × (1 + charge))} (4 at a tap, 8 at full charge). */
	public static int hops(float charge) {
		return Math.round(BASE_HOPS * ChargeMath.size(charge));
	}

	/** Chain Lightning's damage to the target at {@code hop} (0 = the first target): {@code 6 × power × 0.8^hop}. */
	public static float damage(int hop, float power) {
		return (float) (DAMAGE * power * Math.pow(FALLOFF, hop));
	}

	/**
	 * One hop: the index of the point nearest {@code from} (centre distance, at most {@code range}) that {@code ok} accepts; ties go
	 * to the lower index. -1 if none.
	 */
	public static int next(Vec3 from, List<Vec3> points, double range, IntPredicate ok) {
		double r2 = range * range;
		int best = -1;
		double bestD = Double.MAX_VALUE;
		for (int i = 0; i < points.size(); i++) {
			double d = from.distanceToSqr(points.get(i));
			// Strictly nearer wins, so equal distances keep the lower index.
			if (d <= r2 && d < bestD && ok.test(i)) {
				best = i;
				bestD = d;
			}
		}
		return best;
	}

	/** {@link #select(List, int, int, double, Link)} with every link allowed. */
	public static int[] select(List<Vec3> points, int start, int hops, double range) {
		return select(points, start, hops, range, (a, b) -> true);
	}

	/**
	 * The chain's points in hit order: {@code start}, then up to {@code hops} more. Each next point is the nearest one (to the last
	 * point hit, by centre distance, at most {@code range}) that was not hit yet and that {@code link} allows from the last point.
	 * Empty if {@code start} is not an index of {@code points}.
	 */
	public static int[] select(List<Vec3> points, int start, int hops, double range, Link link) {
		int n = points.size();
		if (start < 0 || start >= n) return new int[0];
		int[] order = new int[Math.min(n, 1 + Math.max(0, hops))];
		boolean[] hit = new boolean[n];
		order[0] = start;
		hit[start] = true;
		int count = 1;
		while (count < order.length) {
			int last = order[count - 1];
			int best = next(points.get(last), points, range, i -> !hit[i] && link.allows(last, i));
			if (best < 0) break;
			hit[best] = true;
			order[count++] = best;
		}
		return Arrays.copyOf(order, count);
	}
}
