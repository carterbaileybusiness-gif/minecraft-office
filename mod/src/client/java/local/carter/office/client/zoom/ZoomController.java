package local.carter.office.client.zoom;

import java.util.UUID;

import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import local.carter.office.core.HologramSpec;
import local.carter.office.core.Mode;
import local.carter.office.hologram.HologramBlockEntity;

/**
 * "Zoom in to work": the state machine behind clicking your own hologram. No Minecraft calls of its own; everything
 * the game must do goes through {@link Env} ({@link ZoomClient} in the game, a fake in tests). Client thread only.
 *
 * <pre>
 *   WORLD --request(own LIVE hologram)--> ZOOMING_IN --350 ms--> WORKING
 *     ^                                       |                    |
 *     |                                 exit / ended          exit / ended (double-Esc, platform exit gesture,
 *     |                                       v                    v          capture ended, platform problem, ...)
 *     +------------- reaches 0 ---------- ZOOMING_OUT <------------+
 *   abort() (world unload, disconnect): straight back to WORLD from anywhere.
 * </pre>
 *
 * <p>Effects: {@link Env#begin} when the zoom starts (hide hand/HUD, block game input), {@link Env#startWorking} on
 * arriving (platform zoom-in, {@code SetWorking(true)}), {@link Env#stopWorking} when leaving WORKING (platform zoom-out,
 * {@code SetWorking(false)}), {@link Env#end} back in the world (restore everything). {@code stopWorking} runs only if
 * {@code startWorking} ran, and {@code end} only if {@code begin} did. Every effect is guarded: one that throws is
 * logged and the zoom leaves cleanly (a failing {@code begin} goes straight back to the world).
 *
 * <p>While not in the world, {@link #update} also keeps Minecraft from throttling its frame rate as "AFK"
 * ({@link Env#keepAwake}: the player's input goes to the computer, not to Minecraft) and leaves, with a
 * {@link Env#notice}, as soon as {@link Env#workProblem} reports one (e.g. the helper failed to zoom or quit).
 *
 * <p>MIRROR: once WORKING, Minecraft steps aside (hidden / minimized) and the player uses their real desktop. Minecraft's
 * window losing focus and then getting it back while WORKING means the player went back to Minecraft (Dock, Cmd-Tab,
 * taskbar, a click): that exits too ({@link #focusChanged}).
 */
public final class ZoomController {
	private static final Logger LOGGER = LoggerFactory.getLogger("office");

	public enum State { WORLD, ZOOMING_IN, WORKING, ZOOMING_OUT }

	/** Why a {@link #request} did or didn't start a zoom. */
	public enum Request {
		STARTED,
		/** Someone else's hologram: nothing happens. */
		NOT_OWNER,
		/** Own hologram, but its screen isn't live (connecting, helper missing, window closed). */
		NOT_LIVE,
		/** This computer can't work on a screen right now ({@link Env#workProblem}). */
		UNAVAILABLE,
		/** Already zooming or working: ignored. */
		BUSY
	}

	/** A hologram as the controller sees it; {@code live} = the local player sees its screen LIVE. */
	public record Candidate(UUID hologramId, HologramSpec spec, boolean live) {}

	public interface Env {
		long nowMs();

		@Nullable UUID viewer();

		/** The hologram as a zoom candidate (the game computes LIVE the same way the renderer does). */
		Candidate candidate(HologramBlockEntity be);

		/** Null when working on {@code target} is possible now, else a short reason for the player. */
		@Nullable String workProblem(Candidate target);

		void begin(Candidate target);

		void startWorking(Candidate target);

		void stopWorking(Candidate target);

		/** Back in the world; {@code aborted} = no animation (world unload / disconnect). */
		void end(Candidate target, boolean aborted);

		/** While zoomed (every update): counts as player input for Minecraft's inactivity frame-rate limit. */
		void keepAwake();

		/** A short message for the player (action bar) about why the zoom ended. */
		void notice(String message);
	}

	/** Zoom animation length, both ways. */
	public static final long ANIMATION_MS = 350;

	private static @Nullable ZoomController instance;

	private final Env env;
	private final DoubleEsc doubleEsc = new DoubleEsc();
	private State state = State.WORLD;
	private @Nullable Candidate target;
	/** Linear progress (0 = world view, 1 = working view) when the current animation started. */
	private double fromProgress;
	private long since;
	/** MIRROR, WORKING: Minecraft's window has lost focus since WORKING began (it stepped aside). */
	private boolean mirrorAway;

	public ZoomController(final Env env) {
		this.env = env;
	}

	/** The game's controller (set up by {@link ZoomClient#register}); null before that. */
	public static @Nullable ZoomController get() {
		return instance;
	}

	static void install(final ZoomController controller) {
		instance = controller;
	}

	public State state() {
		return state;
	}

	public @Nullable Candidate target() {
		return target;
	}

	/** True while zooming into, working on, or zooming out of hologram {@code id}. */
	public boolean isZoomedInto(final UUID id) {
		Candidate t = target;
		return state != State.WORLD && t != null && t.hologramId().equals(id);
	}

	/** The player clicked {@code be} (empty hand / not an emitter). */
	public Request request(final HologramBlockEntity be) {
		if (state != State.WORLD) {
			return Request.BUSY;
		}
		if (be.spec() == null) {
			return Request.NOT_LIVE;
		}
		return request(env.candidate(be));
	}

