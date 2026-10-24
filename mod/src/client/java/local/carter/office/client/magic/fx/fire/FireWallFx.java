package local.carter.office.client.magic.fx.fire;

import java.util.HashSet;
import java.util.Set;

import local.carter.office.client.magic.fx.FxKit;
import local.carter.office.client.magic.fx.GhostBlocks;
import local.carter.office.client.magic.fx.GlowMote;
import local.carter.office.client.magic.fx.SpellFx;
import local.carter.office.magic.core.Shapes.Cell;
import local.carter.office.magic.net.FxEvent;
import local.carter.office.magic.spell.fire.FireWallGeometry;

import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.Mth;
import net.minecraft.util.RandomSource;
import net.minecraft.world.entity.projectile.Projectile;
import net.minecraft.world.entity.projectile.arrow.ThrownTrident;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;

/**
 * Fire Wall's visuals, built from {@link FireWallGeometry} exactly as the server places the wall: a burning seal opens on the
 * ground under it and a rune circle flashes in its plane while a curtain of flame rises row by row out of the ground (fire block
 * models over the footprint, no world change), glowing heat, licking flames, embers and smoke over its life; projectiles that
 * fly into it burn up in a puff; at the end the flames sink and it smokes out.
 */
public final class FireWallFx implements SpellFx {
	private static final Vec3 UP = new Vec3(0, 1, 0);
	private static final BlockState FLAME_BLOCK = Blocks.FIRE.defaultBlockState();
	/** Ticks between rows rising (the curtain climbs). */
	private static final int ROW_DELAY = 2;
	/** Rows of flame models, overlapping: each stands this far above the last. */
	private static final int ROWS = 4;
	private static final double ROW_STEP = 0.78;
	/** Share of cells whose top row (the crown) burns, for a ragged top edge. */
	private static final float CROWN = 0.55F;

