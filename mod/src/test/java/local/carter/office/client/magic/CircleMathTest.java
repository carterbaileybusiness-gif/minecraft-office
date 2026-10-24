package local.carter.office.client.magic;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.joml.Quaternionf;
import org.joml.Vector3f;
import org.junit.jupiter.api.Test;

import local.carter.office.client.magic.fx.ChargeCircles;
import local.carter.office.client.magic.fx.MagicCircleRenderer;
import local.carter.office.client.magic.fx.MagicCircleRenderer.Basis;
import local.carter.office.magic.core.ChargeMath;

import net.minecraft.world.phys.Vec3;

class CircleMathTest {
	private static final float EPS = 1.0E-5F;

	@Test
	void heldCircleIsOnTheCrosshairInFirstPersonAndLowerForOthers() {
		Vec3 eye = new Vec3(10, 65.62, -3);
		Vec3 look = new Vec3(0.6, -0.2, 0.8).normalize();
		Vec3 own = ChargeCircles.heldCentre(eye, look, true);
		// On the look line through the eye (the crosshair), AHEAD out.
		assertEquals(0, own.subtract(eye).cross(look).length(), 1.0E-9);
		assertEquals(ChargeCircles.AHEAD, own.distanceTo(eye), 1.0E-9);
		Vec3 others = ChargeCircles.heldCentre(eye, look, false);
		assertEquals(own.subtract(0, ChargeCircles.BELOW, 0), others);
		// Full charge grows it a quarter.
		assertEquals(1F, ChargeCircles.scale(0F));
		assertEquals(1.25F, ChargeCircles.scale(1F), 1.0E-6F);
	}

	@Test
	void ringRadiusGrowsStrictlyFromBase() {
		assertEquals(0.55F, MagicCircleRenderer.ringRadius(0), EPS);
		for (int i = 1; i <= 4; i++) {
			assertTrue(MagicCircleRenderer.ringRadius(i) > MagicCircleRenderer.ringRadius(i - 1), "ring " + i);
			assertEquals(0.55F + 0.15F * i, MagicCircleRenderer.ringRadius(i), EPS);
		}
	}

	@Test
	void ringFadesInOverThreeTicks() {
		assertEquals(0F, MagicCircleRenderer.ringAlpha(0), EPS);
		assertEquals(1F / 3, MagicCircleRenderer.ringAlpha(1), EPS);
		assertEquals(2F / 3, MagicCircleRenderer.ringAlpha(2), EPS);
		assertEquals(1F, MagicCircleRenderer.ringAlpha(3), EPS);
		assertEquals(0F, MagicCircleRenderer.ringAlpha(-2), EPS);
		assertEquals(1F, MagicCircleRenderer.ringAlpha(40), EPS);
	}

	@Test
	void basisFromForwardLookIsOrthonormalAndPerpendicular() {
		Basis b = MagicCircleRenderer.basis(0, 0, 1);
		assertOrthonormal(b);
		assertEquals(0F, b.right().dot(0, 0, 1), EPS);
		assertEquals(0F, b.up().dot(0, 0, 1), EPS);
		assertEquals(1F, b.normal().dot(0, 0, 1), EPS);
		// A vertical circle stands upright.
		assertEquals(1F, b.up().y, EPS);
	}

	@Test
	void basisLookingStraightUpOrDownHasNoNaN() {
		for (double[] n : new double[][] {{0, 1, 0}, {0, -1, 0}, {0, 0, 0}, {1e-9, 1, 0}, {Double.NaN, 1, 0}}) {
			Basis b = MagicCircleRenderer.basis(n[0], n[1], n[2]);
			assertFalse(hasNaN(b.right()) || hasNaN(b.up()) || hasNaN(b.normal()), "NaN for " + java.util.Arrays.toString(n));
			assertOrthonormal(b);
		}
	}

	@Test
	void basisIsOrthonormalForManyLooks() {
		for (int yaw = 0; yaw < 360; yaw += 15) {
			for (int pitch = -90; pitch <= 90; pitch += 15) {
				double y = Math.toRadians(yaw);
				double p = Math.toRadians(pitch);
				double nx = -Math.sin(y) * Math.cos(p);
				double ny = -Math.sin(p);
				double nz = Math.cos(y) * Math.cos(p);
				Basis b = MagicCircleRenderer.basis(nx, ny, nz);
				assertOrthonormal(b);
				assertEquals(0F, b.right().dot((float) nx, (float) ny, (float) nz), 1e-4F);
				assertEquals(0F, b.up().dot((float) nx, (float) ny, (float) nz), 1e-4F);
				// The quad's local axes land on the basis.
				Quaternionf q = MagicCircleRenderer.orientation(b, new Quaternionf());
				Vector3f x = q.transform(new Vector3f(1, 0, 0));
				Vector3f z = q.transform(new Vector3f(0, 0, 1));
				assertEquals(1F, x.dot(b.right()), 1e-4F);
				assertEquals(1F, z.dot(b.normal()), 1e-4F);
			}
		}
	}

