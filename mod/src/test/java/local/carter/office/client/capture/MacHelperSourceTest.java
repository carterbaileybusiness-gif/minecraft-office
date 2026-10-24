package local.carter.office.client.capture;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import local.carter.office.client.helper.HelperChannel;
import local.carter.office.client.helper.HelperConnection;
import local.carter.office.client.helper.HelperProtocol.Capture;
import local.carter.office.client.helper.HelperProtocol.CaptureKind;
import local.carter.office.client.helper.HelperProtocol.CreateDisplay;
import local.carter.office.client.helper.HelperProtocol.ListApps;
import local.carter.office.client.helper.HelperProtocol.Display;
import local.carter.office.client.helper.HelperProtocol.Ended;
import local.carter.office.client.helper.HelperProtocol.Frame;
import local.carter.office.client.helper.HelperProtocol.Message;
import local.carter.office.client.helper.HelperProtocol.Patch;
import local.carter.office.client.helper.HelperProtocol.PixelRect;
import local.carter.office.client.helper.HelperProtocol.Ready;
import local.carter.office.client.helper.HelperProtocol.RemoveDisplay;
import local.carter.office.client.helper.HelperProtocol.RequestPermissions;
import local.carter.office.client.helper.HelperProtocol.Stop;
import local.carter.office.client.helper.HelperProtocol.Welcome;
import local.carter.office.client.helper.MacHelper;
import local.carter.office.client.render.HologramLook;
import local.carter.office.core.HologramSpec;
import local.carter.office.core.Mode;
import local.carter.office.core.Privacy;
import local.carter.office.core.Shape;

/** MacHelperSource + MacHelper routing, driven with helper messages on a fake channel. */
class MacHelperSourceTest {
	private static final UUID ME = UUID.fromString("00000000-0000-0000-0000-00000000000a");
	private static final String NOTES = "com.apple.Notes|4321";

	private final List<Message> sent = new ArrayList<>();
	private boolean channelOpen;
	private MacHelper helper;
	private final RecordingSink sink = new RecordingSink();
	private final List<Long> delays = new ArrayList<>();
	private final List<Runnable> scheduled = new ArrayList<>();
	private final List<HelperConnection.Listener> listeners = new ArrayList<>();

	@BeforeEach
	void setUp() {
		helper = new MacHelper(listener -> {
			listeners.add(listener);
			return new HelperChannel() {
				@Override
				public void start() {
					channelOpen = true;
				}

				@Override
				public boolean send(final Message m) {
					if (!(m instanceof ListApps)) { // the Dock's app list (asked on every connect) isn't a capture's business
						sent.add(m);
					}
					return true;
				}

				@Override
				public void close() {
					channelOpen = false;
				}
			};
		}, Runnable::run, (task, delayMs) -> {
			delays.add(delayMs);
			scheduled.add(task);
		});
		helper.setSetUp(true);
		helper.start();
	}

	private void welcome(final boolean screenRecording) {
		helper.connected(new Welcome(1, screenRecording, true));
	}

	private MacHelperSource source() {
		return new MacHelperSource(helper);
	}

	private <T extends Message> T last(final Class<T> type) {
		for (int i = sent.size() - 1; i >= 0; i--) {
			if (type.isInstance(sent.get(i))) {
				return type.cast(sent.get(i));
			}
		}
		throw new AssertionError("no " + type.getSimpleName() + " in " + sent);
	}

	private static Target window(final String key) {
		return new Target(Mode.WINDOW, 1, key, Shape.WIDE_16_9);
	}

	private static Target display(final int slot, final Shape shape) {
		return new Target(Mode.EXTEND, slot, null, shape);
	}

	private static ByteBuffer pixels(final int... bytes) {
		byte[] b = new byte[bytes.length];
		for (int i = 0; i < bytes.length; i++) {
			b[i] = (byte) bytes[i];
		}
		return ByteBuffer.wrap(b).asReadOnlyBuffer();
	}

