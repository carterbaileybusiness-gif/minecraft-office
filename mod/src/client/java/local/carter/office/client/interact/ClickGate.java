package local.carter.office.client.interact;

/**
 * When a right-click may be taken for a hologram, and swallowing the auto-repeat of a click we already took (pure).
 *
 * <p>Vanilla's {@code startUseItem} does nothing while a block is being destroyed or the hands are busy, and sets a
 * 4-tick right-click delay; holding the button repeats it. We respect the same guards, apply the same delay, and treat
 * every repeat of a consumed click as part of that click until the button is released, so holding right-click on a
 * hologram never re-opens settings, re-starts a drag or spams "Owned by …".
 */
public final class ClickGate {
	/** Vanilla's right-click repeat delay in ticks ({@code Minecraft.rightClickDelay = 4}). */
	public static final int RIGHT_CLICK_DELAY = 4;

	private boolean held;

	/** Same guards as vanilla {@code startUseItem}: never intercept while destroying a block or with busy hands. */
	public static boolean mayIntercept(final boolean destroying, final boolean handsBusy) {
		return !destroying && !handsBusy;
	}

	/** True while the use key is still held after a click we consumed: the call is a repeat, swallow it silently. */
	public boolean isRepeat() {
		return held;
	}

	/** A click was consumed for a hologram. */
	public void consumed() {
		held = true;
	}

	/** Every client tick: releasing the use key ends the consumed click. */
	public void tick(final boolean useKeyDown) {
		if (!useKeyDown) {
			held = false;
		}
	}
}
