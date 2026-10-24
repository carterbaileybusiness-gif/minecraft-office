package local.carter.office.magic.spell.wind;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.HashSet;
import java.util.List;
import java.util.Random;
import java.util.Set;

import org.junit.jupiter.api.Test;

import local.carter.office.magic.core.Shapes;
import local.carter.office.magic.core.Shapes.Cell;

import net.minecraft.world.phys.Vec3;

class DrillGeometryTest {
	private static final double EPS = 1.0E-9;
	private static final Vec3 SOUTH = new Vec3(0, 0, 1);

	private static double dist(Cell c) {
		return Math.sqrt(c.x() * c.x() + c.y() * c.y() + c.z() * c.z());
	}

	@Test
	void lookingSouthEveryCellIsAheadWithinRadiusThreeAndTwelve() {
		for (int level = 1; level <= 20; level++) {
			int length = DrillGeometry.length(level);
			List<Cell> cells = DrillGeometry.cells(SOUTH, length);
			assertTrue(cells.size() >= 20, "level " + level + ": " + cells.size() + " cells");
			for (Cell c : cells) {
				assertTrue(c.z() >= 0, "behind the impact: " + c);
				assertTrue(Math.sqrt(c.x() * c.x() + c.y() * c.y()) <= 3 + EPS, "wider than 3: " + c);
				assertTrue(dist(c) <= 12 + EPS, "beyond 12: " + c);
				assertTrue(c.z() < length, "longer than " + length + ": " + c);
			}
		}
	}

	@Test
	void lengthIsEightPlusLevelsPastSevenCappedAtTwelve() {
		assertEquals(8, DrillGeometry.length(7));
		assertEquals(9, DrillGeometry.length(8));
		assertEquals(11, DrillGeometry.length(10));
		assertEquals(12, DrillGeometry.length(11));
		assertEquals(12, DrillGeometry.length(40));
		// The drill really is that long, and its far end is about radius 1.
		List<Cell> cells = DrillGeometry.cells(SOUTH, 8);
		assertEquals(7, cells.stream().mapToInt(Cell::z).max().orElseThrow());
		long tip = cells.stream().filter(c -> c.z() == 7).count();
		assertEquals(5, tip, "the last layer is radius 1: the axis and its 4 neighbours");
		long mouth = cells.stream().filter(c -> c.z() == 0).count();
		assertEquals(29, mouth, "the first layer is radius 3");
	}

	@Test
	void layersFollowTheConeRuleAndShrink() {
		// Looking +Y each layer is the Shapes.cone rule (x² + z² ≤ r²) at the drill's radius for that layer.
		int length = 8;
		List<Cell> up = DrillGeometry.cells(new Vec3(0, 1, 0), length);
		int prev = Integer.MAX_VALUE;
		for (int y = 0; y < length; y++) {
			double r = DrillGeometry.radiusAt(y, length);
			Set<Cell> layer = new HashSet<>();
			for (Cell c : up) if (c.y() == y) layer.add(c);
			Set<Cell> rule = new HashSet<>();
			int n = (int) Math.floor(r);
			for (int x = -n; x <= n; x++) for (int z = -n; z <= n; z++) if (x * x + z * z <= r * r + EPS) rule.add(new Cell(x, y, z));
			assertEquals(rule, layer, "layer " + y);
			assertTrue(layer.size() <= prev, "layer " + y + " is wider than the one before");
			prev = layer.size();
		}
		assertEquals(3, DrillGeometry.radiusAt(0, length), EPS);
		assertEquals(1, DrillGeometry.radiusAt(length - 1, length), EPS);
		// The mouth layer is exactly Shapes.cone's base layer.
		Set<Cell> base = new HashSet<>();
		for (Cell c : Shapes.cone(3, 8)) if (c.y() == 0) base.add(c);
		Set<Cell> mouth = new HashSet<>();
		for (Cell c : up) if (c.y() == 0) mouth.add(c);
		assertEquals(base, mouth);
	}

	@Test
	void anyLookStaysAheadInsideTheConeAndTwelve() {
		Random r = new Random(7);
		for (int i = 0; i < 40; i++) {
			Vec3 look = new Vec3(r.nextGaussian(), r.nextGaussian(), r.nextGaussian()).normalize();
			List<Cell> cells = DrillGeometry.cells(look, 12);
			Set<Cell> unique = new HashSet<>(cells);
			assertEquals(cells.size(), unique.size(), "no cell twice");
			assertTrue(unique.contains(new Cell(0, 0, 0)), "the struck block is drilled");
			for (Cell c : cells) {
				Vec3 d = new Vec3(c.x(), c.y(), c.z());
				double along = d.dot(look);
				assertTrue(along >= -EPS, "behind: " + c + " for " + look);
				assertTrue(DrillGeometry.radial(c, look) <= 3 + EPS, "wider than 3: " + c);
				assertTrue(dist(c) <= 12 + EPS, "beyond 12: " + c);
			}
			// Ordered from the mouth inward.
			double prev = -1;
			for (Cell c : cells) {
				double along = new Vec3(c.x(), c.y(), c.z()).dot(look);
				assertTrue(along >= prev - EPS);
				prev = along;
			}
		}
	}

	@Test
	void dashFliesAlongTheLookFromTheHand() {
		Vec3 eye = new Vec3(0.5, 65.62, 0.5);
		for (int t = 0; t <= DrillGeometry.DASH_TICKS; t++) {
			Vec3 s = DrillGeometry.sphere(eye, new Vec3(0, 0, 2), t);
			assertEquals(0.5, s.x, EPS);
			assertEquals(65.62, s.y, EPS);
			assertEquals(0.5 + 1.0 + 1.8 * t, s.z, EPS);
			assertEquals(t, WindSphere.tAt(eye, SOUTH, s), EPS);
		}
	}

	@Test
	void debrisSpinsRoundTheAxisAndThrowsAtMostThirtyTwo() {
		List<Cell> cells = DrillGeometry.cells(SOUTH, 8);
		List<Integer> thrown = DrillGeometry.thrown(cells, SOUTH, DrillGeometry.LAUNCH);
		assertEquals(32, thrown.size());
		assertEquals(32, new HashSet<>(thrown).size());
		Random r = new Random(1);
		for (int i : thrown) {
			Cell c = cells.get(i);
			assertTrue(c.z() < 3 && DrillGeometry.radial(c, SOUTH) >= 1, "thrown from the mouth, off the axis: " + c);
			Vec3 v = DrillGeometry.debris(c, SOUTH, r);
			assertTrue(v.z < 0, "back out of the hole: " + v);
			assertTrue(v.length() < 1.2, "not too fast: " + v);
		}
		// Drilling straight down, the spin and the throw are level and the lift is out of the pit.
		Vec3 down = new Vec3(0, -1, 0);
		List<Cell> pit = DrillGeometry.cells(down, 8);
		for (int i : DrillGeometry.thrown(pit, down, DrillGeometry.LAUNCH)) {
			Cell c = pit.get(i);
			Vec3 v = DrillGeometry.debris(c, down, r);
			Vec3 out = new Vec3(c.x(), 0, c.z()).normalize();
			Vec3 spin = down.cross(out);
			assertTrue(v.dot(spin) > 0.4, "spun round the axis: " + v);
			assertTrue(v.dot(out) > 0.2, "thrown out from the axis: " + v);
			assertTrue(v.y > 0.5, "up out of the pit: " + v);
			assertTrue(v.length() < 1.2, "not too fast: " + v);
		}
	}
}
