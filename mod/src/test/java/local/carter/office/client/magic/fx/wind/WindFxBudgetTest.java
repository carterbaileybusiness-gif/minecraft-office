package local.carter.office.client.magic.fx.wind;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import local.carter.office.client.magic.fx.FxBudget;

class WindFxBudgetTest {
	@Test
	void windSphereSharesFitTheUltimateBudget() {
		int shares = WindSphereFx.ORB_SHARE + WindSphereFx.WAKE_SHARE + WindSphereFx.BURST_SHARE + WindSphereFx.DRILL_SHARE
				+ WindSphereFx.ROCK_SHARE + WindSphereFx.MOTE_SHARE;
		assertTrue(shares <= FxBudget.ULTIMATE_PARTICLES, "shares " + shares);
		assertEquals(10, WindSphereFx.STEP);
		assertEquals(10, TornadoFx.STEP);
	}

	@Test
	void windSphereShakesAtLeastPointEightWithin32() {
		for (double d = 0; d <= 32; d += 0.5) {
			assertTrue(WindSphereFx.shake(d) >= 0.8F - 1.0E-6F, "shake at " + d + " = " + WindSphereFx.shake(d));
		}
		assertEquals(0.9F, WindSphereFx.shake(0), 1.0E-6F);
		assertEquals(0.9F, WindSphereFx.shake(24), 1.0E-6F);
		assertEquals(0.8F, WindSphereFx.shake(32), 1.0E-6F);
		assertEquals(0F, WindSphereFx.shake(48), 1.0E-6F);
		assertEquals(0F, WindSphereFx.shake(200), 1.0E-6F);
		for (double d = 0; d < 60; d += 0.5) {
			assertTrue(WindSphereFx.shake(d + 0.5) <= WindSphereFx.shake(d) + 1.0E-6F, "never stronger further away, at " + d);
		}
	}

	@Test
	void ribbonBudgetCostIsOnePerFiftyQuadsAtLeastOne() {
		assertEquals(1, WindRibbon.budgetCost(0));
		assertEquals(1, WindRibbon.budgetCost(50));
		assertEquals(2, WindRibbon.budgetCost(51));
		assertEquals(20, WindRibbon.budgetCost(1000));
	}
}
