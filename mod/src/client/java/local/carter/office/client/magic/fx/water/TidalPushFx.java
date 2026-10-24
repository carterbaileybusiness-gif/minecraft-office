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
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;

/**
 * Tidal Push: a circle opens under the caster and another before them, spray is flung across the whole cone, and a wave of
 * water rolls out from the caster's feet along {@link TidalWaveGeometry}, its columns following the ground by the same rule
 * the server uses ({@link TidalWaveGeometry#step}: climbing steps, pouring down drops, breaking on walls in a burst of spray).
 * Its body is ghost water (one borderless, animated body, the biome's tint) rising in the air cells it passes, tall at the front and low behind; below it a curtain of falling water
 * runs down the front face, spray flies off the crest, glowing water lights it from inside, and drops patter down behind it,
 * until it crashes eight blocks out. Nothing is placed in the world. In the caster's own first-person view the wave shows
 * from {@link #OWN_CLEAR} blocks out, so it never fills their screen.
 */
public final class TidalPushFx implements SpellFx {
	/**
	 * How long each level of the wave's body stands (client-only ghost water, never placed): the foot longest, the crest least, so
	 * the wave is tall at its front and falls away low behind it.
	 */
	static final int[] LEVEL_LIFE = {7, 5, 4};
	/** How full the topmost cell of a column is: its surface sits a little under the cell's top, like a swell. */
	static final float CREST_FULL = 0.85F;
	/** How far from the caster their own first-person view starts showing the wave. */
	static final double OWN_CLEAR = 4.0;

	@Override
	public void play(FxEvent e, ClientLevel level, FxKit kit) {
		Vec3 look = WaterFx.unit(e.dir());
		Vec3 eye = e.origin();
		Vec3 start = e.target();
		float c = Mth.clamp(e.charge(), 0F, 1F);
		float[] data = e.data();
		double range = data.length > 0 && data[0] > 0 ? data[0] : TidalWaveGeometry.range(c);
		TidalWaveGeometry wave = new TidalWaveGeometry(start, look, TidalWaveGeometry.length(range));
		RandomSource r = kit.random();

		// Circles: one flat under the caster (the wave rises from it), one upright before them.
		kit.circle(start.add(0, 0.06, 0), new Vec3(0, 1, 0), WaterFx.ARGB, 1.25F * (1F + c), 3 + Math.round(2 * c), 24);
		Vec3 front = eye.add(look.scale(1.6)).add(0, -0.3, 0);
		kit.circle(front, look.reverse(), WaterFx.ARGB, 0.7F * (1F + c), 2 + Math.round(3 * c), 14);

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
			if (en != null) kit.burst(en.position().add(0, en.getBbHeight() * 0.5, 0), ParticleTypes.SPLASH, 3, 0.25);
		}
		level.playLocalSound(front.x, front.y, front.z, SoundEvents.PLAYER_SPLASH_HIGH_SPEED, SoundSource.PLAYERS, 0.8F, 0.75F + 0.1F * (1 - c), false);
		level.playLocalSound(start.x, start.y, start.z, SoundEvents.TRIDENT_RIPTIDE_2.value(), SoundSource.PLAYERS, 0.55F, 1.15F, false);
		kit.shake(0.16F + 0.2F * c, 8);
		kit.fovKick(0.03F + 0.03F * c);

