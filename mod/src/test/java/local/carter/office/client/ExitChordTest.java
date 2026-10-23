package local.carter.office.client;

import static org.junit.jupiter.api.Assertions.assertEquals;

import local.carter.office.client.platform.ExitChord;
import local.carter.office.client.platform.ExitChord.Action;
import org.junit.jupiter.api.Test;

/** The Windows keyboard-hook exit gesture: double-tap Esc (≤ 350 ms) or Ctrl+Alt+Shift+Esc. Pure logic. */
class ExitChordTest {
	private static final int ESC = ExitChord.VK_ESCAPE;
	private static final int A = 0x41;

	private static Action down(final ExitChord c, final int vk, final long ms) {
		return c.key(vk, true, false, false, false, ms);
	}

	private static Action up(final ExitChord c, final int vk, final long ms) {
		return c.key(vk, false, false, false, false, ms);
	}

	@Test
	void twoEscWithin300msExits() {
		ExitChord c = new ExitChord();
		assertEquals(Action.PASS, down(c, ESC, 0)); // the single press still reaches the app
		assertEquals(Action.PASS, up(c, ESC, 60));
		assertEquals(Action.EXIT, down(c, ESC, 300));
		assertEquals(Action.SWALLOW, up(c, ESC, 360)); // the key-up of a swallowed press is swallowed too
	}

	@Test
	void exactly350msStillExits() {
		ExitChord c = new ExitChord();
		down(c, ESC, 1000);
		up(c, ESC, 1050);
		assertEquals(Action.EXIT, down(c, ESC, 1350));
	}

	@Test
	void twoEscAt400msDoNotExit() {
		ExitChord c = new ExitChord();
		down(c, ESC, 0);
		up(c, ESC, 50);
		assertEquals(Action.PASS, down(c, ESC, 400));
		up(c, ESC, 450);
		// ...but the 400 ms press starts a new pair
		assertEquals(Action.EXIT, down(c, ESC, 700));
	}

	@Test
	void autoRepeatDoesNotCountAsASecondPress() {
		ExitChord c = new ExitChord();
		assertEquals(Action.PASS, down(c, ESC, 0));
		assertEquals(Action.PASS, down(c, ESC, 30)); // held: Windows repeats WM_KEYDOWN
		assertEquals(Action.PASS, down(c, ESC, 60));
		assertEquals(Action.PASS, up(c, ESC, 500));
		assertEquals(Action.PASS, down(c, ESC, 1000));
	}

	@Test
	void anotherKeyBetweenResetsThePair() {
		ExitChord c = new ExitChord();
		down(c, ESC, 0);
		up(c, ESC, 20);
		assertEquals(Action.PASS, down(c, A, 40));
		up(c, A, 60);
		assertEquals(Action.PASS, down(c, ESC, 100));
	}

	@Test
	void ctrlAltShiftEscExitsAtOnce() {
		ExitChord c = new ExitChord();
		assertEquals(Action.EXIT, c.key(ESC, true, true, true, true, 5000));
		assertEquals(Action.SWALLOW, c.key(ESC, false, true, true, true, 5050));
	}

	@Test
	void escWithSomeModifiersIsLeftAlone() {
		ExitChord c = new ExitChord();
		assertEquals(Action.PASS, c.key(ESC, true, true, false, false, 0)); // Ctrl+Esc = Start menu
		c.key(ESC, false, true, false, false, 20);
		assertEquals(Action.PASS, c.key(ESC, true, false, false, false, 100)); // not paired with the Ctrl+Esc
		assertEquals(Action.PASS, c.key(ESC, true, true, true, false, 5000)); // Ctrl+Shift+... is not the chord
	}

	@Test
	void otherKeysPass() {
		ExitChord c = new ExitChord();
		assertEquals(Action.PASS, down(c, A, 0));
		assertEquals(Action.PASS, up(c, A, 10));
	}

	@Test
	void resetForgetsAHalfPair() {
		ExitChord c = new ExitChord();
		down(c, ESC, 0);
		up(c, ESC, 10);
		c.reset();
		assertEquals(Action.PASS, down(c, ESC, 100));
	}
}
