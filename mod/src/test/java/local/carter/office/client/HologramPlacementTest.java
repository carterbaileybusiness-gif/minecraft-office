package local.carter.office.client;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.UUID;

import org.junit.jupiter.api.Test;

import local.carter.office.client.interact.CornerDrag;
import local.carter.office.client.render.HologramPlacement;
import local.carter.office.core.HologramGeometry;
import local.carter.office.core.HologramGeometry.Corner;
import local.carter.office.core.HologramGeometry.Frame;
import local.carter.office.core.HologramSpec;
import local.carter.office.core.Mode;
import local.carter.office.core.Privacy;
import local.carter.office.core.Shape;
import local.carter.office.core.Vec3d;

class HologramPlacementTest {
	private static final UUID OWNER = UUID.randomUUID();
	private static final double EPS = 1e-6;

	private static HologramSpec spec(double width, double offY, float yaw) {
		return new HologramSpec(OWNER, Shape.WIDE_16_9, Mode.EXTEND, Privacy.PUBLIC, 1, null, width, 0, offY, 0, yaw);
	}

	private static void near(Vec3d expected, Vec3d actual) {
		assertEquals(expected.x(), actual.x(), EPS, "x");
		assertEquals(expected.y(), actual.y(), EPS, "y");
		assertEquals(expected.z(), actual.z(), EPS, "z");
	}

	/** Bottom-left corner of a frame, in world space. */
	private static Vec3d corner(Frame f, double sx, double sy) {
		return f.center().add(f.right().scale(sx * f.w() / 2)).add(f.up().scale(sy * f.h() / 2));
	}

	@Test
	void frameSitsAtEmitterCentrePlusOffsetAndFacesYaw() {
		HologramSpec s = new HologramSpec(OWNER, Shape.WIDE_16_9, Mode.EXTEND, Privacy.PUBLIC, 1, null, 2.0, 1, 2, 3, 0f);
		Frame f = HologramPlacement.frame(10, 64, -5, s);
		near(new Vec3d(11.5, 66.5, -1.5), f.center());
		near(new Vec3d(1, 0, 0), f.right()); // front faces south (+Z): a viewer looking north sees +X on the right
		near(new Vec3d(0, 1, 0), f.up());
		assertEquals(2.0, f.w(), EPS);
		assertEquals(2.0 / (16.0 / 9.0), f.h(), EPS);
		// normal (right x up) points out of the front
		near(new Vec3d(0, 0, 1), f.right().cross(f.up()));

		Frame west = HologramPlacement.frame(0, 0, 0, spec(2.0, 1.0, 90f));
		near(new Vec3d(-1, 0, 0), west.right().cross(west.up()));
		Frame north = HologramPlacement.frame(0, 0, 0, spec(2.0, 1.0, 180f));
		near(new Vec3d(0, 0, -1), north.right().cross(north.up()));
	}

	@Test
	void distanceToPanelUsesNearestPoint() {
		Frame f = HologramPlacement.frame(0, 0, 0, spec(4.0, 2.0, 0f)); // centre (0.5, 2.5, 0.5), 4 wide
		assertEquals(3.0, HologramPlacement.distanceToPanel(f, new Vec3d(0.5, 2.5, 3.5)), EPS);
		// beside the right edge (x = 2.5), 3 in front
		assertEquals(Math.hypot(1.0, 3.0), HologramPlacement.distanceToPanel(f, new Vec3d(3.5, 2.5, 3.5)), EPS);
	}

	@Test
	void growingBottomRightKeepsTopLeftFixed() {
		HologramSpec s = spec(2.0, 2.0, 0f);
		Frame f = HologramPlacement.frame(0, 0, 0, s);
		Vec3d topLeft = corner(f, -1, 1);
		Vec3d dragTo = corner(f, 1, -1).add(new Vec3d(1.0, 0, 0)); // one block further right
		CornerDrag.Result r = CornerDrag.resize(f, Corner.BOTTOM_RIGHT, dragTo, s);
		assertEquals(3.0, r.width(), EPS);
		Frame after = HologramPlacement.frame(0, 0, 0, r.apply(s));
		near(topLeft, corner(after, -1, 1));
	}

	@Test
	void shrinkingIsClampedToMinimumWidth() {
		HologramSpec s = spec(2.0, 2.0, 0f);
		Frame f = HologramPlacement.frame(0, 0, 0, s);
		CornerDrag.Result r = CornerDrag.resize(f, Corner.TOP_RIGHT, corner(f, -1, -1), s);
		assertEquals(HologramSpec.MIN_WIDTH, r.width(), EPS);
	}

	@Test
	void growingTallerNeverSinksBelowTheEmitterTop() {
		// Default-ish hologram just above the emitter; drag the bottom-left corner far down and left.
		HologramSpec s = spec(2.0, 0.5 + (2.0 / (16.0 / 9.0)) / 2 + 0.1, 0f);
		Frame f = HologramPlacement.frame(0, 0, 0, s);
		Vec3d dragTo = corner(f, -1, -1).add(new Vec3d(-3.0, -3.0, 0));
		CornerDrag.Result r = CornerDrag.resize(f, Corner.BOTTOM_LEFT, dragTo, s);
		assertTrue(r.width() > 2.0, "grew");
		HologramSpec after = r.apply(s);
		double bottom = after.offY() - after.height() / 2;
		assertTrue(bottom >= HologramPlacement.EMITTER_TOP - EPS, "bottom " + bottom + " stays above the emitter top");
	}

	@Test
	void hologramAlreadyBelowTheEmitterKeepsItsBottomEdge() {
		// Hung under a ceiling emitter: bottom edge well below the emitter. Dragging a top corner keeps the bottom.
		HologramSpec s = spec(2.0, -2.0, 0f);
		Frame f = HologramPlacement.frame(0, 0, 0, s);
		double oldBottom = s.offY() - s.height() / 2;
		CornerDrag.Result r = CornerDrag.resize(f, Corner.TOP_RIGHT, corner(f, 1, 1).add(new Vec3d(1, 0, 0)), s);
		HologramSpec after = r.apply(s);
		assertEquals(oldBottom, after.offY() - after.height() / 2, EPS);
	}

	@Test
	void resizeKeepsOffsetsWithinServerLimits() {
		HologramSpec s = spec(2.0, 15.0, 0f);
		Frame f = HologramPlacement.frame(0, 0, 0, s);
		CornerDrag.Result r = CornerDrag.resize(f, Corner.BOTTOM_RIGHT, corner(f, 1, -1).add(new Vec3d(20, 0, 0)), s);
		assertEquals(HologramSpec.MAX_WIDTH, r.width(), EPS);
		assertTrue(Math.abs(r.offY()) <= 16.0 && Math.abs(r.offX()) <= 16.0 && Math.abs(r.offZ()) <= 16.0);
	}

	@Test
	void pickedFrameMatchesGeometryHit() {
		HologramSpec s = spec(2.0, 2.0, 0f);
		Frame f = HologramPlacement.frame(0, 0, 0, s);
		// Looking north from 3 blocks south of the centre: hits the middle of the front face.
		var hit = HologramGeometry.hit(new Vec3d(0.5, 2.5, 3.5), new Vec3d(0, 0, -1), f).orElseThrow();
		assertEquals(0.5, hit.u(), EPS);
		assertEquals(0.5, hit.v(), EPS);
		assertTrue(hit.frontFacing());
	}
}
