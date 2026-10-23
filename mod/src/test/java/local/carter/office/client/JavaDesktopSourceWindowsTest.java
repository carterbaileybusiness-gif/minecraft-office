package local.carter.office.client;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.ByteBuffer;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;
import java.util.function.LongSupplier;

import com.sun.jna.Native;
import com.sun.jna.Pointer;
import com.sun.jna.win32.StdCallLibrary;
import com.sun.jna.win32.W32APIOptions;

import dev.onvoid.webrtc.media.FourCC;
import dev.onvoid.webrtc.media.video.NativeI420Buffer;
import dev.onvoid.webrtc.media.video.VideoBufferConverter;
import dev.onvoid.webrtc.media.video.desktop.WindowCapturer;

import local.carter.office.client.capture.FrameSink;
import local.carter.office.client.capture.JavaDesktopSource;
import local.carter.office.client.capture.Target;
import local.carter.office.client.capture.WebRtcNatives;
import local.carter.office.client.interact.WindowListProvider.WindowChoice;
import local.carter.office.client.platform.Platform;
import local.carter.office.client.platform.Win32;
import local.carter.office.client.platform.WindowKey;
import local.carter.office.client.platform.WindowsPlatform;
import local.carter.office.core.HologramSpec;
import local.carter.office.core.Mode;
import local.carter.office.core.Privacy;
import local.carter.office.core.Shape;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;

/**
 * Windows capture and window control against a real Notepad window (CI: GitHub Actions windows-latest). If the runner
 * has no interactive desktop the GUI tests are skipped with the reason logged — never passed without checking.
 */
@EnabledOnOs(OS.WINDOWS)
class JavaDesktopSourceWindowsTest {
	/** keybd_event, to type into the low-level keyboard hook like a real keyboard would. */
	public interface Keys extends StdCallLibrary {
		Keys INSTANCE = Native.load("user32", Keys.class, W32APIOptions.DEFAULT_OPTIONS);

		void keybd_event(byte vk, byte scan, int flags, Pointer extra);
	}

	static final class Recorder implements FrameSink {
		final List<String> ended = new CopyOnWriteArrayList<>();
		final AtomicInteger fulls = new AtomicInteger();
		volatile int w;
		volatile int h;
		volatile int litPixels;
		volatile int alphaOk;

		@Override
		public void full(final int width, final int height, final ByteBuffer rgba) {
			w = width;
			h = height;
			int lit = 0;
			int opaque = 0;
			int p = rgba.position();
			int n = width * height;
			for (int i = 0; i < n; i += 97) {
				int o = p + i * 4;
				if ((rgba.get(o) | rgba.get(o + 1) | rgba.get(o + 2)) != 0) {
					lit++;
				}
				if (rgba.get(o + 3) == (byte) 255) {
					opaque++;
				}
			}
			litPixels = lit;
			alphaOk = opaque == (n + 96) / 97 ? 1 : 0;
			fulls.incrementAndGet();
		}

		@Override
		public void patch(final int x, final int y, final int width, final int height, final ByteBuffer rgba) {}

		@Override
		public void ended(final String reason) {
			ended.add(reason);
		}
	}

	private static boolean waitUntil(final BooleanSupplier cond, final long ms) throws InterruptedException {
		long end = System.currentTimeMillis() + ms;
		while (System.currentTimeMillis() < end) {
			if (cond.getAsBoolean()) {
				return true;
			}
			Thread.sleep(50);
		}
		return cond.getAsBoolean();
	}

	private static long waitForNonZero(final LongSupplier value, final long ms) throws InterruptedException {
		long end = System.currentTimeMillis() + ms;
		while (System.currentTimeMillis() < end) {
			long v = value.getAsLong();
			if (v != 0) {
				return v;
			}
			Thread.sleep(100);
		}
		return value.getAsLong();
	}

	/** Skips the test, printing the reason to the CI log first (JUnit only keeps it in the XML report). */
	private static void skip(final String reason) {
		System.out.println("[win-test] SKIP: " + reason);
		Assumptions.abort(reason);
	}

