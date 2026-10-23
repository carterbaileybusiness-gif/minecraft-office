package local.carter.office.client.zoom;

/**
 * The "back to the office" gesture inside Minecraft: two Esc presses at most {@link #WINDOW_MS} apart (pure logic, the
 * same rule as the Mac helper's global hotkey). A single Esc belongs to the app being worked on ({@link Result#PASS}):
 * Minecraft never acts on it while working, so it never opens the pause menu. Key auto-repeat (no release in between),
 * a modified Esc, or another key in between never complete a double-tap.
 */
public final class DoubleEsc {
	/** Max time between the two presses, inclusive (same as the helper's ExitHotkey). */
	public static final long WINDOW_MS = 350;

	public enum Result {
		/** Leave the screen and zoom back out. */
		EXIT,
		/** Not an exit: the key is the app's. */
		PASS
	}

	private boolean armed;
	private long armedAt;
	private boolean down;

	/** An unmodified Esc press at {@code nowMs}. */
	public Result press(final long nowMs) {
		return key(nowMs, false);
	}

	/** An Esc press at {@code nowMs}; {@code modified} = Shift/Ctrl/Alt/Cmd held. */
	public Result key(final long nowMs, final boolean modified) {
		if (down) {
			return Result.PASS; // auto-repeat of a held Esc
		}
		down = true;
		if (modified) {
			armed = false;
			return Result.PASS;
		}
		if (armed && nowMs >= armedAt && nowMs - armedAt <= WINDOW_MS) {
			armed = false;
			return Result.EXIT;
		}
		armed = true;
		armedAt = nowMs;
		return Result.PASS;
	}

	/** Esc was released. */
	public void release() {
		down = false;
	}

	/** Any other key was pressed. */
	public void otherKey() {
		armed = false;
	}

	public void reset() {
		armed = false;
		down = false;
	}
}
