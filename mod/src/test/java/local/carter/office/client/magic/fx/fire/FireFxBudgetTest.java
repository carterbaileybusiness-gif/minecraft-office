package local.carter.office.client.magic.fx.fire;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import local.carter.office.client.magic.fx.FxBudget;

class FireFxBudgetTest {
	@Test
	void meteorPhasesShareTheUltimateBudgetExactly() {
		assertEquals(FxBudget.ULTIMATE_PARTICLES, MeteorFx.CAST + MeteorFx.FALL + MeteorFx.IMPACT + MeteorFx.SMOULDER);
	}

	@Test
	void meteorShakesAtLeastPointEightWithin32() {
		for (double d = 0; d <= 32; d += 0.5) assertTrue(MeteorFx.nearness(d) >= 0.8F, "shake at " + d + ": " + MeteorFx.nearness(d));
		assertEquals(1F, MeteorFx.nearness(10));
		assertEquals(0F, MeteorFx.nearness(70));
	}

	@Test
	void meteorRockFitsTheGhostBudget() {
		int n = (int) Math.ceil(MeteorRock.RADIUS);
		int cells = 0;
		for (int x = -n; x <= n; x++)
			for (int y = -n; y <= n; y++)
				for (int z = -n; z <= n; z++)
					if (Math.sqrt(x * x + y * y + z * z) <= MeteorRock.RADIUS) cells++;
		assertTrue(cells <= FxBudget.GHOST_BLOCKS, "rock blocks " + cells);
	}

	@Test
	void allowanceGivesNoMoreThanItsShare() {
		Allowance a = new Allowance(10);
		assertEquals(6, a.take(6));
		assertEquals(4, a.take(6));
		assertEquals(0, a.take(1));
		assertEquals(0, new Allowance(-3).take(1));
	}
}
