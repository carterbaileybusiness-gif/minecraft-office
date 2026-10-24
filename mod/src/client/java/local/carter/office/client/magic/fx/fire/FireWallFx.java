package local.carter.office.client.magic.fx.fire;

import java.util.HashSet;
import java.util.Set;

import local.carter.office.client.magic.fx.FxKit;
import local.carter.office.client.magic.fx.GlowMote;
import local.carter.office.client.magic.fx.SpellFx;
import local.carter.office.magic.net.FxEvent;
import local.carter.office.magic.spell.fire.FireWall;
import local.carter.office.magic.spell.fire.FireWallGeometry;

import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.particle.SingleQuadParticle;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.LightCoordsUtil;
import net.minecraft.util.Mth;
import net.minecraft.util.RandomSource;
import net.minecraft.world.entity.projectile.Projectile;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.Vec3;

/**
 * Fire Wall's visuals, built from {@link FireWallGeometry} exactly as the server places the wall: a burning seal opens on the
 * ground under it and a rune circle flashes in its plane while a curtain of flame climbs out of the ground row by row. The curtain
 * is upright, staggered flame sheets (the animated fire texture, tinted orange, full bright) laid along the wall's line itself,
 * so it is seamless at any angle; over its life flames lick up it, embers rise and smoke drifts off the top; projectiles that fly
 * into it burn up in a puff; at the end the flames shrink into the ground and it smokes out. No world change.
 */
public final class FireWallFx implements SpellFx {
	private static final Vec3 UP = new Vec3(0, 1, 0);
	/** Ticks between rows rising (the curtain climbs). */
	private static final int ROW_DELAY = 2;
	/** Rows of flame sheets: half-size (a sheet is twice this tall) and how high its foot stands. The last row is the crown. */
	private static final float[] ROW_SIZE = {0.55F, 0.5F, 0.42F, 0.32F};
	private static final double[] ROW_FOOT = {0.0, 0.6, 1.2, 1.75};
	/**
	 * Share of the fire texture's dense bottom (the white-hot base) cut off the sheets of rows above the ground: there the flame
	 * tongues alone, stretched tall, rise out of the row below instead of stacking bands of base.
	 */
	private static final float CROP = 0.3F;
	/** Distance between sheets along the wall. */
	private static final double SPACING = 0.45;
	/** Share of crown sheets that burn, for a ragged top edge. */
	private static final float CROWN = 0.6F;

