package local.carter.office.client;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.ByteBuffer;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import local.carter.office.client.capture.FrameSink;
import local.carter.office.client.capture.JavaDesktopSource;
import local.carter.office.client.capture.Target;
import local.carter.office.client.render.HologramLook;
import local.carter.office.core.Mode;
import local.carter.office.core.Shape;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.DisabledOnOs;
import org.junit.jupiter.api.condition.OS;

/** The parts of Windows capture that run without Windows: target checks and frame pacing (never loads natives). */
class JavaDesktopSourceTest {
	static final class Recorder implements FrameSink {
		final List<String> ended = new CopyOnWriteArrayList<>();
		volatile int fulls;

		@Override
		public void full(final int w, final int h, final ByteBuffer rgba) {
			fulls++;
		}

		@Override
		public void patch(final int x, final int y, final int w, final int h, final ByteBuffer rgba) {}

		@Override
		public void ended(final String reason) {
			ended.add(reason);
		}
	}

	@Test
	void extendTargetEndsWithComingSoon() {
		Recorder r = new Recorder();
		JavaDesktopSource s = new JavaDesktopSource();
		s.start(new Target(Mode.EXTEND, 1, null, Shape.WIDE_16_9), 30, r);
		assertEquals(List.of(JavaDesktopSource.REASON_EXTEND), r.ended);
		assertFalse(s.isLive());
		s.stop();
		assertEquals(1, r.ended.size());
	}

	@Test
	void missingOrForeignWindowKeyEndsAsWindowClosed() {
		for (String key : new String[] {null, "", "mac:42", "notepad.exe|zz"}) {
			Recorder r = new Recorder();
			JavaDesktopSource s = new JavaDesktopSource();
			s.start(new Target(Mode.WINDOW, 1, key, Shape.WIDE_16_9), 30, r);
			assertEquals(List.of(JavaDesktopSource.REASON_WINDOW_CLOSED), r.ended, "key " + key);
			assertFalse(s.isLive());
			assertEquals(0, r.fulls);
		}
	}

	@Test
	@DisabledOnOs(OS.WINDOWS)
	void validKeyOffWindowsEndsWithAReasonWithoutNatives() {
		Recorder r = new Recorder();
		JavaDesktopSource s = new JavaDesktopSource();
		s.start(new Target(Mode.WINDOW, 1, "notepad.exe|10", Shape.WIDE_16_9), 30, r);
		assertEquals(List.of(JavaDesktopSource.REASON_NOT_WINDOWS), r.ended);
		assertFalse(s.isLive());
	}

	/** OwnScreens' look provider reads source.status(): the ended reason maps to Task 11's looks. */
	@Test
	@DisabledOnOs(OS.WINDOWS)
	void statusFollowsTheEndedReason() {
		JavaDesktopSource s = new JavaDesktopSource();
		assertEquals(HologramLook.OwnSource.READY, s.status());
		s.start(new Target(Mode.EXTEND, 1, null, Shape.WIDE_16_9), 30, new Recorder());
		assertEquals(HologramLook.OwnSource.PICK_WINDOW, s.status());
		s.start(new Target(Mode.WINDOW, 1, "mac:42", Shape.WIDE_16_9), 30, new Recorder());
		assertEquals(HologramLook.OwnSource.WINDOW_CLOSED, s.status());
		s.start(new Target(Mode.WINDOW, 1, "notepad.exe|10", Shape.WIDE_16_9), 30, new Recorder());
		assertEquals(HologramLook.OwnSource.UNAVAILABLE, s.status());
	}

	@Test
	@DisabledOnOs(OS.WINDOWS)
	void mirrorOffWindowsEndsWithAReasonWithoutNatives() {
		Recorder r = new Recorder();
		JavaDesktopSource s = new JavaDesktopSource();
		s.start(new Target(Mode.MIRROR, 0, null, Shape.WIDE_16_9), 30, r);
		assertEquals(List.of(JavaDesktopSource.REASON_NOT_WINDOWS), r.ended);
		assertFalse(s.isLive());
	}

	@Test
	void mirrorPicksThePrimaryScreen() {
		assertEquals(Long.valueOf(1), JavaDesktopSource.pickScreen(List.of(0L, 1L, 2L), 1), "the primary monitor");
		assertEquals(Long.valueOf(0), JavaDesktopSource.pickScreen(List.of(0L, 2L), 1), "primary not listed: the first screen");
		assertEquals(Long.valueOf(3), JavaDesktopSource.pickScreen(List.of(3L), -1), "none marked primary");
		assertEquals(null, JavaDesktopSource.pickScreen(List.of(), 0), "no screens");
	}

	@Test
	void mirrorCapturesAtTenFpsEvenWhileWorking() {
		Target t = new Target(Mode.MIRROR, 0, null, Shape.WIDE_16_9);
		assertEquals(10, JavaDesktopSource.fpsFor(t, 30, null));
		assertEquals(10, JavaDesktopSource.fpsFor(t, 30, "notepad.exe|10"));
	}

	@Test
	void excludeFromCaptureNeedsWindows10_2004() {
		assertTrue(local.carter.office.client.platform.Win32.supportsExcludeFromCapture(19041));
		assertTrue(local.carter.office.client.platform.Win32.supportsExcludeFromCapture(26100));
		assertFalse(local.carter.office.client.platform.Win32.supportsExcludeFromCapture(18363), "1909 would black Minecraft out instead");
		assertTrue(local.carter.office.client.platform.Win32.supportsExcludeFromCapture(0), "unknown build: try, then read it back");
	}

	@Test
	void stopWithoutStartIsHarmless() {
		JavaDesktopSource s = new JavaDesktopSource();
		s.stop();
		s.stop();
		assertFalse(s.isLive());
	}

	@Test
	void tenFpsNormallyThirtyWhileWorkingOnThatWindow() {
		Target t = new Target(Mode.WINDOW, 1, "notepad.exe|10", Shape.WIDE_16_9);
		assertEquals(10, JavaDesktopSource.fpsFor(t, 30, null));
		assertEquals(10, JavaDesktopSource.fpsFor(t, 30, "chrome.exe|20"));
		assertEquals(30, JavaDesktopSource.fpsFor(t, 30, "notepad.exe|10"));
		assertEquals(5, JavaDesktopSource.fpsFor(t, 5, "notepad.exe|10")); // never above what the caller allows
		assertEquals(1, JavaDesktopSource.fpsFor(t, 0, null));
	}
}
