package local.carter.office.client.screens;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.Test;

import local.carter.office.client.platform.Platform.Rect;

/** Snapping a dropped Office display, and the touching rule shared with the helper (vectors V1-V6). */
class DisplaySnapTest {
	private static final Rect MAIN = new Rect(0, 0, 1440, 900);

	@Test
	void sharedTouchingVectors() {
		List<Rect> main = List.of(MAIN);
		assertTrue(DisplaySnap.valid(new Rect(1440, 0, 1920, 1080), main), "V1 right of main");
		assertFalse(DisplaySnap.valid(new Rect(1440, 900, 1920, 1080), main), "V2 corner only");
		assertFalse(DisplaySnap.valid(new Rect(1000, 0, 1920, 1080), main), "V3 overlap");
		assertFalse(DisplaySnap.valid(new Rect(1500, 0, 1920, 1080), main), "V4 detached");
		assertTrue(DisplaySnap.valid(new Rect(-1920, -180, 1920, 1080), main), "V5 left of main, raised");
		assertTrue(DisplaySnap.valid(new Rect(0, -1080, 1920, 1080), main), "V6 above main");
	}

	@Test
	void aDropBesideMainLinesUp() {
		assertArrayEquals(new int[] {1440, 0}, DisplaySnap.snap(new Rect(1500, 40, 1920, 1080), List.of(MAIN)),
				"within 6 %: the tops line up, edge to edge");
	}

	@Test
	void aDropOverlappingMainGoesToTheNearestTouchingSpot() {
		int[] at = DisplaySnap.snap(new Rect(1000, 0, 1920, 1080), List.of(MAIN));
		assertArrayEquals(new int[] {1440, 0}, at);
		Rect r = new Rect(at[0], at[1], 1920, 1080);
		assertFalse(DisplaySnap.overlaps(r, MAIN));
		// dropped mostly below main: below it, never overlapping
		int[] below = DisplaySnap.snap(new Rect(300, 700, 1920, 1080), List.of(MAIN));
		assertArrayEquals(new int[] {300, 900}, below);
	}

	@Test
	void resultsAlwaysShareTenPercentOfAnEdge() {
		List<Rect> fixed = List.of(MAIN, new Rect(1440, 0, 1920, 1080));
		for (int x = -5000; x <= 5000; x += 731) {
			for (int y = -3000; y <= 3000; y += 577) {
				int[] at = DisplaySnap.snap(new Rect(x, y, 1920, 1080), fixed);
				assertNotNull(at, x + "," + y);
				Rect r = new Rect(at[0], at[1], 1920, 1080);
				boolean shares = false;
				for (Rect f : fixed) {
					assertFalse(DisplaySnap.overlaps(r, f), r + " overlaps " + f);
					long v = Math.min(r.y() + r.h(), f.y() + f.h()) - Math.max(r.y(), f.y());
					long h = Math.min(r.x() + r.w(), f.x() + f.w()) - Math.max(r.x(), f.x());
					boolean side = (r.x() + r.w() == f.x() || f.x() + f.w() == r.x()) && v >= 108;
					boolean top = (r.y() + r.h() == f.y() || f.y() + f.h() == r.y()) && h >= 192;
					shares |= side || top;
				}
				assertTrue(shares, r + " shares at least 10 % of an edge");
			}
		}
	}

	@Test
	void noRoomMeansNoSpot() {
		assertNull(DisplaySnap.snap(new Rect(0, 0, 10, 10), List.of()), "nothing to touch");
	}

	@Test
	void layoutsMustReachARealMonitor() {
		Rect d1 = new Rect(1440, 0, 1920, 1080);
		Rect d2 = new Rect(3360, 0, 1920, 1080);
		assertTrue(DisplaySnap.layoutValid(List.of(MAIN), List.of(d1, d2)), "a chain to main");
		assertFalse(DisplaySnap.layoutValid(List.of(MAIN), List.of(new Rect(5000, 0, 1920, 1080), new Rect(6920, 0, 1920, 1080))),
				"two Office displays touching only each other");
		assertFalse(DisplaySnap.layoutValid(List.of(MAIN), List.of(d1, new Rect(2000, 0, 1920, 1080))), "overlapping each other");
	}

	/** Shared vector V7 (the helper's ArrangementTests has the same): every screen stays joined to the main display. */
	@Test
	void sharedVectorV7MonitorAttachedOnlyThroughOffice1() {
		Rect lg = new Rect(3360, 0, 1920, 1080); // main | Office 1 | LG
		assertTrue(DisplaySnap.layoutValid(List.of(MAIN, lg), List.of(new Rect(1440, 0, 1920, 1080))), "V7 as it is: one piece");
		assertFalse(DisplaySnap.layoutValid(List.of(MAIN, lg), List.of(new Rect(-1920, 0, 1920, 1080))),
				"V7: Office 1 moved left of main cuts LG off");
		assertFalse(DisplaySnap.layoutValid(List.of(MAIN, lg), List.of(new Rect(5280, 0, 1920, 1080))),
				"Office 1 joined only to the cut-off LG");
		// an Office display that is the main display seeds the piece
		Rect officeMain = MAIN;
		Rect left = new Rect(-1920, 0, 1920, 1080);
		assertTrue(DisplaySnap.layoutValid(List.of(left), List.of(officeMain, new Rect(0, -1080, 1920, 1080)), 0),
				"around an Office main display");
		assertFalse(DisplaySnap.layoutValid(List.of(left), List.of(officeMain, new Rect(1440, 900, 1920, 1080)), 0),
				"only a corner of the Office main display");
	}

	@Test
	void spotsAreNearestFirstAndStartWithSnap() {
		Rect dropped = new Rect(1500, 40, 1920, 1080);
		List<int[]> spots = DisplaySnap.spots(dropped, List.of(MAIN));
		assertArrayEquals(DisplaySnap.snap(dropped, List.of(MAIN)), spots.get(0));
		double last = -1;
		for (int[] s : spots) {
			double d = Math.hypot(s[0] - dropped.x(), s[1] - dropped.y());
			assertTrue(d >= last, "nearest first");
			last = d;
			assertFalse(DisplaySnap.overlaps(new Rect(s[0], s[1], 1920, 1080), MAIN));
		}
	}
}
