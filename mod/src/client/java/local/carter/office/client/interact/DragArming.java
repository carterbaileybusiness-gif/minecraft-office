package local.carter.office.client.interact;

/**
 * Tells a quick tap from a drag (pure). A press only becomes a drag once the button has been held for
 * {@link #HOLD_MS} or the aim has moved more than {@link #MOVE_BLOCKS} on the hologram's plane; until then nothing
 * changes, and releasing early counts as a tap (which opens settings, like tapping the body).
 */
public final class DragArming {
	public static final long HOLD_MS = 150;
	public static final double MOVE_BLOCKS = 0.04;

	private final long startMs;
	private boolean armed;

	public DragArming(final long startMs) {
		this.startMs = startMs;
	}

	/** Feeds the current time and how far the aim has moved since the press; returns whether this is now a drag. */
	public boolean update(final long nowMs, final double movedBlocks) {
		if (!armed && (nowMs - startMs >= HOLD_MS || movedBlocks > MOVE_BLOCKS)) {
			armed = true;
		}
		return armed;
	}

	public boolean armed() {
		return armed;
	}
}
