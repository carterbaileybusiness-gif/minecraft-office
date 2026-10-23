package local.carter.office.client.zoom;

import java.util.UUID;

import org.jspecify.annotations.Nullable;

import local.carter.office.core.HologramSpec;
import local.carter.office.hologram.HologramBlockEntity;

/**
 * "Zoom in to work": the state machine behind clicking your own hologram. No Minecraft calls of its own; everything
 * the game must do goes through {@link Env} ({@link ZoomClient} in the game, a fake in tests). Client thread only.
 *
 * <pre>
 *   WORLD --request(own LIVE hologram)--> ZOOMING_IN --350 ms--> WORKING
 *     ^                                       |                    |
 *     |                                 exit / ended          exit / ended (double-Esc, EXIT_ZOOM, capture ended,
 *     |                                       v                    v          hologram gone, helper gone)
 *     +------------- reaches 0 ---------- ZOOMING_OUT <------------+
 *   abort() (world unload, disconnect): straight back to WORLD from anywhere.
 * </pre>
 *
 * <p>Effects: {@link Env#begin} when the zoom starts (hide hand/HUD, block game input), {@link Env#startWorking} on
 * arriving (platform zoom-in, {@code SetWorking(true)}), {@link Env#stopWorking} when leaving WORKING (platform zoom-out,
 * {@code SetWorking(false)}), {@link Env#end} back in the world (restore everything). {@code stopWorking} runs only if
 * {@code startWorking} did, and {@code end} only if {@code begin} did.
 */
public final class ZoomController {
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
		if (env.workProblem(c) != null) {
			return Request.UNAVAILABLE;
		}
		target = c;
		state = State.ZOOMING_IN;
		fromProgress = 0;
		since = env.nowMs();
		doubleEsc.reset();
		env.begin(c);
		return Request.STARTED;
	}

	/** The hologram's spec changed while zoomed (e.g. moved): keep following it. */
	public void retarget(final HologramSpec spec) {
		Candidate t = target;
		if (t != null) {
			target = new Candidate(t.hologramId(), spec, t.live());
		}
	}

	/** Advances the animation; call every frame and tick. */
	public void update() {
		Candidate t = target;
		if (t == null) {
			return;
		}
		double p = linear(env.nowMs());
		if (state == State.ZOOMING_IN && p >= 1) {
			state = State.WORKING;
			env.startWorking(t);
		} else if (state == State.ZOOMING_OUT && p <= 0) {
			state = State.WORLD;
			target = null;
			env.end(t, false);
		}
	}

	/** Zoom back out (double-Esc, the platform's exit hotkey, or anything that ends the session). */
	public boolean exit() {
		Candidate t = target;
		if (t == null || (state != State.ZOOMING_IN && state != State.WORKING)) {
			return false;
		}
		long now = env.nowMs();
		double p = linear(now);
		boolean wasWorking = state == State.WORKING;
		state = State.ZOOMING_OUT;
		fromProgress = p;
		since = now;
		if (wasWorking) {
			env.stopWorking(t);
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
			env.stopWorking(t);
		}
		env.end(t, true);
	}

	/** Eased progress now: 0 = the player's own view, 1 = the hologram fills the view. */
	public double progress() {
		return ease(linear(env.nowMs()));
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
