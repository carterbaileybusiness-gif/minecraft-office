package local.carter.office.client.magic;

import static org.junit.jupiter.api.Assertions.*;

import java.util.List;

import org.junit.jupiter.api.Test;

import com.mojang.blaze3d.platform.InputConstants;

import local.carter.office.client.magic.screen.HandSignInput;
import local.carter.office.client.magic.screen.HandSignScreen;
import local.carter.office.client.magic.screen.HandSignInput.State;
import local.carter.office.magic.core.Dir;

class HandSignInputTest {
	private static final Dir[] SEQ = {Dir.UP, Dir.LEFT, Dir.DOWN, Dir.RIGHT};

	/** Opened at game tick 100 with a 40-tick limit and default keys. */
	private static HandSignInput fresh() {
		return new HandSignInput(SEQ, HandSignInput.DEFAULT, 100, 40, List.of());
	}

	@Test
	void arrowsAndWasdMapToDirs() {
		assertEquals(Dir.UP, HandSignInput.dirFor(InputConstants.KEY_UP));
		assertEquals(Dir.UP, HandSignInput.dirFor(InputConstants.KEY_W));
		assertEquals(Dir.LEFT, HandSignInput.dirFor(InputConstants.KEY_A));
		assertEquals(Dir.RIGHT, HandSignInput.dirFor(InputConstants.KEY_D));
		assertEquals(Dir.DOWN, HandSignInput.dirFor(InputConstants.KEY_S));
		assertEquals(Dir.DOWN, HandSignInput.dirFor(InputConstants.KEY_DOWN));
		assertEquals(Dir.LEFT, HandSignInput.dirFor(InputConstants.KEY_LEFT));
		assertEquals(Dir.RIGHT, HandSignInput.dirFor(InputConstants.KEY_RIGHT));
		assertNull(HandSignInput.dirFor(InputConstants.KEY_E));
		assertNull(HandSignInput.dirFor(InputConstants.KEY_ESCAPE));
		assertNull(HandSignInput.dirFor(InputConstants.KEY_SPACE));
	}

	@Test
	void screenUsesTheSameMapping() {
		assertEquals(Dir.UP, HandSignScreen.dirFor(InputConstants.KEY_UP));
		assertEquals(Dir.UP, HandSignScreen.dirFor(InputConstants.KEY_W));
		assertEquals(Dir.LEFT, HandSignScreen.dirFor(InputConstants.KEY_A));
		assertEquals(Dir.RIGHT, HandSignScreen.dirFor(InputConstants.KEY_D));
		assertEquals(Dir.DOWN, HandSignScreen.dirFor(InputConstants.KEY_S));
		assertNull(HandSignScreen.dirFor(InputConstants.KEY_E));
	}

	@Test
	void defaultsAreArrowsAndWasd() {
		assertEquals(8, HandSignInput.DEFAULT.size());
		assertEquals(HandSignInput.DEFAULT.keySet(), fresh().keys());
	}

	@Test
	void reboundMovementKeysTypeArrowsAndWasdNoLonger() {
		// ESDF instead of WASD, and "right" on a mouse button (no keyboard key).
		var keys = HandSignInput.bindings(InputConstants.KEY_E, InputConstants.KEY_D, InputConstants.KEY_S, HandSignInput.NO_KEY);
		HandSignInput in = new HandSignInput(SEQ, keys, 100, 40, List.of());
		assertEquals(Dir.UP, in.dir(InputConstants.KEY_E));
		assertEquals(Dir.DOWN, in.dir(InputConstants.KEY_D));
		assertEquals(Dir.LEFT, in.dir(InputConstants.KEY_S));
		assertNull(in.dir(InputConstants.KEY_W));
		assertNull(in.dir(InputConstants.KEY_A));
		assertEquals(Dir.RIGHT, in.dir(InputConstants.KEY_RIGHT), "arrows always work");
		assertEquals(Dir.UP, in.dir(InputConstants.KEY_UP));
		assertEquals(State.ENTERING, in.keyDown(InputConstants.KEY_W, 1000), "W is not bound any more: ignored");
		assertEquals(State.ENTERING, in.keyDown(InputConstants.KEY_E, 1000));
		assertEquals(State.ENTERING, in.keyDown(InputConstants.KEY_S, 1100));
		assertEquals(State.ENTERING, in.keyDown(InputConstants.KEY_D, 1200));
		assertEquals(State.COMPLETE, in.keyDown(InputConstants.KEY_RIGHT, 1300));
	}

	@Test
	void arrowKeysWinOverAMovementKeyBoundToThem() {
		var keys = HandSignInput.bindings(InputConstants.KEY_DOWN, InputConstants.KEY_S, InputConstants.KEY_A, InputConstants.KEY_D);
		assertEquals(Dir.DOWN, keys.get(InputConstants.KEY_DOWN));
	}

