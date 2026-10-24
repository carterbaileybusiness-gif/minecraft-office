package local.carter.office.client.magic.fx.shadow;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import local.carter.office.client.magic.fx.FxBudget;

class ShadowFxBudgetTest {
	@Test
	void blackHolePhasesFitTheUltimateBudget() {
		int core = BlackHoleFx.Singularity.cost() + BlackHoleFx.Shock.cost() + ShadowPop.cost();
		assertTrue(BlackHoleFx.CAST + BlackHoleFx.LIFE_SHARE + BlackHoleFx.BURST <= FxBudget.ULTIMATE_PARTICLES, "the phases fit");
		assertTrue(core < BlackHoleFx.CAST, "the drawn pieces (charged per 50 a frame) fit the cast's share: " + core);
	}

	@Test
	void blackHoleShakesAtLeastPointEightWithin32() {
		for (double d = 0; d <= 32; d += 0.5) {
			assertTrue(BlackHoleFx.SHAKE * BlackHoleFx.nearness(d) >= 0.8F, "shake at " + d + ": " + BlackHoleFx.nearness(d));
		}
		assertEquals(1F, BlackHoleFx.nearness(10));
		assertEquals(0F, BlackHoleFx.nearness(80));
		for (double d = 0; d < 80; d += 0.5) assertTrue(BlackHoleFx.nearness(d + 0.5) <= BlackHoleFx.nearness(d), "fades with distance");
	}

	@Test
	void hazeLaysItsPieces() {
		assertEquals(3, Umbra.HAZE_PIECES);
		assertEquals(Umbra.HAZE_PIECES + 3 + 24 + 24, Umbra.orbPieces(24));
	}
}
