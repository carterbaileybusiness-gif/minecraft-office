package local.carter.office.magic.spell.shadow;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import net.minecraft.world.phys.Vec3;

class WellGeometryTest {
	private static final Vec3 CENTRE = new Vec3(-120.5, 70, 33.25);
	private static final double EPS = 1.0E-9;

	@Test
	void pullPointsAtTheCentre() {
		Vec3[] dirs = {new Vec3(1, 0, 0), new Vec3(0, 0, -1), new Vec3(1, 1, 1), new Vec3(-0.3, -0.8, 0.5), new Vec3(0, 1, 0)};
		for (Vec3 d : dirs) {
			for (double dist : new double[] {0.5, 1, 2.5, 4, 6}) {
				Vec3 at = CENTRE.add(d.normalize().scale(dist));
				Vec3 pull = WellGeometry.pull(CENTRE, at);
				Vec3 toCentre = CENTRE.subtract(at).normalize();
				assertEquals(1.0, pull.normalize().dot(toCentre), 1.0E-9, "pull from " + at + " points at the centre");
				assertEquals(0.12 + 0.03 * dist, pull.length(), 1.0E-9, "pull at distance " + dist);
				// It brings the thing closer, never past the centre.
				assertTrue(at.add(pull).distanceTo(CENTRE) < dist, "closer after one tick");
			}
		}
	}

	@Test
	void pullIsCappedAtPointSix() {
		for (double dist : new double[] {16, 20, 40, 300}) {
			Vec3 pull = WellGeometry.pull(CENTRE, CENTRE.add(dist, 0, 0));
			assertEquals(0.6, pull.length(), EPS, "capped at distance " + dist);
		}
		assertEquals(0.6, WellGeometry.speed(1000), EPS);
		assertEquals(0.30, WellGeometry.speed(WellGeometry.RADIUS), EPS, "at the edge of its reach");
		for (double dist = 0; dist < 50; dist += 0.37) {
			assertTrue(WellGeometry.pull(CENTRE, CENTRE.add(0, dist, 0)).length() <= 0.6 + EPS, "never above 0.6 at " + dist);
		}
	}

	@Test
	void pullIsZeroAtTheCentreAndNeverOvershoots() {
		assertEquals(Vec3.ZERO, WellGeometry.pull(CENTRE, CENTRE));
		for (double dist : new double[] {0.01, 0.05, 0.1, 0.12}) {
			Vec3 at = CENTRE.add(0, 0, dist);
			Vec3 pull = WellGeometry.pull(CENTRE, at);
			assertEquals(dist, pull.length(), EPS, "lands exactly on the centre from " + dist);
			assertTrue(at.add(pull).distanceTo(CENTRE) < EPS);
		}
	}

	@Test
	void radiusAtMatchesRepeatedPulls() {
		for (double r0 : new double[] {0, 0.3, 2, 4, 5, 6}) {
			Vec3 at = CENTRE.add(new Vec3(0.6, 0.2, -0.77).normalize().scale(r0));
			for (int t = 0; t <= 60; t++) {
				assertEquals(at.distanceTo(CENTRE), WellGeometry.radiusAt(r0, t), 1.0E-6, "r0 " + r0 + " tick " + t);
				at = at.add(WellGeometry.pull(CENTRE, at));
			}
		}
		// From the edge it arrives in about a second and a half; from 5 well before the 60 ticks a tap lasts.
		assertEquals(31, WellGeometry.arrival(6));
		assertTrue(WellGeometry.arrival(5) <= 28);
		assertEquals(0, WellGeometry.arrival(0));
	}

	@Test
	void spiralFollowsTheSameLawAndEndsInTheCore() {
		double r0 = 5.5;
		double prevAngle = Double.NEGATIVE_INFINITY;
		for (int t = 0; t <= 40; t++) {
			Vec3 p = WellGeometry.spiral(CENTRE, r0, 1.2, 1.5, t);
			double flat = Math.hypot(p.x - CENTRE.x, p.z - CENTRE.z);
			assertEquals(WellGeometry.radiusAt(r0, t), flat, 1.0E-9, "tick " + t + ": distance law");
			assertTrue(Math.abs(p.y - CENTRE.y) <= 1.5 + EPS, "height stays within its lift");
			double a = WellGeometry.angle(r0, 1.2, t);
			assertTrue(a > prevAngle, "winds one way");
			prevAngle = a;
		}
		Vec3 start = WellGeometry.spiral(CENTRE, r0, 0, 1.5, 0);
		assertEquals(CENTRE.add(r0, 1.5, 0).distanceTo(start), 0, 1.0E-9, "starts where it is put");
		Vec3 end = WellGeometry.spiral(CENTRE, r0, 0, 1.5, WellGeometry.arrival(r0));
		assertEquals(0, end.distanceTo(CENTRE), 1.0E-9, "ends in the core");
	}

	@Test
	void behindCoreIsTheFarSideNearTheViewLine() {
		Vec3 eye = CENTRE.add(0, 0.5, -8);
		Vec3 ahead = CENTRE.subtract(eye).normalize();
		double within = 1.1 * 0.8;
		// Straight behind the centre, and a little off the line: hidden.
		assertTrue(WellGeometry.behindCore(eye, CENTRE, CENTRE.add(ahead.scale(1.5)), within));
		assertTrue(WellGeometry.behindCore(eye, CENTRE, CENTRE.add(ahead.scale(1.5)).add(0.6, 0, 0), within));
		// In front of the centre (the near side of the disc), or too far off the line: drawn in full.
		assertFalse(WellGeometry.behindCore(eye, CENTRE, CENTRE.subtract(ahead.scale(1.5)), within), "near side");
		assertFalse(WellGeometry.behindCore(eye, CENTRE, CENTRE.add(ahead.scale(1.5)).add(1.2, 0, 0), within), "beside the core");
		assertFalse(WellGeometry.behindCore(CENTRE, CENTRE, CENTRE.add(1, 0, 0), within), "eye in the core");
	}

	@Test
	void lifeAndReach() {
		assertEquals(80, WellGeometry.life(0));
		assertEquals(120, WellGeometry.life(0.5F));
		assertEquals(160, WellGeometry.life(1));
		assertEquals(160, WellGeometry.life(3), "charge clamped");
		assertEquals(6, WellGeometry.radius(0), 1e-9);
		assertEquals(12, WellGeometry.radius(1), 1e-9, "a full charge pulls from twice as far");
		assertTrue(WellGeometry.inReach(CENTRE, CENTRE.add(12, 0, 0), WellGeometry.radius(1)));
		assertTrue(WellGeometry.inReach(CENTRE, CENTRE.add(6, 0, 0)));
		assertFalse(WellGeometry.inReach(CENTRE, CENTRE.add(6.01, 0, 0)));
		assertFalse(WellGeometry.inReach(CENTRE, CENTRE.add(4.3, 4.3, 0)));
	}
}
