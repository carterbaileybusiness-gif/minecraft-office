package local.carter.office.magic.core;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

class GrimoireTierTest {
	@Test void tiers() {
		int[] lv = {1, 2, 3, 4, 5, 6, 7, 10};
		int[] ex = {0, 0, 1, 1, 2, 2, 3, 3};
		for (int i = 0; i < lv.length; i++) assertEquals(ex[i], GrimoireTier.of(lv[i]), "level " + lv[i]);
	}
}
