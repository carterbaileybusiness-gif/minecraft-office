package local.carter.office.magic.spell.shadow;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.Test;

import local.carter.office.magic.spell.shadow.BlackHoleGeometry.Cell;

import net.minecraft.world.phys.Vec3;

class BlackHoleGeometryTest {
	private static final Vec3 CENTRE = new Vec3(-40.3, 66, 12.75);
	private static final double EPS = 1.0E-9;

	@Test
	void radiusStartsAtOneEndsAtTheCapAndNeverShrinks() {
		for (int level = 7; level <= 10; level++) {
			double cap = BlackHoleGeometry.cap(level);
			assertEquals(1.0, BlackHoleGeometry.radius(0, cap), EPS, "radius at tick 0, level " + level);
			assertEquals(cap, BlackHoleGeometry.radius(BlackHoleGeometry.LIFE, cap), EPS, "radius at tick 100, level " + level);
			assertEquals(cap, BlackHoleGeometry.radius(400, cap), EPS, "held at the cap after its life");
			double last = BlackHoleGeometry.radius(-5, cap);
			assertEquals(1.0, last, EPS, "never below 1");
			for (double t = 0; t <= BlackHoleGeometry.LIFE + 10; t += 0.25) {
				double r = BlackHoleGeometry.radius(t, cap);
				assertTrue(r >= last, "monotonic at " + t);
				assertTrue(r <= cap + EPS, "never past the cap at " + t);
				last = r;
			}
		}
		// Strictly growing over its life (it really devours more every tick).
		assertTrue(BlackHoleGeometry.radius(51, 6) > BlackHoleGeometry.radius(50, 6));
	}

	@Test
	void capIsSixPlusLevelsPastSevenUpToTwelve() {
		assertEquals(6, BlackHoleGeometry.cap(7), EPS);
		assertEquals(7, BlackHoleGeometry.cap(8), EPS);
		assertEquals(9, BlackHoleGeometry.cap(10), EPS);
		assertEquals(12, BlackHoleGeometry.cap(13), EPS);
		assertEquals(12, BlackHoleGeometry.cap(40), EPS, "min(12, ...)");
		assertEquals(1, BlackHoleGeometry.cap(-30), EPS, "never under its start");
	}

	@Test
	void pullGrowsFromPointZeroEightToPointTwoFiveAndPointsIn() {
		assertEquals(0.08, BlackHoleGeometry.pullSpeed(0), EPS);
		assertEquals(0.165, BlackHoleGeometry.pullSpeed(50), EPS);
		assertEquals(0.25, BlackHoleGeometry.pullSpeed(100), EPS);
		assertEquals(0.25, BlackHoleGeometry.pullSpeed(300), EPS);
		Vec3[] dirs = {new Vec3(1, 0, 0), new Vec3(0, 0, -1), new Vec3(1, 1, 1), new Vec3(-0.3, -0.8, 0.5)};
		for (Vec3 d : dirs) {
			for (double dist : new double[] {0.5, 3, 7.5, 12}) {
				Vec3 at = CENTRE.add(d.normalize().scale(dist));
				for (int t : new int[] {0, 40, 100}) {
					Vec3 pull = BlackHoleGeometry.pull(CENTRE, at, t);
					assertEquals(1.0, pull.normalize().dot(CENTRE.subtract(at).normalize()), 1.0E-9, "points at the centre");
					assertEquals(Math.min(dist, BlackHoleGeometry.pullSpeed(t)), pull.length(), EPS, "speed at " + dist + ", tick " + t);
					assertTrue(at.add(pull).distanceTo(CENTRE) < dist, "closer, never past");
				}
			}
		}
		assertEquals(Vec3.ZERO, BlackHoleGeometry.pull(CENTRE, CENTRE, 50), "zero at the centre");
		assertEquals(0.05, BlackHoleGeometry.pull(CENTRE, CENTRE.add(0.05, 0, 0), 100).length(), EPS, "lands on the centre");
		assertTrue(BlackHoleGeometry.inReach(CENTRE, CENTRE.add(12, 0, 0)));
		assertFalse(BlackHoleGeometry.inReach(CENTRE, CENTRE.add(12.01, 0, 0)));
	}

	@Test
	void sphereCellsLieWithinTheRadiusNearestFirst() {
		for (double r : new double[] {1, 2.5, 6, 9}) {
			List<Cell> cells = BlackHoleGeometry.sphere(CENTRE, r);
			Set<Long> seen = new HashSet<>();
			double last = 0;
			for (Cell c : cells) {
				double d = c.middle().distanceTo(CENTRE);
				assertEquals(d, c.dist(), 1.0E-9, "distance kept");
				assertTrue(d <= r + EPS, "within " + r + ": " + c);
				assertTrue(d >= last, "nearest first");
				assertTrue(seen.add(((long) c.x() << 40) ^ ((long) c.y() << 20) ^ c.z()), "no duplicates");
				last = d;
			}
			// About a ball's volume (4/3 π r³), so nothing within reach is missing.
			double volume = 4.0 / 3.0 * Math.PI * r * r * r;
			if (r >= 6) assertEquals(volume, cells.size(), volume * 0.08, "cells of a radius-" + r + " ball");
		}
		// Every cell within 6 is in it (no gaps).
		List<Cell> six = BlackHoleGeometry.sphere(CENTRE, 6);
		Set<String> in = new HashSet<>();
		for (Cell c : six) in.add(c.x() + "," + c.y() + "," + c.z());
		for (int x = -50; x <= -30; x++) {
			for (int y = 56; y <= 76; y++) {
				for (int z = 2; z <= 22; z++) {
					double d = new Vec3(x + 0.5, y + 0.5, z + 0.5).distanceTo(CENTRE);
					assertEquals(d <= 6, in.contains(x + "," + y + "," + z), "cell " + x + "," + y + "," + z + " at " + d);
				}
			}
		}
	}

