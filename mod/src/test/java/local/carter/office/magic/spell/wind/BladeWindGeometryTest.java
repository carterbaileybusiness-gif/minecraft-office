package local.carter.office.magic.spell.wind;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.Test;

import local.carter.office.magic.spell.ProjectileSpec;
import local.carter.office.magic.spell.Projectiles;

import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.Vec3;

class BladeWindGeometryTest {
	private static final double EPS = 1.0E-9;
	private static final Vec3 EYE = new Vec3(10.5, 65.62, 4.5);
	private static final Vec3 SOUTH = new Vec3(0, 0, 1);

	@Test
	void fullChargeFanIsTwiceAsWideWithMoreBlades() {
		assertArrayEquals(new double[] {-15, 0, 15}, BladeWindGeometry.angles(0F), EPS);
		assertArrayEquals(new double[] {-16.8, 0, 16.8}, BladeWindGeometry.angles(0.12F), 1.0E-5);
		assertArrayEquals(new double[] {-22.5, -11.25, 0, 11.25, 22.5}, BladeWindGeometry.angles(0.5F), 1.0E-5);
		assertArrayEquals(new double[] {-30, -20, -10, 0, 10, 20, 30}, BladeWindGeometry.angles(1F), 1.0E-5);
		assertEquals(3, BladeWindGeometry.specs(EYE, SOUTH, 0F).size());
		assertEquals(5, BladeWindGeometry.specs(EYE, SOUTH, 0.25F).size());
		assertEquals(7, BladeWindGeometry.specs(EYE, SOUTH, 0.75F).size());
		assertEquals(7, BladeWindGeometry.specs(EYE, SOUTH, 1F).size());
		assertEquals(2 * BladeWindGeometry.spread(0F), BladeWindGeometry.spread(1F), EPS, "a full charge's fan is twice as wide");
	}

	@Test
	void bladesAreThePlannedProjectiles() {
		for (float charge : new float[] {0F, 1F}) {
			List<ProjectileSpec> specs = BladeWindGeometry.specs(EYE, SOUTH, charge);
			double[] angles = BladeWindGeometry.angles(charge);
			for (int i = 0; i < specs.size(); i++) {
				ProjectileSpec s = specs.get(i);
				assertEquals(2.0, s.velocity().length(), EPS, "speed");
				assertEquals(0.6, s.radius(), EPS, "radius");
				assertEquals(14, s.maxTicks(), "max ticks");
				assertTrue(s.pierce(), "pierce");
				assertEquals(0, s.gravity(), EPS, "gravity");
				// Level fan about the look: the turn from +Z is the planned angle, and every blade stays at eye height.
				Vec3 d = s.velocity().normalize();
				// Facing +Z the caster's right is -X, so a positive turn swings the blade toward -X.
				assertEquals(angles[i], Math.toDegrees(Math.atan2(-d.x, d.z)), 1.0E-6, "turn of blade " + i);
				assertEquals(0, d.y, EPS, "level");
				assertEquals(0.5, s.origin().subtract(EYE).length(), EPS, "starts half a block out");
			}
		}
	}

	@Test
	void middleBladeFollowsTheLookEvenLookingUpOrDown() {
		for (Vec3 look : new Vec3[] {new Vec3(0, 1, 0), new Vec3(0, -1, 0), new Vec3(1, 1, 1).normalize(), new Vec3(-0.3, -0.8, 0.52).normalize()}) {
			List<ProjectileSpec> specs = BladeWindGeometry.specs(EYE, look, 0F);
			assertEquals(1, specs.get(1).velocity().normalize().dot(look), 1.0E-9, "middle blade along " + look);
			BladeWindGeometry.Frame f = BladeWindGeometry.frame(look);
			assertEquals(0, f.side().y, EPS, "side is level");
			assertEquals(0, f.side().dot(f.forward()), EPS);
			assertEquals(0, f.up().dot(f.forward()), EPS);
			assertEquals(0, f.up().dot(f.side()), EPS);
			assertTrue(f.up().y >= 0, "up leans up");
			for (ProjectileSpec s : specs) assertTrue(Double.isFinite(s.velocity().x + s.velocity().y + s.velocity().z));
		}
	}

	@Test
	void pathIsProjectilesStep() {
		ProjectileSpec s = BladeWindGeometry.specs(EYE, SOUTH, 0F).get(0);
		for (int t = 0; t <= 14; t++) {
			Vec3 expected = s.origin().add(s.velocity().scale(t));
			assertEquals(0, BladeWindGeometry.at(s, t).distanceTo(expected), 1.0E-9, "t " + t);
			assertEquals(0, BladeWindGeometry.at(s, t).distanceTo(Projectiles.step(s, t)), 1.0E-9, "step " + t);
		}
		assertEquals(0, BladeWindGeometry.at(s, 2.25).distanceTo(s.origin().add(s.velocity().scale(2.25))), 1.0E-9, "fraction");
	}

	@Test
	void stretchesAreSixLongWithTheirMiddleAsOrigin() {
		ProjectileSpec s = BladeWindGeometry.specs(EYE, SOUTH, 0F).get(1);
		assertEquals(0, BladeWindGeometry.segment(-1));
		assertEquals(0, BladeWindGeometry.segment(5.99));
		assertEquals(1, BladeWindGeometry.segment(6));
		assertEquals(4, BladeWindGeometry.segment(27.9));
		assertEquals(0, BladeWindGeometry.segmentOrigin(s, 2).distanceTo(s.origin().add(0, 0, 15)), EPS);
	}

	@Test
	void swathIsThreeWideFromBladeToFeetAndNeverRepeatsAcrossTicks() {
		ProjectileSpec s = BladeWindGeometry.specs(EYE, SOUTH, 0F).get(1);
		BladeWindGeometry.Frame f = BladeWindGeometry.frame(s.velocity());
		Set<BlockPos> all = new HashSet<>();
		int total = 0;
		for (int t = 0; t < 14; t++) {
			List<BlockPos> cells = BladeWindGeometry.cuts(BladeWindGeometry.at(s, t), BladeWindGeometry.at(s, t + 1), f);
			total += cells.size();
			all.addAll(cells);
			for (int i = 1; i < cells.size(); i++) assertTrue(cells.get(i).getZ() >= cells.get(i - 1).getZ(), "ordered along the path");
			for (BlockPos p : cells) {
				assertTrue(p.getY() == 65 || p.getY() == 64, "the blade's layer and the feet layer: " + p);
				assertTrue(Math.abs(p.getX() - 10) <= 1, "three wide: " + p);
				Vec3 c = Vec3.atCenterOf(p);
				double k = BladeWindGeometry.along(s, c);
				assertTrue(c.distanceTo(BladeWindGeometry.segmentOrigin(s, BladeWindGeometry.segment(k))) <= 6, "inside its batch radius");
			}
		}
		assertEquals(all.size(), total, "no cell twice");
		assertTrue(all.contains(new BlockPos(9, 65, 14)) && all.contains(new BlockPos(10, 65, 14)) && all.contains(new BlockPos(11, 65, 14)));
		// Eye at 65.62: the feet stand at 64, so ground plants in 64 are cut; nothing above the blade's layer or under the feet.
		assertTrue(all.contains(new BlockPos(9, 64, 14)) && all.contains(new BlockPos(10, 64, 14)) && all.contains(new BlockPos(11, 64, 14)));
		assertFalse(all.contains(new BlockPos(10, 66, 14)) || all.contains(new BlockPos(10, 63, 14)) || all.contains(new BlockPos(8, 65, 14)));
	}
}
