package local.carter.office.magic.spell.earth;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Set;
import java.util.function.Predicate;

import org.junit.jupiter.api.Test;

import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.Vec3;

class EarthWallGeometryTest {
	private static final Vec3 FEET = new Vec3(16.5, 4, 4.5);
	/** Flat ground: everything at y ≥ 4 is open. */
	private static final Predicate<BlockPos> FLAT = p -> p.getY() >= 4;

	@Test
	void sizesFollowCharge() {
		assertEquals(5, EarthWallGeometry.width(0F));
		assertEquals(5, EarthWallGeometry.width(0.49F));
		assertEquals(7, EarthWallGeometry.width(0.5F));
		assertEquals(7, EarthWallGeometry.width(1F));
		assertEquals(3, EarthWallGeometry.height(0F));
		assertEquals(3, EarthWallGeometry.height(0.9F));
		assertEquals(4, EarthWallGeometry.height(1F));
	}

	@Test
	void standsThreeAheadAcrossTheLook() {
		EarthWallGeometry g = EarthWallGeometry.of(FEET, new Vec3(0, -0.2, 1), EarthWallGeometry.AHEAD, 0F);
		assertEquals(new BlockPos(16, 4, 7), g.origin());
		assertEquals(1, g.ax());
		assertEquals(0, g.az());
		// Looking along +X the wall runs along Z; a mostly-+X diagonal still counts as +X.
		EarthWallGeometry x = EarthWallGeometry.of(FEET, new Vec3(1, 0, 0), EarthWallGeometry.AHEAD, 0F);
		assertEquals(new BlockPos(19, 4, 4), x.origin());
		assertArrayEquals(new int[] {0, 1}, EarthWallGeometry.axis(new Vec3(0.8, 0, 0.6)));
		assertArrayEquals(new int[] {1, 0}, EarthWallGeometry.axis(new Vec3(-0.6, 0, -0.8)));
		// Something in the way: never closer than MIN_AHEAD, never farther than AHEAD.
		assertEquals(new BlockPos(16, 4, 5), EarthWallGeometry.of(FEET, new Vec3(0, 0, 1), 0.2, 0F).origin());
		assertEquals(new BlockPos(16, 4, 7), EarthWallGeometry.of(FEET, new Vec3(0, 0, 1), 9, 0F).origin());
		// Straight down: no horizontal part, so +Z (the spell passes the body's yaw instead).
		assertEquals(new BlockPos(16, 4, 7), EarthWallGeometry.of(FEET, new Vec3(0, -1, 0), 3, 0F).origin());
	}

	@Test
	void columnsRiseFromTheCentreOutOverSixTicks() {
		EarthWallGeometry five = new EarthWallGeometry(BlockPos.ZERO, 1, 0, 5, 3);
		assertArrayEquals(new int[] {0, -1, 1, -2, 2}, five.order());
		assertArrayEquals(new int[] {3, 5, 5, 6, 6}, places(five));
		EarthWallGeometry seven = new EarthWallGeometry(BlockPos.ZERO, 1, 0, 7, 4);
		assertArrayEquals(new int[] {0, -1, 1, -2, 2, -3, 3}, seven.order());
		assertArrayEquals(new int[] {3, 4, 4, 5, 5, 6, 6}, places(seven));
		for (EarthWallGeometry g : List.of(five, seven)) {
			int last = -1;
			for (int k : g.order()) {
				assertTrue(g.placeTick(k) >= last, "rising order is non-decreasing");
				last = g.placeTick(k);
				assertEquals(EarthWallGeometry.RISE_TICKS, g.placeTick(k) - g.riseStart(k));
				assertTrue(g.riseStart(k) >= 0 && g.placeTick(k) <= EarthWallGeometry.LAST_PLACE);
				assertEquals(k == 0 ? 0 : k - Integer.signum(k), EarthWallGeometry.inner(k));
			}
			assertEquals(EarthWallGeometry.LAST_PLACE, g.placeTick(g.half()), "the ends land on tick 6");
		}
	}

