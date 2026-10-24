package local.carter.office.magic.spell.lightning;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.HashSet;
import java.util.List;
import java.util.Random;
import java.util.Set;

import org.junit.jupiter.api.Test;

import local.carter.office.magic.core.Shapes.Cell;
import local.carter.office.magic.spell.lightning.ChidoriGeometry.Column;

import net.minecraft.world.phys.Vec3;

class ChidoriGeometryTest {
	private static final Vec3 START = new Vec3(16.5, 4, 4.5);

	@Test
	void pathPointsAreEvenlySpacedOverSixTicks() {
		Random r = new Random(5);
		for (int n = 0; n < 50; n++) {
			Vec3 end = START.add(r.nextGaussian() * 9, r.nextGaussian() * 2, r.nextGaussian() * 9);
			ChidoriGeometry g = new ChidoriGeometry(START, end, 4);
			assertEquals(6, ChidoriGeometry.TICKS);
			assertTrue(g.point(0).distanceTo(START) < 1.0E-9, "starts at the start");
			assertTrue(g.point(6).distanceTo(end) < 1.0E-9, "ends at the end");
			assertTrue(g.point(9).distanceTo(end) < 1.0E-9, "stays at the end");
			double step = START.distanceTo(end) / 6;
			for (int t = 1; t <= 6; t++) {
				Vec3 a = g.point(t - 1);
				Vec3 b = g.point(t);
				assertEquals(step, a.distanceTo(b), 1.0E-9, "tick " + t + " covers an even share");
				// On the straight line start → end.
				Vec3 d = end.subtract(START).normalize();
				Vec3 off = b.subtract(START);
				assertTrue(off.subtract(d.scale(off.dot(d))).length() < 1.0E-9, "on the line at tick " + t);
			}
		}
	}

	@Test
	void trenchCellsAreWithinOneOfThePathLine() {
		Random r = new Random(11);
		for (int n = 0; n < 50; n++) {
			Vec3 end = START.add(r.nextGaussian() * 9, 0, r.nextGaussian() * 9);
			ChidoriGeometry g = new ChidoriGeometry(START, end, 4);
			List<Column> trench = g.trench();
			assertFalse(trench.isEmpty());
			Set<Long> seen = new HashSet<>();
			for (Column c : trench) {
				assertTrue(segmentDistance(c.x() + 0.5, c.z() + 0.5, START, end) <= 1 + 1.0E-9, "trench cell " + c + " is within 1 of the path");
				assertTrue(seen.add(key(c)), "no column twice");
			}
			// Every column whose centre is within 1 is in the trench (no gaps), and the lips are beyond 1 and within 2.
			for (int x = -30; x <= 60; x++) {
				for (int z = -30; z <= 60; z++) {
					double d = segmentDistance(x + 0.5, z + 0.5, START, end);
					if (d <= 1 - 1.0E-6) assertTrue(seen.contains(key(x, z)), "column " + x + "," + z + " at " + d + " is cut");
				}
			}
			for (Column c : g.lips()) {
				double d = segmentDistance(c.x() + 0.5, c.z() + 0.5, START, end);
				assertTrue(d > 1 && d <= 2 + 1.0E-9, "lip " + c + " at " + d);
				assertFalse(seen.contains(key(c)), "a lip is not in the trench");
			}
		}
	}

	@Test
	void aStraightTrenchIsTwoToThreeColumnsWideAndRunsTheWholePath() {
		// Along +Z down the middle of column x = 16: columns 15, 16 and 17 have centres within 1 (2 wide either side of the line).
		ChidoriGeometry g = new ChidoriGeometry(START, START.add(0, 0, 13.5), 4);
		Set<Integer> xs = new HashSet<>();
		for (Column c : g.trench()) xs.add(c.x());
		assertEquals(Set.of(15, 16, 17), xs);
		for (int z = 4; z <= 18; z++) {
			final int zz = z;
			assertTrue(g.trench().stream().anyMatch(c -> c.x() == 16 && c.z() == zz), "z " + z + " is cut");
		}
		// Off a block boundary the line has two columns.
		ChidoriGeometry edge = new ChidoriGeometry(new Vec3(16, 4, 4.5), new Vec3(16, 4, 18), 4);
		Set<Integer> xs2 = new HashSet<>();
		for (Column c : edge.trench()) xs2.add(c.x());
		assertEquals(Set.of(15, 16), xs2);
	}

