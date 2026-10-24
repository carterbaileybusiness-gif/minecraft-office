package local.carter.office.client.capture;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import local.carter.office.core.HologramGeometry.Frame;
import local.carter.office.core.Vec3d;

class CaptureRateTest {
	@Test
	void thirtyWhileVisibleZeroWhenNotSixtyWhileWorking() {
		// own screens must feel immediate (spec 5.10 round 2): 5 fps measured 143 ms median, 30 fps 38 ms, 60 fps 30 ms
		assertEquals(30, CaptureRate.fps(true, false));
		assertEquals(0, CaptureRate.fps(false, false));
		assertEquals(60, CaptureRate.fps(true, true));
		assertEquals(60, CaptureRate.fps(false, true), "working / zoomed wins (the camera is inside the panel's frustum edge)");
	}

	@Test
	void visibilityChangesOnlyAfterLastingOneSecond() {
		CaptureRate.Visibility v = new CaptureRate.Visibility();
		assertTrue(v.update(true, 0), "first look counts at once");
		// A camera sweep: away for 900 ms, back: never stopped.
		assertTrue(v.update(false, 100));
		assertTrue(v.update(false, 999));
		assertTrue(v.update(true, 1000));
		// Away again: the clock restarts, and only a full second away hides it.
		assertTrue(v.update(false, 1100));
		assertTrue(v.update(false, 2099));
		assertFalse(v.update(false, 2100));
		// Coming back also needs a second (no CAPTURE for a glance).
		assertFalse(v.update(true, 2200));
		assertFalse(v.update(false, 2300));
		assertFalse(v.update(true, 2400));
		assertTrue(v.update(true, 3400));

		CaptureRate.Visibility hidden = new CaptureRate.Visibility();
		assertFalse(hidden.update(false, 0), "first look counts at once (not visible: no capture)");
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
