package local.carter.office.client.magic.fx.lightning;

import java.util.HashSet;
import java.util.Optional;
import java.util.Set;

import org.jspecify.annotations.Nullable;

import local.carter.office.client.magic.fx.FxKit;
import local.carter.office.client.magic.fx.GlowMote;
import local.carter.office.client.magic.fx.RankFx;
import local.carter.office.client.magic.fx.SpellFx;
import local.carter.office.magic.core.ChargeMath;
import local.carter.office.magic.core.Element;
import local.carter.office.magic.net.FxEvent;
import local.carter.office.magic.spell.ProjectileSpec;
import local.carter.office.magic.spell.Projectiles;
import local.carter.office.magic.spell.RankKit;

import local.carter.office.magic.spell.TargetFilter;
import local.carter.office.magic.spell.lightning.ThunderSpear;
import local.carter.office.magic.tree.SpellRank;

import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.CollisionContext;

/**
 * Thunder Spear's visuals: a lightning circle flares at the caster's off hand and the spear leaves it, a straight, heavy shaft of
 * lightning {@link #LENGTH} long, white-hot at the head, with a blue strand coiling round it and sparks shed behind, flying the
 * server's own path ({@link ThunderSpear#spec} + {@link Projectiles#step}, clipped against blocks and creatures here as the server
 * does). Where it lands: a flash, cracks of lightning running out over the ground, a ring of sparks and smoke, a crack of thunder
 * and a shake; and a strike of lightning from the sky onto it (the fx's own: no vanilla bolt is made). Thunder stays local (volume ≤ 2).
 */
public final class ThunderSpearFx implements SpellFx {
	/** Length of the spear's shaft. */
	static final double LENGTH = 2.6;
	/** Cracks run out over the ground from the impact, this many, this long. */
	static final int CRACKS = 6;
	static final double CRACK_LENGTH = 2.6;

	@Override
	public void play(FxEvent e, ClientLevel level, FxKit kit) {
		SpellRank rank = e.spellRank();
		ProjectileSpec spec = ThunderSpear.spec(e.origin(), e.dir(), rank);
		Vec3 dir = spec.velocity().normalize();
		float c = Mth.clamp(e.charge(), 0F, 1F);
		Vec3 hand = Volts.hand(e, level);
		Allowance all = Allowance.unlimited();
		long seed = e.seed();

		kit.circle(hand, dir.reverse(), Element.LIGHTNING.argb(), 0.5F * (1F + c), 3 + Math.round(2 * c), 12);
		kit.motes(hand, LightningLook.CORE, 8, 0.14, dir.scale(0.12), 0.08, GlowMote.Drift.FLICKER, 0.09F, 7);
		Volts.crackle(kit, level, all, hand, seed + 5);
		level.playLocalSound(hand.x, hand.y, hand.z, SoundEvents.TRIDENT_THROW.value(), SoundSource.PLAYERS, 0.9F, 1.3F, false);
		level.playLocalSound(hand.x, hand.y, hand.z, SoundEvents.LIGHTNING_BOLT_THUNDER, SoundSource.PLAYERS, 0.25F, 1.8F, false);

		boolean[] done = {false};
		Vec3[] tail = {hand};
		kit.simulate(1, ThunderSpear.MAX_TICKS + 1, t -> {
			if (done[0]) return;
			Vec3 from = Projectiles.step(spec, t);
			Vec3 to = Projectiles.step(spec, t + 1);
			BlockHitResult block = level.clip(new ClipContext(from, to, ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, CollisionContext.empty()));
			boolean blocked = block.getType() != HitResult.Type.MISS;
			Vec3 end = blocked ? block.getLocation() : to;
			Vec3 struck = struck(level, e.casterId(), from, end, spec.radius());
			if (struck != null) end = struck;
			fly(kit, level, all, tail[0], end, dir, seed + 13L * t, t);
			tail[0] = end;
			if (struck != null) {
				land(kit, level, all, end, dir.reverse(), seed, c);
				forks(e, kit, level, rank, end);
				done[0] = true;
			} else if (blocked) {
				land(kit, level, all, end, block.getDirection().getUnitVec3(), seed, c);
				forks(e, kit, level, rank, end);
				done[0] = true;
			} else if (t + 1 >= spec.maxTicks()) {
				// Out of range: it fizzles out in the air.
				Volts.zap(kit, level, all, end, 0.6F);
				done[0] = true;
			}
		});
	}

