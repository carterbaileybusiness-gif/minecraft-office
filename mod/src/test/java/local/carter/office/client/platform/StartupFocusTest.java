package local.carter.office.client.platform;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import local.carter.office.client.platform.StartupFocus.Front;
import org.junit.jupiter.api.Test;

/**
 * The startup "bring Minecraft to the front" requests: only while the launcher is frontmost, at most 3 (polite, then
 * forced), and never again once Minecraft got focus or the player went to another app.
 */
class StartupFocusTest {
	private static final long E = StartupFocus.EVERY_MS;

	@Test
	void politeFirstThenForcedThenStops() {
		StartupFocus f = new StartupFocus();
		assertEquals(1, f.next(5_000, false, Front.LAUNCHER), "first tick: polite [NSApp activate]");
		assertEquals(0, f.next(5_050, false, Front.LAUNCHER));
		assertEquals(2, f.next(5_000 + E, false, Front.LAUNCHER), "refused: activateIgnoringOtherApps");
		assertEquals(0, f.next(5_000 + E + E / 2, false, Front.LAUNCHER));
		assertEquals(3, f.next(5_000 + 2 * E, false, Front.LAUNCHER));
		assertEquals(0, f.next(5_000 + 3 * E, false, Front.LAUNCHER), "three attempts at most");
		assertTrue(f.done());
		assertEquals(0, f.next(60_000, false, Front.LAUNCHER));
	}

	@Test
	void stopsForGoodOnceFocused() {
		StartupFocus f = new StartupFocus();
		assertEquals(1, f.next(0, false, Front.LAUNCHER));
		assertEquals(0, f.next(E, true, Front.SELF), "the request worked");
		assertTrue(f.done());
		assertEquals(0, f.next(2 * E, false, Front.LAUNCHER), "the player switching back to the launcher is never undone");
	}

	@Test
	void frontmostIsMinecraftCountsAsFocused() {
		StartupFocus f = new StartupFocus();
		assertEquals(0, f.next(0, false, Front.SELF));
		assertTrue(f.done());
	}

	@Test
	void neverFocusedUserWentElsewhereStops() {
		StartupFocus f = new StartupFocus();
		assertEquals(1, f.next(0, false, Front.LAUNCHER));
		assertEquals(0, f.next(E, false, Front.OTHER), "the player picked another app: leave them there");
		assertTrue(f.done());
		assertEquals(0, f.next(2 * E, false, Front.LAUNCHER), "even if they come back to the Terminal later");
		assertEquals(0, f.next(3 * E, false, Front.LAUNCHER));
	}

	@Test
	void otherAppFrontmostFromTheStartNeverAsks() {
		StartupFocus f = new StartupFocus();
		assertEquals(0, f.next(0, false, Front.OTHER));
		assertTrue(f.done());
	}

	@Test
	void unknownFrontmostWaitsThenGivesUp() {
		StartupFocus f = new StartupFocus();
		assertEquals(0, f.next(0, false, Front.UNKNOWN));
		assertEquals(1, f.next(1_000, false, Front.LAUNCHER));
		assertEquals(0, f.next(1_000 + E, false, Front.UNKNOWN));
		assertEquals(0, f.next(StartupFocus.WINDOW_MS + 1, false, Front.LAUNCHER), "never later than the window");
		assertTrue(f.done());
	}

	@Test
	void classifiesTheFrontmostApp() {
		assertEquals(Front.SELF, StartupFocus.classify(null, 42, 42));
		assertEquals(Front.LAUNCHER, StartupFocus.classify("com.apple.Terminal", 7, 42));
		assertEquals(Front.LAUNCHER, StartupFocus.classify("org.prismlauncher.PrismLauncher", 7, 42));
		assertEquals(Front.LAUNCHER, StartupFocus.classify("local.carter.strawhatterminal", 7, 42));
		assertEquals(Front.OTHER, StartupFocus.classify("com.apple.Safari", 7, 42));
		assertEquals(Front.OTHER, StartupFocus.classify(null, 7, 42));
		assertEquals(Front.UNKNOWN, StartupFocus.classify(null, 0, 42));
	}
}
