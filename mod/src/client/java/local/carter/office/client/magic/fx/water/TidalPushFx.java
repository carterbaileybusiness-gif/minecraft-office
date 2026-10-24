package local.carter.office.client.magic.fx.water;

import java.util.HashSet;
import java.util.Set;

import local.carter.office.client.magic.fx.FxKit;
import local.carter.office.client.magic.fx.GhostBlocks;
import local.carter.office.client.magic.fx.GlowMote;
import local.carter.office.client.magic.fx.SpellFx;
import local.carter.office.magic.net.FxEvent;
import local.carter.office.magic.spell.water.TidalWaveGeometry;

import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.Mth;
import net.minecraft.util.RandomSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;

/**
 * Tidal Push: a circle opens under the caster and another before them, spray is flung across the whole cone, and a wave of
 * water rolls out from the caster's feet along {@link TidalWaveGeometry} — translucent water blocks rising in each air cell the
 * front passes (each living {@link TidalWaveGeometry#CELL_LIFE} ticks), a crest that leaps and throws foam, climbing one-block
 * steps, pouring down drops and breaking on walls — until it crashes down eight blocks out. Nothing is placed in the world.
 * In the caster's own first-person view the wave shows from {@link #OWN_CLEAR} blocks out, so it never fills their screen.
 */
public final class TidalPushFx implements SpellFx {
	/** The wave's body and its leaping crest (client-only ghost blocks: never placed). */
	static final BlockState BODY = Blocks.STAINED_GLASS.lightBlue().defaultBlockState();
	static final BlockState CREST = Blocks.STAINED_GLASS.lightBlue().defaultBlockState();
	/** The crest falls back sooner than the body, so the wave is tall at its front and low behind. */
	static final int CREST_LIFE = 4;
	/** How far from the caster their own first-person view starts showing the wave. */
	static final double OWN_CLEAR = 2.6;

	@Override
	public void play(FxEvent e, ClientLevel level, FxKit kit) {
		Vec3 look = WaterFx.unit(e.dir());
		Vec3 eye = e.origin();
		Vec3 start = e.target();
		float c = Mth.clamp(e.charge(), 0F, 1F);
		float[] data = e.data();
		double range = data.length > 0 && data[0] > 0 ? data[0] : TidalWaveGeometry.range(c);
		TidalWaveGeometry wave = new TidalWaveGeometry(start, look);
		RandomSource r = kit.random();

		// Circles: one flat under the caster (the wave rises from it), one upright before them.
		kit.circle(start.add(0, 0.06, 0), new Vec3(0, 1, 0), WaterFx.ARGB, 1.25F + 0.5F * c, 3 + Math.round(2 * c), 24);
		Vec3 front = eye.add(look.scale(1.6)).add(0, -0.3, 0);
		kit.circle(front, look.reverse(), WaterFx.ARGB, 0.7F + 0.35F * c, 2 + Math.round(3 * c), 14);

		// The shove: spray flung across the whole cone, out to its range.
		kit.burst(front.add(look.scale(0.3)), ParticleTypes.SPLASH, 12 + Math.round(8 * c), 0.35);
		int spray = 16 + Math.round(10 * c);
		Vec3 out = front.add(look.scale(0.8)); // past the circle, so none bloom in the caster's face
		for (int i = 0; i < spray; i++) {
			Vec3 d = WaterFx.inCone(look, TidalWaveGeometry.HALF_ANGLE, r);
			double v = (range - 2.4) / 7.2 * (0.45 + 0.55 * r.nextDouble());
			kit.motes(out, i % 3 == 0 ? WaterFx.FOAM : WaterFx.LIGHT, 1, 0.25, d.scale(v), 0.01, GlowMote.Drift.COAST, 0.15F + 0.06F * c, 11);
		}
		int[] shoved = e.entities();
		for (int i = 0; i < Math.min(shoved.length, 6); i++) {
			Entity en = level.getEntity(shoved[i]);
			if (en != null) kit.burst(en.position().add(0, en.getBbHeight() * 0.5, 0), ParticleTypes.SPLASH, 6, 0.25);
		}
		level.playLocalSound(front.x, front.y, front.z, SoundEvents.PLAYER_SPLASH_HIGH_SPEED, SoundSource.PLAYERS, 0.8F, 0.75F + 0.1F * (1 - c), false);
		level.playLocalSound(start.x, start.y, start.z, SoundEvents.TRIDENT_RIPTIDE_2.value(), SoundSource.PLAYERS, 0.55F, 1.15F, false);
		kit.shake(0.16F + 0.2F * c, 8);
		kit.fovKick(0.03F + 0.03F * c);

		// In the caster's own first-person view the wave is drawn from OWN_CLEAR blocks out, so it rushes away from them
		// instead of filling their screen; everyone else sees it rise at their feet.
		Minecraft mc = Minecraft.getInstance();
		boolean own = mc.player != null && mc.player.getId() == e.casterId() && mc.options.getCameraType().isFirstPerson();
		Roll roll = new Roll(wave, level, kit, BlockPos.containing(start).getY(), own ? OWN_CLEAR : 0);
		kit.simulate(1, TidalWaveGeometry.DURATION, t -> roll.step(t + 1));
	}

