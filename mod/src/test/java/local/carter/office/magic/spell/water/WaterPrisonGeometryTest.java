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
		assertEquals(120, WaterPrisonGeometry.duration(0.5F));
		assertEquals(140, WaterPrisonGeometry.duration(1));
		assertEquals(140, WaterPrisonGeometry.duration(3), "clamped");
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
				double h = Math.sqrt(WaterPrisonGeometry.RADIUS * WaterPrisonGeometry.RADIUS - dx * dx - dz * dz);
				assertTrue(!Double.isNaN(h), "column within the radius: " + p);
				assertTrue(p.getY() + (double) cell.height() <= c.y + h + 1.0E-5, "the water never rises above the sphere: " + p);
				assertTrue(p.getY() + 0.5 >= c.y - h, "nor hangs below it: " + p);
				assertTrue(cell.height() >= WaterPrisonGeometry.MIN_FILL && cell.height() <= 1);
				if (cell.height() < 1) assertFalse(at.contains(p.above()), "only a column's top cell is partly full");
			}
			assertTrue(at.contains(BlockPos.containing(c)), "the middle is water");
			assertTrue(cells.size() >= 25 && cells.size() <= 45, "about a ball of radius 2: " + cells.size());
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
	void airDrainsOneATickAndDrownsOnlyWhenUsedUp() {
		int air = 300;
		int drowned = 0;
		int firstDrown = -1;
		for (int t = 1; t <= 400; t++) {
			if (WaterPrisonGeometry.drowns(air)) {
				drowned++;
				if (firstDrown < 0) firstDrown = t;
			}
			air = WaterPrisonGeometry.air(air);
			if (t == 100) assertEquals(200, air, "one a tick");
		}
		assertEquals(320, firstDrown, "the first drowning comes 20 ticks after the air runs out, as vanilla's");
		assertEquals(5, drowned, "then every 20 ticks");
		assertFalse(WaterPrisonGeometry.drowns(1));
		assertEquals(0, WaterPrisonGeometry.air(-19));
	}
}
