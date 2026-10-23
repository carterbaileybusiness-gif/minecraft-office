package local.carter.office.client.pointer;

import org.jspecify.annotations.Nullable;

import local.carter.office.core.HologramGeometry;
import local.carter.office.core.HologramGeometry.Hit;
import local.carter.office.core.HologramSpec;
import local.carter.office.core.Mode;

/**
 * A pixel of one of the player's virtual displays: what the crosshair points at on an Extend hologram. The hologram's
 * picture is the whole display at its native size ({@code Shape} pixels), so (u, v) of the hit maps straight onto it.
 */
public record PointerPoint(int slot, int x, int y) {
	/**
	 * The display pixel under {@code hit} on a hologram drawn with {@code spec} (use the drawn spec, {@code
	 * HologramPreview.effective}), or null: Mirror and one-window holograms are view-only, and the owner's corner grab
	 * zone just outside the picture is no pixel.
	 */
	public static @Nullable PointerPoint of(final HologramSpec spec, final Hit hit) {
		if (spec.mode() != Mode.EXTEND || hit.u() < 0 || hit.u() > 1 || hit.v() < 0 || hit.v() > 1) {
			return null;
		}
		int[] px = HologramGeometry.toPixel(hit, spec.shape().pxW(), spec.shape().pxH());
		return new PointerPoint(spec.displaySlot(), px[0], px[1]);
	}

	/** On the same display and within {@code pixels} of {@code other}. */
	public boolean near(final PointerPoint other, final int pixels) {
		return slot == other.slot && Math.hypot(x - other.x, y - other.y) <= pixels;
	}
}
