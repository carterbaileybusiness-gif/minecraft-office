package local.carter.office.magic.spell.water;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.function.Predicate;

import org.junit.jupiter.api.Test;

import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.Vec3;

class DragonGeometryTest {
	private static final Vec3 EYE = new Vec3(100.5, 65.62, -40.5);
	private static final Vec3 LOOK = new Vec3(0, -0.2, 1).normalize();
	private static final Vec3 TARGET = new Vec3(103.2, 61.0, -10.0);

	@Test
	void splineStartsAtTheCasterAndEndsAtTheTargetAtTick30() {
		Vec3 start = DragonGeometry.start(EYE, LOOK);
		assertTrue(start.distanceTo(EYE) <= DragonGeometry.START_AHEAD + DragonGeometry.START_DROP + 1.0E-9, "the start is at the caster: " + start);
		for (long seed : new long[] {0, 1, 42, -7, 123456789L}) {
			DragonGeometry g = new DragonGeometry(start, TARGET, seed);
			assertEquals(0, g.at(0).distanceTo(start), 1.0E-9, "seed " + seed + ": tick 0 is the start");
			assertEquals(0, g.at(DragonGeometry.FLIGHT).distanceTo(TARGET), 1.0E-9, "seed " + seed + ": tick 30 is the target");
			assertEquals(30, DragonGeometry.FLIGHT);
			assertTrue(g.at(0.01).distanceTo(start) < 0.1, "seed " + seed + ": it leaves the start smoothly");
			assertTrue(g.at(29.99).distanceTo(TARGET) < 0.3, "seed " + seed + ": it arrives smoothly");
			// Clamped outside the flight.
			assertEquals(0, g.at(-5).distanceTo(start), 1.0E-9);
			assertEquals(0, g.at(40).distanceTo(TARGET), 1.0E-9);
		}
	}

	@Test
	void sameSeedSamePathOtherSeedOtherPath() {
		Vec3 start = DragonGeometry.start(EYE, LOOK);
		DragonGeometry a = new DragonGeometry(start, TARGET, 77);
		DragonGeometry b = new DragonGeometry(start, TARGET, 77);
		DragonGeometry c = new DragonGeometry(start, TARGET, 78);
		double apart = 0;
		for (int i = 0; i <= 300; i++) {
			double t = i / 10.0;
			assertEquals(0, a.at(t).distanceTo(b.at(t)), 0, "tick " + t);
			assertEquals(0, a.tangent(t).distanceTo(b.tangent(t)), 0, "tangent at " + t);
			apart = Math.max(apart, a.at(t).distanceTo(c.at(t)));
		}
		assertTrue(apart > 0.5, "another seed flies another path (apart " + apart + ")");
	}

	@Test
	void flightIsSmoothRisesThenDivesFasterAtTheEnd() {
		Vec3 start = DragonGeometry.start(EYE, LOOK);
		for (long seed = 0; seed < 40; seed++) {
			DragonGeometry g = new DragonGeometry(start, TARGET, seed);
			double max = 0;
			double top = Double.NEGATIVE_INFINITY;
			for (int i = 1; i <= 300; i++) {
				Vec3 p = g.at(i / 10.0);
				max = Math.max(max, p.distanceTo(g.at((i - 1) / 10.0)));
				top = Math.max(top, p.y);
				assertTrue(p.y >= TARGET.y - 0.5, "seed " + seed + " tick " + i / 10.0 + ": never far below the target " + p);
			}
			assertTrue(max < g.length() / 30 * 0.2 * 2.2, "seed " + seed + ": no jumps (" + max + ")");
			assertTrue(top > Math.max(start.y, TARGET.y) + 4, "seed " + seed + ": it climbs before the dive (" + top + ")");
			double early = g.at(3).distanceTo(g.at(0));
			double late = g.at(30).distanceTo(g.at(27));
			assertTrue(late > 2 * early, "seed " + seed + ": fastest in the dive (" + early + " vs " + late + ")");
			assertTrue(g.tangent(30).y < -0.5, "seed " + seed + ": it comes down onto the target " + g.tangent(30));
		}
		// Straight down still loops out first and lands.
		DragonGeometry down = new DragonGeometry(start, start.add(0, -6, 0.2), 5);
		assertEquals(0, down.at(30).distanceTo(start.add(0, -6, 0.2)), 1.0E-9);
		assertTrue(down.length() > 10);
	}

	@Test
	void floodRadiusAndMudRadius() {
		assertEquals(6, DragonGeometry.floodRadius(7));
		assertEquals(9, DragonGeometry.floodRadius(10));
		assertEquals(12, DragonGeometry.floodRadius(13));
		assertEquals(12, DragonGeometry.floodRadius(40));
		assertEquals(9, DragonGeometry.mudRadius(7));
		assertEquals(12, DragonGeometry.mudRadius(10));
		assertEquals(12, DragonGeometry.mudRadius(20));
	}

	private static final BlockPos O = new BlockPos(10, 64, 10);

	/** Flat ground: solid at y ≤ 63, air above. */
	private static final Predicate<BlockPos> FLAT_AIR = p -> p.getY() >= 64;
	private static final Predicate<BlockPos> FLAT_HOLDS = p -> p.getY() <= 63;

