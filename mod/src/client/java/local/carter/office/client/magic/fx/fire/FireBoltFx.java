package local.carter.office.client.magic.fx.fire;

import java.util.Optional;

import org.jspecify.annotations.Nullable;

import local.carter.office.client.magic.fx.FxKit;
import local.carter.office.client.magic.fx.GlowMote;
import local.carter.office.client.magic.fx.SpellFx;
import local.carter.office.magic.net.FxEvent;
import local.carter.office.magic.spell.ProjectileSpec;
import local.carter.office.magic.spell.Projectiles;
import local.carter.office.magic.spell.TargetFilter;
import local.carter.office.magic.spell.fire.FireBolt;

import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.particles.ParticleOptions;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.server.level.ParticleStatus;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.Mth;
import net.minecraft.util.RandomSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.CollisionContext;

/**
 * Fire Bolt's visuals: a small fire circle flashes open in front of the caster, then a white-hot comet streaks out of it along the
 * server's own path ({@link FireBolt#spec} + {@link Projectiles#step}, clipped against blocks and creatures here the way the
 * server does), shedding flames, a spiral of embers and smoke; where it stops it pops in a flash of fire and sparks.
 */
public final class FireBoltFx implements SpellFx {
	/** White-hot core, flame orange-yellow, fire (the element colour), deep ember red. */
	static final int CORE = 0xFFF1C8;
	static final int HOT = 0xFFB040;
	static final int FIRE = 0xFF6A1A;
	static final int EMBER = 0xFF3A10;
	/** A dim fire glow for wide halos (additive: the colour is the brightness). */
	static final int HALO = 0x8A300C;
	/** Glows laid along each tick's step for the comet head. */
	private static final int HEAD = 11;
	/** The bolt is drawn from this far out, so it leaves from the circle and never fills a first-person view. */
	private static final double CLEAR = 1.2;

	@Override
	public void play(FxEvent e, ClientLevel level, FxKit kit) {
		ProjectileSpec spec = FireBolt.spec(e.origin(), e.dir());
		Vec3 dir = spec.velocity().normalize();
		float c = Mth.clamp(e.charge(), 0F, 1F);
		Vec3[] basis = basis(dir);

		Vec3 mouth = e.origin().add(dir.scale(1.6)).add(0, -0.18, 0);
		// The caster's own first-person view already has the charge circle; a second one there would cover the crosshair.
		if (!ownFirstPerson(e.casterId())) {
			kit.circle(mouth, dir.reverse(), 0xFF000000 | FIRE, 0.42F + 0.22F * c, 2 + Math.round(2 * c), 9 + Math.round(4 * c));
		}
		kit.motes(mouth, HOT, 8 + Math.round(6 * c), 0.18, dir.scale(0.22), 0.05, GlowMote.Drift.COAST, 0.11F, 7);
		level.playLocalSound(mouth.x, mouth.y, mouth.z, SoundEvents.FIRECHARGE_USE, SoundSource.PLAYERS, 0.7F + 0.2F * c,
				1.15F + 0.2F * (1 - c) + level.getRandom().nextFloat() * 0.1F, false);
		level.playLocalSound(mouth.x, mouth.y, mouth.z, SoundEvents.BLAZE_SHOOT, SoundSource.PLAYERS, 0.35F, 1.5F, false);

		boolean[] done = {false};
		float size = 1F + 0.35F * c;
		kit.simulate(1, FireBolt.MAX_TICKS + 1, t -> {
			if (done[0]) return;
			Vec3 from = Projectiles.step(spec, t);
			Vec3 to = Projectiles.step(spec, t + 1);
			BlockHitResult block = level.clip(new ClipContext(from, to, ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, CollisionContext.empty()));
			boolean blocked = block.getType() != HitResult.Type.MISS;
			Vec3 end = blocked ? block.getLocation() : to;
			Vec3 struck = struck(level, e.casterId(), from, end, spec.radius());
			if (struck != null) end = struck;
			streak(level, kit, e.origin(), dir, basis, from, end, t, size);
			if (struck != null) {
				pop(level, kit, end, dir.reverse(), size);
				done[0] = true;
			} else if (blocked) {
				Vec3 n = block.getDirection().getUnitVec3();
				pop(level, kit, end.add(n.scale(0.1)), n, size);
				done[0] = true;
			} else if (t + 1 >= spec.maxTicks()) {
				// Out of range: it gutters out.
				kit.burst(end, ParticleTypes.SMOKE, 8, 0.04);
				kit.motes(end, EMBER, 6, 0.2, Vec3.ZERO, 0.03, GlowMote.Drift.RISE, 0.08F, 14);
				done[0] = true;
			}
		});
	}

