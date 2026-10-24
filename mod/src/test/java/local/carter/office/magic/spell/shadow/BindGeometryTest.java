package local.carter.office.magic.spell.shadow;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import net.minecraft.world.phys.Vec3;

class BindGeometryTest {
	private static final Vec3 FEET = new Vec3(10.5, 64, -3.5);
	private static final double EPS = 1.0E-9;

	@Test
	void lifeIsSixtyPlusFortyTimesCharge() {
		assertEquals(60, BindGeometry.life(0));
		assertEquals(80, BindGeometry.life(0.5F));
		assertEquals(100, BindGeometry.life(1));
		assertEquals(60, BindGeometry.life(-2), "clamped");
		assertEquals(100, BindGeometry.life(3), "clamped");
	}

	@Test
	void holdKeepsItInPlaceButLetsItFall() {
		Vec3 anchor = FEET;
		assertEquals(new Vec3(10.5, 64, -3.5), BindGeometry.hold(anchor, FEET.add(0.4, 0, -0.3)), "stepped aside: put back");
		assertEquals(new Vec3(10.5, 64, -3.5), BindGeometry.hold(anchor, FEET.add(0, 0.42, 0)), "jumped: put back down");
		assertEquals(new Vec3(10.5, 62.5, -3.5), BindGeometry.hold(anchor, FEET.add(0.2, -1.5, 0)), "falls, but stays over its spot");
		assertEquals(new Vec3(0, 0, 0), BindGeometry.rooted(new Vec3(0.3, 0.42, -0.2)), "no step, no jump");
		assertEquals(new Vec3(0, -0.5, 0), BindGeometry.rooted(new Vec3(0.3, -0.5, -0.2)), "a fall keeps falling");
	}

	@Test
	void aTeleportBreaksTheRoot() {
		assertFalse(BindGeometry.breaks(FEET, FEET.add(0.5, 0, 0)), "a shove");
		assertFalse(BindGeometry.breaks(FEET, FEET.add(0, -3.9, 0)), "a fall");
		assertTrue(BindGeometry.breaks(FEET, FEET.add(4.01, 0, 0)), "a blink");
		assertTrue(BindGeometry.breaks(FEET, FEET.add(30, 5, -12)), "a teleport");
	}

	@Test
	void tendrilsRiseHoldAndSink() {
		int life = 80;
		assertEquals(0, BindGeometry.reach(0, life), EPS, "not out at the cast");
		assertEquals(1, BindGeometry.reach(BindGeometry.GROW, life), EPS, "fully out after GROW ticks");
		assertEquals(1, BindGeometry.reach(40, life), EPS, "out while it holds");
		assertEquals(0, BindGeometry.reach(life, life), EPS, "gone at the end");
		assertTrue(BindGeometry.reach(life - 2, life) < 1, "sinking at the end");
		for (int i = 0; i < BindGeometry.TENDRILS; i++) {
			Vec3 root = BindGeometry.tendril(FEET, 0.6, 1.95, i, 0, 30, life);
			Vec3 tip = BindGeometry.tendril(FEET, 0.6, 1.95, i, 1, 30, life);
			assertEquals(FEET.y, root.y, EPS, "rooted in the ground");
			assertTrue(tip.y > FEET.y + 1.5, "the tip climbs high on the foe: " + tip.y);
			double rootOut = Math.hypot(root.x - FEET.x, root.z - FEET.z);
			double tipOut = Math.hypot(tip.x - FEET.x, tip.z - FEET.z);
			assertTrue(rootOut > tipOut, "it closes in as it climbs");
			assertTrue(rootOut < 0.6 * 1.25 + 0.07 && tipOut > 0.6 * 0.6 - 0.07, "it hugs the foe: " + rootOut + ", " + tipOut);
			// Early on only the part that is out shows: the tip sits where the reach stops.
			Vec3 early = BindGeometry.tendril(FEET, 0.6, 1.95, i, 1, 2, life);
			assertTrue(early.y < FEET.y + 1.95 * 0.85 * BindGeometry.reach(2, life) + EPS, "only grown so far");
		}
	}
}
