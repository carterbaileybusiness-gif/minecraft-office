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
	void shiftScrollOnAMacArrivesHorizontal() {
		assertEquals(-1.0, MagicInputGate.scrollAmount(0, -1), "plain wheel down");
		assertEquals(1.0, MagicInputGate.scrollAmount(0, 1), "plain wheel up");
		assertEquals(-1.0, MagicInputGate.scrollAmount(1, 0), "Mac Shift + wheel down arrives as +x: next, like vanilla's hotbar");
		assertEquals(1.0, MagicInputGate.scrollAmount(-1, 0), "Mac Shift + wheel up arrives as -x: previous");
		assertEquals(-2.0, MagicInputGate.scrollAmount(5, -2), "the vertical part wins when there is one");
		assertEquals(0.0, MagicInputGate.scrollAmount(0, 0), 0.0, "no scroll");
	}

	@Test
	void shiftScrollAtAHologramTheMonitorDidNotTakeIsSwallowed() {
		assertTrue(MagicInputGate.scrollSwallowed(true, false, true, true, true, true), "stance at a hologram: neither spell nor hotbar");
		assertFalse(MagicInputGate.scrollSwallowed(false, false, true, true, true, true), "no Shift: vanilla");
		assertFalse(MagicInputGate.scrollSwallowed(true, false, false, true, true, true), "not aiming: it selects instead");
		assertFalse(MagicInputGate.scrollSwallowed(true, false, true, true, false, true), "out of stance: vanilla");
		assertFalse(MagicInputGate.scrollSwallowed(true, false, true, true, true, false), "unbound: vanilla");
		assertFalse(MagicInputGate.scrollSwallowed(true, true, true, true, true, true), "typing: not magic's");
		for (boolean aiming : new boolean[] {false, true}) {
			assertFalse(MagicInputGate.scrollSelects(true, false, aiming, true, true, true)
					&& MagicInputGate.scrollSwallowed(true, false, aiming, true, true, true), "never both");
		}
	}

	@Test
	void wheelFeelsTheSameAtAnySensitivity() {
		assertEquals(1.0, SpellWheel.turnScale(0.5), 1e-9, "vanilla's default multiplier");
		for (double s : new double[] {0.3, 0.5, 0.8, 1.0}) {
			double turn = 10 * SpellWheel.turnScale(s); // what vanilla turns for 10 px at this setting
			assertEquals(10.0, SpellWheel.fromTurn(turn, s), 1e-9, "10 px stay 10 wheel units at sensitivity " + s);
		}
		// Very low settings: the divisor is floored at 0.3 (0% sensitivity gives 0.064).
		assertEquals(10 * SpellWheel.turnScale(0.0) / 0.3, SpellWheel.fromTurn(10 * SpellWheel.turnScale(0.0), 0.0), 1e-9);
		assertTrue(SpellWheel.fromTurn(1, 0.0) <= 1 / 0.3 + 1e-9, "a tiny turn never becomes a huge jump");
	}

	@Test
	void releaseIsSuppressedWhileTyping() {
		assertTrue(MagicInputGate.dropsCharge(true, true));
		assertFalse(MagicInputGate.dropsCharge(false, true));
		assertFalse(MagicInputGate.dropsCharge(true, false), "a bow or shield is none of magic's business");
	}
}
