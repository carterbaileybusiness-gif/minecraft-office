package local.carter.office.magic.spell.lightning;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.function.Predicate;

import org.junit.jupiter.api.Test;

import net.minecraft.world.phys.Vec3;

class BlinkPathTest {
	/** A 0.6 × 1.8 body on a world of unit cubes: solid where {@code solid(x, y, z)} says so. */
	private interface Solid {
		boolean at(int x, int y, int z);
	}

	private static Predicate<Vec3> body(Solid solid) {
		return feet -> {
			double x0 = feet.x - 0.3, x1 = feet.x + 0.3, y0 = feet.y, y1 = feet.y + 1.8, z0 = feet.z - 0.3, z1 = feet.z + 0.3;
			for (int x = (int) Math.floor(x0); x <= (int) Math.floor(x1 - 1.0E-7); x++)
				for (int y = (int) Math.floor(y0); y <= (int) Math.floor(y1 - 1.0E-7); y++)
					for (int z = (int) Math.floor(z0); z <= (int) Math.floor(z1 - 1.0E-7); z++)
						if (solid.at(x, y, z)) return false;
			return true;
		};
	}

	private static final Solid FLOOR = (x, y, z) -> y < 0;
	private static final Vec3 FEET = new Vec3(0.5, 0, 0.5);

	@Test
	void goesTheWholeWayInTheOpen() {
		BlinkPath.Walk w = BlinkPath.walk(FEET, new Vec3(0, 0, 1), 10, body(FLOOR));
		assertEquals(10, w.end().z - FEET.z, 1.0E-9);
		assertEquals(0, w.end().y, 1.0E-9);
		assertEquals(10, w.travelled(), 1.0E-9);
		assertFalse(w.blocked());
	}

	@Test
	void stopsBeforeAWallAndNeverInsideIt() {
		// A wall 3 high in the block row z = 6.
		Solid wall = (x, y, z) -> y < 0 || (z == 6 && y < 3);
		BlinkPath.Walk w = BlinkPath.walk(FEET, new Vec3(0, 0, 1), 10, body(wall));
		assertTrue(w.blocked());
		assertTrue(w.end().z + 0.3 <= 6 + 1.0E-9, "the body stops at the wall's face: " + w.end());
		assertTrue(w.end().z - FEET.z > 4.5, "it went right up to it: " + w.end());
		assertTrue(body(wall).test(w.end()), "the body fits where it ends");
	}

	@Test
	void neverPassesThroughAThinWall() {
		// One block thick, 2 high: the far side is open, but the walk must not jump it.
		Solid wall = (x, y, z) -> y < 0 || (z == 3 && y < 2);
		BlinkPath.Walk w = BlinkPath.walk(FEET, new Vec3(0, 0, 1), 10, body(wall));
		assertTrue(w.end().z < 3, "stopped this side: " + w.end());
	}

	@Test
	void slidesAlongGroundItIsAimedInto() {
		// Looking 20° down at the floor: it skims along it instead of stopping dead.
		Vec3 down = new Vec3(0, -Math.sin(Math.toRadians(20)), Math.cos(Math.toRadians(20)));
		BlinkPath.Walk w = BlinkPath.walk(FEET, down, 10, body(FLOOR));
		assertTrue(w.end().z - FEET.z > 8, "went on along the ground: " + w.end());
		assertTrue(w.end().y >= 0 && w.end().y <= BlinkPath.RISE_STEP + 1.0E-9, "on the ground: " + w.end());
	}

	@Test
	void stepsOverAOneBlockBumpButNotATwoBlockWall() {
		Solid bump = (x, y, z) -> y < 0 || (z == 4 && y == 0);
		BlinkPath.Walk over = BlinkPath.walk(FEET, new Vec3(0, 0, 1), 10, body(bump));
		assertEquals(10, over.end().z - FEET.z, 1.0E-9, "stepped over");
		Solid wall = (x, y, z) -> y < 0 || (z == 4 && y < 2);
		assertTrue(BlinkPath.walk(FEET, new Vec3(0, 0, 1), 10, body(wall)).end().z < 4, "a two-block wall stops it");
	}

	@Test
	void staysInATwoHighTunnel() {
		// Floor at y < 0, ceiling at y >= 2, the tunnel runs along +Z.
		Solid tunnel = (x, y, z) -> y < 0 || y >= 2 || x != 0;
		BlinkPath.Walk w = BlinkPath.walk(FEET, new Vec3(0, 0, 1), 10, body(tunnel));
		assertEquals(10, w.end().z - FEET.z, 1.0E-9);
		assertTrue(body(tunnel).test(w.end()));
		// Aimed up at the ceiling it stops where the body still fits.
		Vec3 up = new Vec3(0, Math.sin(Math.toRadians(25)), Math.cos(Math.toRadians(25)));
		BlinkPath.Walk u = BlinkPath.walk(FEET, up, 10, body(tunnel));
		assertTrue(body(tunnel).test(u.end()) && u.end().y + 1.8 <= 2 + 1.0E-9, "under the ceiling: " + u.end());
	}

	@Test
	void endsWhereTheBodyFitsOnRoughGround() {
		java.util.Random r = new java.util.Random(9);
		for (int n = 0; n < 200; n++) {
			long seed = r.nextLong();
			Solid rough = (x, y, z) -> y < -1 || (y < 3 && new java.util.Random(seed ^ (x * 73856093L) ^ (y * 19349663L) ^ (z * 83492791L)).nextInt(9) == 0);
			Predicate<Vec3> fits = body(rough);
			if (!fits.test(FEET)) continue;
			Vec3 dir = new Vec3(r.nextGaussian(), r.nextGaussian() * 0.3, r.nextGaussian());
			BlinkPath.Walk w = BlinkPath.walk(FEET, dir, 14, fits);
			assertTrue(fits.test(w.end()), "ends where it fits: " + w.end());
			assertTrue(w.travelled() <= 14 + 1.0E-9, "never further than asked");
		}
	}

	@Test
	void noDirectionGoesNowhere() {
		BlinkPath.Walk w = BlinkPath.walk(FEET, Vec3.ZERO, 10, p -> true);
		assertEquals(FEET, w.end());
		assertEquals(0, w.travelled());
	}
}
