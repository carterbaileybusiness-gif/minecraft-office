package local.carter.office.magic.spell.fire;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.HashSet;
import java.util.List;
import java.util.Random;
import java.util.Set;

import org.junit.jupiter.api.Test;

import local.carter.office.magic.core.Shapes.Cell;

import net.minecraft.world.phys.Vec3;

class MeteorGeometryTest {
	private static final Vec3 IMPACT = new Vec3(16.5, 4, 16.3);

	@Test
	void heightFallsLinearlyFromFortyToZeroAtTickForty() {
		for (Vec3 dir : new Vec3[] {new Vec3(0, 0, 1), new Vec3(0.6, -0.3, -0.8), new Vec3(0, -1, 0)}) {
			MeteorGeometry g = MeteorGeometry.of(IMPACT, dir, 1, 7);
			for (int t = 0; t <= 40; t++) {
				assertEquals(40.0 * (1 - t / 40.0), g.height(t), 1e-9, "height at tick " + t);
				assertEquals(IMPACT.y + g.height(t), g.position(t).y, 1e-9, "the rock is at that height");
			}
			assertEquals(40, g.height(0), 1e-9);
			assertEquals(20, g.height(20), 1e-9);
			assertEquals(0, g.height(40), 1e-9);
			assertEquals(0, g.height(55), 1e-9, "it stays down");
			assertTrue(g.position(40).distanceTo(IMPACT) < 1e-9, "lands on the impact");
			// Straight line, constant speed.
			assertTrue(g.position(10).subtract(g.position(9)).distanceTo(g.velocity()) < 1e-9);
			assertTrue(MeteorGeometry.landed(40) && !MeteorGeometry.landed(39));
		}
		// Out of the sky the caster faces (facing +Z it comes in from +Z).
		MeteorGeometry g = MeteorGeometry.of(IMPACT, new Vec3(0, 0, 1), 1, 7);
		assertEquals(IMPACT.z + MeteorGeometry.SLANT, g.start().z, 1e-9);
		// Under a roof the drop is shorter, never below MIN_LIFT, and still lands at tick 40.
		MeteorGeometry low = MeteorGeometry.of(IMPACT, new Vec3(0, 0, 1), 0.25, 7);
		assertEquals(10, low.height(0), 1e-9);
		assertEquals(5, low.height(20), 1e-9);
		assertEquals(0, low.height(40), 1e-9);
		assertEquals(4, MeteorGeometry.of(IMPACT, new Vec3(0, 0, 1), 0, 7).height(0), 1e-9);
	}

	@Test
	void radiusGrowsWithLevelUpToTwelve() {
		assertEquals(7, MeteorGeometry.radius(7));
		assertEquals(8, MeteorGeometry.radius(8));
		assertEquals(10, MeteorGeometry.radius(10));
		assertEquals(12, MeteorGeometry.radius(30));
	}

	@Test
	void craterRimAndFloorStayWithinTheRadius() {
		for (int r = 7; r <= 12; r++) {
			MeteorGeometry g = MeteorGeometry.of(IMPACT, new Vec3(0, 0, 1), 1, r);
			List<Cell> bowl = g.bowl();
			long r2 = (long) r * r;
			for (Cell c : bowl) assertTrue(c.x() * c.x() + c.y() * c.y() + c.z() * c.z() <= r2 && c.y() <= 0);
			List<Cell> rim = MeteorGeometry.rim(bowl, r);
			assertTrue(rim.size() >= MeteorGeometry.DEBRIS, "enough rim at radius " + r + ": " + rim.size());
			Set<Cell> bowlSet = new HashSet<>(bowl);
			assertEquals(rim.size(), new HashSet<>(rim).size(), "no rim cell twice");
			for (Cell c : rim) {
				assertTrue(bowlSet.contains(c), "rim cells are crater cells");
				assertEquals(0, c.y(), "top layer");
				assertTrue(Math.sqrt(c.x() * c.x() + c.z() * c.z()) > r - MeteorGeometry.RIM_BAND);
			}
			// The first 40 go all the way round: every quarter has some.
			int[] quarter = new int[4];
			for (Cell c : rim.subList(0, MeteorGeometry.DEBRIS)) quarter[(c.x() >= 0 ? 0 : 1) + (c.z() >= 0 ? 0 : 2)]++;
			for (int q : quarter) assertTrue(q >= 6, "debris spread round the rim: " + java.util.Arrays.toString(quarter));
			Random rnd = new Random(3);
			for (Cell c : rim) {
				Vec3 v = MeteorGeometry.debrisVelocity(c, rnd);
				assertTrue(v.y > 0.5, "thrown up");
				assertTrue(v.x * c.x() + v.z * c.z() > 0, "thrown outward");
			}
			for (Cell f : MeteorGeometry.floor(bowl, r)) {
				assertTrue(!bowlSet.contains(f), "the floor is under the crater, not in it");
				assertTrue(bowlSet.contains(new Cell(f.x(), f.y() + 1, f.z())), "right under a crater cell");
				assertTrue(f.x() * f.x() + f.y() * f.y() + f.z() * f.z() <= r2, "within the radius");
			}
		}
		// About 30 % magma and 30 % blackstone.
		Random rnd = new Random(11);
		int[] kinds = new int[3];
		for (int i = 0; i < 10000; i++) kinds[MeteorGeometry.floorKind(rnd)]++;
		assertEquals(3000, kinds[1], 200);
		assertEquals(3000, kinds[2], 200);
	}
}
