package local.carter.office.client.pointer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.UUID;

import org.junit.jupiter.api.Test;

import local.carter.office.client.render.HologramPlacement;
import local.carter.office.core.HologramGeometry;
import local.carter.office.core.HologramGeometry.Frame;
import local.carter.office.core.HologramGeometry.Hit;
import local.carter.office.core.HologramSpec;
import local.carter.office.core.Mode;
import local.carter.office.core.Privacy;
import local.carter.office.core.Shape;
import local.carter.office.core.Vec3d;

/** The crosshair's hit on a drawn hologram → the pixel of its virtual display (Extend) or of the main display (Mirror). */
class PointerPointTest {
	private static final UUID ME = UUID.fromString("00000000-0000-0000-0000-00000000000a");

	private static HologramSpec spec(final Mode mode, final Shape shape, final int slot) {
		return new HologramSpec(ME, shape, mode, Privacy.PUBLIC, slot, "", 2.0, 0, 1.5, 0, 0);
	}

	@Test
	void centreCornersAndShapes() {
		HologramSpec wide = spec(Mode.EXTEND, Shape.WIDE_16_9, 1);
		assertEquals(new PointerPoint(1, 960, 540), PointerPoint.of(wide, new Hit(0.5, 0.5, 2, null)));
		assertEquals(new PointerPoint(1, 0, 0), PointerPoint.of(wide, new Hit(0, 0, 2, null)));
		assertEquals(new PointerPoint(1, 1919, 1079), PointerPoint.of(wide, new Hit(1, 1, 2, null)), "the far edge is the last pixel");
		assertEquals(new PointerPoint(3, 540, 1919), PointerPoint.of(spec(Mode.EXTEND, Shape.TALL_9_16, 3), new Hit(0.5, 1, 2, null)));
		assertEquals(new PointerPoint(2, 2559, 0), PointerPoint.of(spec(Mode.EXTEND, Shape.ULTRA_21_9, 2), new Hit(1, 0, 2, null)));
	}

	@Test
	void aMirrorPointsAtTheMainDisplayAsAShareOfItsPicture() {
		// spec 5.10: the Mirror is the laptop's own screen, slot 0; (x, y) of the shape's picture + its size, which the
		// helper maps by proportion onto the main display's points (whatever its size or Retina scale)
		HologramSpec mirror = spec(Mode.MIRROR, Shape.WIDE_16_9, 3);
		PointerPoint centre = PointerPoint.of(mirror, new Hit(0.5, 0.5, 2, null));
		assertEquals(new PointerPoint(0, 960, 540, 1920, 1080), centre, "the display slot of a Mirror spec doesn't matter");
		assertTrue(centre.onMain());
		assertEquals(new PointerPoint(0, 0, 0, 1920, 1080), PointerPoint.of(mirror, new Hit(0, 0, 2, null)));
		assertEquals(new PointerPoint(0, 1919, 1079, 1920, 1080), PointerPoint.of(mirror, new Hit(1, 1, 2, null)));
		assertEquals(new PointerPoint(0, 270, 1440, 1080, 1920), PointerPoint.of(spec(Mode.MIRROR, Shape.TALL_9_16, 1), new Hit(0.25, 0.75, 2, null)),
				"a tall Mirror: its own picture size");
		// e.g. the helper then maps (960, 540) of 1920 x 1080 onto a 1440 x 900-point main display at (720.4, 450.4)
		assertFalse(new PointerPoint(1, 960, 540).onMain());
		assertFalse(centre.near(new PointerPoint(1, 960, 540), 6), "the Mirror and display 1 are different screens");
	}

	@Test
	void mirrorCarriesItsMonitor() {
		HologramSpec lg = spec(Mode.MIRROR, Shape.WIDE_16_9, 1).withMirrorDisplay("LG");
		PointerPoint p = PointerPoint.of(lg, new Hit(0.5, 0.5, 2, null));
		assertEquals(new PointerPoint(0, 960, 540, 1920, 1080, "LG"), p);
		assertTrue(p.onMain(), "still slot 0: the helper maps it onto that monitor");
		assertNull(PointerPoint.of(spec(Mode.EXTEND, Shape.WIDE_16_9, 1).withMirrorDisplay("LG"), new Hit(0.5, 0.5, 2, null)).display(),
				"Extend points are on their Office display");
		assertFalse(p.near(new PointerPoint(0, 960, 540, 1920, 1080), 6), "another monitor is another screen");
	}

	@Test
	void onlyExtendAndMirrorHologramsAndOnlyThePicture() {
		assertNull(PointerPoint.of(spec(Mode.MIRROR, Shape.WIDE_16_9, 1), new Hit(1.02, 0.5, 2, null)), "just off the Mirror's picture");
		assertNull(PointerPoint.of(spec(Mode.WINDOW, Shape.WIDE_16_9, 1), new Hit(0.5, 0.5, 2, null)), "one window is view-only");
		// the owner's corner grab zone reaches just outside the picture: that is not a pixel
		assertNull(PointerPoint.of(spec(Mode.EXTEND, Shape.WIDE_16_9, 1), new Hit(-0.02, 0.5, 2, HologramGeometry.Corner.TOP_LEFT)));
		assertNull(PointerPoint.of(spec(Mode.EXTEND, Shape.WIDE_16_9, 1), new Hit(0.5, 1.03, 2, null)));
	}

	@Test
	void aRayThroughTheDrawnHologramLandsOnTheMatchingPixel() {
		HologramSpec s = spec(Mode.EXTEND, Shape.WIDE_16_9, 1);
		Frame f = HologramPlacement.frame(10, 64, -3, s);
		// aim from 3 blocks in front at the point a quarter across and a quarter down
		Vec3d target = f.center().add(f.right().scale(-0.25 * f.w())).add(f.up().scale(0.25 * f.h()));
		Vec3d normal = f.right().cross(f.up());
		Vec3d eye = target.add(normal.scale(-3)).add(new Vec3d(0, 0, 0));
		Hit hit = HologramGeometry.hit(eye, target.sub(eye), f).or(() -> HologramGeometry.hit(target.add(normal.scale(3)),
				normal.scale(-1), f)).orElseThrow();
		assertEquals(new PointerPoint(1, 480, 270), PointerPoint.of(s, hit));
	}
}
