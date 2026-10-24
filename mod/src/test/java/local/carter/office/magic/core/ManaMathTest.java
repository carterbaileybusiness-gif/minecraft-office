package local.carter.office.magic.core;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

class ManaMathTest {
	@Test void max() {
		assertEquals(100, ManaMath.max(1));
		assertEquals(190, ManaMath.max(10));
	}
	@Test void regen() {
		int[] expected = {6, 5, 5, 4, 4, 3, 3, 2, 2, 2};
		for (int l = 1; l <= 10; l++) assertEquals(expected[l - 1], ManaMath.regenInterval(l), "level " + l);
	}
	@Test void pause() { assertEquals(20, ManaMath.REGEN_PAUSE_TICKS); }
}
