package local.carter.office.client.interact;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

/** Right-click with a hologram under the crosshair: never place or use anything through it, whoever owns it. */
class HologramClickRuleTest {
	@Test
	void aFriendsHologramSwallowsTheClickWithAnyItem() {
		assertEquals(HologramClickRule.SWALLOW, HologramClickRule.decide(true, false, false, true));
		assertEquals(HologramClickRule.SWALLOW, HologramClickRule.decide(true, false, false, false), "on Windows too");
	}

	@Test
	void yourOwnHologramIsUsedInTheWorldOnAMacAndZoomedElsewhere() {
		assertEquals(HologramClickRule.POINTER, HologramClickRule.decide(true, true, false, true));
		assertEquals(HologramClickRule.ZOOM, HologramClickRule.decide(true, true, false, false));
	}

	@Test
	void anEmitterInHandEditsWhoeverOwnsIt() {
		assertEquals(HologramClickRule.EDIT, HologramClickRule.decide(true, true, true, true));
		assertEquals(HologramClickRule.EDIT, HologramClickRule.decide(true, false, true, false), "\"Owned by …\"");
	}

	@Test
	void noHologramInFrontIsVanillas() {
		for (boolean own : new boolean[] {true, false}) {
			for (boolean emitter : new boolean[] {true, false}) {
				assertEquals(HologramClickRule.NOT_A_HOLOGRAM, HologramClickRule.decide(false, own, emitter, true));
			}
		}
	}
}
