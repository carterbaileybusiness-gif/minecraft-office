package local.carter.office.magic.spell.water;

import java.util.Optional;

import org.jspecify.annotations.Nullable;

import local.carter.office.magic.core.Element;
import local.carter.office.magic.core.Slots;
import local.carter.office.magic.spell.ActiveEffects;
import local.carter.office.magic.spell.CastContext;
import local.carter.office.magic.spell.CastOutcome;
import local.carter.office.magic.spell.LingeringEffect;
import local.carter.office.magic.spell.Spell;
import local.carter.office.magic.spell.SpellIds;
import local.carter.office.magic.spell.TargetFilter;

import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffectUtil;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.monster.Enemy;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

/**
 * Water Prison (Water, slot 3, medium, lingering): trap a foe in a sphere of water.
 *
 * <p>The cast looks along the caster's view for the first hostile mob ({@link Enemy}, the lanes' ruling: control effects take
 * only foes, never livestock, golems or allays) within {@link WaterPrisonGeometry#RANGE}, in sight (the ray stops at the first
 * solid block). A sphere of {@link WaterPrisonGeometry#RADIUS} forms about its middle for {@link WaterPrisonGeometry#duration}
 * ticks (100 + 40 × charge). If the ray finds no foe, the sphere forms where the ray ends (backed off a wall by its radius) and
 * takes the first foe that comes inside.
 *
 * <p>While its prisoner is inside ({@link WaterPrisonGeometry#inside}), every tick it gets Slowness III, is pulled to the
 * centre ({@link WaterPrisonGeometry#pull}: it hangs in the water), stops burning and loses air as if under water
 * ({@link WaterPrisonGeometry#air}): drowning damage comes only once its air is used up, and creatures that breathe water are
 * immune. A prisoner that dies ends the prison.
 *
 * <p><b>No world change</b>: temporary liquids are forbidden (the temp-safe ruling), so the water is the client's: ghost water
 * cells in the sphere's air cells ({@link WaterPrisonGeometry#fill}).
 *
 * <p>Fx: {@code target} = the sphere's centre, {@code duration} = its life, {@code entities} = the prisoner (none if it waits),
 * {@code data[0]} = its radius.
 */
public final class WaterPrison implements Spell {
	/** Slowness III. */
	public static final int SLOWNESS_AMPLIFIER = 2;
	/** The slowness is laid for this long and renewed each tick inside, so it ends soon after the prisoner is free. */
	public static final int SLOWNESS_TICKS = 10;

	@Override
	public Identifier id() {
		return SpellIds.WATER_PRISON;
	}

	@Override
	public Element element() {
		return Element.WATER;
	}

	@Override
	public int slot() {
		return Slots.MEDIUM_B;
	}

	@Override
	public CastOutcome cast(CastContext ctx) {
		Vec3 eye = ctx.eye();
		Vec3 look = ctx.look().normalize();
		BlockHitResult wall = ctx.level().clip(new ClipContext(eye, eye.add(look.scale(WaterPrisonGeometry.RANGE)), ClipContext.Block.COLLIDER,
				ClipContext.Fluid.NONE, ctx.caster()));
		Vec3 end = wall.getType() == HitResult.Type.MISS ? eye.add(look.scale(WaterPrisonGeometry.RANGE)) : wall.getLocation();
		LivingEntity target = foeOnRay(ctx, eye, end).orElse(null);
		Vec3 centre;
		if (target != null) {
			centre = target.getBoundingBox().getCenter();
		} else {
			double back = wall.getType() == HitResult.Type.MISS ? 0 : Math.min(WaterPrisonGeometry.RADIUS, end.distanceTo(eye));
			centre = end.subtract(look.scale(back));
		}
		int life = WaterPrisonGeometry.duration(ctx.charge());
		Prison prison = new Prison(ctx, centre, life, target);
		if (!ActiveEffects.add(ctx.level(), prison)) return CastOutcome.fizzle("magic.office.busy");
		int[] held = target == null ? new int[0] : new int[] {target.getId()};
		return CastOutcome.cast(ctx.fx().withTarget(centre).withDuration(life).withEntities(held).withData((float) WaterPrisonGeometry.RADIUS));
	}

