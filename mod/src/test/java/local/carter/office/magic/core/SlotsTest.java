package local.carter.office.magic.core;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

class SlotsTest {
	@Test void unlockLevels() {
		int[] expected = {1, 2, 3, 5, 7, 1};
		for (int s = 0; s < 6; s++) assertEquals(expected[s], Slots.unlockLevel(s), "slot " + s);
	}
	@Test void costs() {
		assertArrayEquals(new int[] {8, 10, 2, 0}, Slots.baseCost(0));
		assertArrayEquals(new int[] {25, 60, 5, 0}, Slots.baseCost(1));
		assertArrayEquals(new int[] {12, 40, 2, 0}, Slots.baseCost(2));
		assertArrayEquals(new int[] {30, 100, 5, 0}, Slots.baseCost(3));
		assertArrayEquals(new int[] {60, 600, 20, 3}, Slots.baseCost(4));
		assertArrayEquals(new int[] {0, 1200, 0, 0}, Slots.baseCost(5));
	}
	@Test void constants() {
		assertEquals(0, Slots.SMALL_A);
		assertEquals(1, Slots.MEDIUM_A);
		assertEquals(2, Slots.SMALL_B);
		assertEquals(3, Slots.MEDIUM_B);
		assertEquals(4, Slots.ULTIMATE);
		assertEquals(5, Slots.RECALL);
		assertEquals(6, Slots.WHEEL_SLICES);
	}
	@Test void baseCostIsACopy() {
		Slots.baseCost(4)[0] = 1;
		assertEquals(60, Slots.baseCost(4)[0]);
	}
}
