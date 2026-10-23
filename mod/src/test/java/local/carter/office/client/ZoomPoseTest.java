package local.carter.office.client;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.joml.Matrix4f;
import org.joml.Vector4f;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.api.Test;

import local.carter.office.client.zoom.ZoomPose;
import local.carter.office.client.zoom.ZoomPose.Pose;
import local.carter.office.client.zoom.ZoomPose.Rect;
import local.carter.office.core.HologramGeometry.Frame;
import local.carter.office.core.Shape;
import local.carter.office.core.Vec3d;

class ZoomPoseTest {
	private static Frame frame(final Shape shape, final double width, final float yaw) {
		double r = Math.toRadians(yaw);
		return new Frame(new Vec3d(10.5, 65.2, -3.5), new Vec3d(Math.cos(r), 0, Math.sin(r)), new Vec3d(0, 1, 0), width,
				width / shape.aspect());
	}

	/**
	 * Projects the hologram's corners the way Minecraft does: camera rotation from (yRot, xRot) exactly as
	 * {@code Camera.setRotation}, then a vertical-FOV perspective of the window aspect. Returns {maxAbsX, maxAbsY} in NDC.
	 */
	private static double[] ndcExtent(final Frame f, final Pose p, final double fovDeg, final double aspect) {
		org.joml.Quaternionf rot = new org.joml.Quaternionf().rotationYXZ((float) Math.PI - p.yaw() * (float) (Math.PI / 180.0),
				-p.pitch() * (float) (Math.PI / 180.0), 0.0F);
		Matrix4f view = new Matrix4f().rotation(rot.conjugate(new org.joml.Quaternionf()));
		Matrix4f proj = new Matrix4f().perspective((float) Math.toRadians(fovDeg), (float) aspect, 0.05f, 1000f);
		double mx = 0;
		double my = 0;
		for (int sx = -1; sx <= 1; sx += 2) {
			for (int sy = -1; sy <= 1; sy += 2) {
				Vec3d c = f.center().add(f.right().scale(sx * f.w() / 2)).add(f.up().scale(sy * f.h() / 2)).sub(p.eye());
				Vector4f v = new Vector4f((float) c.x(), (float) c.y(), (float) c.z(), 1f);
				view.transform(v);
				assertTrue(v.z < 0, "corner is in front of the camera");
				proj.transform(v);
				mx = Math.max(mx, Math.abs(v.x / v.w));
				my = Math.max(my, Math.abs(v.y / v.w));
			}
		}
		return new double[] {mx, my};
	}

	@ParameterizedTest(name = "{0} hologram, FOV {1}, window {2}x{3}")
	@CsvSource({
			"WIDE_16_9, 70, 1920, 1080", "WIDE_16_9, 90, 1920, 1080",
			"ULTRA_21_9, 70, 1920, 1080", "ULTRA_21_9, 90, 1920, 1080",
			"TALL_9_16, 70, 1920, 1080", "TALL_9_16, 90, 1920, 1080",
			"WIDE_16_9, 70, 2560, 1080", "WIDE_16_9, 90, 1080, 1920",
			"ULTRA_21_9, 70, 1080, 1920", "TALL_9_16, 90, 2560, 1080",
			"WIDE_16_9, 70, 2880, 1800"})
	void hologramFillsTheViewWithoutCropping(final Shape shape, final double fov, final int winW, final int winH) {
		double aspect = (double) winW / winH;
		for (float yaw : new float[] {0f, 37f, 90f, 180f, 270f}) {
			Frame f = frame(shape, 2.4, yaw);
			Pose p = ZoomPose.framing(f, fov, aspect);
			double[] e = ndcExtent(f, p, fov, aspect);
			double fill = Math.max(e[0], e[1]);
			assertEquals(1.0, fill, 1e-4, "one dimension exactly fills the view (yaw " + yaw + ")");
			assertTrue(e[0] <= 1 + 1e-4 && e[1] <= 1 + 1e-4, "nothing is cropped");
			boolean wider = shape.aspect() >= aspect;
			assertEquals(1.0, wider ? e[0] : e[1], 1e-4, "the hologram's tighter dimension is the one that fills");
			// letterbox = the projected hologram, so the 2D overlay lines up with the zoomed 3D view
			Rect r = ZoomPose.letterbox(winW, winH, shape.aspect());
			assertEquals(e[0] * winW, r.w(), 1.5, "overlay width matches the projection");
			assertEquals(e[1] * winH, r.h(), 1.5, "overlay height matches the projection");
		}
	}

	@Test
	void cameraLooksAtTheFrontFromTheCentreNormal() {
		Frame f = frame(Shape.WIDE_16_9, 2.0, 0f); // front faces south (+Z)
		Pose p = ZoomPose.framing(f, 70, 16.0 / 9.0);
		assertEquals(f.center().x(), p.eye().x(), 1e-9);
		assertEquals(f.center().y(), p.eye().y(), 1e-9);
		assertTrue(p.eye().z() > f.center().z(), "in front of the panel");
		assertEquals(180f, Math.abs(p.yaw()), 1e-4f, "looking north at it");
		assertEquals(0f, p.pitch(), 1e-6f);
		// height-limited 16:9 in 16:9: d = (h/2) / tan(fov/2)
		assertEquals((2.0 / (16.0 / 9.0) / 2) / Math.tan(Math.toRadians(35)), p.eye().z() - f.center().z(), 1e-9);
	}

	@Test
	void letterboxCentresAndFits() {
		assertEquals(new Rect(0, 0, 1920, 1080), ZoomPose.letterbox(1920, 1080, 16.0 / 9.0));
		Rect ultra = ZoomPose.letterbox(1920, 1080, 2560.0 / 1080.0);
		assertEquals(1920, ultra.w());
		assertEquals(810, ultra.h());
		assertEquals(135, ultra.y(), "bars top and bottom");
		Rect tall = ZoomPose.letterbox(1920, 1080, 1080.0 / 1920.0);
		assertEquals(1080, tall.h());
		assertEquals(608, tall.w());
		assertEquals((1920 - 608) / 2, tall.x(), "bars left and right");
	}

	@Test
	void yawTakesTheShortWayRound() {
		assertEquals(5f, ZoomPose.wrap(ZoomPose.lerpDegrees(350f, 20f, 0.5)), 1e-4f);
		assertEquals(-175f, ZoomPose.wrap(ZoomPose.lerpDegrees(170f, -160f, 0.5)), 1e-4f, "across the -180/180 seam");
		assertEquals(20f, ZoomPose.wrap(ZoomPose.lerpDegrees(350f, 20f, 1.0)), 1e-4f);
		assertEquals(350f, ZoomPose.lerpDegrees(350f, 20f, 0.0), 1e-4f);
	}
}