	/** One tick of flight, {@code from} → {@code end}: the comet head, its flames, the ember spiral and smoke. */
	private static void streak(ClientLevel level, FxKit kit, Vec3 origin, Vec3 dir, Vec3[] basis, Vec3 from, Vec3 end, int t, float size) {
		double d0 = from.distanceTo(origin);
		double d1 = end.distanceTo(origin);
		if (d1 <= CLEAR) return;
		if (d0 < CLEAR) from = origin.add(dir.scale(CLEAR));
		RandomSource r = kit.random();
		// The head: a white-hot core trailing into orange then fire red, laid densely along the step and fading fast, so frame to
		// frame it reads as one tapering comet rather than beads.
		for (int i = 0; i < HEAD; i++) {
			float k = i / (float) (HEAD - 1); // 0 at the head, 1 at the tail
			Vec3 at = end.lerp(from, k);
			int rgb = k < 0.3F ? CORE : k < 0.65F ? HOT : FIRE;
			// Living 3–4 ticks, each tick's glows overlap the last two ticks': one continuous streak.
			glow(kit, level, at, Vec3.ZERO, rgb, (0.36F - 0.14F * k) * size, 3 + (i & 1), GlowMote.Drift.COAST);
		}
		glow(kit, level, end, Vec3.ZERO, HALO, 0.6F * size, 3, GlowMote.Drift.COAST);
		// A few flames shed behind it, drifting back and up.
		for (int i = 0; i < 2; i++) {
			Vec3 at = from.lerp(end, r.nextDouble());
			Vec3 v = dir.scale(-0.03).add(jitter(r, 0.025), 0.01 + jitter(r, 0.02), jitter(r, 0.025));
			add(kit, level, i == 0 ? ParticleTypes.SMALL_FLAME : ParticleTypes.FLAME, at, v);
		}
		// An ember spiralling round the path (alternating sides tick by tick).
		double a = t * 1.9 + (t & 1) * Math.PI;
		Vec3 off = basis[0].scale(Math.cos(a) * 0.24).add(basis[1].scale(Math.sin(a) * 0.24));
		kit.motes(from.lerp(end, 0.5).add(off), (t & 1) == 0 ? HOT : EMBER, 1, 0.02, off.scale(0.08), 0.01, GlowMote.Drift.RISE, 0.07F, 14);
		// Smoke only once it is well away, so none hangs in the caster's face.
		if (d0 > 3 && (t & 1) == 0) add(kit, level, ParticleTypes.SMOKE, from, new Vec3(jitter(r, 0.01), 0.02, jitter(r, 0.01)));
	}

	/** Where it stopped: a flash, a burst of flame and sparks, a puff of smoke, a crackling thump. */
	private static void pop(ClientLevel level, FxKit kit, Vec3 at, Vec3 normal, float size) {
		// Glows stay small and the wide one dim: additive light saturates to a white disc otherwise, in daylight most of all.
		glow(kit, level, at, Vec3.ZERO, CORE, 0.7F * size, 4, GlowMote.Drift.COAST);
		RandomSource r = kit.random();
		for (int i = 0; i < 3; i++) {
			Vec3 p = at.add(jitter(r, 0.18), jitter(r, 0.18), jitter(r, 0.18));
			glow(kit, level, p, Vec3.ZERO, HALO, 0.9F * size, 6, GlowMote.Drift.COAST);
		}
		kit.motes(at, HOT, 18, 0.12, normal.scale(0.06), 0.24, GlowMote.Drift.COAST, 0.12F, 11);
		kit.motes(at, EMBER, 10, 0.25, new Vec3(0, 0.04, 0), 0.08, GlowMote.Drift.RISE, 0.08F, 24);
		kit.burst(at, ParticleTypes.FLAME, 30, 0.19);
		kit.burst(at, ParticleTypes.LAVA, 4, 0.1);
		kit.burst(at, ParticleTypes.LARGE_SMOKE, 5, 0.04);
		level.playLocalSound(at.x, at.y, at.z, SoundEvents.GENERIC_EXPLODE.value(), SoundSource.PLAYERS, 0.45F, 1.7F, false);
		level.playLocalSound(at.x, at.y, at.z, SoundEvents.FIRE_EXTINGUISH, SoundSource.PLAYERS, 0.5F, 1.4F, false);
	}

	/**
	 * Where the step {@code from} → {@code end} first meets a creature the server would hit (same filter, hitboxes widened by the
	 * bolt's radius; never the caster), or null.
	 */
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

	/** Whether this client is {@code casterId} looking out of their own eyes. */
	static boolean ownFirstPerson(int casterId) {
		Minecraft mc = Minecraft.getInstance();
		return mc.player != null && mc.player.getId() == casterId && mc.options.getCameraType().isFirstPerson();
	}

	/** Two unit vectors square to {@code dir} and each other. */
	static Vec3[] basis(Vec3 dir) {
		Vec3 up = Math.abs(dir.y) > 0.95 ? new Vec3(1, 0, 0) : new Vec3(0, 1, 0);
		Vec3 u = dir.cross(up).normalize();
		Vec3 v = u.cross(dir).normalize();
		return new Vec3[] {u, v};
	}

	/**
	 * One round glow (never the kit's star-shaped spark sprite, which at this size draws as a pale cross), if the budget grants
	 * it and particles are not on Minimal.
	 */
	static void glow(FxKit kit, ClientLevel level, Vec3 at, Vec3 v, int rgb, float size, int life, GlowMote.Drift drift) {
		Minecraft mc = Minecraft.getInstance();
		if (mc.options.particles().get() == ParticleStatus.MINIMAL || kit.budget().particles(1) == 0) return;
		mc.particleEngine.add(new GlowMote(level, at, v, rgb, size, life, drift, false));
	}

	/** One vanilla particle with its own velocity, if the event's budget grants it. */
	static void add(FxKit kit, ClientLevel level, ParticleOptions p, Vec3 at, Vec3 v) {
		if (kit.budget().particles(1) == 0) return;
		level.addParticle(p, at.x, at.y, at.z, v.x, v.y, v.z);
	}

	static double jitter(RandomSource r, double s) {
		return (r.nextDouble() * 2 - 1) * s;
	}
}
