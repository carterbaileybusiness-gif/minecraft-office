package local.carter.office.magic.core;

import static org.junit.jupiter.api.Assertions.*;

import java.util.List;
import org.junit.jupiter.api.Test;

class ShapesTest {
	@Test void sphereOne() { assertEquals(7, Shapes.sphere(1).size()); }
	@Test void sphereWithinRadius() {
		for (Shapes.Cell c : Shapes.sphere(12)) {
			assertTrue(Math.sqrt(c.x() * c.x() + c.y() * c.y() + c.z() * c.z()) <= 12 + 1e-9);
		}
	}
	@Test void bowlBounds() {
		List<Shapes.Cell> b = Shapes.bowl(6, 0.6);
		assertFalse(b.isEmpty());
		for (Shapes.Cell c : b) {
			assertTrue(c.y() <= 0);
			assertTrue(c.y() >= -4);
			assertTrue(Math.sqrt(c.x() * c.x() + c.y() * c.y() + c.z() * c.z()) <= 6 + 1e-9);
		}
	}
	@Test void coneBounds() {
		int[] width = new int[8];
		for (Shapes.Cell c : Shapes.cone(3, 8)) {
			assertTrue(c.y() >= 0 && c.y() < 8);
			width[c.y()] = Math.max(width[c.y()], Math.max(Math.abs(c.x()), Math.abs(c.z())));
		}
		for (int y = 1; y < 8; y++) assertTrue(width[0] >= width[y]);
		assertEquals(3, width[0]);
	}
	@Test void line() {
		List<Shapes.Cell> l = Shapes.line(0, 1, 8);
		assertEquals(8, l.size());
		for (int i = 0; i < 8; i++) assertEquals(new Shapes.Cell(0, 0, i), l.get(i));
	}
	@Test void diagonalLineStaysOnGround() {
		for (Shapes.Cell c : Shapes.line(1, 1, 8)) assertEquals(0, c.y());
	}
	@Test void firstCellIsOrigin() {
		Shapes.Cell o = new Shapes.Cell(0, 0, 0);
		assertEquals(o, Shapes.sphere(5).get(0));
		assertEquals(o, Shapes.bowl(6, 0.6).get(0));
		assertEquals(o, Shapes.cone(3, 8).get(0));
		assertEquals(o, Shapes.line(0, 1, 8).get(0));
	}
	@Test void sortedByDistance() {
		double prev = -1;
		for (Shapes.Cell c : Shapes.sphere(6)) {
			double d = c.x() * c.x() + c.y() * c.y() + c.z() * c.z();
			assertTrue(d >= prev);
			prev = d;
		}
	}
}