	@Test
	void fullSequenceCompletes() {
		HandSignInput in = fresh();
		assertEquals(State.ENTERING, in.keyDown(InputConstants.KEY_W, 1100));
		in.keyUp(InputConstants.KEY_W);
		assertEquals(State.ENTERING, in.keyDown(InputConstants.KEY_LEFT, 1200));
		assertEquals(State.ENTERING, in.keyDown(InputConstants.KEY_S, 1300));
		assertEquals(2 + 1, in.correct());
		assertEquals(State.COMPLETE, in.keyDown(InputConstants.KEY_D, 1400));
		assertArrayEquals(SEQ, in.answer());
		assertEquals(1400, in.endedAt());
		assertEquals(-1, in.missedAt());
		// Nothing changes after the end.
		assertEquals(State.COMPLETE, in.keyDown(InputConstants.KEY_UP, 1500));
		assertEquals(State.COMPLETE, in.advance(99_999, 9999));
		assertArrayEquals(SEQ, in.answer());
	}

	@Test
	void firstWrongArrowEndsWithThePartialAnswer() {
		HandSignInput in = fresh();
		in.keyDown(InputConstants.KEY_UP, 1100);
		assertEquals(State.MISSED, in.keyDown(InputConstants.KEY_RIGHT, 1200));
		assertArrayEquals(new Dir[] {Dir.UP, Dir.RIGHT}, in.answer());
		assertEquals(1, in.correct());
		assertEquals(1, in.missedAt());
		assertEquals(State.MISSED, in.keyDown(InputConstants.KEY_DOWN, 1300));
		assertEquals(2, in.answer().length);
	}

	@Test
	void otherKeysAreIgnored() {
		HandSignInput in = fresh();
		assertEquals(State.ENTERING, in.keyDown(InputConstants.KEY_E, 1100));
		assertEquals(State.ENTERING, in.keyDown(InputConstants.KEY_SPACE, 1100));
		assertEquals(0, in.answer().length);
	}

	@Test
	void repeatsAndKeysHeldAtOpenDoNotCount() {
		HandSignInput in = new HandSignInput(SEQ, HandSignInput.DEFAULT, 100, 40, List.of(InputConstants.KEY_W));
		// W was held while walking when the prompt opened: its auto-repeat is ignored.
		assertEquals(State.ENTERING, in.keyDown(InputConstants.KEY_W, 1050));
		assertEquals(0, in.answer().length);
		in.keyUp(InputConstants.KEY_W);
		assertEquals(State.ENTERING, in.keyDown(InputConstants.KEY_W, 1100));
		assertEquals(1, in.correct());
		// Holding LEFT: the repeat is not a second LEFT.
		in.keyDown(InputConstants.KEY_LEFT, 1200);
		assertEquals(State.ENTERING, in.keyDown(InputConstants.KEY_LEFT, 1250));
		assertEquals(2, in.correct());
		// The arrow key and its WASD twin are different physical keys.
		assertEquals(State.ENTERING, in.keyDown(InputConstants.KEY_DOWN, 1300));
		assertEquals(3, in.correct());
	}

	@Test
	void timesOutAtTheLimitInGameTicks() {
		HandSignInput in = fresh();
		in.keyDown(InputConstants.KEY_UP, 1100);
		// Wall-clock time does not matter: only game ticks run the timer (it follows the server's tick rate).
		assertEquals(State.ENTERING, in.advance(139.5, 999_999));
		assertEquals(1f - 39.5f / 40f, in.remaining(), 1e-6);
		assertEquals(State.TIMED_OUT, in.advance(140, 1_000_000));
		assertEquals(1_000_000, in.endedAt());
		assertArrayEquals(new Dir[] {Dir.UP}, in.answer());
		assertEquals(0f, in.remaining());
		assertEquals(State.TIMED_OUT, in.keyDown(InputConstants.KEY_LEFT, 1_000_001));
	}

	@Test
	void timerNeverRunsBackwards() {
		HandSignInput in = fresh();
		in.advance(120, 0);
		assertEquals(0.5f, in.remaining(), 1e-6);
		in.advance(115, 0); // a server time correction stepped the client clock back
		assertEquals(0.5f, in.remaining(), 1e-6);
	}

	@Test
	void escapeGivesUp() {
		HandSignInput in = fresh();
		in.keyDown(InputConstants.KEY_UP, 1100);
		in.advance(104, 1150);
		assertEquals(State.GAVE_UP, in.escape(1200));
		assertArrayEquals(new Dir[] {Dir.UP}, in.answer());
		in.advance(130, 1900);
		assertEquals(0.9f, in.remaining(), 1e-6); // frozen when it ended (4 of 40 ticks used)
	}
}