	/** The rolling wave's client state: each column's height as it climbs and falls, and which cells already showed. */
	private static final class Roll {
		final TidalWaveGeometry wave;
		final ClientLevel level;
		final FxKit kit;
		final int[] colY = new int[TidalWaveGeometry.WIDTH];
		final boolean[] broken = new boolean[TidalWaveGeometry.WIDTH];
		final Set<BlockPos> shown = new HashSet<>();
		final double clear;

		Roll(TidalWaveGeometry wave, ClientLevel level, FxKit kit, int y, double clear) {
			this.clear = clear;
			this.wave = wave;
			this.level = level;
			this.kit = kit;
			java.util.Arrays.fill(colY, y);
		}

		/** Geometry tick {@code t} (the server's age). */
		void step(int t) {
			if (!TidalWaveGeometry.frontActive(t)) return;
			double d = TidalWaveGeometry.distance(t);
			int h = TidalWaveGeometry.height(d);
			Vec3 dir = wave.dir();
			int half = TidalWaveGeometry.WIDTH / 2;
			for (int k = -half; k <= half; k++) {
				int i = k + half;
				if (broken[i]) continue;
				BlockPos col = wave.column(t, k);
				BlockPos base = new BlockPos(col.getX(), colY[i], col.getZ());
				if (!free(base)) {
					if (free(base.above()) && free(base.above(2))) {
						base = base.above(); // climbs a step
					} else {
						broken[i] = true; // breaks on the wall
						Vec3 at = Vec3.atCenterOf(base).subtract(dir.scale(0.6)).add(0, 0.4, 0);
						kit.burst(at, ParticleTypes.SPLASH, 10, 0.3);
						kit.motes(at, WaterFx.FOAM, 3, 0.4, new Vec3(0, 0.18, 0).subtract(dir.scale(0.08)), 0.08, GlowMote.Drift.FALL, 0.2F, 12);
						continue;
					}
				} else if (free(base.below())) {
					base = base.below(); // pours down
				}
				colY[i] = base.getY();
				if (d < clear) continue;
				for (int y = 0; y < h; y++) {
					BlockPos cell = base.above(y);
					if (!shown.add(cell) || !air(cell)) continue;
					boolean crest = y == h - 1 && h > 1;
					kit.ghostBlock(cell, crest ? CREST : BODY, Vec3.ZERO, crest ? CREST_LIFE : TidalWaveGeometry.CELL_LIFE,
							GhostBlocks.Motion.RISE);
				}
				// Light inside the water, so it glows blue in the dark.
				Vec3 heart = Vec3.atBottomCenterOf(base).add(0, 0.5, 0);
				kit.motes(heart, WaterFx.BODY, 1, 0.35, dir.scale(0.1), 0.02, GlowMote.Drift.FALL, 0.42F, 5);
				if (h >= 3) kit.motes(heart.add(0, 1.4, 0), WaterFx.LIGHT, 1, 0.35, dir.scale(0.12), 0.02, GlowMote.Drift.FALL, 0.38F, 5);
				// The crest: foam and spray thrown forward off the top.
				Vec3 top = wave.frontPoint(t, k);
				top = new Vec3(top.x, base.getY() + h + 0.1, top.z);
				kit.motes(top, k % 2 == 0 ? WaterFx.FOAM : WaterFx.LIGHT, 1, 0.3, dir.scale(0.16).add(0, 0.06, 0), 0.03, GlowMote.Drift.FALL,
						0.17F, 9);
				if ((t + i) % 2 == 0) kit.burst(top, ParticleTypes.SPLASH, 1, 0.15);
			}
			Vec3 mid = wave.frontCentre(t);
			if (t % 4 == 0) {
				level.playLocalSound(mid.x, mid.y, mid.z, SoundEvents.BOAT_PADDLE_WATER, SoundSource.PLAYERS, 0.5F, 0.8F + 0.05F * t, false);
			}
			if (t == TidalWaveGeometry.FRONT_TICKS) crash(t);
		}

		/** The end: the front collapses in a burst of spray and foam. */
		private void crash(int t) {
			int half = TidalWaveGeometry.WIDTH / 2;
			for (int k = -half; k <= half; k++) {
				if (broken[k + half]) continue;
				Vec3 p = wave.frontPoint(t, k);
				Vec3 at = new Vec3(p.x, colY[k + half] + 0.6, p.z);
				kit.burst(at, ParticleTypes.SPLASH, 7, 0.3);
				kit.motes(at, WaterFx.FOAM, 2, 0.4, wave.dir().scale(0.08).add(0, 0.12, 0), 0.06, GlowMote.Drift.FALL, 0.2F, 12);
			}
			Vec3 mid = wave.frontCentre(t);
			level.playLocalSound(mid.x, mid.y, mid.z, SoundEvents.GENERIC_SPLASH, SoundSource.PLAYERS, 0.9F, 0.9F, false);
		}

		/** Nothing to collide with: the wave may stand here. */
		private boolean free(BlockPos p) {
			return level.getBlockState(p).getCollisionShape(level, p).isEmpty();
		}

		/** Air (or a plant the water would wash over, with no fluid in it): a water cell may show here. */
		private boolean air(BlockPos p) {
			BlockState s = level.getBlockState(p);
			return s.isAir() || (s.canBeReplaced() && s.getFluidState().isEmpty());
		}
	}
}
