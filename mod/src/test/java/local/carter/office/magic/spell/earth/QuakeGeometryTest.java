package local.carter.office.magic.spell.earth;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.Test;

import local.carter.office.magic.spell.earth.QuakeGeometry.Column;

import net.minecraft.core.BlockPos;

class QuakeGeometryTest {
	@Test
	void ringRadiusAtTickTIsHalfT() {
		for (int t = 0; t <= 16; t++) assertEquals(t / 2.0, QuakeGeometry.ringAt(t), 1e-12, "tick " + t);
		assertEquals(6, QuakeGeometry.radius(0F), 1e-9);
		assertEquals(9, QuakeGeometry.radius(0.5F), 1e-9);
		assertEquals(12, QuakeGeometry.radius(1F), 1e-9); // a full charge's ring is twice a tap's
		assertEquals(12, QuakeGeometry.duration(6));
		assertEquals(16, QuakeGeometry.duration(8));
	}

	@Test
	void eachColumnIsReachedTheTickTheRingGetsThere() {
		for (Column c : QuakeGeometry.disc(8)) {
			int t = QuakeGeometry.tickOf(c.dist());
			assertTrue(t >= 1);
			assertTrue(QuakeGeometry.ringAt(t) >= c.dist() - 1e-9, c + " not reached at " + t);
			if (t > 1) assertTrue(QuakeGeometry.ringAt(t - 1) < c.dist(), c + " reached before " + t);
		}
	}

	@Test
	void discIsEveryColumnWithinTheRadiusNearestFirst() {
		List<Column> disc = QuakeGeometry.disc(6);
		int n = 0;
		for (int x = -6; x <= 6; x++)
			for (int z = -6; z <= 6; z++)
				if (x * x + z * z <= 36) n++;
		assertEquals(n, disc.size());
		assertEquals(new Column(0, 0, 0), disc.get(0));
		for (int i = 1; i < disc.size(); i++) assertTrue(disc.get(i).dist() >= disc.get(i - 1).dist());
	}

	@Test
	void arcOriginsKeepEveryEditWithinSix() {
		BlockPos centre = new BlockPos(100, 64, -40);
		for (Column c : QuakeGeometry.disc(QuakeGeometry.radius(1F))) {
			for (int dy = -QuakeGeometry.REACH; dy <= QuakeGeometry.REACH; dy++) {
				BlockPos p = centre.offset(c.dx(), dy, c.dz());
				BlockPos o = QuakeGeometry.originFor(centre, p);
				double d = Math.sqrt(p.distSqr(o));
				assertTrue(d <= QuakeGeometry.EDIT_RADIUS, p + " is " + d + " from its origin " + o);
				if (!o.equals(centre)) {
					// An arc origin lies on its arc, beyond 6 from the centre's column (9 out, give or take its rounding to a cell).
					assertEquals(o.getY(), centre.getY());
					double r = Math.sqrt(Math.pow(o.getX() - centre.getX(), 2) + Math.pow(o.getZ() - centre.getZ(), 2));
					assertTrue(r > 8 && r < 10, "arc origin " + o + " at " + r);
				}
			}
		}
	}

	@Test
	void arcsAreThirtySixDegrees() {
		assertEquals(0, QuakeGeometry.arc(1, 0.1));
		assertEquals(2, QuakeGeometry.arc(0, 1));
		assertEquals(5, QuakeGeometry.arc(-1, -0.01));
		assertEquals(9, QuakeGeometry.arc(1, -0.1));
		for (int k = 0; k < QuakeGeometry.ARCS; k++) {
			BlockPos o = QuakeGeometry.arcOrigin(BlockPos.ZERO, k);
			assertEquals(k, QuakeGeometry.arc(o.getX(), o.getZ()), "origin of arc " + k + " lies in it");
		}
	}

	@Test
	void aQuarterCracksAtMost120Seeded() {
		int n = QuakeGeometry.disc(8).size();
		boolean[] a = QuakeGeometry.cracks(n, 42L);
		assertArrayEquals(a, QuakeGeometry.cracks(n, 42L));
		int picked = 0;
		for (boolean b : a) if (b) picked++;
		assertEquals((int) Math.floor(n * 0.25), picked);
		assertTrue(picked <= QuakeGeometry.MAX_CRACKS);
		assertEquals(120, QuakeGeometry.crackCount(1000));
		boolean same = true;
		boolean[] b = QuakeGeometry.cracks(n, 43L);
		for (int i = 0; i < n; i++) same &= a[i] == b[i];
		assertTrue(!same, "another seed picks other columns");
	}

	@Test
	void surfaceIsTheTopSolidBlockWithinReach() {
		// Ground at y 3 everywhere, a 2-high step at x = 5.
		java.util.function.Predicate<BlockPos> solid = p -> p.getY() <= 3 || (p.getX() == 5 && p.getY() <= 5);
		assertEquals(new BlockPos(0, 3, 0), QuakeGeometry.surface(0, 0, 3, solid));
		assertEquals(new BlockPos(5, 5, 0), QuakeGeometry.surface(5, 0, 3, solid));
		// A wall taller than the reach has no surface in range; neither has a pit deeper than it.
		assertNull(QuakeGeometry.surface(0, 0, 3, p -> p.getY() <= 10));
		assertNull(QuakeGeometry.surface(0, 0, 3, p -> p.getY() <= -5));
	}
}