	/** The forks at the impact: Magnet's drag in, the Returning Spear's flight home, Chain Spear's arcs to the nearest creatures. */
	private static void forks(FxEvent e, FxKit kit, ClientLevel level, SpellRank rank, Vec3 at) {
		if (rank.is(ThunderSpear.MAGNET)) {
			double reach = ThunderSpear.blast(e.charge(), rank) + ThunderSpear.MAGNET_REACH;
			RankFx.vortex(kit, level, at, reach, 10, LightningLook.YELLOW);
		}
		if (rank.is(ThunderSpear.RETURN)) {
			// The server flies it home at the spear's speed; drawn the same way, to where the caster is now.
			Vec3 dir = ThunderSpear.spec(e.origin(), e.dir(), rank).velocity().normalize();
			Vec3[] tail = {at};
			kit.simulate(1, ThunderSpear.RETURN_TICKS, t -> {
				Entity caster = level.getEntity(e.casterId());
				if (caster == null || tail[0] == null) return;
				Vec3 home = caster.getBoundingBox().getCenter();
				Vec3 head = ThunderSpear.homeward(at, home, t + 1);
				fly(kit, level, Allowance.unlimited(), tail[0], head, head.subtract(tail[0]).lengthSqr() > 1.0E-6 ? head.subtract(tail[0]).normalize() : dir,
						e.seed() + 7L * t, 1);
				tail[0] = head.distanceToSqr(home) < 0.25 ? null : head;
			});
		}
		if (rank.is(ThunderSpear.CHAIN)) {
			// The server's chain picks the nearest creatures in its sight: drawn here to the nearest this client sees, never through a wall.
			Vec3 from = at;
			Set<Integer> seen = new HashSet<>();
			for (int hop = 0; hop < ThunderSpear.CHAIN_HOPS; hop++) {
				Entity next = null;
				double best = ThunderSpear.CHAIN_RANGE * ThunderSpear.CHAIN_RANGE;
				Vec3 here = from;
				for (Entity en : level.getEntities((Entity) null, new AABB(from, from).inflate(ThunderSpear.CHAIN_RANGE),
						x -> x.getId() != e.casterId() && TargetFilter.affects(null, x))) {
					double d = en.getBoundingBox().getCenter().distanceToSqr(from);
					if (d < best && d > 0.5 && !seen.contains(en.getId()) && RankKit.reaches(level, here, en.getBoundingBox())) {
						best = d;
						next = en;
					}
				}

				if (next == null) break;
				seen.add(next.getId());
				Vec3 to = next.getBoundingBox().getCenter();
				kit.bolt(from, to, e.seed() + 101L * hop, Element.LIGHTNING.argb());
				from = to;
			}
		}
	}

	/** One tick of flight to {@code head}: the shaft behind it (never further back than where it left the hand), its glow and sparks. */
	private static void fly(FxKit kit, ClientLevel level, Allowance a, Vec3 hand, Vec3 head, Vec3 dir, long seed, int t) {
		Vec3 back = head.subtract(dir.scale(LENGTH));
		Vec3 tail = t == 0 && back.subtract(hand).dot(dir) < 0 ? hand : back;
		Volts.arc(kit, level, a, tail, head, seed, LightningLook.SPEAR);
		Volts.arc(kit, level, a, tail, head, LightningLook.strandSeed(seed), LightningLook.SPEAR_STRAND);
		Volts.glow(kit, level, head, LightningLook.CORE, 0.32F, 2);
		Volts.glow(kit, level, head, LightningLook.BLOOM, 0.75F, 3);
		kit.motes(tail.lerp(head, 0.3), LightningLook.YELLOW, 3, 0.2, dir.scale(-0.05), 0.04, GlowMote.Drift.FLICKER, 0.07F, 6);
		kit.trail(tail, head, ParticleTypes.ELECTRIC_SPARK, 2);
	}

	/** The impact at {@code at} (facing {@code normal}): flash, ground cracks, sparks and smoke, thunder, a shake. */
	private static void land(FxKit kit, ClientLevel level, Allowance a, Vec3 at, Vec3 normal, long seed, float c) {
		Vec3 p = at.add(normal.scale(0.15));
		// A full charge blasts twice as wide (ThunderSpear.blast): the cracks, the ring and the flash grow with it.
		float size = ChargeMath.size(c);
		Volts.zap(kit, level, a, p, 2F * size);
		Vec3 top = LightningLook.skyTop(at, LightningLook.SPEAR_PILLAR_HEIGHT, seed + 3);
		Volts.channel(kit, level, a, top, at, seed + 3, LightningLook.SPEAR_PILLAR, LightningLook.SPEAR_PILLAR_STRAND);
		Volts.glow(kit, level, p, LightningLook.BLUE, 1.6F, 5);
		Vec3 ground = new Vec3(at.x, at.y + 0.08, at.z);
		for (int i = 0; i < CRACKS; i++) {
			double ang = Mth.TWO_PI * i / CRACKS + (Math.floorMod(seed + 7L * i, 100) / 100.0) * 0.8;
			double len = CRACK_LENGTH * size * (0.7 + 0.3 * Math.floorMod(seed * 3 + i, 10) / 10.0);
			Vec3 tip = ground.add(Math.cos(ang) * len, 0, Math.sin(ang) * len);
			Volts.arc(kit, level, a, ground, tip, seed + 41L * i, LightningLook.SPEAR_CRACK);
		}
		kit.ring(ground, 1.1 * size, ParticleTypes.ELECTRIC_SPARK, Math.round(18 * size));
		kit.motes(ground, LightningLook.YELLOW, 10, 0.6, new Vec3(0, 0.03, 0), 0.05, GlowMote.Drift.FLICKER, 0.09F, 14);
		kit.burst(p, ParticleTypes.LARGE_SMOKE, 6, 0.06);
		level.playLocalSound(at.x, at.y, at.z, SoundEvents.LIGHTNING_BOLT_IMPACT, SoundSource.PLAYERS, 1.1F, 0.9F, false);
		level.playLocalSound(at.x, at.y, at.z, SoundEvents.TRIDENT_THUNDER.value(), SoundSource.PLAYERS, 0.7F, 1.2F, false);
		level.playLocalSound(at.x, at.y, at.z, SoundEvents.LIGHTNING_BOLT_THUNDER, SoundSource.PLAYERS, 1.2F, 1.15F, false);
		kit.shake(0.4F * size, 10);
	}

	/** Where the step {@code from} → {@code end} first meets a creature the server would hit (never the caster), or null. */
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
