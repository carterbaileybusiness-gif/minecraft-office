package local.carter.office.client.magic.fx.lightning;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import local.carter.office.client.magic.fx.FxBudget;
import local.carter.office.client.magic.fx.Shake;
import local.carter.office.magic.spell.lightning.ChidoriGeometry;

import net.minecraft.world.phys.Vec3;

class ChidoriBudgetTest {
	@Test
	void phasesAreTheUltimatesWholeBudget() {
		assertEquals(FxBudget.ULTIMATE_PARTICLES, ChidoriLook.total());
		assertTrue(ChidoriLook.chargeShare(ChidoriGeometry.TICKS) * ChidoriGeometry.TICKS <= ChidoriLook.CHARGE);
		int steps = (ChidoriLook.AFTER_TICKS + ChidoriLook.AFTER_EVERY - 1) / ChidoriLook.AFTER_EVERY;
		assertTrue(ChidoriLook.afterShare() * steps <= ChidoriLook.AFTERMATH);
		assertTrue(ChidoriLook.afterShare() > 10, "each aftermath step can draw something");
	}

	@Test
	void shakesAtLeastPointEightWithinThirtyTwo() {
		for (double d = 0; d <= 32; d += 0.5) assertTrue(ChidoriLook.shake(d) >= 0.8F, "shake at " + d + " is " + ChidoriLook.shake(d));
		assertTrue(ChidoriLook.shake(0) <= Shake.MAX, "within the camera's limit");
		assertEquals(0F, ChidoriLook.shake(80), 1.0E-6F);
		assertEquals(3, ChidoriLook.HIT_STOP);
	}

	@Test
	void theImpactsPartsFitItsAllowance() {
		// The impact's own arcs, priced by their exact quads, plus every mote and particle it asks for, plus the second wave.
		Vec3 at = new Vec3(0, 4, 0);
		long seed = 12345;
		int n = 3; // flash glows
		for (int i = 0; i < ChidoriLook.PILLARS; i++) {
			Vec3 top = ChidoriLook.pillarTop(at, i, seed);
			n += ChidoriLook.PILLAR.cost(top, at, seed + 113L * i) + ChidoriLook.PILLAR_STRAND.cost(top, at, LightningLook.strandSeed(seed + 113L * i));
		}
		Vec3 ground = at.add(0, 0.1, 0);
		for (int i = 0; i < ChidoriLook.CRACKS; i++) n += ChidoriLook.CRACK.cost(ground, ChidoriLook.crack(ground, i, ChidoriLook.CRACKS, 12 + 2.5, seed), seed + 131L * i);
		n += 44 + 40 + 60 + 36 + 70 + 50 + 18 + 6 + 20; // ring, ring sparks, motes, flickers, sparks, dust, smoke, cosy smoke, crawl (generous)
		for (int i = 0; i < ChidoriLook.CRACKS_BLUE; i++) {
			n += ChidoriLook.CRACK_BLUE.cost(ground, ChidoriLook.crack(ground, i, ChidoriLook.CRACKS_BLUE, 12 + 1.5, seed + 5), seed + 173L * i);
		}
		n += 20;
		assertTrue(n <= ChidoriLook.IMPACT, "the impact at the largest bowl wants " + n + " of " + ChidoriLook.IMPACT);
	}

	@Test
	void chirpArcsStayRoundTheHand() {
		Vec3 hand = new Vec3(1, 2, 3);
		for (int step = 0; step < 10; step++) {
			Vec3[] arcs = ChidoriLook.chirp(hand, 77, step, ChidoriLook.CHIRPS_OPENING);
			assertEquals(ChidoriLook.CHIRPS_OPENING * 2, arcs.length);
			for (Vec3 p : arcs) assertTrue(p.distanceTo(hand) <= 0.3 + ChidoriLook.CHIRP_REACH, "chirp " + p);
		}
	}
}
