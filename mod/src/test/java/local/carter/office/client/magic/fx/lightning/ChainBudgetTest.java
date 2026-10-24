package local.carter.office.client.magic.fx.lightning;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import local.carter.office.client.magic.fx.FxBudget;

import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

class ChainBudgetTest {
	/** The caster's off hand (eye at y 1.62 looking +Z) and a zombie-sized hitbox standing at {@code feet}. */
	private static final Vec3 HAND = new Vec3(0.42, 1.2, 0.85);

	private static AABB zombie(Vec3 feet) {
		return new AABB(feet.x - 0.3, feet.y, feet.z - 0.3, feet.x + 0.3, feet.y + 1.95, feet.z + 0.3);
	}

	/**
	 * The longest chain there is: the first target at the edge of the 20-block reach, then 6 hops of the full 6 blocks, zig-zagging
	 * (feet positions).
	 */
	private static Vec3[] longestChain() {
		Vec3[] feet = new Vec3[7];
		feet[0] = new Vec3(0, 0, 19.9);
		for (int i = 1; i < feet.length; i++) {
			double side = (i & 1) == 0 ? -1 : 1;
			// 6 apart: 3.6 sideways, 4.8 along.
			feet[i] = feet[i - 1].add(3.6 * side, 0, 4.8);
		}
		return feet;
	}

	private static int chainCost(Vec3[] feet, float charge, long seed, int[] perHop) {
		Vec3 first = zombie(feet[0]).getCenter();
		int opening = LightningLook.openingCost(HAND, first, charge, seed);
		int total = opening;
		for (int hop = 0; hop < feet.length; hop++) {
			AABB body = zombie(feet[hop]);
			Vec3 from = hop == 0 ? first : zombie(feet[hop - 1]).getCenter();
			int cost = LightningLook.hopCost(from, body.getCenter(), body, hop, charge, seed);
			perHop[hop] = cost;
			total += cost;
		}
		return total;
	}

	@Test
	void sevenTargetFullChargeChainFitsTheBudget() {
		Vec3[] feet = longestChain();
		for (int i = 1; i < feet.length; i++) assertEquals(6, zombie(feet[i - 1]).getCenter().distanceTo(zombie(feet[i]).getCenter()), 1e-9);
		int worst = 0;
		for (long seed = 1; seed <= 200; seed++) {
			int[] perHop = new int[feet.length];
			int total = chainCost(feet, 1F, seed * 7919L, perHop);
			worst = Math.max(worst, total);
			assertTrue(total <= FxBudget.PARTICLES, "seed " + seed + ": the chain costs " + total);
			// Nothing is cut: every hop fits the share it is given.
			int share = LightningLook.allowance(LightningLook.openingCost(HAND, zombie(feet[0]).getCenter(), 1F, seed * 7919L), feet.length);
			for (int hop = 0; hop < feet.length; hop++) {
				assertTrue(perHop[hop] <= share, "seed " + seed + " hop " + hop + " costs " + perHop[hop] + " > share " + share);
			}
		}
		System.out.println("[ChainBudgetTest] worst 7-target full-charge chain: " + worst + " of " + FxBudget.PARTICLES);
	}

	@Test
	void tapChainFitsTheBudget() {
		Vec3[] feet = java.util.Arrays.copyOf(longestChain(), 5);
		for (long seed = 1; seed <= 100; seed++) {
			int total = chainCost(feet, 0F, seed * 31L, new int[feet.length]);
			assertTrue(total <= FxBudget.PARTICLES, "seed " + seed + ": " + total);
		}
	}

	@Test
	void sharesSplitWhatIsLeftEvenly() {
		assertEquals(50, LightningLook.allowance(50, 7));
		assertEquals(350, LightningLook.allowance(50, 1));
		assertEquals(0, LightningLook.allowance(500, 3));
		for (int n = 1; n <= 7; n++) assertTrue(60 + n * LightningLook.allowance(60, n) <= FxBudget.PARTICLES);
	}

	@Test
	void allowanceGrantsAndRefuses() {
		Allowance a = new Allowance(10);
		assertEquals(4, a.take(4));
		assertTrue(a.pay(5));
		assertTrue(!a.pay(2), "1 left can't pay 2");
		assertEquals(1, a.take(9));
		assertEquals(0, a.take(1));
	}

	@Test
	void strikesRepeatForASeedAndKeepTheirOffsetsCapped() {
		Vec3 from = new Vec3(0, 1, 0);
		Vec3 to = new Vec3(3, 2, 40);
		ArcPath.Strike a = ArcPath.strike(from, to, 42, 1, 0.18, 3);
		ArcPath.Strike b = ArcPath.strike(from, to, 42, 1, 0.18, 3);
		assertArrayEquals(a.path(), b.path());
		assertEquals(from, a.path()[0]);
		assertEquals(to, a.path()[ArcPath.PATH - 1]);
		// Four levels of midpoints, each pushed at most 0.6 on each of two axes: never further than that from the straight line.
		Vec3 d = to.subtract(from).normalize();
		double bound = 4 * ArcPath.MAX_OFFSET * Math.sqrt(2);
		for (long seed = 0; seed < 50; seed++) {
			for (Vec3 p : ArcPath.strike(from, to, seed, 0, 0.18, 0).path()) {
				Vec3 rel = p.subtract(from);
				double off = rel.subtract(d.scale(rel.dot(d))).length();
				assertTrue(off <= bound + 1e-9, "point " + off + " off the line");
			}
		}
	}

	@Test
	void quadsCountWhatIsLaid() {
		// A straight two-point-per-segment arc: 16 segments of 1 block, width 0.3, no forks.
		Vec3[] path = new Vec3[ArcPath.PATH];
		for (int i = 0; i < path.length; i++) path[i] = new Vec3(0, 0, i);
		ArcPath.Strike s = new ArcPath.Strike(path, new Vec3[0][]);
		int halo = 16 * (int) Math.ceil(1 / ArcPath.haloStep(0.3F));
		int line = 16 * (int) Math.ceil(1 / ArcPath.lineStep(0.3F));
		int core = 16 * (int) Math.ceil(1 / ArcPath.lineStep(0.3F * ArcPath.CORE));
		assertEquals(halo + line + core, ArcPath.quads(s, 0.3F, true));
		assertEquals(halo + line, ArcPath.quads(s, 0.3F, false));
		// Hair-thin lines are never laid closer than MIN_STEP.
		assertEquals(16 * (int) Math.ceil(1 / ArcPath.MIN_STEP), ArcPath.pieces(path, ArcPath.lineStep(0.001F)));
		assertEquals(1, ArcPath.cost(50));
		assertEquals(2, ArcPath.cost(51));
	}
}
