package local.carter.office.client.magic.fx.fire;

import local.carter.office.client.magic.fx.FxKit;
import local.carter.office.client.magic.fx.GlowMote;
import local.carter.office.client.magic.fx.SpellFx;
import local.carter.office.magic.net.FxEvent;
import local.carter.office.magic.spell.fire.FireWallGeometry;
import local.carter.office.magic.spell.fire.FlameDash;

import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.Mth;
import net.minecraft.util.RandomSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.CollisionContext;

/**
 * Flame Dash's visuals: a fire seal flashes on the ground where the dash starts and flames blast out behind the caster; then,
 * for as long as the dash burns, the ground along their path catches in two rows of flame sheets (the Fire Wall's animated
 * sheets, short-lived) that die down behind them, and a comet glow, flames and embers stream off their back. No world change.
 * Everything is drawn behind the caster, so their own view stays clear.
 */
public final class FlameDashFx implements SpellFx {
	private static final Vec3 UP = new Vec3(0, 1, 0);
	/** Ticks the trail follows the caster (the burn window, plus the last of the glide). */
	private static final int FOLLOW = FlameDash.BURN_TICKS + 4;
	/** Distance between flame sheets along the path, and the rows' offset to either side. */
	private static final double SPACING = 0.55;
	private static final double ROW = 0.32;

