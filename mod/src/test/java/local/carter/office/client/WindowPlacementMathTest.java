package local.carter.office.client;

import static org.junit.jupiter.api.Assertions.assertEquals;

import local.carter.office.client.platform.Platform.Rect;
import local.carter.office.client.platform.Win32;
import org.junit.jupiter.api.Test;

/** Zoom placement: the outer window rect that puts a window's visible frame exactly over Minecraft (pure math). */
class WindowPlacementMathTest {
	@Test
	void addsWindows10InvisibleBordersBack() {
		// Typical Win10/11 window at 100%: 7 px invisible border left/right/bottom, none on top.
		Rect outer = new Rect(93, 100, 814, 607);
		Rect visible = new Rect(100, 100, 800, 600);
		assertEquals(new Rect(33, 40, 654, 407), Win32.outerFor(new Rect(40, 40, 640, 400), outer, visible));
	}

	@Test
	void scaledBordersAt150Percent() {
		Rect outer = new Rect(-11, 0, 1942, 1091); // 11 px borders at 150 %
		Rect visible = new Rect(0, 0, 1920, 1080);
		assertEquals(new Rect(989, 200, 1942, 1091), Win32.outerFor(new Rect(1000, 200, 1920, 1080), outer, visible));
	}

	@Test
	void noBordersOrUnknownGeometryMeansTheAreaItself() {
		Rect area = new Rect(5, 6, 700, 500);
		assertEquals(area, Win32.outerFor(area, new Rect(0, 0, 10, 10), new Rect(0, 0, 10, 10)));
		assertEquals(area, Win32.outerFor(area, null, new Rect(0, 0, 10, 10)));
		assertEquals(area, Win32.outerFor(area, new Rect(0, 0, 10, 10), null));
	}

	@Test
	void visibleLargerThanOuterNeverShrinksTheArea() {
		// mismatched coordinate spaces (e.g. a DPI-virtualized rect): clamp negative borders to zero
		assertEquals(new Rect(0, 0, 100, 100), Win32.outerFor(new Rect(0, 0, 100, 100), new Rect(10, 10, 50, 50), new Rect(0, 0, 200, 200)));
	}
}
