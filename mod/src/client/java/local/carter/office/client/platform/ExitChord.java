package local.carter.office.client.platform;

/**
 * The zoom exit gesture as seen by a low-level keyboard hook (Windows): two separate Esc presses within
 * {@link #DOUBLE_MS} ms, or Ctrl+Alt+Shift+Esc at any time. The first Esc of a pair still reaches the app; the press that
 * completes the gesture (and its key-up) is swallowed. Pure logic, one hook thread at a time.
 */
public final class ExitChord {
	public static final int VK_ESCAPE = 0x1B;
	public static final long DOUBLE_MS = 350;

	public enum Action {
		/** Let the key through to the app. */
		PASS,
		/** Eat the key. */
		SWALLOW,
		/** Eat the key and leave the zoom. */
		EXIT
	}

	private static final long NONE = Long.MIN_VALUE;

	private long lastEsc = NONE;
	private boolean escHeld;
	private boolean escSwallowed;

	/** One key event: {@code vk} virtual-key code, {@code down} press (or auto-repeat) vs release, modifiers held, monotonic ms. */
	public Action key(final int vk, final boolean down, final boolean ctrl, final boolean alt, final boolean shift, final long ms) {
		if (vk != VK_ESCAPE) {
			if (down) {
				lastEsc = NONE; // something else typed in between: not a double tap
			}
			return Action.PASS;
		}
		if (!down) {
			escHeld = false;
			boolean eat = escSwallowed;
			escSwallowed = false;
			return eat ? Action.SWALLOW : Action.PASS;
		}
		if (escHeld) {
			return escSwallowed ? Action.SWALLOW : Action.PASS; // auto-repeat of a held Esc
		}
		escHeld = true;
		if (ctrl && alt && shift) {
			lastEsc = NONE;
			escSwallowed = true;
			return Action.EXIT;
		}
		if (ctrl || alt || shift) {
			lastEsc = NONE; // Ctrl+Esc, Alt+Esc... belong to Windows
			return Action.PASS;
		}
		if (lastEsc != NONE && ms - lastEsc <= DOUBLE_MS) {
			lastEsc = NONE;
			escSwallowed = true;
			return Action.EXIT;
		}
		lastEsc = ms;
		return Action.PASS;
	}

	/** Forgets a half-finished double tap (e.g. when a zoom starts). */
	public void reset() {
		lastEsc = NONE;
		escHeld = false;
		escSwallowed = false;
	}
}