	/** Starts Notepad and returns its top-level window, or skips the test (with the reason) if no window can appear. */
	private static long startNotepad(final Win32 w32) throws Exception {
		if (!w32.hasInputDesktop()) {
			skip("no interactive desktop on this runner (OpenInputDesktop failed), so no window can be shown or captured");
		}
		if (!waitUntil(() -> w32.findWindow("Notepad") == 0, 10_000)) { // a previous test's Notepad may still be closing
			skip("a Notepad window is already open; refusing to touch it");
		}
		new ProcessBuilder("notepad.exe").start();
		long hwnd = waitForNonZero(() -> w32.findWindow("Notepad"), 20_000);
		if (hwnd == 0) {
			skip("notepad.exe started but no Notepad window appeared within 20 s (runner desktop not interactive?)");
		}
		System.out.println("[win-test] notepad hwnd=" + Long.toHexString(hwnd) + " exe=" + w32.exeName(hwnd) + " rect=" + w32.windowRect(hwnd));
		return hwnd;
	}

	private static void kill(final Win32 w32, final long hwnd) {
		if (hwnd == 0) {
			return;
		}
		int pid = w32.processId(hwnd);
		if (pid > 0) {
			ProcessHandle.of(pid).ifPresent(ProcessHandle::destroyForcibly);
		}
	}

	private static WindowChoice findChoice(final WindowsPlatform p, final long hwnd) throws InterruptedException {
		for (int i = 0; i < 50; i++) {
			for (WindowChoice c : p.windows()) {
				WindowKey k = WindowKey.parse(c.key());
				if (k != null && k.hwnd() == hwnd) {
					return c;
				}
			}
			Thread.sleep(100);
		}
		return null;
	}

	@Test
	void nativesLoadAndPlatformIsWindows() {
		assertTrue(WebRtcNatives.supported(), "this runner should be 64-bit Windows: " + System.getProperty("os.arch"));
		WebRtcNatives.load();
		WindowCapturer c = new WindowCapturer();
		try {
			assertNotNull(c.getDesktopSources());
		} finally {
			c.dispose();
		}
		assertTrue(Platform.current() instanceof WindowsPlatform);
		assertFalse(Platform.current().supportsExtend());
	}

	@Test
	void i420RoundTripComesOutAsRgba() throws Exception {
		WebRtcNatives.load();
		int w = 16;
		int h = 16;
		byte[] bgra = new byte[w * h * 4];
		for (int i = 0; i < w * h; i++) {
			bgra[i * 4] = 0; // B
			bgra[i * 4 + 1] = 0; // G
			bgra[i * 4 + 2] = (byte) 255; // R
			bgra[i * 4 + 3] = (byte) 255;
		}
		NativeI420Buffer i420 = NativeI420Buffer.allocate(w, h);
		try {
			// The capturer converts its BGRA desktop frame with libyuv FOURCC_ARGB, exactly like this.
			VideoBufferConverter.convertToI420(bgra, i420, FourCC.ARGB);
			ByteBuffer out = ByteBuffer.allocateDirect(w * h * 4);
			JavaDesktopSource.toRgba(i420, out);
			int r = out.get(0) & 0xFF;
			int g = out.get(1) & 0xFF;
			int b = out.get(2) & 0xFF;
			assertTrue(r > 200 && g < 60 && b < 60, "red should stay red: r=" + r + " g=" + g + " b=" + b);
			assertEquals(255, out.get(3) & 0xFF);
		} finally {
			i420.release();
		}
	}

	@Test
	void capturesNotepadAndEndsWhenItIsKilled() throws Exception {
		Win32 w32 = Win32.get();
		long hwnd = startNotepad(w32);
		JavaDesktopSource source = new JavaDesktopSource();
		try {
			WindowChoice choice = findChoice(new WindowsPlatform(), hwnd);
			assertNotNull(choice, "Notepad (hwnd " + Long.toHexString(hwnd) + ") missing from windows(): " + new WindowsPlatform().windows());
			assertEquals("notepad", choice.title().toLowerCase(java.util.Locale.ROOT), "title must be the app name only");
			assertTrue(choice.key().toLowerCase(java.util.Locale.ROOT).startsWith("notepad.exe|"), choice.key());

			Recorder rec = new Recorder();
			source.start(new Target(Mode.WINDOW, 1, choice.key(), Shape.WIDE_16_9), 30, rec);
			waitUntil(() -> rec.fulls.get() > 0 || !rec.ended.isEmpty(), 15_000);
			System.out.println("[win-test] frames=" + rec.fulls.get() + " size=" + rec.w + "x" + rec.h + " litSamples=" + rec.litPixels
					+ " ended=" + rec.ended);
			assertEquals(List.of(), rec.ended, "capture ended early");
			assertTrue(rec.fulls.get() > 0, "no frame captured from Notepad within 15 s");
			assertTrue(rec.w > 0 && rec.h > 0, "frame size " + rec.w + "x" + rec.h);
			assertEquals(1, rec.alphaOk, "every pixel must be opaque");
			assertTrue(source.isLive());

			kill(w32, hwnd);
			assertTrue(waitUntil(() -> !w32.isWindow(hwnd), 10_000), "Notepad window still exists after kill");
			assertTrue(waitUntil(() -> !rec.ended.isEmpty(), 5_000), "source did not notice the window closing");
			assertEquals(List.of(JavaDesktopSource.REASON_WINDOW_CLOSED), rec.ended);
			assertFalse(source.isLive());
		} finally {
			source.stop();
			kill(w32, hwnd);
		}
	}

