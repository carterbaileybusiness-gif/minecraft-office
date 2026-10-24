package local.carter.office.client.magic;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

import local.carter.office.client.magic.screen.SignLayout.Rect;
import local.carter.office.magic.core.WheelMath;

class WheelLayoutTest {
	/** The brief's sizes. */
	private static final int[][] SIZES = {{320, 240}, {427, 240}, {640, 360}, {1920, 1080}};
	/** Odd shapes, tiny to huge (GUI units). */
	private static final int[][] MORE = {{480, 270}, {854, 480}, {3840, 2160}, {300, 200}, {200, 150}, {1000, 240}, {320, 600},
			{240, 160}, {960, 540}, {2560, 1440}, {1280, 720}};

	private static int[][] all() {
		int[][] out = new int[SIZES.length + MORE.length][];
		System.arraycopy(SIZES, 0, out, 0, SIZES.length);
		System.arraycopy(MORE, 0, out, SIZES.length, MORE.length);
		return out;
	}

	@Test
	void outerRingInsideTheScreenWithMargin() {
		for (int[] wh : all()) {
			WheelLayout.Layout l = WheelLayout.layout(wh[0], wh[1]);
			String at = wh[0] + "x" + wh[1] + " " + l;
			for (float r : new float[] {l.outer(), l.hoverOuter()}) {
				assertTrue(l.cx() - r >= 8 && l.cx() + r <= wh[0] - 8, at + ": ring x inside with 8 px");
				assertTrue(l.cy() - r >= 8 && l.cy() + r <= wh[1] - 8, at + ": ring y inside with 8 px");
			}
		}
	}

	@Test
	void briefRadiiTimesTheScale() {
		for (int[] wh : all()) {
			WheelLayout.Layout l = WheelLayout.layout(wh[0], wh[1]);
			assertEquals(22f * l.scale(), l.inner(), 1e-3, wh[0] + "x" + wh[1]);
			assertEquals(70f * l.scale(), l.outer(), 1e-3, wh[0] + "x" + wh[1]);
		}
		assertEquals(1f, WheelLayout.layout(640, 360).scale(), 1e-4, "GUI 3 at 1080p: the brief's size");
		assertEquals(1f, WheelLayout.layout(480, 270).scale(), 1e-4, "GUI 4 at 1080p: the brief's size");
		assertTrue(WheelLayout.layout(320, 240).scale() > 0.9f, "the smallest auto GUI screen shrinks it only a little");
	}

	@Test
	void growsOnScreensLargeInGuiUnits() {
		assertEquals(3f, WheelLayout.layout(1920, 1080).scale(), 1e-4, "GUI 1 at 1080p: as large on the monitor as at GUI 3");
		assertEquals(1.5f, WheelLayout.layout(960, 540).scale(), 1e-4, "GUI 2 at 1080p");
		float physical1 = WheelLayout.layout(1920, 1080).outer() * 1, physical3 = WheelLayout.layout(640, 360).outer() * 3;
		assertEquals(physical3, physical1, 1e-3, "same size on the monitor at GUI 1 and GUI 3");
	}

	@Test
	void scalesDownToFitSmallScreens() {
		for (int[] wh : all()) {
			WheelLayout.Layout l = WheelLayout.layout(wh[0], wh[1]);
			assertTrue(l.outer() <= Math.min(wh[0], wh[1]) * 0.42f + 1e-3, wh[0] + "x" + wh[1] + ": outer ≤ 0.42 × min(w, h)");
			assertEquals(l.inner() / l.outer(), 22f / 70f, 1e-4, "the ring keeps its shape");
		}
		assertTrue(WheelLayout.layout(200, 150).scale() < 1f);
	}

	@Test
	void labelAnchorsNeverOverlapAndStayOnScreen() {
		for (int[] wh : all()) {
			WheelLayout.Layout l = WheelLayout.layout(wh[0], wh[1]);
			assertEquals(WheelLayout.SLICES, l.labels().size());
			for (int i = 0; i < WheelLayout.SLICES; i++) {
				Rect a = l.labels().get(i);
				String at = wh[0] + "x" + wh[1] + " label " + i + " " + a;
				assertTrue(a.w() > 0 && a.h() > 0, at);
				assertTrue(a.insideScreen(wh[0], wh[1], WheelLayout.MARGIN), at + ": on screen with margin");
				assertTrue(a.bottom() <= wh[1] - WheelLayout.HUD_RESERVE, at + ": above the hotbar, mana bar and its number");
				assertTrue(distance(l.cx(), l.cy(), a) >= l.hoverOuter() - 1e-3, at + ": clear of the hovered (1.15×) slice");
				for (int j = i + 1; j < WheelLayout.SLICES; j++) {
					assertFalse(a.overlaps(l.labels().get(j)), at + " overlaps label " + j + " " + l.labels().get(j));
				}
			}
		}
	}

	@Test
	void labelsSitBesideTheirSlice() {
		WheelLayout.Layout l = WheelLayout.layout(427, 240);
		for (int i = 0; i < WheelLayout.SLICES; i++) {
			Rect a = l.labels().get(i);
			int slice = WheelMath.sliceAt(a.cx() - l.cx(), a.cy() - l.cy(), WheelLayout.SLICES);
			assertEquals(i, slice, "label " + i + " " + a + " lies in the direction of its slice");
		}
		assertEquals(WheelLayout.Align.CENTRE, l.aligns().get(0));
		assertEquals(WheelLayout.Align.LEFT, l.aligns().get(1));
		assertEquals(WheelLayout.Align.LEFT, l.aligns().get(2));
		assertEquals(WheelLayout.Align.CENTRE, l.aligns().get(3));
		assertEquals(WheelLayout.Align.RIGHT, l.aligns().get(4));
		assertEquals(WheelLayout.Align.RIGHT, l.aligns().get(5));
	}

	@Test
	void iconsSitInTheMiddleOfTheirSlice() {
		for (int[] wh : all()) {
			WheelLayout.Layout l = WheelLayout.layout(wh[0], wh[1]);
			for (int i = 0; i < WheelLayout.SLICES; i++) {
				float dx = l.iconX(i) - l.cx(), dy = l.iconY(i) - l.cy();
				double r = Math.hypot(dx, dy);
				assertEquals((l.inner() + l.outer()) / 2f, r, 0.01, "icon radius");
				assertEquals(i, WheelMath.sliceAt(dx * 10, dy * 10, WheelLayout.SLICES), "icon " + i + " in its slice");
				assertTrue(l.icon() * WheelLayout.HOVER_SCALE <= (l.outer() - l.inner()) * 0.6f, "icon fits the slice depth");
			}
		}
	}

	/** Shortest distance from (x, y) to the rect. */
	private static double distance(float x, float y, Rect r) {
		double dx = Math.max(Math.max(r.x() - x, 0), x - r.right());
		double dy = Math.max(Math.max(r.y() - y, 0), y - r.bottom());
		return Math.hypot(dx, dy);
	}
}
