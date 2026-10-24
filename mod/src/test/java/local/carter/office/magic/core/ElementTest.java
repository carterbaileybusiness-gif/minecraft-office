package local.carter.office.magic.core;

import static org.junit.jupiter.api.Assertions.*;

import java.util.*;
import org.junit.jupiter.api.Test;

class ElementTest {
	@Test void byKey() {
		assertEquals(Optional.of(Element.FIRE), Element.byKey("fire"));
		assertTrue(Element.byKey("nope").isEmpty());
	}
	@Test void fireColour() { assertEquals(0xFFFF6A1A, Element.FIRE.argb()); }
	@Test void sixDistinctKeys() {
		Set<String> keys = new HashSet<>();
		for (Element e : Element.values()) keys.add(e.key());
		assertEquals(6, Element.values().length);
		assertEquals(6, keys.size());
	}
	@Test void allColours() {
		assertEquals(0xFF2F8CFF, Element.WATER.argb());
		assertEquals(0xFF9CF2C8, Element.WIND.argb());
		assertEquals(0xFFFFE94A, Element.LIGHTNING.argb());
		assertEquals(0xFFB0814A, Element.EARTH.argb());
		assertEquals(0xFF8A3CFF, Element.SHADOW.argb());
	}
}