		// In the caster's own first-person view the wave is drawn from OWN_CLEAR blocks out, so it rushes away from them
		// instead of filling their screen; everyone else sees it rise at their feet.
		Minecraft mc = Minecraft.getInstance();
		boolean own = mc.player != null && mc.player.getId() == e.casterId() && mc.options.getCameraType().isFirstPerson();
		Roll roll = new Roll(wave, level, kit, own ? OWN_CLEAR : 0);
		kit.simulate(1, wave.duration(), t -> roll.step(t + 1));
	}

	/** The rolling wave's client state: the columns (moved by the shared rule) and which crest cells already showed. */
	private static final class Roll {
		final TidalWaveGeometry wave;
		final ClientLevel level;
		final FxKit kit;
		final int[] colY;
		final boolean[] broken = new boolean[TidalWaveGeometry.WIDTH];
		final Set<BlockPos> shown = new HashSet<>();
		final double clear;

		Roll(TidalWaveGeometry wave, ClientLevel level, FxKit kit, double clear) {
			this.wave = wave;
			this.level = level;
			this.kit = kit;
			this.clear = clear;
			this.colY = wave.columns();
		}

		/** Geometry tick {@code t} (the server's age). */
		void step(int t) {
			if (!wave.active(t)) return;
			int broke = wave.step(t, colY, broken, this::free);
			double d = wave.at(t);
			int h = TidalWaveGeometry.height(d);
			Vec3 dir = wave.dir();
			int half = TidalWaveGeometry.WIDTH / 2;
			for (int i = 0; i < TidalWaveGeometry.WIDTH; i++) {
				int k = i - half;
				BlockPos col = wave.column(t, k);
				BlockPos base = new BlockPos(col.getX(), colY[i], col.getZ());
				if ((broke & (1 << i)) != 0) {
					// Breaks on the wall: a burst of spray thrown back up off it.
					Vec3 at = Vec3.atCenterOf(base).subtract(dir.scale(0.6)).add(0, 0.4, 0);
					kit.burst(at, ParticleTypes.SPLASH, 8, 0.3);
					kit.motes(at, WaterFx.FOAM, 2, 0.4, new Vec3(0, 0.18, 0).subtract(dir.scale(0.08)), 0.08, GlowMote.Drift.FALL, 0.2F, 12);
					continue;
				}
				if (broken[i] || d < clear) continue;
				boolean even = (t + i) % 2 == 0;
				// The body: ghost water filling the column up to the crest (all one body of water, from this event's kit).
				for (int y = 0; y < h; y++) {
					BlockPos cell = base.above(y);
					if (shown.add(cell) && air(cell)) {
						kit.ghostFluid(cell, y == h - 1 ? CREST_FULL : 1F, LEVEL_LIFE[Math.min(y, LEVEL_LIFE.length - 1)], GhostBlocks.Motion.RISE);
					}
				}
				Vec3 p = wave.frontPoint(t, k);
				double floor = colY[i];
				// The front face: a curtain of falling water from the crest down to the ground.
				Vec3 face = new Vec3(p.x, floor, p.z).add(dir.scale(0.45));
				kit.trail(face.add(wave.side().scale(-0.35)).add(0, h - 0.1, 0), face.add(wave.side().scale(0.35)).add(0, 0.2, 0),
						ParticleTypes.FALLING_WATER, h >= 3 ? 2 : 1);
				Vec3 top = new Vec3(p.x, floor + h + 0.1, p.z);
				if (even) {
					// Spray off the crest, and light inside the water.
					kit.burst(top, ParticleTypes.SPLASH, 1, 0.18);
					kit.motes(new Vec3(p.x, floor + 0.5, p.z), WaterFx.BODY, 1, 0.35, dir.scale(0.1), 0.02, GlowMote.Drift.FALL, 0.42F, 5);
				} else {
					// Foam thrown forward, and drops pattering down where the wave has passed.
					kit.motes(top, k % 2 == 0 ? WaterFx.FOAM : WaterFx.LIGHT, 1, 0.3, dir.scale(0.16).add(0, 0.06, 0), 0.03,
							GlowMote.Drift.FALL, 0.17F, 9);
					Vec3 behind = new Vec3(p.x, floor + 0.05, p.z).subtract(dir.scale(1.2 + 0.6 * kit.random().nextDouble()));
					kit.burst(behind, ParticleTypes.RAIN, 1, 0.0);
				}
			}
			Vec3 mid = wave.frontCentre(t);
			if (t % 4 == 0) {
				level.playLocalSound(mid.x, mid.y, mid.z, SoundEvents.BOAT_PADDLE_WATER, SoundSource.PLAYERS, 0.5F, 0.8F + 0.05F * t, false);
			}
			if (t == wave.frontTicks()) crash(t);
		}

		/** The end: the front collapses in a burst of spray and foam. */
		private void crash(int t) {
			int half = TidalWaveGeometry.WIDTH / 2;
			for (int i = 0; i < TidalWaveGeometry.WIDTH; i++) {
				if (broken[i]) continue;
				Vec3 p = wave.frontPoint(t, i - half);
				Vec3 at = new Vec3(p.x, colY[i] + 0.6, p.z);
				kit.burst(at, ParticleTypes.SPLASH, 5, 0.3);
				kit.motes(at, WaterFx.FOAM, 1, 0.4, wave.dir().scale(0.08).add(0, 0.12, 0), 0.06, GlowMote.Drift.FALL, 0.2F, 12);
			}
			Vec3 mid = wave.frontCentre(t);
			level.playLocalSound(mid.x, mid.y, mid.z, SoundEvents.GENERIC_SPLASH, SoundSource.PLAYERS, 0.9F, 0.9F, false);
		}

		/** Nothing to collide with: the wave may stand here (the server's rule, on this client's world). */
		private boolean free(BlockPos p) {
			return level.isLoaded(p) && level.getBlockState(p).getCollisionShape(level, p).isEmpty();
		}

		/** Air (or a plant the water would wash over, with no fluid in it): a cell of water may show here. */
		private boolean air(BlockPos p) {
			BlockState s = level.getBlockState(p);
			return s.isAir() || (s.canBeReplaced() && s.getFluidState().isEmpty());
		}
	}
}
