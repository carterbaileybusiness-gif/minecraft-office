package local.carter.office.client.helper;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

import local.carter.office.client.helper.HelperProtocol.CreateDisplay;
import local.carter.office.client.helper.HelperProtocol.Display;
import local.carter.office.client.helper.HelperProtocol.Message;
import local.carter.office.client.helper.HelperProtocol.RemoveDisplay;
import local.carter.office.client.helper.HelperProtocol.Welcome;

/**
 * Against the real OfficeHelper.app (no capture: that needs Screen Recording). Runs only with
 * {@code -Doffice.liveHelperTest=true} on a Mac with a built helper ({@code helper-mac/build/OfficeHelper.app}, or
 * {@code -Doffice.helperPath}). Leaves no helper or virtual display running.
 */
@Tag("liveHelper")
@EnabledIfSystemProperty(named = "office.liveHelperTest", matches = "true")
class HelperLiveTest {
	private static final String EXECUTABLE = "OfficeHelper.app/Contents/MacOS/OfficeHelper";

	@Test
	void helloCreateDisplayRemoveDisplayThroughTheRealHelper() throws Exception {
		Path app = Path.of(System.getProperty(HelperInstaller.HELPER_PATH_PROPERTY, "helper-mac/build/OfficeHelper.app")).toAbsolutePath();
		assumeTrue(Files.isDirectory(app), "built helper at " + app);
		assumeTrue(helperProcesses().isEmpty(), "no helper already running");

		HelperConnectionTest.Events events = new HelperConnectionTest.Events();
		HelperConnection connection = new HelperConnection(HelperInstaller.defaultSupportDir(), (int) ProcessHandle.current().pid(),
				() -> HelperInstaller.launch(app), events);
		try {
			connection.start();
			Welcome w = events.connected.poll(20, TimeUnit.SECONDS);
			assertNotNull(w, "WELCOME from the launched helper");
			assertEquals(HelperProtocol.VERSION, w.version());
			System.out.println("[live] WELCOME screenRecording=" + w.screenRecording() + " accessibility=" + w.accessibility());

			// A second mod connection is refused with ERROR busy and keeps retrying; the first stays connected.
			HelperConnectionTest.Events second = new HelperConnectionTest.Events();
			HelperConnection other = new HelperConnection(HelperInstaller.defaultSupportDir(), 1, null, second);
			other.start();
			assertNull(second.connected.poll(1500, TimeUnit.MILLISECONDS), "second client refused while one is connected");
			other.close();
			assertTrue(connection.isConnected());

			long t0 = System.nanoTime();
			assertTrue(connection.send(new CreateDisplay(1, 1920, 1080)));
			Display d = nextDisplay(events, 10);
			assertNotNull(d, "DISPLAY for slot 1");
			System.out.printf("[live] DISPLAY slot=%d id=%d %dx%d after %d ms%n", d.slot(), d.displayId(), d.w(), d.h(),
					TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - t0));
			assertEquals(new Display(1, d.displayId(), 1920, 1080), d);

			assertTrue(connection.send(new RemoveDisplay(1)));
			Thread.sleep(300);
		} finally {
			connection.close();
			long end = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
			while (!helperProcesses().isEmpty() && System.nanoTime() < end) {
				Thread.sleep(100);
			}
			List<ProcessHandle> left = helperProcesses();
			left.forEach(ProcessHandle::destroy); // SIGTERM: the helper removes its displays, then quits
			assertTrue(left.isEmpty(), "the helper quit when the mod disconnected");
		}
	}

	private static Display nextDisplay(final HelperConnectionTest.Events events, final int seconds) throws InterruptedException {
		long end = System.nanoTime() + TimeUnit.SECONDS.toNanos(seconds);
		while (System.nanoTime() < end) {
			Message m = events.messages.poll(100, TimeUnit.MILLISECONDS);
			if (m instanceof Display d) {
				return d;
			}
			if (m != null) {
				System.out.println("[live] other message: " + m);
			}
		}
		return null;
	}

	static List<ProcessHandle> helperProcesses() {
		return ProcessHandle.allProcesses()
				.filter(p -> p.info().command().map(c -> c.endsWith(EXECUTABLE)).orElse(false))
				.toList();
	}
}
