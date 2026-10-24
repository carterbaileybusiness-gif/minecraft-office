package local.carter.office.client.magic.fx.earth;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.Test;

import local.carter.office.client.magic.fx.FxBudget;
import local.carter.office.client.magic.fx.FxKit;
import local.carter.office.magic.spell.earth.EarthSpikesGeometry;
import local.carter.office.magic.spell.earth.MountainFistGeometry;

/** Earth M3 visuals stay inside their event budgets, and Mountain Fist shakes everyone near its target. */
class EarthFxBudgetTest {
	@Test
	void mountainFistPhasesShareTheUltimateBudgetExactly() {
		assertEquals(FxBudget.ULTIMATE_PARTICLES,
				MountainFistFx.CAST + MountainFistFx.ERUPTION + MountainFistFx.RISE + MountainFistFx.SHATTER + MountainFistFx.SETTLE);
	}

	@Test
	void mountainFistShakesAtLeastPointEightWithin32() {
		for (double d = 0; d <= 32; d += 0.5) {
			float s = MountainFistFx.SHAKE * MountainFistFx.nearness(d);
			assertTrue(s >= 0.8F, "shake at " + d + ": " + s);
		}
		assertEquals(1F, MountainFistFx.nearness(10));
		assertEquals(0F, MountainFistFx.nearness(70));
	}

	@Test
	void mountainFistRocksFitTheGhostBudget() {
		List<MountainFistFx.Voxel> fist = MountainFistFx.fist();
		int rocks = fist.size() + MountainFistFx.PLATES + MountainFistFx.FANGS * EarthSpikesFx.TALL_PARTS + MountainFistFx.EJECTA;
		assertTrue(rocks <= FxBudget.GHOST_BLOCKS, "rocks " + rocks);
		assertTrue(fist.size() >= 50, "a big fist: " + fist.size());
		// No two rocks of the fist in the same place.
		Set<String> at = new HashSet<>();
		for (MountainFistFx.Voxel v : fist) assertTrue(at.add(v.x() + "," + v.y() + "," + v.z()), "duplicate " + v);
		// The rocks fly within the cancellable timer's life (and FxKit's longest timer).
		assertTrue(MountainFistFx.SHATTER_AT + MountainFistFx.SHATTER_LIFE <= MountainFistFx.TOTAL);
		assertTrue(MountainFistFx.TOTAL <= FxKit.MAX_SIMULATE_TICKS);
	}

	@Test
	void quakeFitsTheMediumBudget() {
		assertTrue(QuakeFx.STAMP + QuakeFx.FRONT + QuakeFx.CRUMB_CAP <= FxBudget.PARTICLES);
		assertTrue(QuakeFx.HEAVES <= FxBudget.GHOST_BLOCKS);
	}

	@Test
	void earthSpikesFitTheSmallBudget() {
		int spikes = EarthSpikesGeometry.length(1F);
		assertTrue(EarthSpikesFx.CRUMB_CAP + spikes * EarthSpikesFx.PER_SPIKE + 1 <= FxBudget.PARTICLES);
		assertTrue(spikes * EarthSpikesFx.ROCKS_PER_SPIKE <= FxBudget.GHOST_BLOCKS);
	}

	@Test
	void sharesGiveNoMoreThanTheirPart() {
		EarthFx.Share s = new EarthFx.Share(10);
		assertEquals(6, s.take(6));
		assertEquals(4, s.take(6));
		assertEquals(0, s.take(1));
		assertEquals(0, new EarthFx.Share(-3).take(1));
	}
}
