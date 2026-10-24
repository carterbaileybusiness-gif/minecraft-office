package local.carter.office.magic.core;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

class WheelMathTest {
	@Test void slices() {
		assertEquals(0, WheelMath.sliceAt(0, -30, 6));
		assertEquals(1, WheelMath.sliceAt(26, -15, 6));
		assertEquals(2, WheelMath.sliceAt(26, 15, 6));
		assertEquals(3, WheelMath.sliceAt(0, 30, 6));
		assertEquals(4, WheelMath.sliceAt(-26, 15, 6));
		assertEquals(5, WheelMath.sliceAt(-26, -15, 6));
	}
	@Test void deadZone() {
		assertEquals(-1, WheelMath.sliceAt(5, 5, 6));
		assertEquals(-1, WheelMath.sliceAt(0, 0, 6));
	}
	@Test void slightlyLeftOfUpIsStillZero() { assertEquals(0, WheelMath.sliceAt(-10, -30, 6)); }
	@Test void clamp() {
		assertArrayEquals(new double[] {60, 0}, WheelMath.clamp(300, 0), 1e-9);
		assertArrayEquals(new double[] {10, 20}, WheelMath.clamp(10, 20), 1e-9);
	}
	@Test void constants() {
		assertEquals(14, WheelMath.DEAD_ZONE);
		assertEquals(4, WheelMath.SELECT_MIN);
		assertEquals(60, WheelMath.MAX_RADIUS);
	}
}
