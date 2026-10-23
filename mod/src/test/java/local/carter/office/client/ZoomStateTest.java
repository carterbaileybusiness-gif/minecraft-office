package local.carter.office.client;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;

import local.carter.office.client.zoom.DoubleEsc;
import local.carter.office.client.zoom.ZoomController;
import local.carter.office.client.zoom.ZoomController.Candidate;
import local.carter.office.client.zoom.ZoomController.Request;
import local.carter.office.client.zoom.ZoomController.State;
import local.carter.office.core.HologramSpec;
import local.carter.office.core.Mode;
import local.carter.office.core.Privacy;
import local.carter.office.core.Shape;
import local.carter.office.hologram.HologramBlockEntity;

class ZoomStateTest {
	private static final UUID ME = UUID.fromString("00000000-0000-0000-0000-00000000000a");
	private static final UUID FRIEND = UUID.fromString("00000000-0000-0000-0000-00000000000b");

	/** Records the controller's side effects; the clock is set by the test. */
	private static final class FakeEnv implements ZoomController.Env {
		long now;
		@Nullable String problem;
		final List<String> calls = new ArrayList<>();

		@Override
		public long nowMs() {
			return now;
		}

		@Override
		public @Nullable UUID viewer() {
			return ME;
		}

		@Override
		public Candidate candidate(final HologramBlockEntity be) {
			throw new UnsupportedOperationException("tests pass candidates directly");
		}

		@Override
		public @Nullable String workProblem(final Candidate target) {
			return problem;
		}

		@Override
		public void begin(final Candidate target) {
			calls.add("begin");
		}

		@Override
		public void startWorking(final Candidate target) {
			calls.add("startWorking");
		}

		@Override
		public void stopWorking(final Candidate target) {
			calls.add("stopWorking");
		}

		@Override
		public void end(final Candidate target, final boolean aborted) {
			calls.add(aborted ? "abort" : "end");
		}
	}

	private static Candidate hologram(final UUID owner, final boolean live) {
		HologramSpec spec = new HologramSpec(owner, Shape.WIDE_16_9, Mode.EXTEND, Privacy.PUBLIC, 1, null, 2.0, 0, 1.2, 0, 0f);
		return new Candidate(UUID.randomUUID(), spec, live);
	}

	/** Requests {@code c} and runs the 350 ms zoom-in to WORKING. */
	private static void zoomInFully(final ZoomController zoom, final FakeEnv env, final Candidate c) {
		assertEquals(Request.STARTED, zoom.request(c));
		env.now += ZoomController.ANIMATION_MS;
		zoom.update();
		assertEquals(State.WORKING, zoom.state());
	}

	@Test
	void requestOnANonOwnedHologramStaysInTheWorld() {
		FakeEnv env = new FakeEnv();
		ZoomController zoom = new ZoomController(env);
		assertEquals(Request.NOT_OWNER, zoom.request(hologram(FRIEND, true)));
		assertEquals(State.WORLD, zoom.state());
		assertTrue(env.calls.isEmpty(), "nothing happens (no message, no camera move)");
	}

	@Test
	void requestOnOwnLiveHologramZoomsInThenWorks() {
		FakeEnv env = new FakeEnv();
		ZoomController zoom = new ZoomController(env);
		Candidate c = hologram(ME, true);
		assertEquals(Request.STARTED, zoom.request(c));
		assertEquals(State.ZOOMING_IN, zoom.state());
		assertEquals(List.of("begin"), env.calls);
		assertEquals(0.0, zoom.progress(), 1e-9);
		env.now = 175;
		zoom.update();
		assertEquals(State.ZOOMING_IN, zoom.state());
		assertEquals(0.5, zoom.progress(), 1e-9, "ease-in-out is symmetric: half time = half way");
		env.now = 349;
		zoom.update();
		assertEquals(State.ZOOMING_IN, zoom.state());
		env.now = 350;
		zoom.update();
		assertEquals(State.WORKING, zoom.state());
		assertEquals(1.0, zoom.progress(), 1e-9);
		assertEquals(List.of("begin", "startWorking"), env.calls);
		assertTrue(zoom.isZoomedInto(c.hologramId()));
		assertFalse(zoom.isZoomedInto(UUID.randomUUID()));
	}

	@Test
	void ownHologramThatIsNotLiveIsRefused() {
		FakeEnv env = new FakeEnv();
		ZoomController zoom = new ZoomController(env);
		assertEquals(Request.NOT_LIVE, zoom.request(hologram(ME, false)));
		assertEquals(State.WORLD, zoom.state());
		assertTrue(env.calls.isEmpty());
	}

	@Test
	void platformProblemRefusesTheZoom() {
		FakeEnv env = new FakeEnv();
		env.problem = "helper needs Accessibility";
		ZoomController zoom = new ZoomController(env);
		assertEquals(Request.UNAVAILABLE, zoom.request(hologram(ME, true)));
		assertEquals(State.WORLD, zoom.state());
	}

	@Test
	void requestWhileWorkingIsIgnored() {
		FakeEnv env = new FakeEnv();
		ZoomController zoom = new ZoomController(env);
		Candidate first = hologram(ME, true);
		zoomInFully(zoom, env, first);
		Candidate second = hologram(ME, true);
		assertEquals(Request.BUSY, zoom.request(second));
		assertEquals(State.WORKING, zoom.state());
		assertEquals(first, zoom.target(), "still working on the first screen");
		assertEquals(List.of("begin", "startWorking"), env.calls);
	}