	public Request request(final Candidate c) {
		if (state != State.WORLD) {
			return Request.BUSY;
		}
		UUID viewer = env.viewer();
		if (viewer == null || !viewer.equals(c.spec().owner())) {
			return Request.NOT_OWNER;
		}
		if (!c.live()) {
			return Request.NOT_LIVE;
		}
		if (problem(c) != null) {
			return Request.UNAVAILABLE;
		}
		target = c;
		state = State.ZOOMING_IN;
		fromProgress = 0;
		since = env.nowMs();
		doubleEsc.reset();
		if (!run("begin", () -> env.begin(c))) {
			abort(); // half-begun: restore whatever was changed, no animation
			return Request.UNAVAILABLE;
		}
		return Request.STARTED;
	}

	/** The hologram's spec changed while zoomed (e.g. moved): keep following it. */
	public void retarget(final HologramSpec spec) {
		Candidate t = target;
		if (t != null) {
			target = new Candidate(t.hologramId(), spec, t.live());
		}
	}

	/** Advances the animation, keeps the frame rate up and watches for platform problems; call every frame and tick. */
	public void update() {
		Candidate t = target;
		if (t == null || state == State.WORLD) {
			return;
		}
		run("keepAwake", env::keepAwake);
		if (state == State.ZOOMING_IN || state == State.WORKING) {
			String p = problem(t);
			if (p != null) {
				LOGGER.info("Zoom: leaving ({})", p);
				exit();
				run("notice", () -> env.notice(p));
			}
		}
		double progress = linear(env.nowMs());
		if (state == State.ZOOMING_IN && progress >= 1) {
			state = State.WORKING;
			mirrorAway = false;
			if (!run("startWorking", () -> env.startWorking(t))) {
				exit();
				run("notice", () -> env.notice("Couldn't start working on this screen"));
			}
		} else if (state == State.ZOOMING_OUT && progress <= 0) {
			state = State.WORLD;
			target = null;
			run("end", () -> env.end(t, false));
		}
	}

	/** Zoom back out (double-Esc, the platform's exit gesture, or anything that ends the session). */
	public boolean exit() {
		Candidate t = target;
		if (t == null || (state != State.ZOOMING_IN && state != State.WORKING)) {
			return false;
		}
		long now = env.nowMs();
		double p = linear(now);
		boolean wasWorking = state == State.WORKING;
		state = State.ZOOMING_OUT;
		mirrorAway = false;
		fromProgress = p;
		since = now;
		if (wasWorking) {
			run("stopWorking", () -> env.stopWorking(t));
		}
		return true;
	}

	/** The capture of hologram {@code id} ended (window closed, capture failed): leave if it's ours. */
	public void captureEnded(final UUID id, final String reason) {
		if (isZoomedInto(id)) {
			exit();
		}
	}

	/** Esc pressed inside Minecraft while zoomed; {@code modified} = Shift/Ctrl/Alt/Cmd held. */
	public DoubleEsc.Result escape(final boolean modified) {
		DoubleEsc.Result r = doubleEsc.key(env.nowMs(), modified);
		if (r == DoubleEsc.Result.EXIT) {
			exit();
		}
		return r;
	}

	public void escapeReleased() {
		doubleEsc.release();
	}

	/** Some other key reached Minecraft: breaks a pending double-tap. */
	public void otherKey() {
		doubleEsc.otherKey();
	}

	/**
	 * Minecraft's window gained or lost focus: a key release may have been missed, start the double-tap afresh. In a
	 * MIRROR zoom, getting focus back after losing it while WORKING (the player clicked back into Minecraft) exits.
	 */
	public void focusChanged(final boolean focused) {
		doubleEsc.reset();
		Candidate t = target;
		if (state != State.WORKING || t == null || t.spec().mode() != Mode.MIRROR) {
			return;
		}
		if (!focused) {
			mirrorAway = true;
		} else if (mirrorAway) {
			LOGGER.info("Zoom: leaving (Minecraft focused again)");
			exit();
		}
	}

	/** World unload / disconnect: back to WORLD immediately, cleaning up whatever was started. */
	public void abort() {
		Candidate t = target;
		if (t == null || state == State.WORLD) {
			return;
		}
		boolean wasWorking = state == State.WORKING;
		state = State.WORLD;
		target = null;
		if (wasWorking) {
			run("stopWorking", () -> env.stopWorking(t));
		}
		run("end", () -> env.end(t, true));
	}

	/** Eased progress now: 0 = the player's own view, 1 = the hologram fills the view. */
	public double progress() {
		return ease(linear(env.nowMs()));
	}

	private @Nullable String problem(final Candidate c) {
		try {
			return env.workProblem(c);
		} catch (RuntimeException e) {
			LOGGER.warn("Zoom: checking the platform failed", e);
			return "Can't work on this screen right now";
		}
	}

	/** Runs one effect; a throwing effect is logged and reported as false (never escapes into the game loop). */
	private static boolean run(final String what, final Runnable effect) {
		try {
			effect.run();
			return true;
		} catch (RuntimeException e) {
			LOGGER.warn("Zoom: {} failed", what, e);
			return false;
		}
	}

	private double linear(final long now) {
		double dt = Math.max(0, now - since) / (double) ANIMATION_MS;
		return switch (state) {
			case WORLD -> 0;
			case WORKING -> 1;
			case ZOOMING_IN -> Math.min(1, fromProgress + dt);
			case ZOOMING_OUT -> Math.max(0, fromProgress - dt);
		};
	}

	/** Cubic ease-in-out. */
	public static double ease(final double t) {
		double x = Math.max(0, Math.min(1, t));
		return x < 0.5 ? 4 * x * x * x : 1 - Math.pow(-2 * x + 2, 3) / 2;
	}
}
