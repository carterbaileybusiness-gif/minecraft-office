package local.carter.office.client.magic.fx.fire;

import java.util.List;
import java.util.Optional;

import org.jspecify.annotations.Nullable;

import local.carter.office.client.magic.fx.FxKit;
import local.carter.office.client.magic.fx.GlowMote;
import local.carter.office.client.magic.fx.RankFx;
import local.carter.office.client.magic.fx.Shake;
import local.carter.office.client.magic.fx.SpellFx;
import local.carter.office.magic.net.FxEvent;
import local.carter.office.magic.spell.Projectiles;
import local.carter.office.magic.spell.TargetFilter;
import local.carter.office.magic.spell.fire.BarrageGeometry;
import local.carter.office.magic.spell.fire.FireballBarrage;
import local.carter.office.magic.tree.SpellRank;

import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.core.particles.BlockParticleOption;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.Mth;
import net.minecraft.util.RandomSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.CollisionContext;

/**
 * Fireball Barrage's visuals, built from {@link BarrageGeometry} exactly as the server throws the volley: a fire circle opens in
 * the sky above the spot, facing down at it, and the fireballs leave it one by one (a flash and a whoomph each), roaring down
 * on their arcs (clipped against blocks and creatures here the way the server does) as balls of fire trailing flame and smoke.
 * Each impact is an explosion puff: a flash, flame, lava and smoke, chunks of the ground, a boom, and a small shake (0.2) for
 * anyone within 16 blocks. Every fireball has its own share of the particle budget, so the last is as big as the first.
 */
public final class FireballBarrageFx implements SpellFx {
	/** Particles set aside for the circle's opening. */
	private static final int OPENING = 30;
	/** Most of a fireball's share kept for its explosion. */
	static final int BOOM = 50;
	private static final float SHAKE = 0.2F;
	private static final double SHAKE_RANGE = 16;

	@Override
	public void play(FxEvent e, ClientLevel level, FxKit kit) {
		float[] data = e.data();
		SpellRank rank = e.spellRank();
		int count = data.length > 0 ? Math.max(1, Math.round(data[0])) : BarrageGeometry.count(e.charge(), rank);
		float[] lifts = new float[count];
		for (int i = 0; i < count; i++) lifts[i] = data.length > i + 1 ? data[i + 1] : 1F;
		List<BarrageGeometry.Ball> volley = BarrageGeometry.volley(e.target(), e.dir(), e.seed(), count, lifts, BarrageGeometry.spreadMul(rank),
				BarrageGeometry.interval(rank));
		boolean heavy = rank.is(FireballBarrage.HEAVY);
		RandomSource r = kit.random();

		// The sky circle: where the fireballs leave from, facing down at the spot.
		Vec3 mouth = Vec3.ZERO;
		Vec3 aim = Vec3.ZERO;
		for (BarrageGeometry.Ball b : volley) {
			mouth = mouth.add(b.origin());
			aim = aim.add(b.aim());
		}
		mouth = mouth.scale(1.0 / count);
		aim = aim.scale(1.0 / count);
		Vec3 down = aim.subtract(mouth).normalize();
		int life = volley.get(count - 1).launchTick() + 14;
		kit.circle(mouth.subtract(down.scale(0.6)), down, 0xFF000000 | FireBoltFx.FIRE, 2.3F, 4 + Math.round(e.charge()), life);
		kit.motes(mouth, FireBoltFx.HOT, OPENING, 1.2, down.scale(0.06), 0.05, GlowMote.Drift.COAST, 0.16F, 14);
		level.playLocalSound(mouth.x, mouth.y, mouth.z, SoundEvents.BLAZE_SHOOT, SoundSource.PLAYERS, 2F, 0.6F, false);

		// Each fireball's share of what is left, its explosion's part set aside first.
		int share = Math.max(0, kit.budget().particlesLeft() / count);
		int boom = Math.min(BOOM, share / 2);
		Allowance[] flights = new Allowance[count];
		Allowance[] booms = new Allowance[count];
		boolean[] done = new boolean[count];
		for (int i = 0; i < count; i++) {
			flights[i] = new Allowance(share - boom);
			booms[i] = new Allowance(boom);
		}
		int casterId = e.casterId();
		kit.simulate(1, Math.min(e.duration() > 0 ? e.duration() : 200, life + BarrageGeometry.MAX_TICKS), t -> {
			for (int i = 0; i < count; i++) {
				BarrageGeometry.Ball b = volley.get(i);
				int k = t - b.launchTick();
				if (k < 0 || done[i]) continue;
				if (k == 0) leave(level, kit, b, flights[i]);
				done[i] = fly(level, kit, r, b, k, flights[i], booms[i], casterId, heavy);
			}
		});
	}

