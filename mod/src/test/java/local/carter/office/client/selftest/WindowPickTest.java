package local.carter.office.client.selftest;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.Test;

import local.carter.office.client.platform.Platform;
import local.carter.office.core.HologramSpec;

/** The self-test's window choice: reading never asks for a new list (no LIST_WINDOWS ping-pong), app names only. */
class WindowPickTest {
	private static final Platform.WindowInfo A = new Platform.WindowInfo("a", "Google Chrome", "Bank login", 1, 1);
	private static final Platform.WindowInfo B = new Platform.WindowInfo("b", "Google Chrome", "Mail", 1, 1);
	private static final Platform.WindowInfo C = new Platform.WindowInfo("c", "Notes", "Diary", 1, 1);

	@Test
	void readingTheListNeverRequestsANewOneOrStartsAnything() throws Exception {
		CountingPlatform p = new CountingPlatform(List.of(A, C));
		for (int i = 0; i < 5; i++) { // e.g. five list replies arriving: each only re-reads
			assertEquals(List.of(A, C), WindowPick.read(p));
		}
		assertEquals(0, p.refreshes, "no LIST_WINDOWS from reading");
		assertEquals(0, p.readinessChecks, "no helper start from reading");
	}

	@Test
	void theSameListAgainDoesNotRedrawAndThePickSurvivesANewList() {
		WindowPick pick = new WindowPick();
		assertEquals("looking…", pick.label("none"));
		assertTrue(pick.apply(List.of(A, B, C)));
		assertFalse(pick.apply(List.of(A, B, C)), "unchanged list: no rebuild");
		assertEquals("None (skip)", pick.label("none"));
		assertTrue(pick.cycle());
		assertTrue(pick.cycle());
		assertEquals("b", pick.choice().key());
		assertEquals("Google Chrome (2)", pick.choiceLabel());
		assertTrue(pick.apply(List.of(C, B)));
		assertEquals("b", pick.choice().key(), "still picked after the list changed");
		pick.cycle();
		assertNull(pick.choice(), "cycles back to none");
	}

	@Test
	void labelsAreAppNamesOnly() {
		List<String> labels = WindowPick.labels(List.of(A, B, C));
		assertEquals(List.of("Google Chrome", "Google Chrome (2)", "Notes"), labels);
		for (String l : labels) {
			assertFalse(l.contains("Bank") || l.contains("Mail") || l.contains("Diary"), l);
		}
		WindowPick empty = new WindowPick();
		empty.apply(List.of());
		assertEquals("none yet", empty.label("none yet"));
		assertFalse(empty.cycle());
	}

	private static final class CountingPlatform implements Platform {
		private final List<WindowInfo> list;
		int refreshes;
		int readinessChecks;

		CountingPlatform(final List<WindowInfo> list) {
			this.list = list;
		}

		@Override
		public boolean supportsExtend() {
			return true;
		}

		@Override
		public List<WindowInfo> windows() {
			return list;
		}

		@Override
		public void refreshWindows() {
			refreshes++;
		}

		@Override
		public Readiness readiness() {
			readinessChecks++;
			return new Readiness(true, true, true, "x");
		}

		@Override
		public void zoomIn(final HologramSpec spec, final Rect mcWindow) {}

		@Override
		public void zoomOut() {}

		@Override
		public void onExitRequested(final Runnable r) {}
	}
}