	@Test
	void floodOnFlatGroundIsOneLayerWithinTheRadius() {
		List<BlockPos> cells = DragonGeometry.flood(O, 6, FLAT_AIR, FLAT_HOLDS, DragonGeometry.MAX_SOURCES);
		assertTrue(cells.size() >= 100, "a disc: " + cells.size());
		Set<BlockPos> seen = new HashSet<>();
		for (BlockPos c : cells) {
			assertEquals(64, c.getY(), "one layer on the ground: " + c);
			assertTrue(c.distSqr(O) <= 36, "within the radius: " + c);
			assertTrue(seen.add(c), "no cell twice");
		}
		assertTrue(cells.contains(O), "the impact cell");
		assertTrue(cells.contains(O.offset(6, 0, 0)) && !cells.contains(O.offset(7, 0, 0)), "radius 6 exactly");
	}

	@Test
	void floodFillsAPitBottomUpAndNeverAboveTheImpact() {
		// A 3×3 pit 3 deep at the impact, flat ground around.
		Predicate<BlockPos> air = p -> p.getY() >= 64 || (Math.abs(p.getX() - 10) <= 1 && Math.abs(p.getZ() - 10) <= 1 && p.getY() >= 61);
		Predicate<BlockPos> holds = air.negate();
		BlockPos top = O.above(2); // the impact is in the air above the ground
		List<BlockPos> cells = DragonGeometry.flood(top, 6, air, holds, DragonGeometry.MAX_SOURCES);
		Set<BlockPos> set = new HashSet<>(cells);
		for (int i = 0; i < cells.size(); i++) {
			BlockPos c = cells.get(i);
			assertTrue(c.getY() <= top.getY(), "never above the impact: " + c);
			assertTrue(holds.test(c.below()) || set.contains(c.below()), "nothing floats: " + c);
			if (set.contains(c.below())) assertTrue(cells.indexOf(c.below()) < i, "filled bottom up: " + c);
		}
		for (int y = 61; y <= 66; y++) assertTrue(set.contains(new BlockPos(10, y, 10)), "the pit and the layers above it fill at y " + y);
		assertTrue(set.contains(new BlockPos(14, 64, 10)) && !set.contains(new BlockPos(14, 67, 10)), "layers up to the impact's level only");
	}

	@Test
	void floodStopsAtTheCapAndSkipsSealedCaves() {
		List<BlockPos> few = DragonGeometry.flood(O, 6, FLAT_AIR, FLAT_HOLDS, 10);
		assertEquals(10, few.size());
		assertEquals(O, few.get(0), "nearest first in the layer");
		// A sealed cave 4 below: air, with a floor, but not reachable through air from the impact.
		Predicate<BlockPos> air = p -> p.getY() >= 64 || p.getY() == 60;
		Predicate<BlockPos> holds = air.negate();
		for (BlockPos c : DragonGeometry.flood(O, 6, air, holds, DragonGeometry.MAX_SOURCES)) {
			assertEquals(64, c.getY(), "the cave stays dry: " + c);
		}
		// The impact cell blocked: nothing.
		assertTrue(DragonGeometry.flood(O, 6, p -> false, FLAT_HOLDS, 600).isEmpty());
		// A deep basin is held to MAX_SOURCES, its lowest layers first.
		Predicate<BlockPos> deep = p -> p.getY() >= 53;
		List<BlockPos> basin = DragonGeometry.flood(O, 12, deep, deep.negate(), DragonGeometry.MAX_SOURCES);
		assertEquals(DragonGeometry.MAX_SOURCES, basin.size());
		for (int i = 1; i < basin.size(); i++) assertTrue(basin.get(i).getY() >= basin.get(i - 1).getY(), "bottom up");
		assertEquals(53, basin.get(0).getY());
	}

	@Test
	void mudIsAboutFortyPercentAndSeeded() {
		Predicate<BlockPos> top = p -> p.getY() == 63; // dirt tops of flat ground
		List<BlockPos> a = DragonGeometry.mud(O, 9, top, 99);
		List<BlockPos> b = DragonGeometry.mud(O, 9, top, 99);
		List<BlockPos> c = DragonGeometry.mud(O, 9, top, 100);
		assertEquals(a, b, "same seed, same mud");
		assertFalse(a.equals(c), "another seed, other mud");
		int all = 0;
		for (BlockPos p : BlockPos.betweenClosed(O.offset(-9, -1, -9), O.offset(9, -1, 9))) if (p.distSqr(O) <= 81) all++;
		double share = a.size() / (double) all;
		assertTrue(share > 0.3 && share < 0.5, "about 40 %: " + share);
		for (BlockPos p : a) {
			assertTrue(p.distSqr(O) <= 81 && p.getY() == 63, "within the radius, on the tops: " + p);
			assertTrue(DragonGeometry.mudPick(99, p));
		}
		for (BlockPos p : DragonGeometry.mud(O, 20, top, 5)) assertTrue(p.distSqr(O) <= 144, "never past 12: " + p);
	}
}
