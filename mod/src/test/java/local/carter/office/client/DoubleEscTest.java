package local.carter.office.client;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

import local.carter.office.client.zoom.DoubleEsc;
import local.carter.office.client.zoom.DoubleEsc.Result;

class DoubleEscTest {
	@Test
	void twoPressesWithin350msExit() {
		DoubleEsc esc = new DoubleEsc();
		assertEquals(Result.PASS, esc.press(0));
		esc.release();
		assertEquals(Result.EXIT, esc.press(300));
	}

	@Test
	void exactly350msStillCounts() {
		DoubleEsc esc = new DoubleEsc();
		esc.press(1_000);
		esc.release();
		assertEquals(Result.EXIT, esc.press(1_350));
	}

	@Test
	void twoPresses400msApartDoNotExit() {
		DoubleEsc esc = new DoubleEsc();
		assertEquals(Result.PASS, esc.press(0));
		esc.release();
		assertEquals(Result.PASS, esc.press(400), "too slow: the second press starts a new double-tap");
		esc.release();
		assertEquals(Result.EXIT, esc.press(600), "400 and 600 are a double-tap");
	}

	@Test
	void aSinglePressIsPassedToTheApp() {
		DoubleEsc esc = new DoubleEsc();
		assertEquals(Result.PASS, esc.press(0), "one Esc belongs to the app (Minecraft never acts on it while working)");
		esc.release();
		assertEquals(Result.PASS, esc.press(5_000));
	}

	@Test
	void theDetectorResetsAfterAnExit() {
		DoubleEsc esc = new DoubleEsc();
		esc.press(0);
		esc.release();
		assertEquals(Result.EXIT, esc.press(100));
		esc.release();
		assertEquals(Result.PASS, esc.press(200), "a third quick press is a fresh first press");
	}

	@Test
	void autoRepeatOfAHeldEscIsNotASecondPress() {
		DoubleEsc esc = new DoubleEsc();
		assertEquals(Result.PASS, esc.press(0));
		assertEquals(Result.PASS, esc.press(250), "key repeat (no release in between) never exits");
		assertEquals(Result.PASS, esc.press(300));
		esc.release();
		assertEquals(Result.PASS, esc.press(900));
	}

	@Test
	void aModifiedEscBreaksTheDoubleTap() {
		DoubleEsc esc = new DoubleEsc();
		esc.press(0);
		esc.release();
		assertEquals(Result.PASS, esc.key(100, true), "Cmd/Shift/Ctrl/Alt+Esc is the app's");
		esc.release();
		assertEquals(Result.PASS, esc.press(200), "and the earlier press no longer pairs");
	}

	@Test
	void anotherKeyInBetweenBreaksTheDoubleTap() {
		DoubleEsc esc = new DoubleEsc();
		esc.press(0);
		esc.release();
		esc.otherKey();
		assertEquals(Result.PASS, esc.press(100));
	}

	@Test
	void sameWindowAsTheHelpersHotkey() {
		assertEquals(350, DoubleEsc.WINDOW_MS);
	}
}