	@Test
	void chargeRingsAppearWhenChargeMathShowsThem() {
		for (int k = 0; k < 5; k++) {
			int at = ChargeCircles.ringAppears(k);
			assertEquals(k + 1, ChargeMath.rings(at), "ring " + k + " appears at " + at);
			if (at > 0) assertEquals(k, ChargeMath.rings(at - 1), "ring " + k + " not before " + at);
		}
		assertEquals(ChargeMath.FULL_TICKS, ChargeCircles.ringAppears(4));
	}

	@Test
	void firstPersonClearRadiusIsAThirdOfTheFullCircle() {
		assertEquals(0.35F * MagicCircleRenderer.ringRadius(MagicCircleRenderer.MAX_RINGS), ChargeCircles.FIRST_PERSON_CLEAR, EPS);
		assertEquals(0.3F, ChargeCircles.FIRST_PERSON_ALPHA, EPS);
	}

	@Test
	void clipFindsTheStretchInsideTheClearMiddle() {
		// Straight through the middle: inside from 0.4 to 0.6 of the way.
		float[] t = MagicCircleRenderer.clip(-1, 0, 1, 0, 0.2F);
		assertEquals(0.4F, t[0], 1e-5F);
		assertEquals(0.6F, t[1], 1e-5F);
		// Passing by outside, or stopping short of it: never inside.
		assertEquals(null, MagicCircleRenderer.clip(-1, 0.5F, 1, 0.5F, 0.2F));
		assertEquals(null, MagicCircleRenderer.clip(-1, 0, -0.5F, 0, 0.2F));
		// Starting inside: clamped to 0.
		t = MagicCircleRenderer.clip(0, 0, 1, 0, 0.2F);
		assertEquals(0F, t[0], 1e-5F);
		assertEquals(0.2F, t[1], 1e-5F);
		// The hexagram's sides (triangles in ring 2) dip into the first-person clear middle (0.455): each loses a middle stretch, and
		// what is kept lies outside it.
		float r = MagicCircleRenderer.ringRadius(2) * 0.985F;
		float clear = ChargeCircles.FIRST_PERSON_CLEAR;
		for (int k = 0; k < 3; k++) {
			double a0 = Math.PI / 2 + 2 * Math.PI * k / 3;
			double a1 = Math.PI / 2 + 2 * Math.PI * (k + 1) / 3;
			float u0 = (float) (Math.cos(a0) * r);
			float v0 = (float) (Math.sin(a0) * r);
			float u1 = (float) (Math.cos(a1) * r);
			float v1 = (float) (Math.sin(a1) * r);
			t = MagicCircleRenderer.clip(u0, v0, u1, v1, clear);
			assertTrue(t != null && t[0] > 0 && t[1] < 1, "side " + k);
			for (float s : new float[] {0F, t[0] - 1e-3F, t[1] + 1e-3F, 1F}) {
				float u = u0 + (u1 - u0) * s;
				float v = v0 + (v1 - v0) * s;
				assertTrue(u * u + v * v >= clear * clear * 0.999F, "kept point inside the clear middle");
			}
		}
	}

	private static boolean hasNaN(Vector3f v) {
		return Float.isNaN(v.x) || Float.isNaN(v.y) || Float.isNaN(v.z);
	}

	private static void assertOrthonormal(Basis b) {
		assertEquals(1F, b.right().length(), 1e-4F);
		assertEquals(1F, b.up().length(), 1e-4F);
		assertEquals(1F, b.normal().length(), 1e-4F);
		assertEquals(0F, b.right().dot(b.up()), 1e-4F);
		assertEquals(0F, b.right().dot(b.normal()), 1e-4F);
		assertEquals(0F, b.up().dot(b.normal()), 1e-4F);
		// Right-handed: right × up = normal.
		assertEquals(1F, new Vector3f(b.right()).cross(b.up()).dot(b.normal()), 1e-4F);
	}
}