	@Override
	public void play(FxEvent e, ClientLevel level, FxKit kit) {
		FireWallGeometry g = FireWallGeometry.of(e.target(), e.dir(), e.charge());
		int life = Math.max(1, e.duration() > 0 ? e.duration() : g.duration());
		float c = Mth.clamp(e.charge(), 0F, 1F);
		Vec3 base = g.base();
		double halfW = g.width() / 2;
		RandomSource r = kit.random();
		TextureAtlasSprite fire = Minecraft.getInstance().getModelManager().getBlockStateModelSet()
				.getParticleMaterial(Blocks.FIRE.defaultBlockState()).sprite();

		// The seal on the ground (it lasts as long as the wall) and a rune circle flashing in the wall's plane, facing the caster.
		kit.circle(base.add(0, 0.05, 0), UP, 0xFF000000 | FireBoltFx.FIRE, (float) halfW + 0.3F, 5, life);
		kit.circle(base.add(0, g.height() * 0.5, 0).subtract(g.forward().scale(0.7)), g.forward().reverse(), 0xFF000000 | FireBoltFx.FIRE,
				1.4F, c >= 0.5F ? 5 : 4, 24);
		level.playLocalSound(base.x, base.y, base.z, SoundEvents.FIRECHARGE_USE, SoundSource.PLAYERS, 1.0F, 0.6F, false);
		level.playLocalSound(base.x, base.y, base.z, SoundEvents.BLAZE_SHOOT, SoundSource.PLAYERS, 0.8F, 0.55F, false);

		// The eruption along its foot.
		for (int i = 0; i < 24; i++) {
			double s = -halfW + g.width() * (i + r.nextDouble()) / 24;
			FireBoltFx.add(kit, level, ParticleTypes.FLAME, g.point(s, 0.1),
					new Vec3(FireBoltFx.jitter(r, 0.03), 0.16 + 0.1 * r.nextDouble(), FireBoltFx.jitter(r, 0.03)));
		}
		kit.motes(g.point(0, 0.4), FireBoltFx.HOT, 16, halfW * 0.6, new Vec3(0, 0.1, 0), 0.05, GlowMote.Drift.RISE, 0.14F, 18);

		Set<Integer> burnt = new HashSet<>();
		kit.simulate(1, life, t -> {
			if (t % ROW_DELAY == 0 && t / ROW_DELAY < ROW_SIZE.length) row(level, kit, g, r, fire, t / ROW_DELAY, life - t);
			burnProjectiles(level, kit, g, burnt);
			if (t % 2 != 0) return;
			boolean ending = t >= life - 8;
			// A flame licking up the curtain.
			Vec3 at = g.point(FireBoltFx.jitter(r, halfW), r.nextDouble() * g.height() * 0.7).add(g.forward().scale(FireBoltFx.jitter(r, 0.35)));
			FireBoltFx.add(kit, level, ParticleTypes.FLAME, at, new Vec3(FireBoltFx.jitter(r, 0.01), 0.05 + 0.05 * r.nextDouble(), FireBoltFx.jitter(r, 0.01)));
			if (!ending) {
				// A spark of heat in front of the curtain (small and orange: wide additive glows wash out to white).
				Vec3 heat = g.point(FireBoltFx.jitter(r, halfW * 0.9), 0.3 + r.nextDouble() * (g.height() - 0.5)).subtract(g.forward().scale(0.45));
				FireBoltFx.glow(kit, level, heat, new Vec3(0, 0.03, 0), FireBoltFx.HOT, 0.14F + 0.08F * r.nextFloat(), 16, GlowMote.Drift.RISE);
			}
			if (t % 6 == 0) {
				Vec3 top = g.point(FireBoltFx.jitter(r, halfW), g.height());
				kit.motes(top, FireBoltFx.HOT, 1, 0.2, new Vec3(0, 0.06, 0), 0.03, GlowMote.Drift.RISE, 0.07F, 30);
			}
			if (t % 8 == 0) {
				FireBoltFx.add(kit, level, ParticleTypes.LARGE_SMOKE, g.point(FireBoltFx.jitter(r, halfW), g.height() - 0.3), new Vec3(0, 0.05, 0));
			}
			if (t % 24 == 0) {
				level.playLocalSound(base.x, base.y + 1, base.z, SoundEvents.FIRE_AMBIENT, SoundSource.PLAYERS, 1.2F, 0.7F + r.nextFloat() * 0.3F, false);
			}
			if (ending) {
				// It smokes out.
				for (int i = 0; i < 2; i++) {
					FireBoltFx.add(kit, level, ParticleTypes.SMOKE, g.point(FireBoltFx.jitter(r, halfW), r.nextDouble() * 1.5), new Vec3(0, 0.04, 0));
				}
				if (t >= life - 2) level.playLocalSound(base.x, base.y + 1, base.z, SoundEvents.FIRE_EXTINGUISH, SoundSource.PLAYERS, 0.6F, 0.8F, false);
			}
		});
	}

	/**
	 * One row of flame sheets along the wall's line: every other row shifted half a step, each sheet nudged along and across the
	 * wall and a little varied in size, so the curtain is one ragged, deep sheet of fire with no grid and no stair steps.
	 */
	private static void row(ClientLevel level, FxKit kit, FireWallGeometry g, RandomSource r, TextureAtlasSprite fire, int row, int life) {
		double halfW = g.width() / 2 - 0.25;
		boolean crown = row == ROW_SIZE.length - 1;
		for (double s = -halfW + (row % 2) * SPACING / 2; s <= halfW + 1e-6; s += SPACING) {
			if (crown && r.nextFloat() > CROWN) continue;
			if (kit.budget().particles(1) == 0) return;
			float size = ROW_SIZE[row] * (0.88F + 0.24F * r.nextFloat());
			// Feet scattered well up and down, so no row's lower edge lines up into a band.
			double foot = ROW_FOOT[row] + (row == 0 ? FireBoltFx.jitter(r, 0.05) : FireBoltFx.jitter(r, 0.22));
			Vec3 at = g.point(s + FireBoltFx.jitter(r, 0.08), foot + size).add(g.forward().scale(FireBoltFx.jitter(r, 0.18)));
			// Upper rows burn a little redder.
			float green = 0.74F - 0.06F * row;
			Minecraft.getInstance().particleEngine.add(new Flame(level, at, fire, size, life, r.nextBoolean(), r.nextFloat() * Mth.TWO_PI, green,
					row == 0 ? 0F : CROP));
		}
	}