	@Test
	void heightsOnFlatGround() {
		for (float charge : new float[] {0F, 0.5F, 1F}) {
			EarthWallGeometry g = EarthWallGeometry.of(FEET, new Vec3(0, 0, 1), 3, charge);
			int cells = 0;
			for (int k : g.order()) {
				BlockPos base = EarthWallGeometry.base(g.nominal(k), FLAT);
				assertEquals(new BlockPos(16 + k, 4, 7), base);
				List<BlockPos> col = g.cells(base, FLAT);
				assertEquals(g.height(), col.size());
				for (int i = 0; i < col.size(); i++) assertEquals(base.above(i), col.get(i));
				cells += col.size();
			}
			assertEquals(EarthWallGeometry.width(charge) * EarthWallGeometry.height(charge), cells, "charge " + charge);
		}
	}

	@Test
	void groundClimbsStepsAndReachesDown() {
		BlockPos n = new BlockPos(0, 10, 0);
		assertEquals(n, EarthWallGeometry.base(n, p -> p.getY() >= 10));
		assertEquals(n.above(), EarthWallGeometry.base(n, p -> p.getY() >= 11), "a one-block step");
		assertEquals(n.above(2), EarthWallGeometry.base(n, p -> p.getY() >= 12), "a two-block step");
		assertNull(EarthWallGeometry.base(n, p -> p.getY() >= 13), "a three-block wall: no column");
		assertEquals(n.below(2), EarthWallGeometry.base(n, p -> p.getY() >= 8), "a two-block drop");
		assertEquals(n.below(3), EarthWallGeometry.base(n, p -> p.getY() >= 7), "a three-block drop");
		assertNull(EarthWallGeometry.base(n, p -> p.getY() >= 6), "a pit: no column");
		assertNull(EarthWallGeometry.base(n, p -> true), "nothing to stand on");
	}

	@Test
	void columnStopsBelowTheFirstBlockedCell() {
		EarthWallGeometry g = new EarthWallGeometry(BlockPos.ZERO, 1, 0, 5, 4);
		BlockPos chest = new BlockPos(0, 2, 0);
		List<BlockPos> col = g.cells(BlockPos.ZERO, p -> !p.equals(chest));
		assertEquals(List.of(BlockPos.ZERO, new BlockPos(0, 1, 0)), col);
		assertTrue(g.cells(BlockPos.ZERO, p -> false).isEmpty());
	}

	@Test
	void everyCellIsWithinSixOfTheOrigin() {
		// The worst cases: every column on a two-block step (a 4-high column then reaches y + 5), or down a three-block drop.
		BlockPos o = new BlockPos(100, 64, -40);
		for (int[] axis : new int[][] {{1, 0}, {0, 1}}) {
			for (int width : new int[] {5, 7}) {
				for (int height : new int[] {3, 4}) {
					EarthWallGeometry g = new EarthWallGeometry(o, axis[0], axis[1], width, height);
					for (Predicate<BlockPos> ground : Set.<Predicate<BlockPos>>of(p -> p.getY() >= 66, p -> p.getY() >= 61, p -> p.getY() >= 64)) {
						int n = 0;
						for (int k : g.order()) {
							BlockPos base = EarthWallGeometry.base(g.nominal(k), ground);
							assertNotNull(base);
							for (BlockPos c : g.cells(base, ground)) {
								n++;
								assertTrue(c.distSqr(o) <= 36, "cell " + c + " is " + Math.sqrt(c.distSqr(o)) + " from the origin");
							}
						}
						assertEquals(width * height, n);
					}
				}
			}
		}
	}

	private static int[] places(EarthWallGeometry g) {
		int[] order = g.order();
		int[] out = new int[order.length];
		for (int i = 0; i < order.length; i++) out[i] = g.placeTick(order[i]);
		return out;
	}
}