	@Test
	void zoomPlacesTheWindowHooksKeysAndRestores() throws Exception {
		Win32 w32 = Win32.get();
		long hwnd = startNotepad(w32);
		WindowsPlatform p = new WindowsPlatform();
		try {
			String key = new WindowKey(w32.exeName(hwnd), hwnd).format();
			Platform.Rect before = w32.windowRect(hwnd);
			HologramSpec spec = new HologramSpec(UUID.randomUUID(), Shape.WIDE_16_9, Mode.WINDOW, Privacy.PUBLIC, 1, key, 2.0, 0, 1, 0, 0f);
			AtomicInteger exits = new AtomicInteger();
			p.onExitRequested(exits::incrementAndGet);

			Platform.Rect area = new Platform.Rect(40, 40, 640, 400);
			p.zoomIn(spec, area); // no Minecraft window in a unit test: uses the given rect
			Platform.Rect visible = w32.visibleFrame(hwnd);
			System.out.println("[win-test] before=" + before + " zoomed visible=" + visible + " raw=" + w32.windowRect(hwnd));
			assertNotNull(visible);
			assertTrue(Math.abs(visible.x() - 40) <= 2 && Math.abs(visible.y() - 40) <= 2, "placed at " + visible);
			assertTrue(Math.abs(visible.w() - 640) <= 2 && Math.abs(visible.h() - 400) <= 2, "sized to " + visible);
			assertTrue(p.keyHookRunning(), "keyboard hook not running while zoomed");

			// A single Esc reaches the app; two within 350 ms ask to exit.
			tapEsc();
			Thread.sleep(600);
			assertEquals(0, exits.get());
			tapEsc();
			Thread.sleep(120);
			tapEsc();
			assertTrue(waitUntil(() -> exits.get() == 1, 3_000), "double Esc did not request exit");

			p.zoomOut();
			assertFalse(p.keyHookRunning(), "keyboard hook still running after zoomOut");
			Platform.Rect after = w32.windowRect(hwnd);
			System.out.println("[win-test] restored=" + after);
			assertEquals(before, after, "window rect not restored");
		} finally {
			p.zoomOut();
			kill(w32, hwnd);
		}
	}

	@Test
	void zoomExitsWhenTheWindowCloses() throws Exception {
		Win32 w32 = Win32.get();
		long hwnd = startNotepad(w32);
		WindowsPlatform p = new WindowsPlatform();
		try {
			String key = new WindowKey(w32.exeName(hwnd), hwnd).format();
			HologramSpec spec = new HologramSpec(UUID.randomUUID(), Shape.WIDE_16_9, Mode.WINDOW, Privacy.PUBLIC, 1, key, 2.0, 0, 1, 0, 0f);
			AtomicInteger exits = new AtomicInteger();
			p.onExitRequested(exits::incrementAndGet);
			p.zoomIn(spec, new Platform.Rect(0, 0, 800, 600));
			kill(w32, hwnd);
			assertTrue(waitUntil(() -> exits.get() == 1, 5_000), "closing the zoomed window did not request exit");
			p.zoomOut(); // window gone: must not throw
			assertFalse(p.keyHookRunning());
		} finally {
			p.zoomOut();
			kill(w32, hwnd);
		}
	}

	private static void tapEsc() throws InterruptedException {
		Keys.INSTANCE.keybd_event((byte) 0x1B, (byte) 0, 0, null);
		Thread.sleep(20);
		Keys.INSTANCE.keybd_event((byte) 0x1B, (byte) 0, 0x0002 /* KEYEVENTF_KEYUP */, null);
	}
}
