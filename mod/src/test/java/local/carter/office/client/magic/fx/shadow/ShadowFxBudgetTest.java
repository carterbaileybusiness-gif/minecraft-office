package local.carter.office.client.magic.fx.shadow;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import local.carter.office.client.magic.fx.FxBudget;
import local.carter.office.magic.spell.shadow.BindGeometry;

import net.minecraft.world.phys.Vec3;

class ShadowFxBudgetTest {
	@Test
	void blackHolePhasesFitTheUltimateBudget() {
		int core = BlackHoleFx.Singularity.cost() + BlackHoleFx.Shock.cost() + ShadowPop.cost();
		assertTrue(BlackHoleFx.CAST + BlackHoleFx.LIFE_SHARE + BlackHoleFx.BURST <= FxBudget.ULTIMATE_PARTICLES, "the phases fit");
		assertTrue(core < BlackHoleFx.CAST, "the drawn pieces (charged per 50 a frame) fit the cast's share: " + core);
	}

	@Test
	void blackHoleShakesAtLeastPointEightWithin32() {
		for (double d = 0; d <= 32; d += 0.5) {
			assertTrue(BlackHoleFx.SHAKE * BlackHoleFx.nearness(d) >= 0.8F, "shake at " + d + ": " + BlackHoleFx.nearness(d));
		}
		assertEquals(1F, BlackHoleFx.nearness(10));
		assertEquals(0F, BlackHoleFx.nearness(80));
		for (double d = 0; d < 80; d += 0.5) assertTrue(BlackHoleFx.nearness(d + 0.5) <= BlackHoleFx.nearness(d), "fades with distance");
	}

	@Test
	void hazeLaysItsPieces() {
		assertEquals(3, Umbra.HAZE_PIECES);
		assertEquals(Umbra.HAZE_PIECES + 3 + 24 + 24, Umbra.orbPieces(24));
	}

	@Test
	void bindCoilsUse64PiecesAndStillReadAsStrands() {
		assertEquals(64, ShadowBindFx.Tendrils.PIECES, "64 pieces per coil (was 96: less to draw every frame)");
		assertEquals(Umbra.budgetCost(BindGeometry.TENDRILS * 64 * 2 + 8), ShadowBindFx.Tendrils.cost());
		// Round a zombie (the coils' width and height as the fx sets them), at every tick of a full-charge root: neighbouring pieces
		// are never further apart than the thinner one is wide, so the coil is one strand, not beads.
		double width = 0.6 / 2 + 0.15, height = 1.95;
		int life = BindGeometry.life(1F);
		int n = ShadowBindFx.Tendrils.PIECES;
		for (int t = 0; t <= life; t++) {
			for (int i = 0; i < BindGeometry.TENDRILS; i++) {
				for (int j = 0; j + 1 < n; j++) {
					double s0 = (j + 0.5) / n, s1 = (j + 1.5) / n;
					double gap = BindGeometry.tendril(Vec3.ZERO, width, height, i, s0, t, life)
							.distanceTo(BindGeometry.tendril(Vec3.ZERO, width, height, i, s1, t, life));
					float thinner = Math.min(ShadowBindFx.Tendrils.pieceWidth(s0), ShadowBindFx.Tendrils.pieceWidth(s1));
					assertTrue(gap <= thinner, "tick " + t + ", coil " + i + ", piece " + j + ": gap " + gap + " > width " + thinner);
				}
			}
		}
	}
}
