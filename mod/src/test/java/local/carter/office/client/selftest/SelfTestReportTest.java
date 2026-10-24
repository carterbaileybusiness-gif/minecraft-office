package local.carter.office.client.selftest;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.ZonedDateTime;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;

import local.carter.office.client.capture.CaptureSource;
import local.carter.office.client.capture.FrameSink;
import local.carter.office.client.capture.Target;
import local.carter.office.client.platform.Platform;
import local.carter.office.client.selftest.SelfTestReport.Status;
import local.carter.office.core.HologramSpec;

/**
 * The self-test report a friend pastes to Carter: OS facts plus one result line for every step, app names but never
 * window titles (not even inside an error message), no home folder, no server address, no pixels.
 */
class SelfTestReportTest {
	private static final Platform.WindowInfo BUDGET = new Platform.WindowInfo("w-17", "Microsoft Excel", "Salaries 2026 (secret).xlsx", 1280, 800);
	private static final Platform.WindowInfo BANK = new Platform.WindowInfo("w-18", "Google Chrome", "Carter's Bank - Sign in", 1440, 900);
	private static final Platform.WindowInfo FINDER = new Platform.WindowInfo("w-19", "Finder", "Finder", 800, 600);

	@Test
	void reportHasAppNamesButNoWindowTitlesAnywhere() throws Exception {
		FakePlatform platform = new FakePlatform(List.of(BUDGET, BANK, FINDER));
		// the real source "fails" with a message that quotes the window title (as an OS error might)
		platform.source = new FakeSource(null, "could not capture 'Salaries 2026 (secret).xlsx' at /Users/friend/Library/x");
		SelfTestReport report = new SelfTestReport(new PrivateText("/Users/friend", "friend", null, "/tmp/x"));
		report.hideWindowTitles(platform.windows());
		SelfTest.basicInfo(report);
		SelfTest.Context ctx = new SelfTest.Context(platform, BUDGET, null, report);

		String text = runAll(ctx);

		assertFalse(text.contains("Salaries"), text);
		assertFalse(text.toLowerCase(Locale.ROOT).contains("secret"), text);
		assertFalse(text.contains("Bank"), text);
		assertFalse(text.contains("/Users/friend"), "home folder hidden:\n" + text);
		assertTrue(text.contains("Microsoft Excel"), "the picked window is named by its app:\n" + text);
		assertTrue(text.contains(SelfTestReport.HIDDEN_TITLE), "the title inside the error was replaced:\n" + text);
	}

	@Test
	void reportHasTheOsAndAResultLineForEveryStep() throws Exception {
		FakePlatform platform = new FakePlatform(List.of(BANK));
		platform.source = new FakeSource(new int[] {1440, 900}, null);
		SelfTestReport report = new SelfTestReport();
		report.hideWindowTitles(platform.windows());
		SelfTest.basicInfo(report);
		String text = runAll(new SelfTest.Context(platform, BANK, null, report));

		assertTrue(text.contains("OS: "), text);
		assertTrue(text.contains(System.getProperty("os.name")), text);
		assertTrue(text.contains("Java: ") && text.contains("CPU cores: ") && text.contains("Max memory: "), text);
		List<SelfTest.StepDef> steps = SelfTest.steps();
		assertTrue(steps.size() >= 7, "system, platform, test pattern, real capture, JPEG, UDP, zoom");
		List<String> resultLines = text.lines().filter(l -> l.startsWith("✓ ") || l.startsWith("✗ ") || l.startsWith("– ")).toList();
		assertEquals(steps.size(), resultLines.size(), text);
		for (int i = 0; i < steps.size(); i++) {
			assertTrue(resultLines.get(i).contains(steps.get(i).name()), "line for " + steps.get(i).name() + ":\n" + text);
		}
		assertTrue(text.contains("Google Chrome") && text.contains("1440x900"), "real capture: app + frame size only:\n" + text);
		assertTrue(text.contains("Summary: "), text);
		for (SelfTestReport.Line l : report.lines()) {
			assertTrue(l.status() == Status.PASS || l.status() == Status.FAIL || l.status() == Status.SKIP, l.toString());
		}
	}

	@Test
	void noWindowPickedSkipsRealCaptureAndNeverStartsTheSource() throws Exception {
		FakePlatform platform = new FakePlatform(List.of(BANK));
		platform.source = new FakeSource(new int[] {100, 100}, null);
		SelfTestReport report = new SelfTestReport();
		runAll(new SelfTest.Context(platform, null, null, report));
		assertEquals(0, ((FakeSource) platform.source).starts, "never captures without the player choosing a window");
		assertEquals(Status.SKIP, line(report, "real-capture").status());
	}

	@Test
	void unknownTitlesInInfoAndAddressesInDetailsAreScrubbed() {
		SelfTestReport report = new SelfTestReport(new PrivateText("/home/x", "x", null, "/tmp/x"));
		report.hideWindowTitles(List.of(BANK));
		report.info("Java", "25.0.4.1 (Eclipse Adoptium)");
		report.result(new SelfTestReport.Line("udp", "Shared-screen link (UDP)", Status.FAIL,
				"PortUnreachableException: 203.0.113.7:8123 unreachable; [2001:db8::1]:8123; at 12:34:56", 5));
		String text = report.render(ZonedDateTime.now());
		assertFalse(text.contains("203.0.113"), text);
		assertFalse(text.contains("2001:db8"), text);
		assertTrue(text.contains("25.0.4.1"), "versions in info lines are kept:\n" + text);
		assertTrue(text.contains("12:34:56"), "clock times aren't addresses:\n" + text);
	}

