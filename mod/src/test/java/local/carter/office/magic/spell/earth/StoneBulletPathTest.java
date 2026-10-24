package local.carter.office.magic.spell.earth;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import local.carter.office.magic.spell.ProjectileSpec;
import local.carter.office.magic.spell.Projectiles;

import net.minecraft.world.phys.Vec3;

class StoneBulletPathTest {
	private static final ProjectileSpec SPEC = StoneBullet.spec(new Vec3(16.5, 5.62, 4.5), new Vec3(0, 0, 1));

	@Test
	void specIsTheBriefs() {
		assertEquals(2.2, SPEC.velocity().length(), 1e-9);
		assertEquals(0.02, SPEC.gravity());
		assertEquals(0.35, SPEC.radius());
		assertEquals(30, SPEC.maxTicks());
		assertTrue(!SPEC.pierce());
	}

	@Test
	void pathMatchesTheServerStepAtWholeTicks() {
		for (int t = 0; t <= StoneBullet.MAX_TICKS; t++) {
			Vec3 a = StoneBullet.path(SPEC, t);
			Vec3 b = Projectiles.step(SPEC, t);
			assertEquals(0, a.distanceTo(b), 1e-9, "tick " + t);
		}
	}

	@Test
	void pathArcsDownAndIsSmoothBetweenTicks() {
		// Thrown flat, it drops 0.02 × t(t−1)/2: 1.1 blocks by tick 11, about 8.7 by the end of its range.
		assertEquals(5.62 - 1.1, StoneBullet.path(SPEC, 11).y, 1e-9);
		assertEquals(5.62 - 0.02 * 30 * 29 / 2.0, StoneBullet.path(SPEC, 30).y, 1e-9);
		for (int t = 0; t < 30; t++) {
			Vec3 a = StoneBullet.path(SPEC, t);
			Vec3 m = StoneBullet.path(SPEC, t + 0.5);
			Vec3 b = StoneBullet.path(SPEC, t + 1);
			assertTrue(m.z > a.z && m.z < b.z, "between ticks it moves forward");
			// Smooth: half way it sits within a hair of the straight step the server clips (gravity × 1/8 off the chord).
			assertEquals((a.y + b.y) / 2, m.y, 0.02 / 8 + 1e-9, "tick " + t + " half way");
			Vec3 h = StoneBullet.heading(SPEC, t + 1);
			assertEquals(1, h.length(), 1e-9);
			assertTrue(h.y <= 0, "flat throw: heading points down after launch");
		}
	}
}
