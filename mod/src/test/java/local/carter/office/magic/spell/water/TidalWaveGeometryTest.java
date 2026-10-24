package local.carter.office.magic.spell.water;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.Test;

import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.Vec3;

class TidalWaveGeometryTest {
	private static final Vec3 START = new Vec3(100.5, 64, -40.5);

	@Test
	void frontIsFiveWideAtHalfTheTick() {
		TidalWaveGeometry g = new TidalWaveGeometry(START, new Vec3(0, -0.3, 1));
		for (int t = 1; t <= TidalWaveGeometry.FRONT_TICKS; t++) {
			List<BlockPos> cells = g.front(t);
			double d = t / 2.0;
			Set<Integer> xs = new HashSet<>();
			for (BlockPos c : cells) {
				xs.add(c.getX());
				assertEquals(BlockPos.containing(START.add(0, 0, d)).getZ(), c.getZ(), "tick " + t + ": the front stands at distance t/2");
				assertTrue(c.getY() >= 64 && c.getY() < 64 + TidalWaveGeometry.height(d), "tick " + t + " row " + c.getY());
			}
			assertEquals(5, xs.size(), "tick " + t + ": five columns");
			assertEquals(Set.of(98, 99, 100, 101, 102), xs, "tick " + t + ": centred on the axis");
			assertEquals(5 * TidalWaveGeometry.height(d), cells.size(), "tick " + t + " cells");
		}
		assertEquals(1, TidalWaveGeometry.height(1.5));
		assertEquals(3, TidalWaveGeometry.height(2));
		assertEquals(3, TidalWaveGeometry.height(3.5));
		assertEquals(2, TidalWaveGeometry.height(4));
		assertEquals(2, TidalWaveGeometry.height(6));
		assertEquals(1, TidalWaveGeometry.height(6.5));
	}

	@Test
	void frontExistsOnlyWhileTravelling() {
		TidalWaveGeometry g = new TidalWaveGeometry(START, new Vec3(1, 0, 0));
		assertTrue(g.front(0).isEmpty());
		assertTrue(g.front(TidalWaveGeometry.FRONT_TICKS + 1).isEmpty());
		assertEquals(8.0, TidalWaveGeometry.distance(TidalWaveGeometry.FRONT_TICKS), 1e-9, "eight blocks in all");
		assertEquals(TidalWaveGeometry.FRONT_TICKS + TidalWaveGeometry.CELL_LIFE, TidalWaveGeometry.DURATION);
	}

	@Test
	void everySegmentsCellsAreWithinSixOfItsOrigin() {
		for (Vec3 look : List.of(new Vec3(0, 0, 1), new Vec3(1, 0, 0), new Vec3(-1, 0, -1), new Vec3(0.3, -0.5, -0.9), new Vec3(-0.8, 0.2, 0.35))) {
			TidalWaveGeometry g = new TidalWaveGeometry(START, look);
			Set<Integer> seen = new HashSet<>();
			for (int t = 1; t <= TidalWaveGeometry.FRONT_TICKS; t++) {
				int s = TidalWaveGeometry.segment(TidalWaveGeometry.distance(t));
				seen.add(s);
				BlockPos origin = g.segmentOrigin(s);
				for (BlockPos c : g.front(t)) {
					double dist = Math.sqrt(c.distSqr(origin));
					assertTrue(dist <= 6, look + " tick " + t + ": " + c + " is " + dist + " from segment " + s + "'s origin " + origin);
				}
				assertFalse(g.front(t).isEmpty(), "tick " + t);
			}
			assertEquals(Set.of(0, 1), seen, "two 4-block segments");
		}
	}

	@Test
	void segmentsHoldWhereverTheColumnsClimbOrFall() {
		for (Vec3 look : List.of(new Vec3(0, 0, 1), new Vec3(-1, 0, -1), new Vec3(0.3, -0.5, -0.9), new Vec3(-0.8, 0.2, 0.35))) {
			TidalWaveGeometry g = new TidalWaveGeometry(START, look);
			for (int dy : new int[] {TidalWaveGeometry.MAX_CLIMB, -TidalWaveGeometry.MAX_DROP}) {
				int[] colY = g.columns();
				java.util.Arrays.fill(colY, 64 + dy);
				for (int t = 1; t <= TidalWaveGeometry.FRONT_TICKS; t++) {
					BlockPos origin = g.segmentOrigin(TidalWaveGeometry.segment(TidalWaveGeometry.distance(t)));
					for (BlockPos c : g.front(t, colY, new boolean[5])) {
						assertTrue(Math.sqrt(c.distSqr(origin)) <= 6, look + " dy " + dy + " tick " + t + ": " + c + " vs " + origin);
					}
				}
			}
		}
	}

	/** Ground at y 63 (the start stands on it), rising one block per block from z −36 on; a wall 4 tall at z −33 for x ≥ 101. */
	private static boolean free(BlockPos p) {
		int ground = p.getZ() < -36 ? 63 : 63 + Math.min(2, p.getZ() + 37);
		if (p.getY() <= ground) return false;
		return !(p.getX() >= 101 && p.getZ() == -33 && p.getY() <= 67);
	}