	@Test
	void segmentsAreTwelveBlocksAndCutAsTheCasterPasses() {
		ChidoriGeometry g = new ChidoriGeometry(START, START.add(0, 0, 17.5), 4);
		assertEquals(2, g.segments());
		for (Column c : g.trench()) {
			int k = g.segment(c);
			assertTrue(c.along() >= 12 * k - 1.0E-9 && (k == g.segments() - 1 || c.along() < 12 * (k + 1)), c + " in segment " + k);
			// Every cell of a segment is well within the ultimate's 12 of its batch origin.
			Vec3 mid = g.segmentMiddle(k);
			assertTrue(Math.hypot(c.x() + 0.5 - mid.x, c.z() + 0.5 - mid.z) <= 8, c + " near its origin " + mid);
		}
		assertEquals(0, g.segmentsPassed(0));
		assertEquals(0, g.segmentsPassed(4)); // 11.67 of 17.5 done
		assertEquals(1, g.segmentsPassed(5)); // 14.6 done: the first segment is behind the caster
		assertEquals(2, g.segmentsPassed(6));
		ChidoriGeometry shortOne = new ChidoriGeometry(START, START.add(0, 0, 3), 4);
		assertEquals(1, shortOne.segments());
		assertEquals(0, shortOne.segmentsPassed(5));
		assertEquals(1, shortOne.segmentsPassed(6));
	}

	@Test
	void bowlRadiusGrowsWithLevelAndStaysWithinIt() {
		assertEquals(4, ChidoriGeometry.radius(7));
		assertEquals(5, ChidoriGeometry.radius(8));
		assertEquals(7, ChidoriGeometry.radius(10));
		assertEquals(12, ChidoriGeometry.radius(20));
		for (int r : new int[] {4, 7, 12}) {
			ChidoriGeometry g = new ChidoriGeometry(START, START.add(0, 0, 10), r);
			List<Cell> bowl = g.bowl();
			for (Cell c : bowl) assertTrue(c.x() * c.x() + c.y() * c.y() + c.z() * c.z() <= r * r, "bowl " + c);
			assertTrue(bowl.stream().mapToInt(Cell::y).min().orElse(0) >= -Math.ceil(r * ChidoriGeometry.BOWL_DEPTH), "depth");
			for (Cell c : ChidoriGeometry.floor(bowl, r)) assertTrue(c.x() * c.x() + c.y() * c.y() + c.z() * c.z() <= r * r, "floor " + c);
			List<Cell> rim = ChidoriGeometry.rim(bowl, r);
			assertTrue(rim.size() >= ChidoriGeometry.DEBRIS, "enough rim at " + r);
			int[] quarters = new int[4];
			for (Cell c : rim.subList(0, ChidoriGeometry.DEBRIS)) {
				assertEquals(0, c.y(), "rim is the top layer");
				quarters[(c.x() >= 0 ? 0 : 1) + (c.z() >= 0 ? 0 : 2)]++;
				Vec3 v = ChidoriGeometry.debrisVelocity(c, new Random(c.hashCode()));
				assertTrue(v.y > 0.4 && v.x * c.x() + v.z * c.z() >= 0, "debris flies up and out");
			}
			for (int q : quarters) assertTrue(q >= 2, "debris spread round the rim");
		}
	}

	@Test
	void blackstoneIsAboutThirtyPercent() {
		Random r = new Random(3);
		int n = 0;
		for (int i = 0; i < 10000; i++) if (ChidoriGeometry.blackstone(r)) n++;
		assertEquals(3000, n, 150);
	}

	private static long key(Column c) {
		return key(c.x(), c.z());
	}

	private static long key(int x, int z) {
		return ((long) x << 32) ^ (z & 0xFFFFFFFFL);
	}

	/** Distance from (x, z) to the flat segment a → b (independent of the geometry's own maths). */
	private static double segmentDistance(double x, double z, Vec3 a, Vec3 b) {
		double dx = b.x - a.x;
		double dz = b.z - a.z;
		double l2 = dx * dx + dz * dz;
		double t = l2 == 0 ? 0 : Math.max(0, Math.min(1, ((x - a.x) * dx + (z - a.z) * dz) / l2));
		double px = a.x + dx * t - x;
		double pz = a.z + dz * t - z;
		return Math.sqrt(px * px + pz * pz);
	}
}
