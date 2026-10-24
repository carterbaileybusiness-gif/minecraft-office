package local.carter.office.client.magic;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import local.carter.office.client.magic.fx.ChargeCircles;
import local.carter.office.client.magic.fx.MagicCircleRenderer;

/** When a kit circle gets the caster's first-person look (the pure part; caster and camera mode are checked in game). */
class FirstPersonCircleTest {
	/** An eye at 0, 65.62, 0 looking along +z. */
	private static boolean inFace(double cx, double cy, double cz, double nx, double ny, double nz) {
		return MagicCircleRenderer.inCastersFace(cx, cy, cz, nx, ny, nz, 0, 65.62, 0);
	}

	@Test
	void castCircleHeldOutFacingTheCasterIsInTheirFace() {
		assertTrue(inFace(0, 65.62, 1.5, 0, 0, -1), "1.5 ahead, facing them");
		assertTrue(inFace(0, 65.47, 1.7, 0, 0, -1), "DefaultFx's cast circle: 1.7 ahead, a little low");
		assertTrue(inFace(0, 65.62, 1.5, 0, 0, 1), "its back turned: drawn both sides, still in the way");
		assertTrue(inFace(0.4, 65.9, 2.5, 0.2, -0.1, -1), "a little off and tilted");
	}

	@Test
	void farSidewaysOrUnderfootCirclesAreNot() {
		assertFalse(inFace(0, 65.62, 3.2, 0, 0, -1), "beyond 3 blocks");
		assertFalse(inFace(0, 65.62, 1.5, 1, 0, 0), "edge on to the eye");
		assertFalse(inFace(0, 64.06, 0, 0, 1, 0), "a ground circle at the feet");
		assertFalse(inFace(0, 64.4, 1.5, 0, 1, 0), "a ground circle just ahead");
		assertFalse(inFace(Double.NaN, 65, 1, 0, 0, -1), "NaN never counts");
	}

	@Test
	void eyeInTheMiddleOrNoNormalCountsAsInTheFace() {
		assertTrue(inFace(0, 65.62, 0, 0, 0, -1));
		assertTrue(inFace(0, 65.62, 1, 0, 0, 0));
	}

	@Test
	void sameLookAsTheChargeCircle() {
		assertEquals(0.3F, MagicCircleRenderer.FIRST_PERSON_ALPHA);
		assertEquals(ChargeCircles.FIRST_PERSON_ALPHA, MagicCircleRenderer.FIRST_PERSON_ALPHA);
		assertEquals(0.35F * MagicCircleRenderer.ringRadius(MagicCircleRenderer.MAX_RINGS), ChargeCircles.FIRST_PERSON_CLEAR, 1.0E-6F);
	}
}
