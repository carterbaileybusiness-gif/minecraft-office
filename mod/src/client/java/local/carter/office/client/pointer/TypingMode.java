package local.carter.office.client.pointer;

/**
 * Typing mode (pure, client thread): a click on a hologram gave the clicked app the keyboard. Minecraft shows a small
 * "Typing — Esc to return" hint and stands still (a screen is open, so no movement or looking) while focus is in the
 * app. It ends when the helper reports Esc / Minecraft active again ({@link #helperExited}), when Minecraft gets focus
 * back (the player clicked its window: {@link #focus}), when focus never left within {@link #FOCUS_WAIT_MS} (the click
 * activated nothing), or from the mod's side ({@link #leave}: world left, another screen replaced the hint).
 */
public final class TypingMode {
	public static final long FOCUS_WAIT_MS = 1_500;

	public enum State { OFF, STARTING, TYPING }

	public interface Env {
		/** Open the hint screen (releases the mouse, blocks movement). */
		void showHint();

		void hideHint();

		/** Tell the helper typing is over (EXIT_TYPING), when the helper didn't say so itself. */
		void exitHelper();

		/** Minecraft gets its mouse back (re-grab, keep-awake off, pause-on-focus-loss restored). */
		void backToGame();
	}

	private final Env env;
	private State state = State.OFF;
	private long since;

	public TypingMode(final Env env) {
		this.env = env;
	}

	public State state() {
		return state;
	}

	public boolean active() {
		return state != State.OFF;
	}

	/** A click was sent: the app under it is coming forward. */
	public void enter(final long now) {
		if (state != State.OFF) {
			return;
		}
		state = State.STARTING;
		since = now;
		env.showHint();
	}

	/** Minecraft's window focus, every tick. */
	public void focus(final boolean minecraftActive, final long now) {
		if (state == State.STARTING && !minecraftActive) {
			state = State.TYPING;
		} else if (state == State.TYPING && minecraftActive) {
			leave(true);
		}
	}

	/** TYPING_EXIT from the helper (Esc, or Minecraft became active). */
	public void helperExited() {
		leave(false);
	}

	public void tick(final long now) {
		if (state == State.STARTING && now - since >= FOCUS_WAIT_MS) {
			leave(true);
		}
	}

	/** Ended from the mod's side. */
	public void leave() {
		leave(true);
	}

	private void leave(final boolean tellHelper) {
		if (state == State.OFF) {
			return;
		}
		state = State.OFF;
		env.hideHint();
		if (tellHelper) {
			env.exitHelper();
		}
		env.backToGame();
	}
}