	@Test
	void titlesThatArePartOfAnAppNameStayAndShortOnesToo() {
		SelfTestReport report = new SelfTestReport(new PrivateText("/home/x", "x", null, "/tmp/x"));
		report.hideWindowTitles(List.of(new Platform.WindowInfo("a", "Google Chrome", "Chrome", 1, 1),
				new Platform.WindowInfo("b", "Mail", "Inbox", 1, 1), new Platform.WindowInfo("c", "Terminal", "zsh", 1, 1)));
		String s = report.scrub("Google Chrome, Mail: Inbox, zsh");
		assertEquals("Google Chrome, Mail: " + SelfTestReport.HIDDEN_TITLE + ", zsh", s);
	}

	@Test
	void personalPathsInStepDetailsAreScrubbed() throws Exception {
		SelfTestReport report = new SelfTestReport(new PrivateText("C:\\Users\\John Smith", "John Smith",
				"C:\\Users\\John Smith\\AppData\\Roaming\\PrismLauncher\\instances\\Office\\.minecraft",
				"C:\\Users\\JOHNSM~1\\AppData\\Local\\Temp\\"));
		report.result(new SelfTestReport.Line("platform", "Window capture natives", Status.FAIL,
				"UnsatisfiedLinkError: c:\\users\\john smith\\appdata\\local\\temp\\webrtc-java123.dll; "
						+ "C:\\Users\\JOHNSM~1\\AppData\\Local\\Temp\\lwjgl\\x.dll; written by JOHN SMITH", 3));
		String text = report.render(ZonedDateTime.now());
		assertFalse(text.toLowerCase(Locale.ROOT).contains("john"), text);
		assertFalse(text.toUpperCase(Locale.ROOT).contains("JOHNSM"), text);
		assertTrue(text.contains("webrtc-java123.dll") && text.contains(PrivateText.USER), text);
	}

	/** Writes a sample report (this machine, fake platform) to build/selftest-sample.txt for the task report. */
	@Test
	void writesASampleReport() throws Exception {
		FakePlatform platform = new FakePlatform(List.of(BANK, BUDGET));
		platform.source = new FakeSource(new int[] {1440, 900}, null);
		SelfTestReport report = new SelfTestReport();
		report.hideWindowTitles(platform.windows());
		SelfTest.basicInfo(report);
		report.info("GPU", "(not read in unit tests)");
		String text = runAll(new SelfTest.Context(platform, BANK, null, report));
		Path out = Path.of("build", "selftest-sample.txt");
		Files.createDirectories(out.getParent());
		Files.writeString(out, text, StandardCharsets.UTF_8);
		assertTrue(Files.size(out) > 100);
	}

	// ------------------------------------------------------------------ helpers

	static String runAll(final SelfTest.Context ctx) throws InterruptedException {
		CountDownLatch done = new CountDownLatch(1);
		AtomicReference<String> text = new AtomicReference<>();
		SelfTest.start(SelfTest.steps(), ctx, new SelfTest.Listener() {
			@Override
			public void changed(final SelfTestReport.Line line) {}

			@Override
			public void finished(final SelfTestReport report, final String rendered) {
				text.set(rendered);
				done.countDown();
			}
		});
		assertTrue(done.await(40, TimeUnit.SECONDS), "self-test finished");
		return text.get();
	}

	static SelfTestReport.Line line(final SelfTestReport report, final String id) {
		return report.lines().stream().filter(l -> l.id().equals(id)).findFirst().orElseThrow();
	}

	/** A platform that can capture (like a Mac with permissions), with a scripted window source. */
	static final class FakePlatform implements Platform {
		private final List<WindowInfo> windows;
		@Nullable CaptureSource source;

		FakePlatform(final List<WindowInfo> windows) {
			this.windows = windows;
		}

		@Override
		public boolean supportsExtend() {
			return true;
		}

		@Override
		public List<WindowInfo> windows() {
			return windows;
		}

		@Override
		public void zoomIn(final HologramSpec spec, final Rect mcWindow) {}

		@Override
		public void zoomOut() {}

		@Override
		public void onExitRequested(final Runnable r) {}

		@Override
		public @Nullable CaptureSource newOwnSource() {
			return source;
		}

		@Override
		public Readiness readiness() {
			return new Readiness(true, true, true, "fake helper ready");
		}
	}

	/** Delivers one full frame of {@code size} (grey, not black) or ends with {@code endReason}. */
	static final class FakeSource implements CaptureSource {
		private final int @Nullable [] size;
		private final @Nullable String endReason;
		int starts;

		FakeSource(final int @Nullable [] size, final @Nullable String endReason) {
			this.size = size;
			this.endReason = endReason;
		}

		@Override
		public void start(final Target t, final int maxFps, final FrameSink sink) {
			starts++;
			Thread th = new Thread(() -> {
				if (size != null) {
					ByteBuffer px = ByteBuffer.allocate(size[0] * size[1] * 4);
					for (int i = 0; i < px.capacity(); i++) {
						px.put(i, (byte) 128);
					}
					sink.full(size[0], size[1], px);
				} else {
					sink.ended(endReason);
				}
			});
			th.setDaemon(true);
			th.start();
		}

		@Override
		public void stop() {}

		@Override
		public boolean isLive() {
			return false;
		}
	}
}