	@Test
	void aTenthOfTheRimIsScorchedHalfEach() {
		double cap = 9;
		List<Cell> cells = BlackHoleGeometry.sphere(CENTRE, cap);
		int rim = 0;
		int cracked = 0;
		int black = 0;
		for (Cell c : cells) {
			int kind = BlackHoleGeometry.rimKind(c, cap, 1234L);
			assertEquals(kind, BlackHoleGeometry.rimKind(c, cap, 1234L), "the same on every call (and every client)");
			if (!BlackHoleGeometry.isRim(c, cap)) {
				assertEquals(BlackHoleGeometry.KIND_NONE, kind, "only the rim is scorched");
				continue;
			}
			assertTrue(c.dist() > cap - 1, "the rim is the outermost layer");
			rim++;
			if (kind == BlackHoleGeometry.KIND_CRACKED) cracked++;
			if (kind == BlackHoleGeometry.KIND_BLACKSTONE) black++;
		}
		assertTrue(rim > 500, "rim cells " + rim);
		assertEquals(0.10, (cracked + black) / (double) rim, 0.025, "a tenth of the rim");
		assertEquals(0.5, cracked / (double) (cracked + black), 0.15, "half each");
		int differ = 0;
		for (Cell c : cells) {
			if (BlackHoleGeometry.rimKind(c, cap, 1234L) != BlackHoleGeometry.rimKind(c, cap, 99L)) differ++;
		}
		assertTrue(differ > 0, "another cast scorches other cells");
	}

	@Test
	void onlyTheBowlIsScorchedAndOnlyOverGroundThatStays() {
		double cap = 7;
		List<Cell> cells = BlackHoleGeometry.sphere(CENTRE, cap);
		int scorchable = 0;
		for (Cell c : cells) {
			if (!BlackHoleGeometry.scorchable(c, CENTRE, cap)) continue;
			scorchable++;
			assertTrue(c.y() + 0.5 < CENTRE.y, "below the centre: " + c);
			Vec3 below = new Vec3(c.x() + 0.5, c.y() - 0.5, c.z() + 0.5);
			assertTrue(below.distanceTo(CENTRE) > cap, "the block under it is never eaten: " + c);
		}
		assertTrue(scorchable > 30, "the bowl has room to scorch: " + scorchable);
		assertTrue(BlackHoleGeometry.RESERVE >= 50 && BlackHoleGeometry.RESERVE < 1500, "a reserve for the rim and the collapse");
	}

	@Test
	void lipDebrisFliesUpAndOut() {
		for (Cell c : BlackHoleGeometry.sphere(CENTRE, 6)) {
			if (!BlackHoleGeometry.isRim(c, 6)) continue;
			Vec3 v = BlackHoleGeometry.debrisVelocity(CENTRE, c, 77L);
			assertEquals(v, BlackHoleGeometry.debrisVelocity(CENTRE, c, 77L), "seeded");
			assertTrue(v.y >= 0.5 && v.y <= 0.8, "up " + v.y);
			double out = Math.sqrt(v.x * v.x + v.z * v.z);
			assertTrue(out >= 0.32 - EPS && out <= 0.55 + EPS, "out " + out);
			Vec3 flat = new Vec3(c.x() + 0.5 - CENTRE.x, 0, c.z() + 0.5 - CENTRE.z);
			if (flat.length() > 0.5) assertTrue(new Vec3(v.x, 0, v.z).dot(flat) > 0, "outward from the centre");
			assertEquals(c.y() + 0.5 >= CENTRE.y - BlackHoleGeometry.LIP_DEPTH, BlackHoleGeometry.isLip(c, CENTRE));
		}
	}

	@Test
	void devouredBlocksSpiralIntoTheCentre() {
		Vec3 from = CENTRE.add(5, -3, 2);
		assertTrue(BlackHoleGeometry.infall(CENTRE, from, 1.2, 0, 30).distanceTo(from) < EPS, "starts where it was torn off");
		assertTrue(BlackHoleGeometry.infall(CENTRE, from, 1.2, 30, 30).distanceTo(CENTRE) < 1.0E-6, "ends in the centre");
		double last = from.distanceTo(CENTRE);
		for (double t = 0.5; t <= 30; t += 0.5) {
			double d = BlackHoleGeometry.infall(CENTRE, from, 1.2, t, 30).distanceTo(CENTRE);
			assertTrue(d < last, "always falling in at " + t);
			last = d;
		}
		assertTrue(BlackHoleGeometry.coreRadius(1) >= 0.6);
		assertTrue(BlackHoleGeometry.coreRadius(6) > BlackHoleGeometry.coreRadius(3), "the core grows with what it ate");
	}
}
