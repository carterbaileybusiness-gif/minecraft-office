package local.carter.office.client.pointer;

import org.jspecify.annotations.Nullable;

import local.carter.office.client.helper.HelperProtocol;
import local.carter.office.core.HologramSpec;

/**
 * Where the real cursor is drawn while working (spec 5.10 round 2): the helper reports the display the cursor is on and
 * its position on that display's grid ({@code {slot, x, y, w, h}}: slot 0 = the main display in points, 1-4 = a
 * virtual display in pixels, -1 = none of the player's screens). A hologram that shows that display draws the cursor at
 * the same share of its picture: a Mirror shows the whole main display, an Extend hologram its virtual display pixel
 * for pixel, so the drawn tip sits on the pixel a click goes to. Pure.
 */
public final class WorkCursor {
	private WorkCursor() {}

	/** {u, v} of the cursor's tip on a hologram drawn with {@code spec}, or null when it isn't on that hologram's screen. */
	public static double @Nullable [] on(final HologramSpec spec, final int @Nullable [] cursor) {
		if (cursor == null || cursor.length < 5 || cursor[3] <= 0 || cursor[4] <= 0) {
			return null;
		}
		int slot = cursor[0];
		boolean shown = switch (spec.mode()) {
			case MIRROR -> slot == HelperProtocol.MAIN_SLOT;
			case EXTEND -> slot == spec.displaySlot() && slot != HelperProtocol.MAIN_SLOT;
			case WINDOW -> false;
		};
		if (!shown) {
			return null;
		}
		double u = Math.max(0, Math.min(1, (cursor[1] + 0.5) / cursor[3]));
		double v = Math.max(0, Math.min(1, (cursor[2] + 0.5) / cursor[4]));
		return new double[] {u, v};
	}
}
