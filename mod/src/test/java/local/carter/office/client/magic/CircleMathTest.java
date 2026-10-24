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

class CircleMathTest {
	private static final float EPS = 1.0E-5F;

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
