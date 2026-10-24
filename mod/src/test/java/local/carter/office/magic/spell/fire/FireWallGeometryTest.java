package local.carter.office.magic.spell.fire;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.HashSet;
import java.util.List;

import org.junit.jupiter.api.Test;

import local.carter.office.magic.core.Shapes.Cell;

import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

class FireWallGeometryTest {
	private static final float[] YAWS = {0F, 90F, 180F, -90F, 45F, -135F, 30F, 200F, 12.5F, -71F};
	private static final Vec3[] BASES = {new Vec3(16.5, 4, 8.5), new Vec3(16.0, 4, 8.0), new Vec3(-3.27, 70, 12.91), new Vec3(100.99, 64, -40.01)};

	@Test
	void widthHeightAndDurationFollowCharge() {
		assertEquals(5, FireWallGeometry.width(0F));
		assertEquals(5, FireWallGeometry.width(0.49F));
		assertEquals(7, FireWallGeometry.width(0.5F));
		assertEquals(7, FireWallGeometry.width(1F));
		assertEquals(100, FireWallGeometry.duration(0F));
		assertEquals(130, FireWallGeometry.duration(0.5F));
		assertEquals(160, FireWallGeometry.duration(1F));
		FireWallGeometry g = FireWallGeometry.of(BASES[0], new Vec3(0, 0, 1), 1F);
		assertEquals(3, g.height());
		assertEquals(160, g.duration());
	}

	@Test
	void axisAlignedFootprintIsOneRowOfWidthCellsAcrossTheAim() {
		for (Vec3 base : BASES) {
			for (float charge : new float[] {0F, 1F}) {
				int width = (int) FireWallGeometry.width(charge);
				// Facing +Z: one row along x, z offset 0.
				List<Cell> z = FireWallGeometry.of(base, FireWallGeometry.forward(0F), charge).footprint();
				assertEquals(width, z.size(), "facing +Z from " + base);
				// One row (the cell row the wall's line runs through: 0, or −1 when the base sits on a cell edge).
				for (Cell c : z) assertEquals(z.get(0).z(), c.z(), "one row across +Z " + c);
				assertTrue(Math.abs(z.get(0).z()) <= 1, "row near the aim point");
				assertEquals(width, new HashSet<>(z.stream().map(Cell::x).toList()).size());
				// Facing −X: one row along z.
				List<Cell> x = FireWallGeometry.of(base, FireWallGeometry.forward(90F), charge).footprint();
				assertEquals(width, x.size(), "facing -X from " + base);
				for (Cell c : x) assertEquals(x.get(0).x(), c.x(), "one row across -X " + c);
				assertTrue(Math.abs(x.get(0).x()) <= 1, "row near the aim point");
				assertEquals(width, new HashSet<>(x.stream().map(Cell::z).toList()).size());
			}
		}
	}

	@Test
	void footprintIsCentredOnTheAimLinePerpendicularAndInsideRadiusSix() {
		for (Vec3 base : BASES) {
			for (float yaw : YAWS) {
				for (float charge : new float[] {0F, 0.5F, 1F}) {
					FireWallGeometry g = FireWallGeometry.of(base, FireWallGeometry.forward(yaw), charge);
					List<Cell> cells = g.footprint();
					String what = "yaw " + yaw + " charge " + charge + " base " + base;
					// Axis-aligned walls are exactly width cells (tested above); diagonal ones step corner to corner (≥ width / √2).
					assertTrue(cells.size() >= Math.floor(g.width() / Math.sqrt(2)), cells.size() + " cells, " + what);
					double sumAcross = 0;
					double minAcross = Double.MAX_VALUE;
					double maxAcross = -Double.MAX_VALUE;
					int bx = (int) Math.floor(base.x);
					int bz = (int) Math.floor(base.z);
					for (Cell c : cells) {
						assertEquals(0, c.y(), what);
						double rx = bx + c.x() + 0.5 - base.x;
						double rz = bz + c.z() + 0.5 - base.z;
						double across = rx * g.side().x + rz * g.side().z;
						double along = rx * g.forward().x + rz * g.forward().z;
						// Perpendicular: every cell sits on the wall's line across the aim, none ahead or behind it.
						assertTrue(Math.abs(along) <= 0.5 + 1e-9, "cell " + c + " is " + along + " off the wall line, " + what);
						assertTrue(Math.abs(across) <= g.width() / 2 + 1e-9, "cell " + c + " beyond the wall's end, " + what);
						// Inside the medium radius of the batch origin (the base cell).
						double d = Math.sqrt(c.x() * c.x() + c.y() * c.y() + c.z() * c.z());
						assertTrue(d <= 6, "cell " + c + " is " + d + " from the origin, " + what);
						sumAcross += across;
						minAcross = Math.min(minAcross, across);
						maxAcross = Math.max(maxAcross, across);
					}
					// Centred on the aim line: as much wall left as right.
					// Centred on the aim line: as much wall left as right (to within one cell step, √2 on a diagonal).
					assertTrue(Math.abs(sumAcross / cells.size()) <= 0.75, "mean offset " + sumAcross / cells.size() + ", " + what);
					assertTrue(Math.abs(maxAcross + minAcross) <= Math.sqrt(2) + 1e-9, "ends " + minAcross + ".." + maxAcross + ", " + what);
					assertTrue(maxAcross - minAcross >= g.width() - 2 * Math.sqrt(2), "spans " + (maxAcross - minAcross) + ", " + what);
				}
			}
		}
	}