	@Test
	void windowClosedEndsTheSinkAndTheLookBecomesWindowClosed() {
		welcome(true);
		MacHelperSource s = source();
		s.start(window(NOTES), 5, sink);
		Capture c = last(Capture.class);
		assertEquals(CaptureKind.WINDOW, c.kind());
		assertEquals(NOTES, c.windowKey());
		assertEquals(5, c.fps());
		assertEquals(1920, c.maxW());

		helper.message(new Ended(c.surface(), "window closed"));

		assertEquals(List.of("window closed"), sink.ended);
		assertFalse(s.isLive());
		assertEquals(HologramLook.OwnSource.WINDOW_CLOSED, s.status());
		HologramSpec spec = new HologramSpec(ME, Shape.WIDE_16_9, Mode.WINDOW, Privacy.PUBLIC, 1, NOTES, 2, 0, 1, 0, 0);
		assertEquals(HologramLook.State.WINDOW_CLOSED, HologramLook.forViewer(spec, ME, true, false, s.status()));
	}

	@Test
	void extendCreatesTheDisplayForItsShapeThenCapturesIt() {
		welcome(true);
		MacHelperSource s = source();
		s.start(display(2, Shape.ULTRA_21_9), 5, sink);
		assertEquals(List.of(new CreateDisplay(2, 2560, 1080)), sent, "no capture before the display is online");

		helper.message(new Display(2, 77, 2560, 1080));
		Capture c = last(Capture.class);
		assertEquals(CaptureKind.DISPLAY, c.kind());
		assertEquals(Integer.valueOf(2), c.slot());
		assertEquals(2560, c.maxW());
		assertTrue(s.isLive());

		// A second hologram on the same slot and shape reuses the display.
		sent.clear();
		MacHelperSource s2 = source();
		s2.start(display(2, Shape.ULTRA_21_9), 5, new RecordingSink());
		assertEquals(1, sent.size());
		assertInstanceOf(Capture.class, sent.get(0));
		assertTrue(last(Capture.class).surface() != c.surface(), "each capture has its own surface");
	}

	@Test
	void mirrorCapturesTheMainDisplayWithoutCreatingOne() {
		welcome(true);
		MacHelperSource s = source();
		s.start(new Target(Mode.MIRROR, 0, null, Shape.WIDE_16_9), 5, sink);
		assertEquals(1, sent.size(), "no CREATE_DISPLAY: Mirror shows the real screen");
		Capture c = last(Capture.class);
		assertEquals(CaptureKind.MAIN, c.kind());
		assertEquals(null, c.slot());
		assertEquals(null, c.windowKey());
		assertEquals(MacHelperSource.MIRROR_MAX_W, c.maxW());
		assertTrue(s.isLive());
		assertEquals(c.surface(), helper.surfaceFor(Target.of(new HologramSpec(ME, Shape.WIDE_16_9, Mode.MIRROR, Privacy.PUBLIC, 3, null, 2, 0, 1, 0, 0))),
				"zoom finds the surface whatever the stored display slot");

		helper.message(new Ended(c.surface(), "no permission"));
		assertEquals(HologramLook.OwnSource.NEEDS_HELPER, s.status(), "Mirror needs Screen Recording too");
	}

	/** Every Mac capture failure surfaces on the hologram (owner only), never an endless "Connecting…". */
	@Test
	void aFailedCaptureShowsScreensUnavailableWithTheReason() {
		welcome(true);
		MacHelperSource s = source();
		s.start(new Target(Mode.MIRROR, 0, null, Shape.WIDE_16_9), 5, sink);
		Capture c = last(Capture.class);
		helper.message(new Ended(c.surface(), "capture failed: Minecraft (pid 42) can't be excluded from the mirror"));
		assertEquals(HologramLook.OwnSource.UNAVAILABLE, s.status());
		assertEquals("Can't hide Minecraft from the mirror", s.statusDetail());
		HologramSpec spec = new HologramSpec(ME, Shape.WIDE_16_9, Mode.MIRROR, Privacy.PUBLIC, 1, null, 2, 0, 1, 0, 0);
		HologramLook.State look = HologramLook.forViewer(spec, ME, true, true, s.status());
		assertEquals(HologramLook.State.UNAVAILABLE, look);
		assertEquals("Can't hide Minecraft from the mirror", HologramLook.label(look, spec, true, "Carter", s.statusDetail()).subtitle());
		assertEquals("", HologramLook.label(look, spec, false, "Carter", s.statusDetail()).subtitle(), "owner only");

		MacHelperSource w = source();
		w.start(window(NOTES), 5, new RecordingSink());
		helper.message(new Ended(last(Capture.class).surface(), "capture failed: The stream was interrupted"));
		assertEquals(HologramLook.OwnSource.UNAVAILABLE, w.status(), "any failure, not only Mirror's");
		assertEquals("The stream was interrupted", w.statusDetail());
		assertEquals(null, source().statusDetail(), "nothing ended: no detail");
	}

