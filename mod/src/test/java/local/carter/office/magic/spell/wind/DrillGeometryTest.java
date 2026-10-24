package local.carter.office.magic.spell.wind;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.HashSet;
import java.util.List;
import java.util.Random;

import org.junit.jupiter.api.Test;

import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.Vec3;

class DrillGeometryTest {
	private static final double EPS = 1.0E-9;
	private static final Vec3 SOUTH = new Vec3(0, 0, 1);
	/** A body line 0.62 over feet standing at y 64, meeting a wall face at z 10. */
	private static final Vec3 FACE = new Vec3(0.5, 64.62, 10.0);

	@Test
	void lookingSouthEveryBlockIsAheadWithinRadiusThreeAndTwelve() {
		for (int level = 7; level <= 20; level++) {
			int length = DrillGeometry.length(level);
			List<BlockPos> blocks = DrillGeometry.blocks(FACE, SOUTH, length);
			assertTrue(blocks.size() >= 20, "level " + level + ": " + blocks.size() + " blocks");
			BlockPos origin = BlockPos.containing(FACE);
			for (BlockPos p : blocks) {
				assertTrue(p.getZ() >= 10, "behind the impact: " + p);
				assertTrue(DrillGeometry.radial(p, FACE, SOUTH) <= 3 + EPS, "wider than 3: " + p);
				assertTrue(p.distSqr(origin) <= 144, "beyond 12: " + p);
				assertTrue(p.getZ() < 10 + length, "longer than " + length + ": " + p);
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
		// The drill really is that long and narrows to its tip.
		List<BlockPos> blocks = DrillGeometry.blocks(FACE, SOUTH, 8);
		assertEquals(17, blocks.stream().mapToInt(BlockPos::getZ).max().orElseThrow());
		int prev = Integer.MAX_VALUE;
		for (int z = 10; z <= 17; z++) {
			final int zz = z;
			int layer = (int) blocks.stream().filter(p -> p.getZ() == zz).count();
			assertTrue(layer > 0 && layer <= prev, "layer " + z + " has " + layer + " blocks after " + prev);
			prev = layer;
		}
		assertEquals(3, DrillGeometry.radiusAt(0, 8), EPS);
		assertEquals(1, DrillGeometry.radiusAt(7, 8), EPS);
	}

	@Test
	void levelDrillKeepsTheFloorUnderTheFeet() {
		// Feet at y 64: the floor (y 63 and down) is never drilled; the tunnel reaches up to the radius.
		List<BlockPos> blocks = DrillGeometry.blocks(FACE, SOUTH, 8);
		assertTrue(blocks.stream().allMatch(p -> p.getY() >= 64), "a trench under the feet");
		assertTrue(blocks.contains(new BlockPos(0, 64, 10)), "the block at the feet is drilled");
		assertTrue(blocks.contains(new BlockPos(0, 65, 10)) && blocks.contains(new BlockPos(0, 66, 10)), "the tunnel is head-high and more");
		// Aimed down at the ground, the drill digs a pit.
		Vec3 down = new Vec3(0, -1, 0.2).normalize();
		Vec3 ground = new Vec3(0.5, 64.0, 3.5);
		List<BlockPos> pit = DrillGeometry.blocks(ground, down, 8);
		assertTrue(pit.stream().anyMatch(p -> p.getY() <= 58), "the pit goes down");
	}

	@Test
	void anyLookStaysAheadInsideTheConeAndTwelve() {
		Random r = new Random(7);
		for (int i = 0; i < 40; i++) {
			Vec3 look = new Vec3(r.nextGaussian(), r.nextGaussian(), r.nextGaussian()).normalize();
			Vec3 start = new Vec3(r.nextDouble() * 4, 64 + r.nextDouble(), r.nextDouble() * 4);
			List<BlockPos> blocks = DrillGeometry.blocks(start, look, 12);
			assertEquals(blocks.size(), new HashSet<>(blocks).size(), "no block twice");
			assertTrue(!blocks.isEmpty(), "something is drilled for " + look);
			BlockPos origin = BlockPos.containing(start);
			double prev = -1;
			for (BlockPos p : blocks) {
				double along = DrillGeometry.along(p, start, look);
				assertTrue(along >= 0, "behind: " + p + " for " + look);
				assertTrue(DrillGeometry.radial(p, start, look) <= 3 + EPS, "wider than 3: " + p);
				assertTrue(p.distSqr(origin) <= 144, "beyond 12: " + p);
				assertTrue(p.getY() + 0.5 >= start.y + look.y * along - DrillGeometry.FEET - EPS, "under the feet line: " + p);
				assertTrue(along >= prev - EPS, "ordered from the mouth");
				prev = along;
			}
		}
	}

	@Test
	void dashFliesAlongTheLookFromTheHandWithTheBodyUnderIt() {
		Vec3 eye = new Vec3(0.5, 65.62, 0.5);
		for (int t = 0; t <= DrillGeometry.DASH_TICKS; t++) {
			Vec3 s = DrillGeometry.sphere(eye, new Vec3(0, 0, 2), t);
			assertEquals(0.5, s.x, EPS);
			assertEquals(65.62, s.y, EPS);
			assertEquals(0.5 + 1.0 + 1.8 * t, s.z, EPS);
			assertEquals(t, WindSphere.tAt(eye, SOUTH, s), EPS);
			assertEquals(64.62, DrillGeometry.body(s).y, EPS);
		}
		Vec3 feet = new Vec3(0.5, 64, 0.5);
		assertEquals(new Vec3(0.5, 64, 0.5 + 1.8 * 3), WindSphere.plannedFeet(feet, SOUTH, 3));
		assertEquals(feet, WindSphere.plannedFeet(feet, SOUTH, -2));
	}

	@Test
	void offPathIsDistanceToThePathSoFar() {
		Vec3 from = new Vec3(0, 64, 0);
		Vec3 to = new Vec3(0, 64, 9);
		// Behind on the path (lag): nowhere near off it.
		assertEquals(0, DrillGeometry.offPath(new Vec3(0, 64, 0), from, to), EPS);
		assertEquals(0, DrillGeometry.offPath(new Vec3(0, 64, 3.6), from, to), EPS);
		// Beside the path, before its start, past its end: the distance to the nearest point of it.
		assertEquals(5, DrillGeometry.offPath(new Vec3(5, 64, 4), from, to), EPS);
		assertEquals(5, DrillGeometry.offPath(new Vec3(0, 64, -5), from, to), EPS);
		assertEquals(2, DrillGeometry.offPath(new Vec3(0, 64, 11), from, to), EPS);
		// No path yet (forming): the distance from the start.
		assertEquals(5, DrillGeometry.offPath(new Vec3(3, 64, 4), from, from), EPS);
	}

	@Test
	void debrisSpinsOutwardAndNeverComesBack() {
		List<BlockPos> blocks = DrillGeometry.blocks(FACE, SOUTH, 8);
		List<Integer> thrown = DrillGeometry.thrown(blocks, FACE, SOUTH, DrillGeometry.LAUNCH);
		assertEquals(32, thrown.size());
		assertEquals(32, new HashSet<>(thrown).size());
		Random r = new Random(1);
		for (int i : thrown) {
			BlockPos p = blocks.get(i);
			assertTrue(DrillGeometry.along(p, FACE, SOUTH) < 3 && DrillGeometry.radial(p, FACE, SOUTH) >= 1, "thrown from the mouth, off the axis: " + p);
			Vec3 v = DrillGeometry.debris(p, FACE, SOUTH, r);
			assertTrue(v.z >= 0, "never back toward the caster: " + v);
			assertTrue(v.length() < 1.2, "not too fast: " + v);
		}
		// Any look: nothing comes back along it.
		Random looks = new Random(3);
		for (int n = 0; n < 40; n++) {
			Vec3 look = new Vec3(looks.nextGaussian(), looks.nextGaussian(), looks.nextGaussian()).normalize();
			for (BlockPos p : DrillGeometry.blocks(FACE, look, 8)) {
				assertTrue(DrillGeometry.debris(p, FACE, look, r).dot(look) >= -EPS, "debris back toward the caster for " + look);
			}
		}
		// Drilling straight down, the spin and the throw are level and the lift is up out of the pit.
		Vec3 down = new Vec3(0, -1, 0);
		Vec3 ground = new Vec3(0.5, 64.0, 0.5);
		List<BlockPos> pit = DrillGeometry.blocks(ground, down, 8);
		for (int i : DrillGeometry.thrown(pit, ground, down, DrillGeometry.LAUNCH)) {
			BlockPos p = pit.get(i);
			Vec3 v = DrillGeometry.debris(p, ground, down, r);
			Vec3 out = new Vec3(p.getX() + 0.5 - ground.x, 0, p.getZ() + 0.5 - ground.z).normalize();
			Vec3 spin = down.cross(out);
			assertTrue(v.dot(spin) > 0.3, "spun round the axis: " + v);
			assertTrue(v.dot(out) > 0.2, "thrown out from the axis: " + v);
			assertTrue(v.y >= 0, "not driven down the pit: " + v);
		}
	}
}