	@Test
	void sideIsPerpendicularToForwardAndFlat() {
		for (float yaw : YAWS) {
			FireWallGeometry g = FireWallGeometry.of(Vec3.ZERO, FireWallGeometry.forward(yaw), 0F);
			assertEquals(0, g.forward().dot(g.side()), 1e-9);
			assertEquals(1, g.forward().length(), 1e-9);
			assertEquals(1, g.side().length(), 1e-9);
			assertEquals(0, g.forward().y, 0);
			assertEquals(0, g.side().y, 0);
		}
		// A look straight down still gives a wall facing ahead (+Z by default), never NaN.
		FireWallGeometry down = FireWallGeometry.of(Vec3.ZERO, new Vec3(0, -1, 0), 0F);
		assertEquals(new Vec3(0, 0, 1), down.forward());
		assertEquals(new Vec3(16.5, 4, 8.5), FireWallGeometry.aimPoint(new Vec3(16.5, 4, 4.5), new Vec3(0, 0.6, 0.8)));
	}

	@Test
	void touchesBoxesInTheSlabOnly() {
		FireWallGeometry g = FireWallGeometry.of(new Vec3(16.5, 4, 8.5), FireWallGeometry.forward(0F), 0F);
		AABB zombie = new AABB(16.2, 4, 8.2, 16.8, 5.95, 8.8);
		assertTrue(g.touches(zombie), "standing in it");
		assertTrue(g.touches(zombie.move(2.3, 0, 0)), "at its end");
		assertFalse(g.touches(zombie.move(3.5, 0, 0)), "past its end");
		assertFalse(g.touches(zombie.move(0, 0, 1.5)), "behind it");
		assertFalse(g.touches(zombie.move(0, 0, -1.5)), "in front of it");
		assertFalse(g.touches(zombie.move(0, 3.1, 0)), "above it");
		assertFalse(g.touches(zombie.move(0, -2.5, 0)), "below it");
		// An arrow 2 in front flying 3 a tick goes through it this tick.
		AABB arrow = new AABB(16.4, 5.4, 6.4, 16.6, 5.6, 6.6);
		assertFalse(g.touches(arrow));
		assertTrue(g.crosses(arrow, new Vec3(0, 0, 3)));
		assertFalse(g.crosses(arrow, new Vec3(0, 0, -3)));
		// A diagonal wall: its middle and ends touch, the corners of its bounds do not.
		FireWallGeometry d = FireWallGeometry.of(new Vec3(0, 0, 0), FireWallGeometry.forward(45F), 1F);
		AABB small = new AABB(-0.1, 0, -0.1, 0.1, 1, 0.1);
		assertTrue(d.touches(small));
		Vec3 end = d.point(3.3, 0);
		assertTrue(d.touches(small.move(end.x, 0, end.z)));
		assertFalse(d.touches(small.move(2.4, 0, -2.4)), "off the diagonal");
		assertTrue(d.bounds().contains(end.add(0, 1, 0)));
	}
}
