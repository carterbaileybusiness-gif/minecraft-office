package local.carter.office.magic.spell.water;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.HashSet;
import java.util.List;

import org.junit.jupiter.api.Test;

import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.Vec3;

class WaterPrisonGeometryTest {
	private static final Vec3 C = new Vec3(20.5, 65.5, -3.5);

	@Test
	void durationGrowsWithCharge() {
		assertEquals(100, WaterPrisonGeometry.duration(0));
		assertEquals(150, WaterPrisonGeometry.duration(0.5F));
		assertEquals(200, WaterPrisonGeometry.duration(1));
		assertEquals(200, WaterPrisonGeometry.duration(3), "clamped");
		assertEquals(2, WaterPrisonGeometry.radius(0), 1e-9);
		assertEquals(3.5, WaterPrisonGeometry.radius(1), 1e-9);
	}

	@Test
	void fillIsTheBallRoundedOnTop() {
		for (Vec3 c : new Vec3[] {C, C.add(0.3, -0.45, 0.1), C.add(-0.2, 0.37, 0.45)}) {
			List<WaterPrisonGeometry.Cell> cells = WaterPrisonGeometry.fill(c);
			HashSet<BlockPos> at = new HashSet<>();
			for (WaterPrisonGeometry.Cell cell : cells) {
				BlockPos p = cell.pos();
				assertTrue(at.add(p), "no cell twice: " + p);
				double dx = p.getX() + 0.5 - c.x;
				double dz = p.getZ() + 0.5 - c.z;
				double h = Math.sqrt(WaterPrisonGeometry.CORE * WaterPrisonGeometry.CORE - dx * dx - dz * dz);
				assertTrue(!Double.isNaN(h), "column within the radius: " + p);
				assertTrue(p.getY() + (double) cell.height() <= c.y + h + 1.0E-5, "the water never rises above the sphere: " + p);
				assertTrue(p.getY() + 0.5 >= c.y - h, "nor hangs below it: " + p);
				assertTrue(cell.height() >= WaterPrisonGeometry.MIN_FILL && cell.height() <= 1);
				if (cell.height() < 1) assertFalse(at.contains(p.above()), "only a column's top cell is partly full");
			}
			assertTrue(at.contains(BlockPos.containing(c)), "the middle is water");
			assertTrue(cells.size() >= 5 && cells.size() <= 25, "about a ball of radius 1.3: " + cells.size());
			for (WaterPrisonGeometry.Cell cell : cells) {
				BlockPos p = cell.pos();
				for (int k = 0; k < 8; k++) {
					Vec3 corner = new Vec3(p.getX() + (k & 1), p.getY() + ((k >> 1) & 1) * cell.height(), p.getZ() + ((k >> 2) & 1));
					assertTrue(corner.distanceTo(c) <= WaterPrisonGeometry.RADIUS + 0.75, "the cells stay within the round surface: " + corner);
				}
			}
			assertTrue(cells.stream().anyMatch(x -> x.height() < 1), "a rounded crown");
		}
	}

	@Test
	void insideAndPull() {
		assertTrue(WaterPrisonGeometry.inside(C, C));
		assertTrue(WaterPrisonGeometry.inside(C, C.add(0, 0, 2.4)));
		assertFalse(WaterPrisonGeometry.inside(C, C.add(0, 0, 2.6)));
		assertEquals(0, WaterPrisonGeometry.pull(C, C).length(), 1.0E-12);
		Vec3 v = WaterPrisonGeometry.pull(C, C.add(1, 0, 0));
		assertEquals(-WaterPrisonGeometry.PULL, v.x, 1.0E-12, "toward the centre");
		assertEquals(WaterPrisonGeometry.MAX_PULL, WaterPrisonGeometry.pull(C, C.add(0, -2.4, 0)).length(), 1.0E-9, "capped");
		// Pulled tick after tick, a prisoner settles at the centre.
		Vec3 at = C.add(1.5, -1, 0.8);
		for (int i = 0; i < 30; i++) at = at.add(WaterPrisonGeometry.pull(C, at));
		assertTrue(at.distanceTo(C) < 0.01, "settles: " + at.distanceTo(C));
	}

	@Test
	void airDrainsThreeATickAndDrownsOnlyWhenUsedUp() {
		int air = 300;
		java.util.List<Integer> drowned = new java.util.ArrayList<>();
		for (int t = 1; t <= 400; t++) {
			if (WaterPrisonGeometry.drowns(air)) drowned.add(t);
			air = WaterPrisonGeometry.air(air);
			if (t == 50) assertEquals(150, air, "three a tick");
		}
		assertEquals(107, drowned.get(0), "no drowning until the air is used up (and vanilla's 20 more)");
		for (int i = 1; i < drowned.size(); i++) assertEquals(7, drowned.get(i) - drowned.get(i - 1), "then every 7 ticks");
		long inPrison = drowned.stream().filter(t -> t <= WaterPrisonGeometry.duration(1)).count();
		assertTrue(inPrison >= 4, "a full breath runs out within a charged prison: " + inPrison + " drownings");
		assertFalse(WaterPrisonGeometry.drowns(1));
		assertEquals(0, WaterPrisonGeometry.air(-18));
	}
}
