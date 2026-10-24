package local.carter.office.magic.core;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

class LevelCurveTest {
	@Test void xpToNext() {
		assertEquals(20, LevelCurve.xpToNext(1));
		assertEquals(180, LevelCurve.xpToNext(9));
	}
	@Test void singleLevel() { assertEquals(new LevelCurve.Progress(2, 5, 1), LevelCurve.add(1, 15, 10)); }
	@Test void multiLevel() { assertEquals(new LevelCurve.Progress(5, 0, 4), LevelCurve.add(1, 0, 200)); }
	@Test void pinnedAtMax() { assertEquals(new LevelCurve.Progress(10, 0, 0), LevelCurve.add(10, 0, 999)); }
	@Test void noLevel() { assertEquals(new LevelCurve.Progress(1, 10, 0), LevelCurve.add(1, 5, 5)); }
	@Test void reachesMaxAndDropsRest() { assertEquals(new LevelCurve.Progress(10, 0, 1), LevelCurve.add(9, 0, 100000)); }
}
