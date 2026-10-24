package local.carter.office.magic.spell.earth;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.Test;

import local.carter.office.magic.core.Shapes;
import local.carter.office.magic.core.Shapes.Cell;

import net.minecraft.world.phys.Vec3;

class MountainFistGeometryTest {
	@Test
	void sizesGrowWithLevelUpToTheirCaps() {
		assertEquals(3, MountainFistGeometry.baseRadius(7));
		assertEquals(8, MountainFistGeometry.height(7));
		assertEquals(4, MountainFistGeometry.baseRadius(8));
		assertEquals(10, MountainFistGeometry.height(8));
		assertEquals(6, MountainFistGeometry.baseRadius(10));
		assertEquals(12, MountainFistGeometry.height(9));
		assertEquals(12, MountainFistGeometry.height(10));
		assertEquals(6, MountainFistGeometry.baseRadius(20));
		assertEquals(12, MountainFistGeometry.debris(7));
		assertEquals(18, MountainFistGeometry.debris(10));
	}

	@Test
	void spireIsTheConeBottomUp() {
		for (int level = 7; level <= 10; level++) {
			int r = MountainFistGeometry.baseRadius(level);
			int h = MountainFistGeometry.height(level);
			List<Cell> spire = MountainFistGeometry.spire(r, h);
			assertEquals(Shapes.cone(r, h).size(), spire.size());
			for (int i = 1; i < spire.size(); i++) assertTrue(spire.get(i).y() >= spire.get(i - 1).y(), "bottom-up");
			// With the skirts under the base, still within the ultimate's 12 of the target cell and the 900-block cap.
			int skirt = 0;
			for (Cell c : spire) {
				assertTrue(Math.sqrt(c.x() * c.x() + c.y() * c.y() + c.z() * c.z()) <= 12, "cell " + c);
				if (c.y() == 0) {
					skirt += MountainFistGeometry.SKIRT;
					double d = Math.sqrt(c.x() * c.x() + MountainFistGeometry.SKIRT * MountainFistGeometry.SKIRT + c.z() * c.z());
					assertTrue(d <= 12, "skirt under " + c);
				}
				assertTrue(c.y() < h);
			}
			assertTrue(spire.size() + skirt <= 1500, "inside the ultimate budget");
		}
		assertEquals(96, MountainFistGeometry.spire(3, 8).size(), "level 7: 96 cells");
	}

	@Test
	void targetsAreRefusedNotSnapped() {
		assertTrue(!MountainFistGeometry.refused(true, 0.5, 12), "ground right under the aim, in reach");
		assertTrue(MountainFistGeometry.refused(false, 0.5, 12), "no solid ground: the void, an End column");
		assertTrue(MountainFistGeometry.refused(true, 8.5, 12), "a cliff: more than 8 below the aim");
		assertTrue(!MountainFistGeometry.refused(true, 8, 12), "8 is still fine");
		assertTrue(MountainFistGeometry.refused(true, -1.5, 12), "ground above the aim: the aim was inside a block");
		assertTrue(MountainFistGeometry.refused(true, 1, MountainFistGeometry.RANGE + 2.5), "out of reach");
		assertTrue(!MountainFistGeometry.refused(true, 1, MountainFistGeometry.RANGE + 2), "just in reach");
	}

	@Test
	void layersArePlacedBottomUpOverTwentyTicks() {
		for (int h : new int[] {8, 10, 12}) {
			int last = 0;
			for (int y = 0; y < h; y++) {
				int t = MountainFistGeometry.layerTick(y, h);
				assertTrue(t >= last && t >= 1, "layer " + y + " at " + t);
				assertTrue(t > MountainFistGeometry.skirtTick());
				last = t;
			}
			assertEquals(MountainFistGeometry.RISE, MountainFistGeometry.layerTick(h - 1, h));
			assertEquals(0, MountainFistGeometry.topAt(0, h), 1e-9);
			assertEquals(h, MountainFistGeometry.topAt(MountainFistGeometry.RISE, h), 1e-9);
			assertEquals(h, MountainFistGeometry.topAt(99, h), 1e-9);
		}
	}

	@Test
	void materialIsSixtyTwentyFiveFifteen() {
		int[] n = new int[3];
		int total = 0;
		for (int x = -20; x <= 20; x++)
			for (int y = 0; y < 20; y++)
				for (int z = -20; z <= 20; z++) {
					n[MountainFistGeometry.material(1234L, x, y, z)]++;
					total++;
				}
		assertEquals(0.60, n[0] / (double) total, 0.02);
		assertEquals(0.25, n[1] / (double) total, 0.02);
		assertEquals(0.15, n[2] / (double) total, 0.02);
		assertEquals(MountainFistGeometry.material(5L, 1, 2, 3), MountainFistGeometry.material(5L, 1, 2, 3));
	}

	@Test
	void debrisFliesFromOutsideTheFootprintOutward() {
		for (int level = 7; level <= 10; level++) {
			int r = MountainFistGeometry.baseRadius(level);
			int n = MountainFistGeometry.debris(level);
			assertTrue(n <= 48);
			for (int i = 0; i < n; i++) {
				int[] o = MountainFistGeometry.debrisCell(i, n, r, 99L);
				double d = Math.sqrt(o[0] * o[0] + o[1] * o[1]);
				assertTrue(d > r && d <= 12 - 1, "debris " + i + " at " + d);
				Vec3 v = MountainFistGeometry.debrisVelocity(i, o[0], o[1], 99L);
				assertTrue(v.y > 0 && v.x * o[0] + v.z * o[1] > 0, "up and out: " + v);
			}
		}
	}
}
