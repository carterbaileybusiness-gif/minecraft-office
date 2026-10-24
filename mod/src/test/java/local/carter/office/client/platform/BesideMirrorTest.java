package local.carter.office.client.platform;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.UUID;

import org.junit.jupiter.api.Test;

import local.carter.office.client.render.HologramPlacement;
import local.carter.office.core.HologramGeometry.Frame;
import local.carter.office.core.HologramSpec;
import local.carter.office.core.Mode;
import local.carter.office.core.Privacy;
import local.carter.office.core.Shape;
import local.carter.office.core.Vec3d;

/** A new Extend hologram goes beside the Mirror on the side the display arrangement put its display. */
class BesideMirrorTest {
	private static final UUID ME = UUID.fromString("00000000-0000-0000-0000-00000000000a");

	private static HologramSpec spec(final Mode mode, final int slot, final float yaw) {
		return new HologramSpec(ME, Shape.WIDE_16_9, mode, Privacy.PUBLIC, slot, "", 2.0, 0, 1.2, 0, yaw);
	}

	/** Position of the placed hologram along the Mirror's right vector (negative = viewer's left), and its bottom. */
	private static double[] where(final Vec3d mirrorBlock, final HologramSpec mirror, final Vec3d block, final HologramSpec extend,
			final BesideMirror.Placement p) {
		Frame m = HologramPlacement.frame(mirrorBlock.x(), mirrorBlock.y(), mirrorBlock.z(), mirror);
		HologramSpec moved = new HologramSpec(ME, extend.shape(), extend.mode(), extend.privacy(), extend.displaySlot(), "",
				extend.width(), p.offX(), p.offY(), p.offZ(), p.yaw());
		Frame e = HologramPlacement.frame(block.x(), block.y(), block.z(), moved);
		Vec3d rel = e.center().sub(m.center());
		return new double[] {rel.dot(m.right()), e.center().y() - e.h() / 2, m.center().y() - m.h() / 2, rel.dot(m.right().cross(m.up()))};
	}

	@Test
	void leftWhenTheDisplaysAreLeftRightOtherwiseBottomsLevelSamePlane() {
		Vec3d mirrorBlock = new Vec3d(0, 64, 0);
		HologramSpec mirror = spec(Mode.MIRROR, 1, 90f);
		Vec3d block = new Vec3d(0, 64, 3); // the player put the emitter 3 blocks along the wall
		HologramSpec extend = spec(Mode.EXTEND, 1, 0f);
		BesideMirror.Placement left = BesideMirror.place(mirrorBlock, mirror, block, extend, true);
		assertNotNull(left);
		double[] l = where(mirrorBlock, mirror, block, extend, left);
		assertEquals(-(1.0 + BesideMirror.GAP + 1.0), l[0], 1e-9, "display 1 just left of the Mirror");
		assertEquals(l[2], l[1], 1e-9, "bottom edges level");
		assertEquals(0, l[3], 1e-9, "same plane");
		assertEquals(90f, left.yaw(), "facing the same way");
		double[] r = where(mirrorBlock, mirror, block, extend, BesideMirror.place(mirrorBlock, mirror, block, extend, false));
		assertEquals(2.1, r[0], 1e-9, "right when the arrangement is right");
	}

	@Test
	void displayTwoGoesPastDisplayOne() {
		Vec3d mirrorBlock = new Vec3d(10, 64, 10);
		HologramSpec mirror = spec(Mode.MIRROR, 1, 0f);
		HologramSpec two = spec(Mode.EXTEND, 2, 0f);
		Vec3d block = new Vec3d(8, 64, 10);
		double[] p = where(mirrorBlock, mirror, block, two, BesideMirror.place(mirrorBlock, mirror, block, two, true));
		assertEquals(-(1.0 + 0.1 + 2.0 + 0.1 + 1.0), p[0], 1e-9);
		assertTrue(BesideMirror.hint(2, true).contains("left edge"), BesideMirror.hint(2, true));
		assertTrue(BesideMirror.hint(1, false).contains("right edge"));
	}

	@Test
	void aMirrorFarAwayLeavesTheNewHologramWherePlaced() {
		assertNull(BesideMirror.place(new Vec3d(0, 64, 0), spec(Mode.MIRROR, 1, 0f), new Vec3d(30, 64, 0), spec(Mode.EXTEND, 1, 0f), true));
	}
}