	/** Whether the prison may take {@code e}: a foe the caster's spells may touch. */
	static boolean takes(CastContext ctx, Entity e) {
		return e instanceof Enemy && TargetFilter.affects(ctx.caster(), e);
	}

	/** The nearest foe whose hitbox (inflated by {@link WaterPrisonGeometry#FORGIVENESS}) the ray {@code eye → end} passes through. */
	private static Optional<LivingEntity> foeOnRay(CastContext ctx, Vec3 eye, Vec3 end) {
		AABB sweep = new AABB(eye, end).inflate(WaterPrisonGeometry.FORGIVENESS + 1);
		LivingEntity best = null;
		double bestDist = Double.MAX_VALUE;
		for (LivingEntity e : ctx.level().getEntitiesOfClass(LivingEntity.class, sweep, e -> takes(ctx, e))) {
			AABB box = e.getBoundingBox().inflate(WaterPrisonGeometry.FORGIVENESS + e.getPickRadius());
			double d;
			if (box.contains(eye)) {
				d = 0;
			} else {
				Optional<Vec3> at = box.clip(eye, end);
				if (at.isEmpty()) continue;
				d = eye.distanceToSqr(at.get());
			}
			if (d < bestDist) {
				bestDist = d;
				best = e;
			}
		}
		return Optional.ofNullable(best);
	}

	/** The sphere: holds its prisoner at the centre, slowed and losing air. */
	static final class Prison implements LingeringEffect {
		private final CastContext ctx;
		private final Vec3 centre;
		private final int life;
		private @Nullable LivingEntity held;
		/** The prisoner's air as the prison keeps it (vanilla would refill it each tick out of water). */
		private int air;

		Prison(CastContext ctx, Vec3 centre, int life, @Nullable LivingEntity target) {
			this.ctx = ctx;
			this.centre = centre;
			this.life = life;
			hold(target);
		}

		private void hold(@Nullable LivingEntity e) {
			held = e;
			if (e != null) air = e.getAirSupply();
		}

		@Override
		public boolean tick(ServerLevel level, int age) {
			if (held == null) {
				hold(waiting(level));
			} else if (!held.isAlive() || held.isRemoved() || held.level() != level) {
				return false; // the prisoner died or left: the water falls away
			}
			LivingEntity e = held;
			if (e != null && WaterPrisonGeometry.inside(centre, e.getBoundingBox().getCenter())) {
				e.addEffect(new MobEffectInstance(MobEffects.SLOWNESS, SLOWNESS_TICKS, SLOWNESS_AMPLIFIER, false, false, true), ctx.caster());
				e.setDeltaMovement(WaterPrisonGeometry.pull(centre, e.getBoundingBox().getCenter()));
				e.syncVelocity = true;
				e.needsSync = true;
				e.resetFallDistance();
				if (e.isOnFire()) e.extinguishFire();
				breathe(level, e);
			}
			return age < life;
		}

		/** One tick of held breath: air down by one; drowning only once it is used up; water breathers are immune. */
		private void breathe(ServerLevel level, LivingEntity e) {
			if (e.canBreatheUnderwater() || MobEffectUtil.hasWaterBreathing(e)) return;
			boolean drowns = WaterPrisonGeometry.drowns(air);
			air = WaterPrisonGeometry.air(air);
			e.setAirSupply(air);
			if (drowns) e.hurtServer(level, level.damageSources().drown(), WaterPrisonGeometry.DROWN_DAMAGE);
		}

		/** An empty prison takes the nearest foe that comes inside. */
		private @Nullable LivingEntity waiting(ServerLevel level) {
			double r = WaterPrisonGeometry.RADIUS + WaterPrisonGeometry.HOLD;
			LivingEntity best = null;
			double bestDist = Double.MAX_VALUE;
			for (LivingEntity e : level.getEntitiesOfClass(LivingEntity.class, new AABB(centre, centre).inflate(r + 2), e -> takes(ctx, e))) {
				Vec3 m = e.getBoundingBox().getCenter();
				if (!WaterPrisonGeometry.inside(centre, m)) continue;
				double d = m.distanceToSqr(centre);
				if (d < bestDist) {
					bestDist = d;
					best = e;
				}
			}
			return best;
		}

		@Override
		public String toString() {
			return "WaterPrison.Prison[" + ctx.caster().getScoreboardName() + "]";
		}
	}
}
