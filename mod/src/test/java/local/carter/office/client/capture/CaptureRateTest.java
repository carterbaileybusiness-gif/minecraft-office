package local.carter.office.client.capture;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import local.carter.office.core.HologramGeometry.Frame;
import local.carter.office.core.Vec3d;

class CaptureRateTest {
	@Test
	void fiveWhileVisibleZeroWhenNotThirtyWhileZoomed() {
		assertEquals(5, CaptureRate.fps(true, false));
		assertEquals(0, CaptureRate.fps(false, false));
		assertEquals(30, CaptureRate.fps(true, true));
		assertEquals(30, CaptureRate.fps(false, true), "zoomed wins (the camera is inside the panel's frustum edge)");
	}

	@Test
	void onlyTheFrontWithinViewDistanceCounts() {
		// yaw 0: front faces +Z (normal = right x up).
		Frame f = new Frame(new Vec3d(0, 64, 0), new Vec3d(1, 0, 0), new Vec3d(0, 1, 0), 2, 1.125);
		assertTrue(CaptureRate.seesFront(f, new Vec3d(0, 64, 5)));
		assertTrue(CaptureRate.seesFront(f, new Vec3d(3, 65, 0.5)));
		assertFalse(CaptureRate.seesFront(f, new Vec3d(0, 64, -5)), "behind: only the faint back sheet shows");
		assertFalse(CaptureRate.seesFront(f, new Vec3d(0, 64, 65)), "beyond the 64-block view distance");
		assertTrue(CaptureRate.seesFront(f, new Vec3d(0, 64, 63)));
	}
}
