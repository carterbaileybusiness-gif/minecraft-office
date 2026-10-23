package local.carter.office.core;

import static org.junit.jupiter.api.Assertions.assertEquals;

import local.carter.office.OfficeMod;
import org.junit.jupiter.api.Test;

class SmokeTest {
	@Test
	void idUsesModNamespace() {
		assertEquals("office:x", OfficeMod.id("x").toString());
	}
}
