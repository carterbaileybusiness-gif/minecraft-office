package local.carter.office.client.magic;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

class MagicInputGateTest {
	@Test
	void typingBlocksEverything() {
		for (boolean aiming : new boolean[] {false, true}) {
			for (boolean off : new boolean[] {false, true}) {
				for (boolean empty : new boolean[] {false, true}) {
					String at = "aiming " + aiming + " off " + off + " empty " + empty;
					assertTrue(MagicInputGate.blocksUse(true, aiming, off, empty), at + ": nothing reaches the world while typing");
					assertFalse(MagicInputGate.scrollSelects(true, true, aiming, off, empty, true), at);
					assertFalse(MagicInputGate.scrollSelects(false, true, aiming, off, empty, true), at);
				}
			}
			assertFalse(MagicInputGate.wheelAllowed(false, true, true, true), "typing: no wheel");
			assertFalse(MagicInputGate.wheelAllowed(true, true, true, true), "typing: no wheel");
		}
	}

	@Test
	void hologramOwnsClicksAndScrollButNotTheWheel() {
		assertTrue(MagicInputGate.blocksUse(false, true, true, true), "grimoire off hand + empty hand + hologram: never cast");
		assertTrue(MagicInputGate.wheelAllowed(false, false, true, true), "R still opens the wheel on a hologram");
		assertFalse(MagicInputGate.scrollSelects(true, false, true, true, true, true), "the monitor gets the scroll");
	}

	@Test
	void anItemInTheMainHandIsVanilla() {
		assertFalse(MagicInputGate.blocksUse(false, false, true, false), "tool in the main hand");
		assertFalse(MagicInputGate.blocksUse(false, true, true, false), "tool in the main hand on a hologram: the hologram code decides");
		assertFalse(MagicInputGate.scrollSelects(true, false, false, true, false, true), "tool in the main hand: hotbar scroll");
	}

	@Test
	void notAimingIsVanillaAndCasts() {
		assertFalse(MagicInputGate.blocksUse(false, false, true, true), "not aiming at a hologram: the grimoire casts");
		assertFalse(MagicInputGate.blocksUse(false, false, false, true), "no grimoire");
		assertTrue(MagicInputGate.scrollSelects(true, false, false, true, true, true), "Shift + scroll selects");
		assertFalse(MagicInputGate.scrollSelects(false, false, false, true, true, true), "no Shift: hotbar");
		assertFalse(MagicInputGate.scrollSelects(true, false, false, false, true, true), "no grimoire in the off hand");
	}

	@Test
	void unboundNeverSelects() {
		assertFalse(MagicInputGate.scrollSelects(true, false, false, true, true, false));
		assertFalse(MagicInputGate.wheelAllowed(false, false, true, false));
	}

	@Test
	void wheelNeedsAGrimoireAndNoScreen() {
		assertTrue(MagicInputGate.wheelAllowed(false, false, true, true));
		assertFalse(MagicInputGate.wheelAllowed(true, false, true, true), "a screen is open");
		assertFalse(MagicInputGate.wheelAllowed(false, false, false, true), "no grimoire held");
	}

	@Test
	void castingStanceMatchesTheManaBar() {
		assertTrue(MagicInputGate.stance(false, true, true), "off hand + empty main hand");
		assertTrue(MagicInputGate.stance(true, true, false), "grimoire in the main hand");
		assertTrue(MagicInputGate.stance(true, false, false), "grimoire in the main hand, off hand empty");
		assertFalse(MagicInputGate.stance(false, false, true), "off hand + a tool");
		assertFalse(MagicInputGate.stance(false, true, false), "no grimoire");
	}

	@Test
	void releaseIsSuppressedWhileTyping() {
		assertTrue(MagicInputGate.dropsCharge(true, true));
		assertFalse(MagicInputGate.dropsCharge(false, true));
		assertFalse(MagicInputGate.dropsCharge(true, false), "a bow or shield is none of magic's business");
	}
}
