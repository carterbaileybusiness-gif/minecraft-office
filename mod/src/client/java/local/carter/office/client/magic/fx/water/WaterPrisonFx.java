package local.carter.office.client.magic.fx.water;

import java.util.ArrayList;
import java.util.List;

import local.carter.office.client.magic.fx.FxKit;
import local.carter.office.client.magic.fx.GhostBlocks;
import local.carter.office.client.magic.fx.GlowMote;
import local.carter.office.client.magic.fx.RankFx;
import local.carter.office.client.magic.fx.SpellFx;
import local.carter.office.magic.net.FxEvent;
import local.carter.office.magic.spell.water.WaterPrison;
import local.carter.office.magic.spell.water.WaterPrisonGeometry;

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
 * Water Prison: a jet of water leaves a circle before the caster and wells up into a ball round the foe: ghost water
 * ({@link FxKit#ghostFluid}, one borderless body) in the air cells of {@link WaterPrisonGeometry#fill} (rounded on top), rising into place,
 * held by a glowing membrane ({@link WaterSphere}) that wobbles like a water surface. Bubbles rise inside it, the prisoner's
 * breath escapes in bubbles from its mouth, and currents swirl over the surface. When the prison ends (or its prisoner dies)
 * the ball collapses in a splash and its water drains away. Nothing is placed in the world.
 *
 * <p>The ghost water rises into place once and lives the prison's whole life, then sinks away. If the prisoner dies first, the
 * ball is laid again as short-lived still water, so it drains at once (the event's 128 ghost cells cover both; a ball is about 35 cells).
 */
public final class WaterPrisonFx implements SpellFx {
	/** How fast the ball drains when its prisoner dies. */
	private static final int DRAIN_TICKS = 6;
	/** Particles kept back for the collapse. */
	private static final int COLLAPSE_PARTICLES = 45;

	@Override
	public void play(FxEvent e, ClientLevel level, FxKit kit) {
		Vec3 centre = e.target();
		int life = Math.max(1, e.duration());
		float[] data = e.data();
		float radius = data.length > 0 && data[0] > 0 ? data[0] : (float) WaterPrisonGeometry.RADIUS;
		int[] held = e.entities();
		RandomSource r = kit.random();
		Vec3 look = WaterFx.unit(e.dir());
		Vec3 eye = e.origin();

		// The cast: a circle before the caster, a jet of water to the target.
		Vec3 front = eye.add(look.scale(1.5)).add(0, -0.25, 0);
		float c0 = Mth.clamp(e.charge(), 0F, 1F);
		kit.circle(front, look.reverse(), WaterFx.ARGB, 0.75F * (1F + c0), 3 + Math.round(2 * c0), 12);
		Vec3 from = front.add(look.scale(0.4));
		kit.trail(from, centre, ParticleTypes.FALLING_WATER, 10);
		int jet = (int) Math.min(18, Math.max(6, from.distanceTo(centre) * 1.2));
		for (int i = 0; i < jet; i++) {
			Vec3 at = from.lerp(centre, (i + r.nextDouble()) / jet);
			kit.motes(at, i % 3 == 0 ? WaterFx.FOAM : WaterFx.BODY, 1, 0.12, centre.subtract(from).normalize().scale(0.05), 0.02,
					GlowMote.Drift.COAST, 0.22F, 6 + i / 3);
		}
		level.playLocalSound(front.x, front.y, front.z, SoundEvents.PLAYER_SPLASH_HIGH_SPEED, SoundSource.PLAYERS, 0.6F, 1.3F, false);
		level.playLocalSound(centre.x, centre.y, centre.z, SoundEvents.BUCKET_EMPTY, SoundSource.PLAYERS, 1F, 0.8F, false);
		level.playLocalSound(centre.x, centre.y, centre.z, SoundEvents.BUBBLE_COLUMN_WHIRLPOOL_AMBIENT, SoundSource.PLAYERS, 0.8F, 1F, false);

		ball(level, kit, r, centre, radius, life, held.length > 0 ? held[0] : -1);
		// Twin Prison's second sphere, and Whirlpool's drag round it.
		if (data.length >= 4 && held.length > 1) ball(level, kit, r, new Vec3(data[1], data[2], data[3]), radius, life, held[1]);
		if (e.spellRank().is(WaterPrison.WHIRLPOOL)) RankFx.vortex(kit, level, centre, radius + WaterPrison.WHIRLPOOL_REACH, life, WaterFx.LIGHT);
	}

	/** One ball: ghost water in its air cells, the membrane, bubbles and the current; it collapses at its end or its prisoner's. */
	private static void ball(ClientLevel level, FxKit kit, RandomSource r, Vec3 centre, float radius, int life, int heldId) {
		List<WaterPrisonGeometry.Cell> cells = new ArrayList<>();
		for (WaterPrisonGeometry.Cell c : WaterPrisonGeometry.fill(centre, radius)) {
			if (open(level, c.pos())) cells.add(c);
		}
		WaterSphere skin = new WaterSphere(level, partial -> centre, radius + 0.15F, life, WaterSphere.Style.PRISON);
		int skinStep = skin.cost();
		int skinTotal = skinStep * (life / 10 + 1);
		if (kit.budget().particles(skinStep) == skinStep) Minecraft.getInstance().particleEngine.add(skin);
		for (WaterPrisonGeometry.Cell c : cells) kit.ghostFluid(c.pos(), c.height(), life, GhostBlocks.Motion.RISE);

		boolean[] over = new boolean[1];
		kit.simulate(1, life + 1, t -> {
			if (over[0]) return;
			Entity prisoner = heldId >= 0 ? level.getEntity(heldId) : null;
			boolean died = heldId >= 0 && (prisoner == null || prisoner.isRemoved() || !prisoner.isAlive());
			if (died || t >= life) {
				over[0] = true;
				skin.pop();
				collapse(kit, level, centre, radius, r);
				if (died && life - t > DRAIN_TICKS) {
					for (WaterPrisonGeometry.Cell c : cells) kit.ghostFluid(c.pos(), c.height(), DRAIN_TICKS, GhostBlocks.Motion.STATIC); // drains now
				}
				return;
			}
			if (t > 0 && t % 10 == 0 && kit.budget().particles(skinStep) < skinStep) skin.dim();
			int spare = kit.budget().particlesLeft() - (skinTotal - skinStep * (t / 10 + 1)) - COLLAPSE_PARTICLES;
			if (spare <= 0) return;
			if (t % 3 == 0) {
				// Bubbles rising through the water.
				Vec3 in = centre.add(inBall(r).scale(radius * 0.85));
				kit.motes(in, WaterFx.FOAM, 1, 0.02, new Vec3(0, 0.03, 0), 0.005, GlowMote.Drift.RISE, 0.1F, 14);
			}
			if (t % 4 == 1) {
				// A current running over the surface.
				double a = t * 0.21;
				double h = Math.sin(t * 0.13) * 0.6;
				double s = Math.sqrt(1 - h * h);
				Vec3 on = centre.add(Math.cos(a) * s * radius, h * radius, Math.sin(a) * s * radius);
				Vec3 along = new Vec3(-Math.sin(a), 0, Math.cos(a)).scale(0.07);
				kit.motes(on, WaterFx.LIGHT, 1, 0.03, along, 0.01, GlowMote.Drift.COAST, 0.16F, 9);
			}
			if (prisoner != null && t % 7 == 3) {
				// Its breath, escaping.
				Vec3 mouth = prisoner.getEyePosition();
				kit.motes(mouth, 0xFFFFFF, 2, 0.08, new Vec3(0, 0.05, 0), 0.01, GlowMote.Drift.RISE, 0.09F, 16);
			}
			if (t % 30 == 15) {
				level.playLocalSound(centre.x, centre.y, centre.z, SoundEvents.BUBBLE_COLUMN_WHIRLPOOL_AMBIENT, SoundSource.PLAYERS, 0.35F, 0.9F, false);
			}
		});
	}

	/** The ball collapses: water spills off it and falls, with a splash. */
	private static void collapse(FxKit kit, ClientLevel level, Vec3 c, float radius, RandomSource r) {
		for (int i = 0; i < 14; i++) {
			double a = Mth.TWO_PI * i / 14;
			Vec3 at = c.add(Math.cos(a) * radius, -0.2 + 0.6 * r.nextDouble(), Math.sin(a) * radius);
			kit.burst(at, ParticleTypes.SPLASH, 1, 0.15);
			kit.motes(at, i % 2 == 0 ? WaterFx.FOAM : WaterFx.BODY, 1, 0.1, new Vec3(Math.cos(a), 0.5, Math.sin(a)).scale(0.08), 0.03,
					GlowMote.Drift.FALL, 0.2F, 12);
		}
		kit.burst(c.add(0, -radius * 0.5, 0), ParticleTypes.FALLING_WATER, 14, 0.12);
		level.playLocalSound(c.x, c.y, c.z, SoundEvents.GENERIC_SPLASH, SoundSource.PLAYERS, 1F, 0.8F, false);
	}

	/** Air (or a plant water would wash over, with no fluid): a ghost cell may show here. */
	private static boolean open(ClientLevel level, BlockPos p) {
		BlockState s = level.getBlockState(p);
		return s.isAir() || (s.canBeReplaced() && s.getFluidState().isEmpty());
	}

	/** A random point in the unit ball. */
	private static Vec3 inBall(RandomSource r) {
		for (int i = 0; i < 8; i++) {
			Vec3 v = new Vec3(r.nextDouble() * 2 - 1, r.nextDouble() * 2 - 1, r.nextDouble() * 2 - 1);
			if (v.lengthSqr() <= 1) return v;
		}
		return Vec3.ZERO;
	}
}
