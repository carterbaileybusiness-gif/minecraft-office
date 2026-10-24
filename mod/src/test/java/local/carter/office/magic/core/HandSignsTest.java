package local.carter.office.magic.core;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

class HandSignsTest {
	@Test void lengths() {
		assertEquals(6, HandSigns.length(7));
		assertEquals(5, HandSigns.length(8));
		assertEquals(4, HandSigns.length(9));
		assertEquals(4, HandSigns.length(10));
	}
	@Test void limit() {
		assertEquals(92, HandSigns.limitTicks(6));
		assertEquals(10, HandSigns.GRACE_TICKS);
	}
	@Test void sequenceDeterministic() {
		Dir[] a = HandSigns.sequence(42, 6);
		assertArrayEquals(a, HandSigns.sequence(42, 6));
		assertEquals(6, a.length);
	}
	@Test void noRepeats() {
		for (long seed = 0; seed < 1000; seed++) {
			Dir[] s = HandSigns.sequence(seed, 6);
			assertEquals(6, s.length);
			for (int i = 1; i < s.length; i++) assertNotEquals(s[i - 1], s[i], "seed " + seed);
		}
	}
	@Test void matches() {
		Dir[] e = {Dir.UP, Dir.LEFT, Dir.DOWN, Dir.RIGHT};
		assertTrue(HandSigns.matches(e, e.clone()));
		assertFalse(HandSigns.matches(e, new Dir[] {Dir.UP, Dir.DOWN, Dir.LEFT, Dir.RIGHT}));
		assertFalse(HandSigns.matches(e, new Dir[] {Dir.UP, Dir.LEFT, Dir.DOWN}));
		assertFalse(HandSigns.matches(e, new Dir[] {Dir.UP, Dir.LEFT, Dir.DOWN, Dir.RIGHT, Dir.UP}));
	}
	@Test void fizzle() {
		assertEquals(30, HandSigns.fizzleMana(60));
		assertEquals(1, HandSigns.fizzleMana(1));
		assertEquals(0, HandSigns.fizzleMana(0));
	}
}
