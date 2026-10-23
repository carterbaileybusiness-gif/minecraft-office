package local.carter.office.client.platform;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/** The startup "bring Minecraft to the front" requests: at most once a second, only until the first focus, 20 s max. */
class StartupFocusTest {
	@Test
	void asksAtOnceThenAboutOnceASecond() {
		StartupFocus f = new StartupFocus();
		assertTrue(f.due(5_000, false), "first tick asks");
		assertFalse(f.due(5_050, false));
		assertFalse(f.due(5_999, false));
		assertTrue(f.due(6_000, false));
		assertFalse(f.due(6_500, false));
		assertTrue(f.due(7_200, false));
	}

	@Test
	void stopsForGoodOnceFocused() {
		StartupFocus f = new StartupFocus();
		assertTrue(f.due(0, false));
		assertFalse(f.due(1_000, true), "focused: nothing to do");
		assertTrue(f.done());
		assertFalse(f.due(2_000, false), "the player switching away later is never undone");
		assertFalse(f.due(10_000, false));
	}

	@Test
	void alreadyFocusedNeverAsks() {
		StartupFocus f = new StartupFocus();
		assertFalse(f.due(0, true));
		assertFalse(f.due(5_000, false));
	}

	@Test
	void givesUpAfterTheWindow() {
		StartupFocus f = new StartupFocus();
		assertTrue(f.due(100, false));
		assertTrue(f.due(100 + StartupFocus.WINDOW_MS, false), "still inside the window");
		assertFalse(f.due(101 + StartupFocus.WINDOW_MS, false));
		assertTrue(f.done());
		assertFalse(f.due(200_000, false));
	}
}
