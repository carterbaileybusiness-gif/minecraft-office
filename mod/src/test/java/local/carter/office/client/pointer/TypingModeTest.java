package local.carter.office.client.pointer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;

import local.carter.office.client.pointer.TypingMode.State;

/** A click on a hologram hands the keyboard to the app; Esc (helper) or clicking back into Minecraft returns. */
class TypingModeTest {
	private final List<String> fx = new ArrayList<>();
	private final TypingMode t = new TypingMode(new TypingMode.Env() {
		@Override
		public void showHint() {
			fx.add("hint");
		}

		@Override
		public void hideHint() {
			fx.add("unhint");
		}

		@Override
		public void exitHelper() {
			fx.add("exit-helper");
		}

		@Override
		public void backToGame() {
			fx.add("game");
		}
	});

	@Test
	void clickThenFocusLeavesThenEscFromTheHelper() {
		t.enter(0);
		assertEquals(State.STARTING, t.state());
		assertTrue(t.active());
		t.focus(false, 80); // the clicked app came forward
		assertEquals(State.TYPING, t.state());
		t.tick(60_000); // typing for a minute is fine
		assertEquals(State.TYPING, t.state());
		t.helperExited(); // Esc, swallowed by the helper, which re-activates Minecraft
		assertEquals(State.OFF, t.state());
		assertEquals(List.of("hint", "unhint", "game"), fx, "the helper already knows: no EXIT_TYPING back");
	}

	@Test
	void clickingBackIntoMinecraftEndsTypingAndTellsTheHelper() {
		t.enter(0);
		t.focus(false, 50);
		t.focus(true, 9_000);
		assertEquals(State.OFF, t.state());
		assertEquals(List.of("hint", "unhint", "exit-helper", "game"), fx);
		t.helperExited(); // the helper's own TYPING_EXIT "minecraft" arriving late changes nothing
		assertEquals(4, fx.size());
	}

	@Test
	void focusThatNeverLeavesGivesUpAfterTheWait() {
		t.enter(0);
		t.focus(true, 10); // still Minecraft: not a return, we never left
		t.tick(TypingMode.FOCUS_WAIT_MS - 1);
		assertEquals(State.STARTING, t.state());
		t.tick(TypingMode.FOCUS_WAIT_MS);
		assertEquals(State.OFF, t.state());
		assertEquals(List.of("hint", "unhint", "exit-helper", "game"), fx);
	}

	@Test
	void leavingFromTheModSideAndEnteringAgain() {
		t.enter(0);
		t.focus(false, 10);
		t.leave(); // world left, or another screen replaced ours
		assertFalse(t.active());
		t.leave();
		t.enter(100);
		t.enter(120); // a second click while starting keeps one hint
		assertEquals(List.of("hint", "unhint", "exit-helper", "game", "hint"), fx);
	}
}
