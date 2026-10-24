package local.carter.office.magic.core;

import static org.junit.jupiter.api.Assertions.*;

import local.carter.office.magic.core.TerrainRules.Verdict;
import org.junit.jupiter.api.Test;

class TerrainRulesTest {
	private static Verdict d(boolean be, boolean im, boolean cl, boolean sp, boolean in, double dist, double cap, int used, int budget) {
		return TerrainRules.decide(be, im, cl, sp, in, dist, cap, used, budget);
	}

	@Test void allow() { assertEquals(Verdict.ALLOW, d(false, false, false, false, true, 6, 6, 255, 256)); }
	@Test void priority() {
		assertEquals(Verdict.BLOCK_ENTITY, d(true, true, true, true, false, 99, 6, 999, 256));
		assertEquals(Verdict.IMMUNE, d(false, true, true, true, false, 99, 6, 999, 256));
		assertEquals(Verdict.CLAIMED, d(false, false, true, true, false, 99, 6, 999, 256));
		assertEquals(Verdict.SPAWN_PROTECTED, d(false, false, false, true, false, 99, 6, 999, 256));
		assertEquals(Verdict.OUTSIDE_BORDER, d(false, false, false, false, false, 99, 6, 999, 256));
		assertEquals(Verdict.BEYOND_RADIUS, d(false, false, false, false, true, 99, 6, 999, 256));
		assertEquals(Verdict.OVER_BUDGET, d(false, false, false, false, true, 1, 6, 256, 256));
	}
	@Test void beyondRadius() { assertEquals(Verdict.BEYOND_RADIUS, d(false, false, false, false, true, 6.01, 6, 0, 256)); }
}
