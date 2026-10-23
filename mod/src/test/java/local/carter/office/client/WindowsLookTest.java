package local.carter.office.client;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.UUID;

import local.carter.office.client.capture.JavaDesktopSource;
import local.carter.office.client.platform.WindowsPlatform;
import local.carter.office.client.render.HologramLook;
import local.carter.office.client.render.HologramLook.Label;
import local.carter.office.client.render.HologramLook.OwnSource;
import local.carter.office.client.render.HologramLook.State;
import local.carter.office.core.HologramSpec;
import local.carter.office.core.Mode;
import local.carter.office.core.Privacy;
import local.carter.office.core.Shape;
import org.junit.jupiter.api.Test;

/** What an owner on Windows sees when their capture can't run: every ended reason maps to a look that says why. */
class WindowsLookTest {
	private static final UUID OWNER = UUID.fromString("00000000-0000-0000-0000-000000000001");
	private static final UUID FRIEND = UUID.fromString("00000000-0000-0000-0000-000000000002");

	private static HologramSpec spec(final Mode mode, final String key) {
		return new HologramSpec(OWNER, Shape.WIDE_16_9, mode, Privacy.PUBLIC, 1, key, 2.0, 0, 1, 0, 0f);
	}

	@Test
	void endedReasonsMapToLooks() {
		assertEquals(OwnSource.READY, WindowsPlatform.ownSourceFor(null));
		assertEquals(OwnSource.WINDOW_CLOSED, WindowsPlatform.ownSourceFor(JavaDesktopSource.REASON_WINDOW_CLOSED));
		assertEquals(OwnSource.PICK_WINDOW, WindowsPlatform.ownSourceFor(JavaDesktopSource.REASON_EXTEND));
		for (String r : new String[] {JavaDesktopSource.REASON_ARM, JavaDesktopSource.REASON_NO_LIBRARY,
				JavaDesktopSource.REASON_CANT_CAPTURE, JavaDesktopSource.REASON_FAILED, JavaDesktopSource.REASON_NOT_WINDOWS,
				"something new"}) {
			assertEquals(OwnSource.UNAVAILABLE, WindowsPlatform.ownSourceFor(r), r); // never "Connecting…" forever
		}
	}

	@Test
	void extendHologramOnWindowsAsksToPickAWindow() {
		HologramSpec ext = spec(Mode.EXTEND, null);
		State s = HologramLook.forViewer(ext, OWNER, true, false, OwnSource.PICK_WINDOW);
		assertEquals(State.PICK_WINDOW, s);
		Label l = HologramLook.label(s, ext, true, "Carter");
		assertEquals("Pick a window", l.title());
		assertEquals("Right-click the emitter", l.subtitle());
	}

	@Test
	void unavailableShowsTheShortReasonToTheOwnerOnly() {
		HologramSpec win = spec(Mode.WINDOW, "notepad.exe|10");
		State s = HologramLook.forViewer(win, OWNER, true, true, OwnSource.UNAVAILABLE);
		assertEquals(State.UNAVAILABLE, s); // even with an old frame: the source has stopped
		Label l = HologramLook.label(s, win, true, "Carter", JavaDesktopSource.REASON_ARM);
		assertEquals("Screens unavailable", l.title());
		assertTrue(l.subtitle().length() <= HologramLook.MAX_DETAIL, l.subtitle());
		assertTrue(l.subtitle().startsWith("Window capture isn"), l.subtitle());
		assertEquals("", HologramLook.label(s, win, false, "Carter", JavaDesktopSource.REASON_ARM).subtitle());
		// friends never see the owner's capture problems
		assertFalse(HologramLook.forViewer(win, FRIEND, true, true, OwnSource.UNAVAILABLE) == State.UNAVAILABLE);
	}

	@Test
	void shortReasonsFitWithoutTrimming() {
		for (String r : new String[] {JavaDesktopSource.REASON_NO_LIBRARY, JavaDesktopSource.REASON_CANT_CAPTURE,
				JavaDesktopSource.REASON_FAILED, JavaDesktopSource.REASON_NOT_WINDOWS}) {
			assertEquals(r, HologramLook.label(State.UNAVAILABLE, spec(Mode.WINDOW, "a.exe|1"), true, "", r).subtitle());
		}
	}
}
