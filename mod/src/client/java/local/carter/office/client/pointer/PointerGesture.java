package local.carter.office.client.pointer;

import org.jspecify.annotations.Nullable;

import local.carter.office.client.helper.HelperProtocol.PointerButton;

/**
 * The left button on a hologram (pure, client thread). A press that comes up without the crosshair moving is a click
 * there (it enters typing mode). Once the crosshair moves past {@link #DRAG_THRESHOLD_PX} (or onto another hologram, or
 * off every hologram) it is a drag: {@code down} at the press point, then {@code move} to every new pixel the crosshair
 * reaches, on any of the player's holograms; between holograms the last point is kept.
 *
 * <p>The helper drives the drag from then on (it takes the physical mouse; Minecraft loses focus and its key state
 * reads "released"), so a drag ends with the helper's DRAG_ENDED ({@link #dragEnded}), not with Minecraft's key. Only
 * if Minecraft still has focus and sees the button up for {@link #STUCK_MS} (the helper never took over) does the mod
 * end it itself with {@code up}. A right press is an immediate right-click.
 */
public final class PointerGesture {
	/** Crosshair movement (display pixels) that turns a press into a drag. */
	public static final int DRAG_THRESHOLD_PX = 6;
	/** A drag the helper didn't take over is released by the mod this long after Minecraft saw the button come up. */
	public static final long STUCK_MS = 1_000;

	public enum State { IDLE, PRESSED, DRAGGING }

	/** What the gesture sends (to the helper). */
	public interface Out {
		void down(PointerPoint p);

		void move(PointerPoint p);

		void up(PointerPoint p);

		void click(PointerPoint p, PointerButton button);
	}

	private final Out out;
	private State state = State.IDLE;
	private @Nullable PointerPoint start;
	private @Nullable PointerPoint last;
	private long releasedAt = -1;

	public PointerGesture(final Out out) {
		this.out = out;
	}

	public State state() {
		return state;
	}

	/** Pressed or dragging: Minecraft must not attack (break blocks) meanwhile. */
	public boolean busy() {
		return state != State.IDLE;
	}

	/** Where the cursor is during a press or drag (the last valid point), else null. */
	public @Nullable PointerPoint point() {
		return state == State.IDLE ? null : last;
	}

	/** Left button pressed on {@code p}. False (ignored) while a press or drag is already going on. */
	public boolean press(final PointerPoint p, final long now) {
		if (state != State.IDLE) {
			return false;
		}
		state = State.PRESSED;
		start = p;
		last = p;
		releasedAt = -1;
		return true;
	}

	/** Every frame: the pixel under the crosshair now, or null when it is on none of the player's holograms. */
	public void aim(final @Nullable PointerPoint p, final long now) {
		switch (state) {
			case IDLE -> {
				// hover only
			}
			case PRESSED -> {
				if (p != null && p.near(start, DRAG_THRESHOLD_PX)) {
					return;
				}
				state = State.DRAGGING;
				out.down(start);
				if (p != null) {
					last = p;
					out.move(p);
				}
			}
			case DRAGGING -> {
				if (p != null && !p.equals(last)) {
					last = p;
					out.move(p);
				}
			}
		}
	}

	/** Minecraft sees the left button up. {@code focused}: Minecraft still has the mouse (the helper hasn't taken it). */
	public void release(final boolean focused, final long now) {
		if (state == State.PRESSED) {
			PointerPoint p = start;
			reset();
			out.click(p, PointerButton.LEFT);
		} else if (state == State.DRAGGING && focused && releasedAt < 0) {
			releasedAt = now;
		}
	}

	/** Right button pressed on {@code p}: an immediate right-click (not while a drag is going on). */
	public void rightClick(final PointerPoint p) {
		if (state == State.IDLE) {
			out.click(p, PointerButton.RIGHT);
		}
	}

	/** The helper ended the drag (it posted the up and gives Minecraft its mouse back). */
	public void dragEnded() {
		if (state == State.DRAGGING) {
			reset();
		}
	}

	/** Every tick: a drag the helper never took over ends after {@link #STUCK_MS}. */
	public void tick(final long now) {
		if (state == State.DRAGGING && releasedAt >= 0 && now - releasedAt > STUCK_MS) {
			cancel();
		}
	}

	/** World left, helper gone: a drag is released at its last point, a press forgotten. */
	public void cancel() {
		PointerPoint p = last;
		boolean dragging = state == State.DRAGGING;
		reset();
		if (dragging && p != null) {
			out.up(p);
		}
	}

	private void reset() {
		state = State.IDLE;
		start = null;
		last = null;
		releasedAt = -1;
	}
}