	@Test
	void columnsClimbStepsAndBreakOnWalls() {
		TidalWaveGeometry g = new TidalWaveGeometry(START, new Vec3(0, 0, 1)); // start z −40.5, columns x 102 (i 0) .. 98 (i 4)
		int[] colY = g.columns();
		boolean[] broken = new boolean[5];
		int brokeMask = 0;
		for (int t = 1; t <= TidalWaveGeometry.FRONT_TICKS; t++) {
			brokeMask |= g.step(t, colY, broken, TidalWaveGeometryTest::free);
			for (BlockPos c : g.front(t, colY, broken)) assertTrue(free(c), "tick " + t + ": a wave cell inside the ground or the wall: " + c);
		}
		// Columns at x 102 and 101 (i 0, 1) hit the wall at z −33; the rest climbed the slope to the top (2 above the start).
		assertTrue(broken[0] && broken[1], "the wall broke them");
		assertEquals(0b00011, brokeMask);
		for (int i = 2; i < 5; i++) {
			assertFalse(broken[i], "column " + i);
			assertEquals(66, colY[i], "column " + i + " stands on the slope's top");
		}
		// An entity on the slope in a standing column is in the wave; one behind the wall is not.
		assertTrue(g.inFront(16, new Vec3(99.5, 66, -32.5), colY, broken), "on the slope");
		assertFalse(g.inFront(16, new Vec3(101.5, 66, -32.5), colY, broken), "behind the wall");
		assertFalse(g.inFront(16, new Vec3(99.5, 63, -32.5), colY, broken), "far below the column");
	}

	@Test
	void columnsPourDownAndStopAtMaxDrop() {
		TidalWaveGeometry g = new TidalWaveGeometry(START, new Vec3(0, 0, 1));
		int[] colY = g.columns();
		boolean[] broken = new boolean[5];
		for (int t = 1; t <= TidalWaveGeometry.FRONT_TICKS; t++) g.step(t, colY, broken, p -> p.getY() >= 50 || p.getZ() < -39);
		for (int i = 0; i < 5; i++) assertEquals(64 - TidalWaveGeometry.MAX_DROP, colY[i], "column " + i + " poured down to the limit");
	}

	@Test
	void entitiesInTheFrontAreFound() {
		TidalWaveGeometry g = new TidalWaveGeometry(START, new Vec3(0, 0, 1));
		assertTrue(g.inFront(8, START.add(0, 0, 4)));
		assertTrue(g.inFront(8, START.add(2.4, 1, 4.5)));
		assertFalse(g.inFront(8, START.add(0, 0, 6)), "ahead of the front");
		assertFalse(g.inFront(8, START.add(3.5, 0, 4)), "beside it");
		assertFalse(g.inFront(0, START), "before it starts");
	}

	@Test
	void coneHasItsRangeAndAngle() {
		Vec3 eye = new Vec3(0.5, 65.62, 0.5);
		Vec3 look = new Vec3(0, 0, 1);
		assertEquals(8, TidalWaveGeometry.range(0), 1e-9);
		assertEquals(12, TidalWaveGeometry.range(1), 1e-9);
		assertTrue(TidalWaveGeometry.inCone(eye, look, 12, eye.add(0, -1, 10)));
		assertFalse(TidalWaveGeometry.inCone(eye, look, 8, eye.add(0, -1, 10)), "out of range at charge 0");
		double tan = Math.tan(Math.toRadians(34));
		assertTrue(TidalWaveGeometry.inCone(eye, look, 12, eye.add(5 * tan, 0, 5)), "34° in");
		tan = Math.tan(Math.toRadians(36));
		assertFalse(TidalWaveGeometry.inCone(eye, look, 12, eye.add(5 * tan, 0, 5)), "36° out");
		List<BlockPos> cells = TidalWaveGeometry.coneCells(eye, look, 12);
		assertTrue(cells.contains(new BlockPos(0, 64, 10)), "the fire 10 ahead");
		for (BlockPos c : cells) assertTrue(TidalWaveGeometry.inCone(eye, look, 12, Vec3.atCenterOf(c)), c.toString());
	}

	@Test
	void fireSegmentsKeepEveryCellWithinSixOfTheirOrigin() {
		Vec3 eye = new Vec3(-3.5, 70.62, 12.5);
		for (BlockPos c : TidalWaveGeometry.coneCells(eye, new Vec3(-0.6, -0.3, 0.7), 12)) {
			BlockPos origin = TidalWaveGeometry.fireSegmentOrigin(TidalWaveGeometry.fireSegment(c));
			assertTrue(Math.sqrt(c.distSqr(origin)) <= 6, c + " vs " + origin);
		}
		assertEquals(new BlockPos(-1, 11, 2), TidalWaveGeometry.fireSegment(new BlockPos(-1, 66, 12)));
		assertEquals(new BlockPos(-3, 69, 15), TidalWaveGeometry.fireSegmentOrigin(new BlockPos(-1, 11, 2)));
	}
}