	@Test
	void requestWhileZoomingIsIgnored() {
		FakeEnv env = new FakeEnv();
		ZoomController zoom = new ZoomController(env);
		zoom.request(hologram(ME, true));
		env.now = 100;
		assertEquals(Request.BUSY, zoom.request(hologram(ME, true)));
		assertEquals(State.ZOOMING_IN, zoom.state());
	}

	@Test
	void captureEndedWhileWorkingZoomsOutThenReturnsToTheWorld() {
		FakeEnv env = new FakeEnv();
		ZoomController zoom = new ZoomController(env);
		Candidate c = hologram(ME, true);
		zoomInFully(zoom, env, c);
		env.now += 10;
		zoom.captureEnded(c.hologramId(), "window closed");
		assertEquals(State.ZOOMING_OUT, zoom.state());
		assertEquals(List.of("begin", "startWorking", "stopWorking"), env.calls);
		assertEquals(1.0, zoom.progress(), 1e-9);
		env.now += 175;
		zoom.update();
		assertEquals(State.ZOOMING_OUT, zoom.state());
		assertEquals(0.5, zoom.progress(), 1e-9);
		env.now += 175;
		zoom.update();
		assertEquals(State.WORLD, zoom.state());
		assertEquals(0.0, zoom.progress(), 1e-9);
		assertEquals(List.of("begin", "startWorking", "stopWorking", "end"), env.calls);
		assertEquals(null, zoom.target());
	}

	@Test
	void captureEndedOfAnotherHologramIsIgnored() {
		FakeEnv env = new FakeEnv();
		ZoomController zoom = new ZoomController(env);
		zoomInFully(zoom, env, hologram(ME, true));
		zoom.captureEnded(UUID.randomUUID(), "window closed");
		assertEquals(State.WORKING, zoom.state());
	}

	@Test
	void doubleEscWhileWorkingExits() {
		FakeEnv env = new FakeEnv();
		ZoomController zoom = new ZoomController(env);
		zoomInFully(zoom, env, hologram(ME, true));
		assertEquals(DoubleEsc.Result.PASS, zoom.escape(false));
		assertEquals(State.WORKING, zoom.state(), "a single Esc never leaves (and never opens the pause menu)");
		zoom.escapeReleased();
		env.now += 200;
		assertEquals(DoubleEsc.Result.EXIT, zoom.escape(false));
		assertEquals(State.ZOOMING_OUT, zoom.state());
	}

	@Test
	void exitFromThePlatformHotkeyWhileWorking() {
		FakeEnv env = new FakeEnv();
		ZoomController zoom = new ZoomController(env);
		zoomInFully(zoom, env, hologram(ME, true));
		assertTrue(zoom.exit());
		assertEquals(State.ZOOMING_OUT, zoom.state());
		assertFalse(zoom.exit(), "the helper's EXIT_ZOOM and Minecraft's own double-Esc can both arrive: second is a no-op");
		assertEquals(List.of("begin", "startWorking", "stopWorking"), env.calls);
	}

	@Test
	void exitDuringZoomInReversesFromWhereItIs() {
		FakeEnv env = new FakeEnv();
		ZoomController zoom = new ZoomController(env);
		zoom.request(hologram(ME, true));
		env.now = 175; // half way
		zoom.update();
		assertTrue(zoom.exit());
		assertEquals(State.ZOOMING_OUT, zoom.state());
		assertEquals(0.5, zoom.progress(), 1e-9, "no jump");
		env.now = 175 + 175;
		zoom.update();
		assertEquals(State.WORLD, zoom.state(), "half the way back takes half the time");
		assertEquals(List.of("begin", "end"), env.calls, "never started working, so never stops");
	}

	@Test
	void abortReturnsImmediatelyAndCleansUp() {
		FakeEnv env = new FakeEnv();
		ZoomController zoom = new ZoomController(env);
		zoomInFully(zoom, env, hologram(ME, true));
		zoom.abort();
		assertEquals(State.WORLD, zoom.state());
		assertEquals(List.of("begin", "startWorking", "stopWorking", "abort"), env.calls);
		zoom.abort();
		assertEquals(4, env.calls.size(), "abort in the world does nothing");
	}

	@Test
	void canZoomAgainAfterReturning() {
		FakeEnv env = new FakeEnv();
		ZoomController zoom = new ZoomController(env);
		zoomInFully(zoom, env, hologram(ME, true));
		zoom.exit();
		env.now += ZoomController.ANIMATION_MS;
		zoom.update();
		assertEquals(State.WORLD, zoom.state());
		assertEquals(Request.STARTED, zoom.request(hologram(ME, true)));
	}

	@Test
	void easingIsInOut() {
		assertEquals(0.0, ZoomController.ease(0), 1e-12);
		assertEquals(1.0, ZoomController.ease(1), 1e-12);
		assertEquals(0.5, ZoomController.ease(0.5), 1e-12);
		assertTrue(ZoomController.ease(0.1) < 0.1, "slow start");
		assertTrue(ZoomController.ease(0.9) > 0.9, "slow end");
	}
}
