package local.carter.office.magic.spell.fire;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.Test;

import local.carter.office.magic.spell.ProjectileSpec;
import local.carter.office.magic.spell.Projectiles;

import net.minecraft.world.phys.Vec3;

class BarrageGeometryTest {
	private static final Vec3[] POINTS = {new Vec3(16.5, 4, 16.5), new Vec3(-120.3, 71, 44.9), new Vec3(3, -20, -3)};
	private static final Vec3[] DIRS = {new Vec3(0, 0, 1), new Vec3(1, -0.4, 0), new Vec3(-0.6, 0.2, -0.8), new Vec3(0, -1, 0)};

	@Test
	void countFollowsCharge() {
		assertEquals(3, BarrageGeometry.count(0F));
		assertEquals(4, BarrageGeometry.count(0.5F));
		assertEquals(5, BarrageGeometry.count(1F));
	}

	@Test
	void impactPointsForASeedAreTheSameEveryCallAndWithinSpreadOfTheAim() {
		for (Vec3 point : POINTS) {
			for (long seed = -500; seed < 500; seed += 7) {
				List<Vec3> a = BarrageGeometry.aims(point, seed, 5);
				List<Vec3> b = BarrageGeometry.aims(point, seed, 5);
				assertEquals(a, b, "same seed, same points");
				assertEquals(5, a.size());
				for (Vec3 p : a) {
					assertTrue(p.distanceTo(point) <= BarrageGeometry.SPREAD + 1e-9, "within 1.5 of the aim: " + p.distanceTo(point));
				}
				// A volley built from the same numbers aims at exactly those points.
				List<BarrageGeometry.Ball> v = BarrageGeometry.volley(point, DIRS[0], seed, 5, new float[0]);
				for (int i = 0; i < 5; i++) assertEquals(a.get(i), v.get(i).aim());
			}
		}
		// The spread really is spread (and seeded): different seeds give different points.
		assertNotEquals(BarrageGeometry.aims(POINTS[0], 1, 3), BarrageGeometry.aims(POINTS[0], 2, 3));
	}

	@Test
	void eachFireballFliesAtSpeedThroughItsAim() {
		for (Vec3 point : POINTS) {
			for (Vec3 dir : DIRS) {
				for (float lift : new float[] {1F, 0.5F, 0.15F}) {
					for (BarrageGeometry.Ball b : BarrageGeometry.volley(point, dir, 42, 5, new float[] {lift, lift, lift, lift, lift})) {
						ProjectileSpec s = b.spec();
						assertEquals(BarrageGeometry.GRAVITY, s.gravity());
						// Speed 1.8 (less the half tick of gravity the stepped path needs).
						assertEquals(BarrageGeometry.SPEED, s.velocity().length(), 0.02, "speed");
						assertTrue(s.origin().y > b.aim().y, "it drops from above");
						double t = BarrageGeometry.flightTicks(s.origin(), b.aim());
						assertTrue(t > 0 && t < BarrageGeometry.MAX_TICKS, "flight " + t);
						assertTrue(BarrageGeometry.at(s, t).distanceTo(b.aim()) < 1e-6, "passes through its aim");
						// The fractional path agrees with the server's stepped one at whole ticks.
						for (int k = 0; k < 20; k++) assertTrue(BarrageGeometry.at(s, k).distanceTo(Projectiles.step(s, k)) < 1e-9);
						assertEquals(b.at(3), Projectiles.step(s, 3));
					}
				}
			}
		}
	}

	@Test
	void fireballsLeaveEveryFourTicksFromTheSkyBeyondTheAim() {
		List<BarrageGeometry.Ball> v = BarrageGeometry.volley(POINTS[0], new Vec3(0, 0, 1), 9, 4, new float[] {1F, 0.5F});
		for (int i = 0; i < v.size(); i++) assertEquals(4 * i, v.get(i).launchTick());
		Vec3 drop = v.get(0).origin().subtract(v.get(0).aim());
		assertEquals(BarrageGeometry.HEIGHT, drop.y, 1e-9);
		assertEquals(BarrageGeometry.BEYOND, drop.z, 1e-9, "beyond the aim, away from the caster (facing +Z)");
		// A lift shortens the drop; a missing lift is the whole drop.
		assertEquals(BarrageGeometry.HEIGHT / 2, v.get(1).origin().y - v.get(1).aim().y, 1e-9);
		assertEquals(BarrageGeometry.HEIGHT, v.get(3).origin().y - v.get(3).aim().y, 1e-9);
		assertEquals(1.0, BarrageGeometry.falloff(0));
		assertEquals(0.0, BarrageGeometry.falloff(2.5));
		assertEquals(0.6, BarrageGeometry.falloff(1), 1e-9);
	}
}
