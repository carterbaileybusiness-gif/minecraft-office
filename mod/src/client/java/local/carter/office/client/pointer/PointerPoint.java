package local.carter.office.client.pointer;

import org.jspecify.annotations.Nullable;

import local.carter.office.client.helper.HelperProtocol;
import local.carter.office.core.HologramGeometry;
import local.carter.office.core.HologramGeometry.Hit;
import local.carter.office.core.HologramSpec;
import local.carter.office.core.Mode;

/**
 * What the crosshair points at on one of your holograms: a pixel ({@code x}, {@code y}) of a {@code w} x {@code h}
 * picture. On an Extend hologram the picture is the whole virtual display {@code slot} at its native size ({@code
 * Shape} pixels). On a Mirror hologram (spec 5.10) it is the main display, slot {@link HelperProtocol#MAIN_SLOT}: the
 * hologram shows the whole screen stretched onto its shape, so (u, v) of the hit is the same share of the screen; the
 * helper maps the pixel onto the main display's bounds in points by proportion (Retina safe).
 */
public record PointerPoint(int slot, int x, int y, int w, int h) {
	/** A pixel of virtual display {@code slot} (its size is the helper's to know). */
	public PointerPoint(final int slot, final int x, final int y) {
		this(slot, x, y, 0, 0);
	}

	/** On the Mirror's main display. */
	public boolean onMain() {
		return slot == HelperProtocol.MAIN_SLOT;
	}

	/**
	 * The pixel under {@code hit} on a hologram drawn with {@code spec} (use the drawn spec, {@code
	 * HologramPreview.effective}), or null: one-window holograms are view-only, and the owner's corner grab zone just
	 * outside the picture is no pixel.
	 */
	public static @Nullable PointerPoint of(final HologramSpec spec, final Hit hit) {
		if (hit.u() < 0 || hit.u() > 1 || hit.v() < 0 || hit.v() > 1) {
			return null;
		}
		int pw = spec.shape().pxW();
		int ph = spec.shape().pxH();
		int[] px = HologramGeometry.toPixel(hit, pw, ph);
		return switch (spec.mode()) {
			case EXTEND -> new PointerPoint(spec.displaySlot(), px[0], px[1]);
			case MIRROR -> new PointerPoint(HelperProtocol.MAIN_SLOT, px[0], px[1], pw, ph);
			case WINDOW -> null;
		};
	}

	/** On the same display and within {@code pixels} of {@code other}. */
	public boolean near(final PointerPoint other, final int pixels) {
		return slot == other.slot && Math.hypot(x - other.x, y - other.y) <= pixels;
	}
}
