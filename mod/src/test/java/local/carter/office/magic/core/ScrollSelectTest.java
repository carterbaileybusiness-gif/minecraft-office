package local.carter.office.magic.core;

import static org.junit.jupiter.api.Assertions.*;

import java.util.List;
import org.junit.jupiter.api.Test;

class ScrollSelectTest {
	private final List<String> l = List.of("a", "b", "c");

	@Test void nextWraps() {
		assertEquals("a", ScrollSelect.next(l, "c", -1));
		assertEquals("c", ScrollSelect.next(l, "b", -1));
	}
	@Test void previousWraps() {
		assertEquals("c", ScrollSelect.next(l, "a", 1));
		assertEquals("a", ScrollSelect.next(l, "b", 1));
	}
	@Test void unknownCurrent() {
		assertEquals("a", ScrollSelect.next(l, "z", -1));
		assertEquals("a", ScrollSelect.next(l, null, 1));
	}
	@Test void empty() { assertNull(ScrollSelect.next(List.<String>of(), "a", -1)); }
}