	@Override
	public void play(FxEvent e, ClientLevel level, FxKit kit) {
		Vec3 start = e.target();
		Vec3 dir = e.dir().lengthSqr() < 1.0E-6 ? new Vec3(0, 0, 1) : e.dir().normalize();
		Vec3 side = new Vec3(-dir.z, 0, dir.x);
		RandomSource r = kit.random();
		TextureAtlasSprite fire = Minecraft.getInstance().getModelManager().getBlockStateModelSet()
				.getParticleMaterial(Blocks.FIRE.defaultBlockState()).sprite();

		// The launch: a seal under the start and a blast of flame behind.
		float c = Mth.clamp(e.charge(), 0F, 1F);
		kit.circle(start.add(0, 0.05, 0), UP, 0xFF000000 | FireBoltFx.FIRE, 1.15F * (1F + c), 3 + Math.round(2 * c), 14);
		Vec3 back = start.add(0, 0.6, 0).subtract(dir.scale(0.4));
		for (int i = 0; i < 18; i++) {
			Vec3 v = dir.scale(-0.12 - 0.12 * r.nextDouble()).add(FireBoltFx.jitter(r, 0.07), 0.02 + 0.06 * r.nextDouble(), FireBoltFx.jitter(r, 0.07));
			FireBoltFx.add(kit, level, i % 3 == 0 ? ParticleTypes.SMALL_FLAME : ParticleTypes.FLAME, back.add(FireBoltFx.jitter(r, 0.25), FireBoltFx.jitter(r, 0.4), FireBoltFx.jitter(r, 0.25)), v);
		}
		kit.motes(back, FireBoltFx.HOT, 12, 0.3, dir.scale(-0.12), 0.06, GlowMote.Drift.COAST, 0.13F, 9);
		kit.burst(start.add(0, 0.1, 0), ParticleTypes.LARGE_SMOKE, 4, 0.05);
		level.playLocalSound(start.x, start.y, start.z, SoundEvents.FIRECHARGE_USE, SoundSource.PLAYERS, 0.9F, 0.75F, false);
		level.playLocalSound(start.x, start.y, start.z, SoundEvents.BLAZE_SHOOT, SoundSource.PLAYERS, 0.7F, 1.35F, false);
		level.playLocalSound(start.x, start.y, start.z, SoundEvents.BREEZE_WIND_CHARGE_BURST.value(), SoundSource.PLAYERS, 0.6F, 0.8F, false);

		double[] carry = {SPACING * 0.5};
		int[] sheets = {0};
		Vec3[] last = {start};
		int casterId = e.casterId();
		kit.simulate(1, FOLLOW, t -> {
			Entity caster = level.getEntity(casterId);
			if (caster == null) return;
			Vec3 now = caster.position();
			Vec3 step = now.subtract(last[0]);
			double len = Math.sqrt(step.x * step.x + step.z * step.z);
			// The burning ground: sheets every SPACING along the path, two staggered rows, while the dash burns.
			// A teleport is no dash: nothing is laid across the jump (the server burns nothing there either).
			if (t <= FlameDash.BURN_TICKS && len > 1.0E-3 && step.lengthSqr() <= FireWallGeometry.MAX_SWEEP * FireWallGeometry.MAX_SWEEP) {
				Vec3 flat = new Vec3(step.x / len, 0, step.z / len);
				// carry[0]: path walked since the last sheet; sheets[0]: sheets laid (their parity picks the row).
				double s = SPACING - carry[0];
				for (; s <= len; s += SPACING) {
					double across = ((sheets[0]++ & 1) == 0 ? -ROW : ROW) + FireBoltFx.jitter(r, 0.08);
					sheet(level, kit, r, fire, last[0].add(flat.scale(s)).add(side.scale(across)), t);
				}
				carry[0] = len - (s - SPACING);
			}
			// The comet off their back: glow, flames, embers (all behind them, never in their face).
			if (t <= FlameDash.BURN_TICKS) {
				Vec3 body = now.add(0, caster.getBbHeight() * 0.5, 0).subtract(dir.scale(0.7));
				FireBoltFx.glow(kit, level, body, dir.scale(0.2), FireBoltFx.HALO, 0.9F, 3, GlowMote.Drift.COAST);
				FireBoltFx.glow(kit, level, body.subtract(dir.scale(0.3)), dir.scale(0.1), FireBoltFx.HOT, 0.45F, 3, GlowMote.Drift.COAST);
				for (int i = 0; i < 4; i++) {
					Vec3 p = body.add(FireBoltFx.jitter(r, 0.3), FireBoltFx.jitter(r, 0.7), FireBoltFx.jitter(r, 0.3));
					FireBoltFx.add(kit, level, ParticleTypes.FLAME, p, dir.scale(-0.05).add(0, 0.03, 0));
				}
				kit.motes(body, FireBoltFx.EMBER, 2, 0.3, new Vec3(0, 0.04, 0), 0.03, GlowMote.Drift.RISE, 0.08F, 18);
				if ((t & 1) == 0) FireBoltFx.add(kit, level, ParticleTypes.SMOKE, last[0].add(0, 0.3, 0), new Vec3(0, 0.03, 0));
			}
			last[0] = now;
		});
	}

	/** One short-lived flame sheet standing on the ground under {@code at} (none if there is no ground close below). */
	private static void sheet(ClientLevel level, FxKit kit, RandomSource r, TextureAtlasSprite fire, Vec3 at, int t) {
		BlockHitResult ground = level.clip(new ClipContext(at.add(0, 0.6, 0), at.add(0, -2.5, 0), ClipContext.Block.COLLIDER,
				ClipContext.Fluid.ANY, CollisionContext.empty()));
		if (ground.getType() == HitResult.Type.MISS || kit.budget().particles(1) == 0) return;
		float size = 0.38F + 0.18F * r.nextFloat();
		Vec3 foot = ground.getLocation().add(0, size, 0);
		int life = 16 + r.nextInt(8) + 2 * (FlameDash.BURN_TICKS - t);
		Minecraft.getInstance().particleEngine.add(new FireWallFx.Flame(level, foot, fire, size, life, r.nextBoolean(),
				r.nextFloat() * Mth.TWO_PI, 0.7F + 0.06F * r.nextFloat(), 0F));
		if (r.nextInt(3) == 0) FireBoltFx.add(kit, level, ParticleTypes.FLAME, foot, new Vec3(0, 0.04, 0));
	}
}