	@Test
	void framesAndPatchesReachTheSinkAndAreAcknowledged() {
		welcome(true);
		MacHelperSource s = source();
		s.start(window(NOTES), 5, sink);
		int surface = last(Capture.class).surface();

		helper.message(new Frame(surface, 3, 2, 1, pixels(1, 2, 3, 4, 5, 6, 7, 8)));
		assertEquals(List.of("full 2x1 [1, 2, 3, 4, 5, 6, 7, 8]"), sink.calls);
		assertEquals(new Ready(surface), last(Ready.class));

		sent.clear();
		helper.message(new Patch(surface, 3, 2, 1, List.of(new PixelRect(0, 0, 1, 1), new PixelRect(1, 0, 1, 1)), pixels(9, 9, 9, 9, 8, 8, 8, 8)));
		assertEquals(List.of("full 2x1 [1, 2, 3, 4, 5, 6, 7, 8]", "patch 0,0 1x1 [9, 9, 9, 9]", "patch 1,0 1x1 [8, 8, 8, 8]"), sink.calls);
		assertEquals(List.of(new Ready(surface)), sent, "one READY per message");

		// A patch of another generation (a restarted stream) is dropped, but still acknowledged.
		sent.clear();
		helper.message(new Patch(surface, 4, 2, 1, List.of(new PixelRect(0, 0, 1, 1)), pixels(7, 7, 7, 7)));
		assertEquals(3, sink.calls.size());
		assertEquals(List.of(new Ready(surface)), sent);

		// Frames for surfaces we don't own are ignored and not acknowledged.
		sent.clear();
		helper.message(new Frame(surface + 100, 1, 1, 1, pixels(1, 1, 1, 1)));
		assertEquals(3, sink.calls.size());
		assertTrue(sent.isEmpty());
	}

	@Test
	void invisibleStopsCaptureAndZoomCapturesAt30() {
		welcome(true);
		MacHelperSource s = source();
		s.start(window(NOTES), 5, sink);
		int first = last(Capture.class).surface();

		s.setMaxFps(0);
		assertEquals(new Stop(first), last(Stop.class));
		sent.clear();
		helper.message(new Frame(first, 1, 1, 1, pixels(1, 1, 1, 1)));
		assertTrue(sink.calls.isEmpty(), "no frames once stopped");
		assertTrue(s.isLive(), "paused, not ended");

		s.setMaxFps(30);
		Capture c = last(Capture.class);
		assertEquals(30, c.fps());
		assertTrue(c.surface() != first);

		sent.clear();
		s.setMaxFps(30);
		assertTrue(sent.isEmpty(), "same rate: nothing sent");
		s.setMaxFps(60);
		assertEquals(List.of(new local.carter.office.client.helper.HelperProtocol.Rate(c.surface(), 60)), sent,
				"a live capture changes its rate in place: RATE, no STOP / CAPTURE (no stream rebuild entering work mode)");
		s.setMaxFps(30);
		assertEquals(new local.carter.office.client.helper.HelperProtocol.Rate(c.surface(), 30), sent.get(sent.size() - 1));
		assertEquals(0, sent.stream().filter(m -> m instanceof Stop || m instanceof Capture).count());
	}

	@Test
	void stopSendsStopAndSilencesTheSink() {
		welcome(true);
		MacHelperSource s = source();
		s.start(window(NOTES), 5, sink);
		int surface = last(Capture.class).surface();
		s.stop();
		assertEquals(new Stop(surface), last(Stop.class));
		helper.message(new Frame(surface, 1, 1, 1, pixels(1, 1, 1, 1)));
		helper.message(new Ended(surface, "window closed"));
		assertTrue(sink.calls.isEmpty());
		assertTrue(sink.ended.isEmpty());
		assertFalse(s.isLive());
		s.stop(); // idempotent
	}

