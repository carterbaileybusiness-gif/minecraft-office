package local.carter.office.magic.spell.water;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import net.minecraft.world.phys.Vec3;

class BubbleShieldGeometryTest {
	private static final Vec3 C = new Vec3(0.5, 65.9, 0.5);

	@Test
	void durationAndCentre() {
		assertEquals(120, BubbleShieldGeometry.duration(0));
		assertEquals(180, BubbleShieldGeometry.duration(0.5F));
		assertEquals(240, BubbleShieldGeometry.duration(1));
		assertEquals(2 * BubbleShieldGeometry.radius(0), BubbleShieldGeometry.radius(1), 1.0E-9, "a full charge's bubble is twice as wide");
		assertEquals(0, new Vec3(0.5, 65.9, 0.5).distanceTo(BubbleShieldGeometry.centre(new Vec3(0.5, 65, 0.5), 1.8)), 1.0E-9);
	}

	@Test
	void stopsWhatIsInsideOrAboutToEnter() {
		assertNotNull(BubbleShieldGeometry.entry(C, C.add(0, 0, 2), Vec3.ZERO), "inside, even standing still");
		// An arrow 6 out flying straight in at 3 a tick: not yet; at 4 out, its next move crosses the surface.
		assertFalse(BubbleShieldGeometry.threatens(C, C.add(0, 0, 6), new Vec3(0, 0, -3)));
		Vec3 hit = BubbleShieldGeometry.entry(C, C.add(0, 0, 4.5), new Vec3(0, 0, -3));
		assertNotNull(hit);
		assertEquals(BubbleShieldGeometry.RADIUS, hit.distanceTo(C), 1.0E-9, "on the surface");
		assertEquals(C.z + BubbleShieldGeometry.RADIUS, hit.z, 1.0E-9, "where it crosses in");
		// A fast arrow that would pass right through in one tick is still caught.
		assertTrue(BubbleShieldGeometry.threatens(C, C.add(0, 0, 4), new Vec3(0, 0, -8)));
	}

	@Test
	void sparesWhatMissesOrFliesAway() {
		assertNull(BubbleShieldGeometry.entry(C, C.add(0, 0, 4), new Vec3(0, 0, 3)), "flying away");
		assertNull(BubbleShieldGeometry.entry(C, C.add(0, 3, 4), new Vec3(0, 0, -3)), "passing over the top");
		assertNull(BubbleShieldGeometry.entry(C, C.add(0, 0, 4), new Vec3(0, 0, -1)), "not there yet");
		assertNull(BubbleShieldGeometry.entry(C, C.add(0, 0, 4), Vec3.ZERO), "still, outside");
	}
}
