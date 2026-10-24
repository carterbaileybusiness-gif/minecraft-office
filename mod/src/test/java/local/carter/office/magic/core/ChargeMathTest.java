package local.carter.office.magic.core;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

class ChargeMathTest {
	@Test void charge() {
		assertEquals(0f, ChargeMath.charge(0));
		assertEquals(0f, ChargeMath.charge(7));
		assertEquals(0f, ChargeMath.charge(8));
		assertEquals(0.5f, ChargeMath.charge(24));
		assertEquals(1f, ChargeMath.charge(40));
		assertEquals(1f, ChargeMath.charge(400));
	}
	@Test void rings() {
		assertEquals(1, ChargeMath.rings(0));
		assertEquals(3, ChargeMath.rings(24));
		assertEquals(5, ChargeMath.rings(40));
	}
	@Test void cost() {
		assertEquals(50, ChargeMath.cost(25, 1f, false)); // a full charge costs twice a tap
		assertEquals(16, ChargeMath.cost(8, 1f, false));
		assertEquals(12, ChargeMath.cost(8, 0.5f, false));
		assertEquals(8, ChargeMath.cost(8, 0f, false));
		assertEquals(60, ChargeMath.cost(60, 1f, true));
	}
	@Test void affordable() {
		assertEquals(0.21875f, ChargeMath.affordableCharge(30, 25, 1f)); // 7/32: 25 × 1.21875 = 30.47 rounds to 30
		assertEquals(-1f, ChargeMath.affordableCharge(7, 8, 0f));
		assertEquals(1f, ChargeMath.affordableCharge(100, 25, 1f));
		assertEquals(0.5f, ChargeMath.affordableCharge(100, 25, 0.5f));
		assertEquals(0f, ChargeMath.affordableCharge(25, 25, 1f)); // 1/32 already costs 26
		assertEquals(0f, ChargeMath.affordableCharge(8, 8, 0f));
	}
	@Test void size() {
		assertEquals(1f, ChargeMath.size(0f));
		assertEquals(1.5f, ChargeMath.size(0.5f));
		assertEquals(2f, ChargeMath.size(1f));
		assertEquals(2f, ChargeMath.size(3f));
	}
	@Test void power() {
		assertEquals(2.9, ChargeMath.power(1f, 10), 1e-6);
		assertEquals(1.0, ChargeMath.power(0f, 1), 1e-6);
	}
}
