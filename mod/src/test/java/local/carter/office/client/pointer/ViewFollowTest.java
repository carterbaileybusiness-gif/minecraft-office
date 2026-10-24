package local.carter.office.client.pointer;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import local.carter.office.core.Vec3d;

/** Work mode: the view follows the cursor — aimed exactly, smoothed to settle within ~100 ms, the short way round. */
class ViewFollowTest {
	@Test
	void aimsLikeMinecraft() {
		Vec3d eye = new Vec3d(0, 65.6, 0);
		assertArrayEquals(new float[] {0f, 0f}, ViewFollow.aim(eye, new Vec3d(0, 65.6, 5)), 1e-4f, "south, level");
		float[] north = ViewFollow.aim(eye, new Vec3d(0, 65.6, -5));
		assertEquals(0f, ViewFollow.wrap(north[0] - 180f), 1e-4f, "north (±180)");
		assertArrayEquals(new float[] {90f, 0f}, ViewFollow.aim(eye, new Vec3d(-5, 65.6, 0)), 1e-4f, "west");
		assertEquals(-45f, ViewFollow.aim(eye, new Vec3d(0, 70.6, 5))[1], 1e-4f, "up is negative pitch");
	}

	@Test
	void settlesWithinAHundredMillisecondsWithoutOvershoot() {
		float yaw = 0, pitch = 0;
		float last = 0;
		for (int ms = 0; ms < 100; ms += 16) { // 60 fps frames
			float[] next = ViewFollow.step(yaw, pitch, 20f, -10f, 16);
			assertTrue(next[0] >= last && next[0] <= 20f, "monotonic, no overshoot: " + next[0]);
			last = next[0];
			yaw = next[0];
			pitch = next[1];
		}
		assertTrue(Math.abs(20f - yaw) < 1.0f, "within 5 % of a 20° jump after ~100 ms: " + yaw);
		assertTrue(Math.abs(-10f - pitch) < 0.5f, "pitch too: " + pitch);
		float[] still = ViewFollow.step(20f, -10f, 20f, -10f, 16);
		assertArrayEquals(new float[] {20f, -10f}, still, 0f, "on target: no jitter");
	}

	@Test
	void yawTakesTheShortWayRound() {
		float[] next = ViewFollow.step(170f, 0f, -170f, 0f, 1_000);
		assertEquals(190f, next[0], 1e-3f, "170 → -170 goes through 180, not back through 0");
		assertEquals(-170f, ViewFollow.wrap(190f), 1e-6f);
		assertEquals(180f, ViewFollow.wrap(-180f), 1e-6f);
	}
}