	/** Projectiles about to fly into the wall (the server discards them this tick): a puff of smoke and flame and a hiss. */
	private static void burnProjectiles(ClientLevel level, FxKit kit, FireWallGeometry g, Set<Integer> burnt) {
		for (Projectile p : level.getEntitiesOfClass(Projectile.class, g.bounds().inflate(4),
				p -> FireWall.burns(p) && g.crosses(p.getBoundingBox(), p.getDeltaMovement()))) {
			if (!burnt.add(p.getId())) continue;
			Vec3 at = p.position();
			kit.burst(at, ParticleTypes.SMOKE, 6, 0.05);
			kit.burst(at, ParticleTypes.FLAME, 5, 0.08);
			FireBoltFx.glow(kit, level, at, Vec3.ZERO, FireBoltFx.HOT, 0.5F, 3, GlowMote.Drift.COAST);
			level.playLocalSound(at.x, at.y, at.z, SoundEvents.FIRE_EXTINGUISH, SoundSource.PLAYERS, 0.5F, 1.6F, false);
		}
	}

	/**
	 * One upright sheet of flame: the animated fire texture on a quad that turns about the vertical to face the camera, tinted
	 * orange (so the texture's white core never washes out at night), full bright. It climbs out of the ground as it appears,
	 * flickers in size, and shrinks back into the ground over its last ticks.
	 */
	private static final class Flame extends SingleQuadParticle {
		private static final int IN = 5;
		private static final int OUT = 8;
		private final double footY;
		private final float size;
		private final boolean flip;
		private final float phase;
		private final float crop;

		Flame(ClientLevel level, Vec3 at, TextureAtlasSprite sprite, float size, int life, boolean flip, float phase, float green, float crop) {
			super(level, at.x, at.y, at.z, sprite);
			this.footY = at.y - size;
			this.size = size;
			this.flip = flip;
			this.phase = phase;
			this.crop = crop;
			this.lifetime = Math.max(2, life);
			this.hasPhysics = false;
			this.gravity = 0;
			this.friction = 1;
			this.xd = 0;
			this.yd = 0;
			this.zd = 0;
			setColor(1F, green, 0.36F);
			setAlpha(0.95F);
			setSize(2 * size, 2 * size);
			place(0);
			this.yo = this.y;
		}

		@Override
		public void tick() {
			super.tick();
			if (!removed) place(age);
		}

		/** Size and height at {@code age}: growing up out of the ground, flickering, sinking away at the end. */
		private void place(int age) {
			float grow = Mth.clamp(age / (float) IN, 0F, 1F);
			float shrink = Mth.clamp((lifetime - age) / (float) OUT, 0F, 1F);
			float k = Math.min(1 - (1 - grow) * (1 - grow), shrink);
			float flicker = 1F + 0.07F * Mth.sin(age * 0.8F + phase) + 0.04F * Mth.sin(age * 2.3F + phase * 2);
			quadSize = size * (0.25F + 0.75F * k) * flicker;
			// The sheet's foot stays on its row's line as it grows: its centre rises with it (setPos keeps the culling box with it).
			setPos(x, footY + quadSize, z);
			setAlpha(0.95F * Math.min(1F, shrink * 1.5F));
		}

		@Override
		protected float getU0() {
			return flip ? super.getU1() : super.getU0();
		}

		@Override
		protected float getU1() {
			return flip ? super.getU0() : super.getU1();
		}

		/** The bottom of the sheet: with {@code crop}, the texture's base is cut off and its tongues fill the sheet. */
		@Override
		protected float getV1() {
			return crop <= 0 ? super.getV1() : Mth.lerp(1 - crop, super.getV0(), super.getV1());
		}

		@Override
		public FacingCameraMode getFacingCameraMode() {
			return FacingCameraMode.LOOKAT_Y;
		}

		@Override
		public int getLightCoords(float partial) {
			return LightCoordsUtil.FULL_BRIGHT;
		}

		@Override
		protected Layer getLayer() {
			return Layer.TRANSLUCENT_TERRAIN;
		}
	}
}
