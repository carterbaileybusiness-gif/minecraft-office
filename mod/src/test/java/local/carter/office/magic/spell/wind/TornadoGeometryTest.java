package local.carter.office.magic.spell.wind;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import net.minecraft.world.phys.Vec3;

class TornadoGeometryTest {
	private static final double EPS = 1.0E-9;
	private static final Vec3 ORIGIN = new Vec3(10.5, 64, 20.5);

	private static void near(Vec3 expected, Vec3 actual) {
		assertEquals(expected.x, actual.x, EPS, "x of " + actual);
		assertEquals(expected.y, actual.y, EPS, "y of " + actual);
		assertEquals(expected.z, actual.z, EPS, "z of " + actual);
	}

	@Test
	void positionAtTickIsOriginPlusDirTimesSpeed() {
		Vec3 diagonal = new Vec3(1, 0, 1).normalize();
		for (Vec3 dir : new Vec3[] {new Vec3(0, 0, 1), new Vec3(-1, 0, 0), diagonal}) {
			TornadoGeometry g = new TornadoGeometry(ORIGIN, dir, 160);
			for (int t = 0; t <= 160; t++) near(ORIGIN.add(dir.scale(0.15 * t)), g.at(t));
			near(ORIGIN.add(dir.scale(0.15 * 12.5)), g.at(12.5));
		}
	}

	@Test
	void pathIsLevelAndHeldAtItsEnds() {
		// A tilted look travels level, at unit speed share.
		TornadoGeometry g = new TornadoGeometry(ORIGIN, new Vec3(0, -3, 4), 120);
		near(new Vec3(0, 0, 1), g.dir());
		near(ORIGIN.add(0, 0, 0.15 * 50), g.at(50));
		near(ORIGIN, g.at(-5));
		near(g.at(120), g.at(500));
	}

	@Test
	void lifeIs120Plus60PerCharge() {
		assertEquals(120, TornadoGeometry.life(0F));
		assertEquals(150, TornadoGeometry.life(0.5F));
		assertEquals(180, TornadoGeometry.life(1F));
		assertEquals(180, TornadoGeometry.life(3F));
		assertEquals(8, TornadoGeometry.radius(1F), 1e-9, "a full charge's reach is twice a tap's");
	}

	@Test
	void reachIsAColumnOfRadiusFour() {
		Vec3 base = new Vec3(0, 64, 0);
		assertTrue(TornadoGeometry.inReach(base, new Vec3(4, 64, 0)));
		assertTrue(TornadoGeometry.inReach(base, new Vec3(2, 73, 2)));
		assertTrue(TornadoGeometry.inReach(base, new Vec3(0, 63, 0)));
		assertFalse(TornadoGeometry.inReach(base, new Vec3(4.01, 64, 0)));
		assertFalse(TornadoGeometry.inReach(base, new Vec3(0, 73.01, 0)));
		assertFalse(TornadoGeometry.inReach(base, new Vec3(0, 62.9, 0)));
	}

	@Test
	void pushSpinsPullsInAndLifts() {
		Vec3 dir = new Vec3(0, 0, 1);
		TornadoGeometry g = new TornadoGeometry(ORIGIN, dir, 120);
		Vec3 base = g.at(0);
		// 3 east of the axis: in toward it 0.15 (−X), round it 0.35 (anticlockwise from above: −Z), up 0.25, plus travel +Z 0.15.
		Vec3 v = g.push(base, base.add(3, 1, 0));
		near(new Vec3(-0.15, 0.25, -0.35 + 0.15), v);
		// The tangent is level, unit, and at right angles to the offset, everywhere round.
		for (int i = 0; i < 16; i++) {
			double a = Math.PI * 2 * i / 16;
			Vec3 off = new Vec3(Math.cos(a) * 2, 0, Math.sin(a) * 2);
			Vec3 t = TornadoGeometry.tangent(off.x, off.z);
			assertEquals(1, t.length(), EPS);
			assertEquals(0, t.y, EPS);
			assertEquals(0, t.dot(off), EPS);
			// Anticlockwise seen from above (a positive turn about +Y): offset × tangent points up.
			assertTrue(off.cross(t).y > 0);
		}
		// Inside the core nothing pulls in.
		Vec3 core = g.push(base, base.add(0.3, 0, 0));
		assertEquals(0, core.x, EPS);
		assertEquals(0.25, core.y, EPS);
	}
}
