package local.carter.office.client.magic;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

import local.carter.office.client.magic.hud.ManaBarLayout;
import local.carter.office.client.magic.screen.SignLayout.Rect;

class ManaBarLayoutTest {
	private static final int[][] SIZES = {{320, 240}, {427, 240}, {640, 360}, {1920, 1080}, {480, 270}, {854, 480}};

	@Test
	void barIsTheVanillaXpBar() {
		for (int[] wh : SIZES) {
			ManaBarLayout.Layout l = ManaBarLayout.layout(wh[0], wh[1]);
			assertEquals(new Rect((wh[0] - 182) / 2, wh[1] - 29, 182, 5), l.bar(), wh[0] + "x" + wh[1]);
		}
	}

	@Test
	void xpLevelNeverOverlapsTheManaNumber() {
		for (int[] wh : SIZES) {
			ManaBarLayout.Layout l = ManaBarLayout.layout(wh[0], wh[1]);
			// Widest plausible texts: mana up to "190" (and room to spare), XP levels up to 5 digits; +2 for the outline.
			for (int manaW = 5; manaW <= 40; manaW++) {
				for (int xpW = 5; xpW <= 40; xpW += 5) {
					Rect mana = l.manaText(manaW);
					Rect xp = l.xpText(xpW);
					String at = wh[0] + "x" + wh[1] + " mana " + mana + " xp " + xp;
					assertFalse(mana.overlaps(xp), at);
					assertTrue(xp.insideScreen(wh[0], wh[1], 0), at + ": xp level on screen");
					assertTrue(mana.insideScreen(wh[0], wh[1], 0), at + ": mana number on screen");
				}
			}
		}
	}

	@Test
	void manaNumberIsCentredAboveTheBarAndXpLevelAtItsRightEnd() {
		ManaBarLayout.Layout l = ManaBarLayout.layout(640, 360);
		Rect mana = l.manaText(17);
		assertEquals(l.bar().cx(), mana.cx(), 1);
		assertTrue(mana.bottom() <= l.bar().y(), "mana number above the bar");
		// Vanilla's level spot (the gap between hearts and food, y = h - 24 - 9 - 2) lifted 2 so the outline clears the bar.
		assertEquals(360 - 37, l.manaY());
		Rect xp = l.xpText(11);
		assertTrue(xp.x() >= l.bar().right(), "xp level right of the bar");
		assertTrue(xp.x() - l.bar().right() <= 6, "xp level hugs the bar's right end");
		assertTrue(xp.y() <= l.bar().cy() && xp.bottom() >= l.bar().cy(), "xp level beside the bar");
	}

	@Test
	void spellLabelSitsWhereTheHeldItemNameGoes() {
		ManaBarLayout.Layout l = ManaBarLayout.layout(640, 360);
		assertEquals(360 - 59, l.labelY(true));
		assertEquals(360 - 45, l.labelY(false));
	}

	@Test
	void fillWidthAndNotches() {
		assertEquals(182, ManaBarLayout.fillWidth(100, 100));
		assertEquals(0, ManaBarLayout.fillWidth(0, 100));
		assertEquals(91, ManaBarLayout.fillWidth(50, 100));
		assertEquals(182, ManaBarLayout.fillWidth(500, 100), "clamped");
		// One notch per 10 mana inside the bar (none at either end).
		assertEquals(9, ManaBarLayout.notches(100).length);
		assertEquals(18, ManaBarLayout.notches(190).length);
		for (int x : ManaBarLayout.notches(130)) assertTrue(x > 0 && x < 182);
	}
}
