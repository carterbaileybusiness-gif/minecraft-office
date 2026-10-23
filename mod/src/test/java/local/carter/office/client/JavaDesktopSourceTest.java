package local.carter.office.client;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

import java.nio.ByteBuffer;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import local.carter.office.client.capture.FrameSink;
import local.carter.office.client.capture.JavaDesktopSource;
import local.carter.office.client.capture.Target;
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
