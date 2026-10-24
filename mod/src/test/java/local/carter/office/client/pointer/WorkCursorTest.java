package local.carter.office.client.pointer;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.util.UUID;

import org.junit.jupiter.api.Test;

import local.carter.office.core.HologramGeometry;
import local.carter.office.core.HologramGeometry.Hit;
import local.carter.office.core.HologramSpec;
import local.carter.office.core.Mode;
import local.carter.office.core.Privacy;
import local.carter.office.core.Shape;

/** Work mode: the real cursor is drawn on the hologram that shows its display, on the pixel a click goes to. */
class WorkCursorTest {
	private static final UUID ME = UUID.fromString("00000000-0000-0000-0000-00000000000a");

	private static HologramSpec spec(final Mode mode, final int slot) {
		return new HologramSpec(ME, Shape.WIDE_16_9, mode, Privacy.PRIVATE, slot, "", 2.0, 0, 1.5, 0, 0);
	}

	@Test
	void onTheMirrorTheMainDisplaysPointsAsAShareOfThePicture() {
		double[] uv = WorkCursor.on(spec(Mode.MIRROR, 1), new int[] {0, 720, 450, 1440, 900});
		assertArrayEquals(new double[] {720.5 / 1440, 450.5 / 900}, uv, 1e-12);
		assertNull(WorkCursor.on(spec(Mode.MIRROR, 1), new int[] {1, 10, 10, 1920, 1080}), "the cursor is on display 1, not the Mirror");
	}

	@Test
	void onAnExtendHologramItsOwnDisplaysPixelExactlyWhereAClickLands() {
		HologramSpec extend = spec(Mode.EXTEND, 2);
		int[] cursor = {2, 961, 541, 1920, 1080};
		double[] uv = WorkCursor.on(extend, cursor);
		// the hologram's own pixel mapping (crosshair → pixel) at that (u, v) gives back the same pixel: ±0
		int[] px = HologramGeometry.toPixel(new Hit(uv[0], uv[1], 1, null), 1920, 1080);
		assertArrayEquals(new int[] {961, 541}, px);
		assertNull(WorkCursor.on(spec(Mode.EXTEND, 1), cursor), "display 2's cursor isn't on display 1's hologram");
		assertNull(WorkCursor.on(spec(Mode.EXTEND, 0), new int[] {0, 5, 5, 1440, 900}), "an Extend hologram never shows the main display");
	}

	@Test
	void noCursorWhenItIsElsewhereOrUnknown() {
		assertNull(WorkCursor.on(spec(Mode.MIRROR, 1), null));
		assertNull(WorkCursor.on(spec(Mode.MIRROR, 1), new int[] {-1, 0, 0, 0, 0}), "on another monitor");
		assertNull(WorkCursor.on(spec(Mode.WINDOW, 1), new int[] {0, 5, 5, 1440, 900}));
	}
}