	/** A fireball leaves the circle: a flash and a whoomph. */
	private static void leave(ClientLevel level, FxKit kit, BarrageGeometry.Ball b, Allowance a) {
		Vec3 o = b.origin();
		if (a.take(1) == 1) FireBoltFx.glow(kit, level, o, Vec3.ZERO, FireBoltFx.HOT, 1.1F, 3, GlowMote.Drift.COAST);
		kit.burst(o, ParticleTypes.FLAME, a.take(5), 0.12);
		level.playLocalSound(o.x, o.y, o.z, SoundEvents.GHAST_SHOOT, SoundSource.PLAYERS, 1.2F, 1.3F + 0.2F * (b.launchTick() % 3), false);
	}

	/** One tick of a fireball's flight: true once it has landed (or flown out its time). */
	private static boolean fly(ClientLevel level, FxKit kit, RandomSource r, BarrageGeometry.Ball b, int k, Allowance a, Allowance boom,
			int casterId, boolean heavy) {
		Vec3 from = Projectiles.step(b.spec(), k);
		Vec3 to = Projectiles.step(b.spec(), k + 1);
		BlockHitResult block = level.clip(new ClipContext(from, to, ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, CollisionContext.empty()));
		boolean blocked = block.getType() != HitResult.Type.MISS;
		Vec3 end = blocked ? block.getLocation() : to;
		Vec3 struck = struck(level, casterId, from, end, b.spec().radius());
		if (struck != null) end = struck;
		ball(level, kit, r, from, end, a, k);
		if (struck != null || blocked) {
			Vec3 dir = to.subtract(from).normalize();
			BlockState hit = blocked ? level.getBlockState(block.getBlockPos()) : null;
			explode(level, kit, r, end.subtract(dir.scale(0.2)), hit, boom);
			// Heavy Shells: a wider blast ring round each impact.
			if (heavy) RankFx.blast(kit, level, end.subtract(dir.scale(0.2)), BarrageGeometry.HEAVY_BLAST, FireBoltFx.HOT, ParticleTypes.LAVA);
			return true;
		}
		if (k + 1 >= b.spec().maxTicks()) {
			kit.burst(end, ParticleTypes.SMOKE, boom.take(6), 0.05);
			return true;
		}
		return false;
	}

	/** The fireball over one tick's step: a white-hot core in a ball of fire, flames and smoke streaming off it. */
	private static void ball(ClientLevel level, FxKit kit, RandomSource r, Vec3 from, Vec3 end, Allowance a, int k) {
		Vec3 dir = end.subtract(from);
		double len = dir.length();
		if (len < 1.0E-6) return;
		dir = dir.scale(1 / len);
		int glows = a.take(3);
		for (int i = 0; i < glows; i++) {
			Vec3 at = end.lerp(from, i * 0.4);
			// No white core: in daylight additive white washes out; the flame sheet carries the fire.
			int rgb = i == 0 ? FireBoltFx.HOT : i == 1 ? FireBoltFx.FIRE : FireBoltFx.EMBER;
			// Living 4 ticks, so this tick's glows overlap the next ones': a streak, not beads.
			FireBoltFx.glow(kit, level, at, Vec3.ZERO, rgb, (0.85F - 0.18F * i), 4, GlowMote.Drift.COAST);
		}
		if ((k & 1) == 0 && a.take(1) == 1) FireBoltFx.glow(kit, level, end, Vec3.ZERO, FireBoltFx.HALO, 1.7F, 3, GlowMote.Drift.COAST);
		int flames = a.spend(kit, 2);
		for (int i = 0; i < flames; i++) {
			Vec3 at = from.lerp(end, r.nextDouble()).add(FireBoltFx.jitter(r, 0.2), FireBoltFx.jitter(r, 0.2), FireBoltFx.jitter(r, 0.2));
			Vec3 v = dir.scale(-0.04).add(FireBoltFx.jitter(r, 0.02), 0.02, FireBoltFx.jitter(r, 0.02));
			level.addParticle(ParticleTypes.FLAME, at.x, at.y, at.z, v.x, v.y, v.z);
		}
		// A sheet of real flame wrapped round the ball.
		if (a.spend(kit, 1) == 1) {
			Minecraft.getInstance().particleEngine.add(new FireWallFx.Flame(level, end.add(0, 0.1, 0), fire(), 0.55F + 0.1F * r.nextFloat(), 3,
					r.nextBoolean(), r.nextFloat() * Mth.TWO_PI, 0.62F, 0F));
		}
		if ((k & 1) == 1 && a.spend(kit, 1) == 1) {
			Vec3 at = from.lerp(end, 0.3);
			level.addParticle(ParticleTypes.SMOKE, at.x, at.y, at.z, 0, 0.02, 0);
		}
	}