	@Test
	void startWaitsForTheHelperAndAReconnectRecreatesDisplayAndCapture() {
		MacHelperSource s = source();
		s.start(display(1, Shape.WIDE_16_9), 5, sink);
		assertTrue(sent.isEmpty(), "nothing sent before WELCOME");

		welcome(true);
		assertEquals(List.of(new CreateDisplay(1, 1920, 1080)), sent);
		helper.message(new Display(1, 5, 1920, 1080));
		int surface = last(Capture.class).surface();

		helper.disconnected();
		sent.clear();
		helper.message(new Frame(surface, 1, 1, 1, pixels(1, 1, 1, 1)));
		assertTrue(sink.calls.isEmpty());
		assertTrue(s.isLive(), "a helper drop is not the end of the source");

		welcome(true);
		assertEquals(List.of(new CreateDisplay(1, 1920, 1080)), sent, "display re-requested once");
		helper.message(new Display(1, 9, 1920, 1080));
		assertTrue(last(Capture.class).surface() != surface);
	}

	@Test
	void missingScreenRecordingShowsNeedsHelper() {
		welcome(false);
		assertEquals(MacHelper.Status.NO_PERMISSION, helper.status());
		MacHelperSource s = source();
		s.start(window(NOTES), 5, sink);
		assertEquals(HologramLook.OwnSource.NEEDS_HELPER, s.status());

		// The helper still asks macOS (once per launch) and answers "no permission".
		int surface = last(Capture.class).surface();
		helper.message(new Ended(surface, "no permission"));
		assertTrue(sink.ended.isEmpty(), "no permission is not a terminal end");
		assertEquals(HologramLook.OwnSource.NEEDS_HELPER, s.status());

		// Granted + helper relaunched: capture resumes.
		helper.disconnected();
		welcome(true);
		assertEquals(HologramLook.OwnSource.READY, s.status());
		assertTrue(last(Capture.class).surface() != surface);
	}

	@Test
	void aLaterWelcomeUpdatesPermissionsWithoutARestart() {
		helper.connected(new Welcome(1, false, false));
		MacHelperSource s = source();
		s.start(window(NOTES), 5, sink);
		int surface = last(Capture.class).surface();
		helper.message(new Ended(surface, "no permission"));
		assertEquals(HologramLook.OwnSource.NEEDS_HELPER, s.status());
		int captures = (int) sent.stream().filter(Capture.class::isInstance).count();

		// Accessibility switched on in System Settings: the helper re-sends WELCOME on the same connection.
		helper.message(new Welcome(1, false, true));
		assertTrue(helper.welcome().accessibility());
		assertEquals(MacHelper.Status.NO_PERMISSION, helper.status());
		assertEquals(captures, (int) sent.stream().filter(Capture.class::isInstance).count(), "no capture without Screen Recording");

		// Screen Recording granted too: status READY and the capture starts again, still on this connection.
		helper.message(new Welcome(1, true, true));
		assertEquals(MacHelper.Status.READY, helper.status());
		assertEquals(HologramLook.OwnSource.READY, s.status());
		assertTrue(last(Capture.class).surface() != surface);
		assertTrue(channelOpen);
	}

	@Test
	void requestPermissionsIsSentToTheHelper() {
		welcome(false);
		assertTrue(helper.requestPermissions());
		assertInstanceOf(RequestPermissions.class, sent.get(sent.size() - 1));
	}

	@Test
	void notSetUpShowsNeedsHelper() {
		helper.setSetUp(false);
		MacHelperSource s = source();
		s.start(window(NOTES), 5, sink);
		assertEquals(HologramLook.OwnSource.NEEDS_HELPER, s.status());
		assertEquals(MacHelper.Status.NOT_SET_UP, helper.status());
	}

	@Test
	void leavingTheWorldRemovesDisplaysAndCloses() {
		welcome(true);
		source().start(display(1, Shape.WIDE_16_9), 5, sink);
		source().start(display(3, Shape.TALL_9_16), 5, new RecordingSink());
		assertEquals(new CreateDisplay(3, 1080, 1920), last(CreateDisplay.class));
		sent.clear();
		helper.stop();
		assertEquals(Set.of(new RemoveDisplay(1), new RemoveDisplay(3)), Set.copyOf(sent));
		assertFalse(channelOpen);
		assertEquals(MacHelper.Status.STOPPED, helper.status());
	}

