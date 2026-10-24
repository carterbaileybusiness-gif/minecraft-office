package local.carter.office.magic.spell.earth;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.function.Predicate;

import org.junit.jupiter.api.Test;

import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.Vec3;

class EarthSpikesGeometryTest {
	private static final Vec3 FEET = new Vec3(16.5, 4, 4.5);
	private static final Vec3 SOUTH = new Vec3(0, -0.3, 1);
	/** Flat ground: solid below y 4. */
	private static final Predicate<BlockPos> FLAT = p -> p.getY() < 4;

	@Test
	void lengthIsEightTimesOnePlusCharge() {
		assertEquals(8, EarthSpikesGeometry.length(0F));
		assertEquals(12, EarthSpikesGeometry.length(0.5F));
		assertEquals(16, EarthSpikesGeometry.length(1F)); // a full charge's line is twice a tap's
	}

	@Test
	void aLineOfCellsAheadOnePerTick() {
		BlockPos start = EarthSpikesGeometry.start(FEET, SOUTH);
		assertEquals(new BlockPos(16, 4, 5), start);
		List<BlockPos> path = EarthSpikesGeometry.path(start, SOUTH, 8, FLAT);
		assertEquals(8, path.size());
		for (int i = 0; i < 8; i++) {
			assertEquals(new BlockPos(16, 4, 5 + i), path.get(i));
			assertEquals(i, EarthSpikesGeometry.burstTick(i));
		}
	}

	@Test
	void followsTheGroundUpAStepAndDownADrop() {
		// A one-block step from z 8, then the ground drops 3 from z 11.
		Predicate<BlockPos> ground = p -> p.getZ() >= 11 ? p.getY() < 2 : p.getZ() >= 8 ? p.getY() < 5 : p.getY() < 4;
		List<BlockPos> path = EarthSpikesGeometry.path(new BlockPos(16, 4, 5), SOUTH, 8, ground);
		assertEquals(8, path.size());
		assertEquals(4, path.get(2).getY());
		assertEquals(5, path.get(3).getY(), "climbed the step");
		assertEquals(2, path.get(6).getY(), "dropped down");
		for (BlockPos p : path) assertTrue(!ground.test(p) && ground.test(p.below()), p + " stands on the ground");
	}

	@Test
	void endsAtAWallAndAtAPit() {
		Predicate<BlockPos> wall = p -> p.getY() < 4 || (p.getZ() == 9 && p.getY() < 8);
		assertEquals(4, EarthSpikesGeometry.path(new BlockPos(16, 4, 5), SOUTH, 8, wall).size(), "stops before the wall");
		Predicate<BlockPos> pit = p -> p.getY() < 4 && (p.getZ() < 10 || p.getY() < -10);
		assertEquals(5, EarthSpikesGeometry.path(new BlockPos(16, 4, 5), SOUTH, 8, pit).size(), "stops at the pit's edge");
	}

	@Test
	void diagonalLinesStayConnected() {
		Vec3 diag = new Vec3(1, 0, 1);
		List<BlockPos> path = EarthSpikesGeometry.path(EarthSpikesGeometry.start(FEET, diag), diag, 12, FLAT);
		assertTrue(path.size() >= 8);
		for (int i = 1; i < path.size(); i++) {
			BlockPos a = path.get(i - 1);
			BlockPos b = path.get(i);
			assertTrue(Math.abs(a.getX() - b.getX()) <= 1 && Math.abs(a.getZ() - b.getZ()) <= 1, a + " → " + b);
		}
	}
}
