package local.carter.office.client;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import local.carter.office.client.interact.ClickGate;

class ClickGateTest {
	@Test
	void respectsVanillaGuards() {
		assertTrue(ClickGate.mayIntercept(false, false));
		assertFalse(ClickGate.mayIntercept(true, false), "never while destroying a block");
		assertFalse(ClickGate.mayIntercept(false, true), "never with busy hands");
	}

	@Test
	void usesVanillaRightClickDelay() {
		assertEquals(4, ClickGate.RIGHT_CLICK_DELAY);
	}

	@Test
	void holdingAfterAConsumedClickOnlyRepeatsUntilRelease() {
		ClickGate gate = new ClickGate();
		assertFalse(gate.isRepeat(), "first press is a real click");
		gate.consumed();
		gate.tick(true);
		gate.tick(true);
		assertTrue(gate.isRepeat(), "still held: repeats are swallowed (no second settings/drag/\"Owned by\")");
		gate.tick(false);
		assertFalse(gate.isRepeat(), "after release the next press is a new click");
	}

	@Test
	void unconsumedClicksAreNeverSwallowed() {
		ClickGate gate = new ClickGate();
		gate.tick(true);
		assertFalse(gate.isRepeat(), "a click we didn't take stays vanilla's, even while held");
	}
}