	/** The explosion puff where it landed. */
	private static void explode(ClientLevel level, FxKit kit, RandomSource r, Vec3 at, @Nullable BlockState hit, Allowance a) {
		if (a.take(3) == 3) {
			FireBoltFx.glow(kit, level, at, Vec3.ZERO, FireBoltFx.CORE, 1.3F, 3, GlowMote.Drift.COAST);
			FireBoltFx.glow(kit, level, at.add(0, 0.4, 0), Vec3.ZERO, FireBoltFx.HALO, 2.4F, 7, GlowMote.Drift.COAST);
			FireBoltFx.glow(kit, level, at.add(0, 0.2, 0), Vec3.ZERO, FireBoltFx.HOT, 1.6F, 5, GlowMote.Drift.COAST);
		}
		// Flames bursting out of the ground round it.
		int sheets = a.spend(kit, 6);
		for (int i = 0; i < sheets; i++) {
			double ang = r.nextDouble() * Mth.TWO_PI;
			double rad = 0.3 + r.nextDouble() * 1.1;
			float size = 0.65F + 0.45F * r.nextFloat();
			Vec3 p = at.add(Math.cos(ang) * rad, size - 0.2, Math.sin(ang) * rad);
			Minecraft.getInstance().particleEngine.add(new FireWallFx.Flame(level, p, fire(), size, 10 + r.nextInt(7), r.nextBoolean(),
					r.nextFloat() * Mth.TWO_PI, 0.62F, 0F));
		}
		kit.burst(at.add(0, 0.3, 0), ParticleTypes.FLAME, a.take(12), 0.28);
		kit.burst(at, ParticleTypes.LAVA, a.take(3), 0.2);
		kit.burst(at.add(0, 0.5, 0), ParticleTypes.LARGE_SMOKE, a.take(2), 0.06);
		kit.burst(at.add(0, 0.4, 0), ParticleTypes.SMOKE, a.take(5), 0.08);
		kit.motes(at, FireBoltFx.HOT, a.take(8), 0.3, new Vec3(0, 0.1, 0), 0.25, GlowMote.Drift.COAST, 0.13F, 12);
		kit.motes(at, FireBoltFx.EMBER, a.take(4), 0.6, new Vec3(0, 0.05, 0), 0.08, GlowMote.Drift.RISE, 0.09F, 30);
		if (hit != null && !hit.isAir()) {
			int chunks = a.spend(kit, 6);
			BlockParticleOption o = new BlockParticleOption(ParticleTypes.BLOCK, hit);
			for (int i = 0; i < chunks; i++) {
				double ang = r.nextDouble() * Mth.TWO_PI;
				level.addParticle(o, at.x, at.y + 0.2, at.z, Math.cos(ang) * 0.2, 0.25 + 0.2 * r.nextDouble(), Math.sin(ang) * 0.2);
			}
		}
		level.playLocalSound(at.x, at.y, at.z, SoundEvents.GENERIC_EXPLODE.value(), SoundSource.PLAYERS, 1.6F, 0.85F + 0.25F * r.nextFloat(), false);
		level.playLocalSound(at.x, at.y, at.z, SoundEvents.FIRECHARGE_USE, SoundSource.PLAYERS, 0.8F, 0.6F, false);
		if (Minecraft.getInstance().gameRenderer.mainCamera().position().distanceTo(at) <= SHAKE_RANGE) Shake.shake(SHAKE, 8);
	}

	private static TextureAtlasSprite fire() {
		return Minecraft.getInstance().getModelManager().getBlockStateModelSet().getParticleMaterial(Blocks.FIRE.defaultBlockState()).sprite();
	}

	/** Where the step first meets a creature the server would hit (same filter, hitboxes widened by the radius; never the caster). */
	private static @Nullable Vec3 struck(ClientLevel level, int casterId, Vec3 from, Vec3 end, double radius) {
		Vec3 best = null;
		double bestD = Double.MAX_VALUE;
		for (Entity en : level.getEntities((Entity) null, new AABB(from, end).inflate(radius),
				x -> x.getId() != casterId && TargetFilter.affects(null, x))) {
			AABB box = en.getBoundingBox().inflate(radius);
			if (box.contains(from)) return from;
			Optional<Vec3> at = box.clip(from, end);
			if (at.isPresent() && from.distanceToSqr(at.get()) < bestD) {
				bestD = from.distanceToSqr(at.get());
				best = at.get();
			}
		}
		return best;
	}
}