	@Override
	public void play(FxEvent e, ClientLevel level, FxKit kit) {
		FireWallGeometry g = FireWallGeometry.of(e.target(), e.dir(), e.charge());
		int life = Math.max(1, e.duration() > 0 ? e.duration() : g.duration());
		float c = Mth.clamp(e.charge(), 0F, 1F);
		Vec3 base = g.base();
		double halfW = g.width() / 2;
		BlockPos foot = BlockPos.containing(base);
		RandomSource r = kit.random();

		// The seal on the ground (it lasts as long as the wall) and a rune circle flashing in the wall's plane, facing the caster.
		kit.circle(base.add(0, 0.05, 0), UP, 0xFF000000 | FireBoltFx.FIRE, (float) halfW + 0.3F, 5, life);
		kit.circle(base.add(0, g.height() * 0.5, 0).subtract(g.forward().scale(0.7)), g.forward().reverse(), 0xFF000000 | FireBoltFx.FIRE, 1.4F, c >= 0.5F ? 5 : 4, 24);
		level.playLocalSound(base.x, base.y, base.z, SoundEvents.FIRECHARGE_USE, SoundSource.PLAYERS, 1.0F, 0.6F, false);
		level.playLocalSound(base.x, base.y, base.z, SoundEvents.BLAZE_SHOOT, SoundSource.PLAYERS, 0.8F, 0.55F, false);

		// The eruption along its foot.
		for (int i = 0; i < 24; i++) {
			double s = -halfW + g.width() * (i + r.nextDouble()) / 24;
			FireBoltFx.add(kit, level, ParticleTypes.FLAME, g.point(s, 0.1), new Vec3(FireBoltFx.jitter(r, 0.03), 0.16 + 0.1 * r.nextDouble(), FireBoltFx.jitter(r, 0.03)));
		}
		kit.motes(g.point(0, 0.4), FireBoltFx.HOT, 16, halfW * 0.6, new Vec3(0, 0.1, 0), 0.05, GlowMote.Drift.RISE, 0.14F, 18);

		Set<Integer> burnt = new HashSet<>();
		kit.simulate(1, life, t -> {
			// Rows of flame rise out of the ground one after another and all sink together at the end. Rows overlap and every flame
			// is nudged a little, so the curtain reads as one ragged sheet of fire rather than a grid of fire blocks.
			if (t % ROW_DELAY == 0 && t / ROW_DELAY < ROWS) {
				int row = t / ROW_DELAY;
				for (Cell cell : g.footprint()) {
					BlockPos pos = foot.offset(cell.x(), 0, cell.z());
					if (row < ROWS - 1 || r.nextFloat() < CROWN) flame(kit, g, r, pos, row * ROW_STEP, 0, life - t);
					// A second, staggered layer behind the lower rows gives the wall depth.
					if (row < 2) flame(kit, g, r, pos, row * ROW_STEP + 0.3, 0.32, life - t);
				}
			}
			burnProjectiles(level, kit, g, burnt);
			if (t % 2 != 0) return;
			boolean ending = t >= life - 8;
			// Flames licking up the curtain and the glow of its heat (additive, so overlapping glows build a bright sheet).
			for (int i = 0; i < 2; i++) {
				Vec3 at = g.point(FireBoltFx.jitter(r, halfW), r.nextDouble() * g.height() * 0.7).add(g.forward().scale(FireBoltFx.jitter(r, 0.35)));
				FireBoltFx.add(kit, level, ParticleTypes.FLAME, at, new Vec3(FireBoltFx.jitter(r, 0.01), 0.05 + 0.05 * r.nextDouble(), FireBoltFx.jitter(r, 0.01)));
			}
			if (!ending) {
				// Sparks of heat glowing in the curtain (small: wide additive glows wash out to white).
				Vec3 heat = g.point(FireBoltFx.jitter(r, halfW * 0.9), 0.3 + r.nextDouble() * (g.height() - 0.5)).subtract(g.forward().scale(0.45));
				kit.motes(heat, r.nextInt(3) == 0 ? FireBoltFx.CORE : FireBoltFx.HOT, 1, 0.1, new Vec3(0, 0.03, 0), 0.01, GlowMote.Drift.RISE,
						0.16F + 0.1F * r.nextFloat(), 16);
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

	/** One rising fire model at {@code pos}, {@code up} above the ground, pushed {@code back} away from the caster, a little jittered. */
	private static void flame(FxKit kit, FireWallGeometry g, RandomSource r, BlockPos pos, double up, double back, int life) {
		double across = FireBoltFx.jitter(r, 0.2) + (back > 0 ? 0.5 : 0);
		double along = back + FireBoltFx.jitter(r, 0.1);
		Vec3 off = g.side().scale(across).add(g.forward().scale(along)).add(0, up + FireBoltFx.jitter(r, 0.06), 0);
		kit.ghostBlock(pos, FLAME_BLOCK, off, life, GhostBlocks.Motion.RISE);
	}

	/** Projectiles about to fly into the wall (the server discards them this tick): a puff of smoke and flame and a hiss. */
	private static void burnProjectiles(ClientLevel level, FxKit kit, FireWallGeometry g, Set<Integer> burnt) {
		for (Projectile p : level.getEntitiesOfClass(Projectile.class, g.bounds().inflate(4),
				p -> !(p instanceof ThrownTrident) && g.crosses(p.getBoundingBox(), p.getDeltaMovement()))) {
			if (!burnt.add(p.getId())) continue;
			Vec3 at = p.position();
			kit.burst(at, ParticleTypes.SMOKE, 6, 0.05);
			kit.burst(at, ParticleTypes.FLAME, 5, 0.08);
			kit.motes(at, FireBoltFx.CORE, 1, 0, Vec3.ZERO, 0, GlowMote.Drift.COAST, 0.6F, 3);
			level.playLocalSound(at.x, at.y, at.z, SoundEvents.FIRE_EXTINGUISH, SoundSource.PLAYERS, 0.5F, 1.6F, false);
		}
	}
}
