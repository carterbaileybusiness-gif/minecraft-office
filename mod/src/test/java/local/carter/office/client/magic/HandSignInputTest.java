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

	private static HandSignInput fresh() {
		return new HandSignInput(SEQ, 1000, 2000, List.of());
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
	void everyMappedKeyIsListed() {
		for (int key : HandSignInput.KEYS) assertNotNull(HandSignInput.dirFor(key), "key " + key);
		assertEquals(8, HandSignInput.KEYS.length);
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
		assertEquals(State.COMPLETE, in.tick(99_999));
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
		HandSignInput in = new HandSignInput(SEQ, 1000, 2000, List.of(InputConstants.KEY_W));
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
	void timesOutAtTheLimit() {
		HandSignInput in = fresh();
		in.keyDown(InputConstants.KEY_UP, 1100);
		assertEquals(State.ENTERING, in.tick(2999));
		assertEquals(1f - 1999f / 2000f, in.remaining(2999), 1e-6);
		assertEquals(State.TIMED_OUT, in.tick(3000));
		assertArrayEquals(new Dir[] {Dir.UP}, in.answer());
		assertEquals(0f, in.remaining(5000));
		assertEquals(State.TIMED_OUT, in.keyDown(InputConstants.KEY_LEFT, 3001));
	}

	@Test
	void escapeGivesUp() {
		HandSignInput in = fresh();
		in.keyDown(InputConstants.KEY_UP, 1100);
		assertEquals(State.GAVE_UP, in.escape(1200));
		assertArrayEquals(new Dir[] {Dir.UP}, in.answer());
		assertEquals(0.9f, in.remaining(1900), 1e-6); // frozen when it ended (200 of 2000 ms used)
	}
}
