package local.carter.office.magic.spell.lightning;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Random;
import java.util.Set;

import org.junit.jupiter.api.Test;

import net.minecraft.world.phys.Vec3;

class ChainHopsTest {
	private static List<Vec3> line(double... zs) {
		List<Vec3> out = new ArrayList<>();
		for (double z : zs) out.add(new Vec3(0, 0, z));
		return out;
	}

	@Test
	void hopsAndDamageFollowTheBrief() {
		assertEquals(4, ChainHops.hops(0F));
		assertEquals(4, ChainHops.hops(0.24F));
		assertEquals(5, ChainHops.hops(0.25F));
		assertEquals(5, ChainHops.hops(0.5F));
		assertEquals(6, ChainHops.hops(1F));
		assertEquals(6, ChainHops.hops(3F), "charge is clamped");
		assertEquals(6F, ChainHops.damage(0, 1F), 1e-6);
		assertEquals(4.8F, ChainHops.damage(1, 1F), 1e-5);
		assertEquals(3.84F, ChainHops.damage(2, 1F), 1e-5);
		assertEquals(12F * 0.8F * 0.8F, ChainHops.damage(2, 2F), 1e-5);
		for (int hop = 0; hop < 7; hop++) assertTrue(ChainHops.damage(hop + 1, 1.3F) < ChainHops.damage(hop, 1.3F), "damage falls off");
	}

	@Test
	void picksTheNearestUnhitPointEachHop() {
		// From 0: nearest is 2 (z 3), then from z 3 nearest unhit is 3 (z 5), then 1 (z 8), then 4 (z 12).
		List<Vec3> pts = line(0, 8, 3, 5, 12);
		assertArrayEquals(new int[] {0, 2, 3, 1, 4}, ChainHops.select(pts, 0, 10, 6));
		// The nearest from the LAST hit, not from the start: a point 1 from the start but 7 from the chain's end is left.
		List<Vec3> zig = List.of(new Vec3(0, 0, 0), new Vec3(0, 0, 4), new Vec3(0, 0, 8), new Vec3(-1, 0, 0.5));
		assertArrayEquals(new int[] {0, 3, 1, 2}, ChainHops.select(zig, 0, 10, 6), "3 is nearest from 0 and gets hit first");
		List<Vec3> zag = List.of(new Vec3(0, 0, 0), new Vec3(0, 0, 4), new Vec3(0, 0, 8), new Vec3(0, 0, 14.5));
		assertArrayEquals(new int[] {0, 1, 2}, ChainHops.select(zag, 0, 10, 6), "14.5 is 6.5 from 8: out of range");
	}

	@Test
	void stopsWhenNoPointIsInRange() {
		assertArrayEquals(new int[] {0}, ChainHops.select(line(0, 6.01), 0, 4, 6));
		assertArrayEquals(new int[] {0, 1}, ChainHops.select(line(0, 6.0), 0, 4, 6), "exactly 6 is in range");
		assertArrayEquals(new int[] {0, 1, 2}, ChainHops.select(line(0, 3, 6, 13, 16), 0, 4, 6), "a 7-gap ends the chain");
		assertArrayEquals(new int[] {1}, ChainHops.select(line(0, 20), 1, 4, 6));
	}

	@Test
	void stopsAtTheHopLimit() {
		List<Vec3> pts = line(0, 3, 6, 9, 12, 15, 18, 21, 24);
		assertArrayEquals(new int[] {0, 1, 2, 3, 4}, ChainHops.select(pts, 0, ChainHops.hops(0F), 6), "a tap: first + 4");
		assertArrayEquals(new int[] {0, 1, 2, 3, 4, 5, 6}, ChainHops.select(pts, 0, ChainHops.hops(1F), 6), "full charge: first + 6");
		assertArrayEquals(new int[] {4}, ChainHops.select(pts, 4, 0, 6), "no hops: the first target alone");
		assertArrayEquals(new int[] {4, 3}, ChainHops.select(pts, 4, 1, 6), "one arc (Spark): the nearest, ties to the lower index");
	}

	@Test
	void neverRevisits() {
		// Two points close together and one far: it must not bounce back and forth between the close pair.
		List<Vec3> pts = line(0, 1, 30);
		assertArrayEquals(new int[] {0, 1}, ChainHops.select(pts, 0, 6, 6));
		Random r = new Random(7);
		for (int trial = 0; trial < 200; trial++) {
			List<Vec3> cloud = new ArrayList<>();
			for (int i = 0; i < 12; i++) cloud.add(new Vec3(r.nextDouble() * 20, r.nextDouble() * 4, r.nextDouble() * 20));
			int[] order = ChainHops.select(cloud, r.nextInt(12), 8, 6);
			Set<Integer> seen = new HashSet<>();
			for (int i = 0; i < order.length; i++) {
				assertTrue(seen.add(order[i]), "revisited " + order[i]);
				if (i > 0) assertTrue(cloud.get(order[i - 1]).distanceTo(cloud.get(order[i])) <= 6 + 1e-9, "hop over 6");
			}
			assertTrue(order.length <= 9);
		}
	}

	@Test
	void linkCanForbidAHop() {
		// Point 1 is nearest but "behind a wall": the chain goes to 2 instead, then may still reach 1 from 2 if that link is open.
		List<Vec3> pts = line(0, 2, 4);
		assertArrayEquals(new int[] {0, 2, 1}, ChainHops.select(pts, 0, 4, 6, (a, b) -> !(a == 0 && b == 1)));
		assertArrayEquals(new int[] {0}, ChainHops.select(pts, 0, 4, 6, (a, b) -> false));
	}

	@Test
	void badStartIsEmpty() {
		assertArrayEquals(new int[0], ChainHops.select(line(0, 1), 2, 4, 6));
		assertArrayEquals(new int[0], ChainHops.select(List.of(), 0, 4, 6));
	}
}
