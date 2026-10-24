package local.carter.office.client.magic;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import local.carter.office.client.magic.fx.FxBudget;
import local.carter.office.client.magic.fx.FxKit;
import local.carter.office.magic.spell.SpellIds;

class FxBudgetTest {
	@Test
	void nonUltimateGrantsAtMost400Particles() {
		FxBudget b = new FxBudget(false);
		assertEquals(400, b.particles(500));
		assertEquals(0, b.particles(1));
	}

	@Test
	void ultimateGrantsAtMost1500Particles() {
		FxBudget b = new FxBudget(true);
		assertEquals(1500, b.particles(2000));
		assertEquals(0, b.particles(10));
	}

	@Test
	void grantsAddUpAcrossCalls() {
		FxBudget b = new FxBudget(false);
		assertEquals(150, b.particles(150));
		assertEquals(150, b.particles(150));
		assertEquals(100, b.particles(150));
		assertEquals(0, b.particlesLeft());
		assertEquals(0, b.particles(-5));
	}

	@Test
	void the129thGhostBlockIsRefused() {
		FxBudget b = new FxBudget(true);
		for (int i = 0; i < 128; i++) {
			assertTrue(b.ghostBlock(), "ghost " + (i + 1));
		}
		assertFalse(b.ghostBlock());
		assertEquals(0, b.ghostBlocksLeft());
	}

	@Test
	void budgetsResetPerFxEvent() {
		FxBudget first = new FxBudget(false);
		first.particles(400);
		for (int i = 0; i < 128; i++) first.ghostBlock();
		FxBudget next = new FxBudget(false);
		assertEquals(400, next.particles(400));
		assertTrue(next.ghostBlock());
	}

	@Test
	void ultimatesAreSlotFour() {
		assertTrue(FxKit.isUltimate(SpellIds.METEOR));
		assertTrue(FxKit.isUltimate(SpellIds.BLACK_HOLE));
		assertFalse(FxKit.isUltimate(SpellIds.FIRE_BOLT));
		assertFalse(FxKit.isUltimate(SpellIds.RECALL));
	}
}