	@Test
	void windowModeWithoutAPickedWindowCapturesNothing() {
		welcome(true);
		MacHelperSource s = source();
		s.start(window(null), 5, sink);
		s.setMaxFps(30);
		assertTrue(sent.isEmpty(), "no CAPTURE without a window key");
		assertTrue(s.isLive());
	}

	@Test
	void aDisplayRemovedUnderUsIsRequestedAgainWithBackoff() {
		welcome(true);
		MacHelperSource s = source();
		s.start(display(1, Shape.WIDE_16_9), 5, sink);
		helper.message(new Display(1, 5, 1920, 1080));
		int first = last(Capture.class).surface();

		sent.clear();
		helper.message(new Ended(first, "display removed"));
		assertTrue(sent.isEmpty(), "not re-created on the spot");
		assertEquals(List.of(500L), delays);
		assertTrue(sink.ended.isEmpty() && s.isLive(), "not the end of the source");

		scheduled.remove(0).run();
		assertEquals(List.of(new CreateDisplay(1, 1920, 1080)), sent, "asked for again");
		// The helper never answers (display_failed): it keeps asking, backing off.
		assertEquals(List.of(500L, 1000L), delays);
		scheduled.remove(0).run();
		assertEquals(new CreateDisplay(1, 1920, 1080), sent.get(sent.size() - 1));
		assertEquals(2000L, delays.get(delays.size() - 1));

		helper.message(new Display(1, 6, 1920, 1080));
		int second = last(Capture.class).surface();
		assertTrue(second != first);
		sent.clear();
		scheduled.remove(scheduled.size() - 1).run(); // a retry that fires after the capture is back does nothing
		assertTrue(sent.isEmpty());

		// "no display" (the helper lost it) is retried the same way, from the current backoff.
		helper.message(new Ended(second, "no display"));
		assertEquals(4000L, delays.get(delays.size() - 1));
		scheduled.remove(scheduled.size() - 1).run();
		assertEquals(new CreateDisplay(1, 1920, 1080), last(CreateDisplay.class));
	}

	@Test
	void callbacksFromAClosedConnectionAreIgnored() {
		welcome(true);
		HelperConnection.Listener old = listeners.get(0);
		helper.restart();
		HelperConnection.Listener fresh = listeners.get(1);
		fresh.connected(new Welcome(1, true, true));
		assertTrue(helper.isConnected());
		old.disconnected(); // the old connection's thread reports its end late
		assertTrue(helper.isConnected(), "stale disconnect ignored");
		assertEquals(MacHelper.Status.READY, helper.status());
		fresh.disconnected();
		assertFalse(helper.isConnected());
	}

	@Test
	void surfaceForFindsTheCaptureOfATarget() {
		welcome(true);
		MacHelperSource s = source();
		s.start(window(NOTES), 5, sink);
		assertEquals(last(Capture.class).surface(), helper.surfaceFor(window(NOTES)));
		assertEquals(0, helper.surfaceFor(window("com.apple.Safari|1")));
	}

	static final class RecordingSink implements FrameSink {
		final List<String> calls = new ArrayList<>();
		final List<String> ended = new ArrayList<>();

		private static String bytes(final ByteBuffer b, final int n) {
			byte[] out = new byte[n];
			b.get(b.position(), out);
			int[] v = new int[n];
			for (int i = 0; i < n; i++) {
				v[i] = out[i] & 0xFF;
			}
			return java.util.Arrays.toString(v);
		}

		@Override
		public void full(final int w, final int h, final ByteBuffer rgba) {
			calls.add("full " + w + "x" + h + " " + bytes(rgba, w * h * 4));
		}

		@Override
		public void patch(final int x, final int y, final int w, final int h, final ByteBuffer rgba) {
			calls.add("patch " + x + "," + y + " " + w + "x" + h + " " + bytes(rgba, w * h * 4));
		}

		@Override
		public void ended(final String reason) {
			ended.add(reason);
		}
	}
}
