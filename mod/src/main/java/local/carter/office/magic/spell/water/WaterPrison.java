package local.carter.office.magic.spell.water;

import java.util.Optional;

import org.jspecify.annotations.Nullable;

import local.carter.office.magic.core.Element;
import local.carter.office.magic.core.Slots;
import local.carter.office.magic.net.FxEvent;
import local.carter.office.magic.spell.ActiveEffects;
import local.carter.office.magic.spell.CastContext;
import local.carter.office.magic.spell.CastOutcome;
import local.carter.office.magic.spell.Hurt;
import local.carter.office.magic.spell.LingeringEffect;
import local.carter.office.magic.spell.RankKit;
import local.carter.office.magic.spell.Spell;
import local.carter.office.magic.spell.SpellIds;
import local.carter.office.magic.spell.TargetFilter;
import local.carter.office.magic.spell.Targets;
import local.carter.office.magic.tree.Knob;

import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffectUtil;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

/**
 * Water Prison (Water, slot 3, medium, lingering): trap a foe in a sphere of water.
 *
 * <p>The cast looks along the caster's view for the first creature a control effect may take ({@link #takes}: hostile mobs and
 * animals, never golems, pets, villagers, named mobs or a boss) within {@link WaterPrisonGeometry#RANGE}, in sight (the ray stops
 * at the first solid block). A sphere of {@link WaterPrisonGeometry#radius} (2 at a tap, 3.5 at full charge) forms about its
 * middle for {@link WaterPrisonGeometry#duration} ticks (100 + 100 × charge). If the ray finds no foe, the sphere forms where the ray ends (backed off a wall by its radius) and
 * takes the first creature that comes inside.
 *
 * <p>While its prisoner is inside ({@link WaterPrisonGeometry#inside}), every tick it gets Slowness III, is pulled to the
 * centre ({@link WaterPrisonGeometry#pull}: it hangs in the water), stops burning and loses air as if under water
 * ({@link WaterPrisonGeometry#air}): drowning damage comes only once its air is used up, and creatures that breathe water are
 * immune. A prisoner that dies ends the prison.
 *
 * <p><b>No world change</b>: temporary liquids are forbidden (the temp-safe ruling), so the water is the client's: ghost water
 * cells in the sphere's air cells ({@link WaterPrisonGeometry#fill}).
 *
 * <p>Ranks grow the sphere (the size knob). Forks: III {@link #CRUSHING} (the prisoner takes {@link #CRUSH_DAMAGE}{@code × P} a
 * second) or {@link #LONG_HOLD} (lasts {@link #LONG_HOLD_LIFE}× as long); V {@link #TWIN} (a second sphere takes the nearest other
 * creature it may take within {@link #TWIN_RANGE} of the first) or {@link #HURL} (when it bursts it flings its prisoner the way the
 * caster is looking, {@link #HURL_KNOCK} and {@link #HURL_UP} up).
 *
 * <p>Fx: {@code target} = the sphere's centre, {@code duration} = its life, {@code entities} = the prisoner (none if it waits),
 * then Twin Prison's second prisoner, {@code data} = {@code {radius}}, or {@code {radius, x, y, z}} with Twin Prison's second
 * centre.
 */
public final class WaterPrison implements Spell {
	/** Slowness III. */
	public static final int SLOWNESS_AMPLIFIER = 2;
	/** The slowness is laid for this long and renewed each tick inside, so it ends soon after the prisoner is free. */
	public static final int SLOWNESS_TICKS = 10;
	public static final String CRUSHING = "crushing";
	public static final String LONG_HOLD = "long_hold";
	public static final String TWIN = "twin";
	public static final String HURL = "hurl";
	public static final float CRUSH_DAMAGE = 1.5F;
	public static final float LONG_HOLD_LIFE = 1.6F;
	public static final double TWIN_RANGE = 8;
	public static final double HURL_KNOCK = 1.6;
	public static final double HURL_UP = 0.6;

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
		double radius = WaterPrisonGeometry.radius(ctx.charge()) * ctx.rank().mul(Knob.SIZE);
		Vec3 centre;
		if (target != null) {
			centre = target.getBoundingBox().getCenter();
		} else {
			double back = wall.getType() == HitResult.Type.MISS ? 0 : Math.min(radius, end.distanceTo(eye));
			centre = end.subtract(look.scale(back));
		}
		int life = Math.round(WaterPrisonGeometry.duration(ctx.charge()) * (ctx.rank().is(LONG_HOLD) ? LONG_HOLD_LIFE : 1));
		Prison prison = new Prison(ctx, centre, radius, life, target);
		if (!ActiveEffects.add(ctx, prison)) return CastOutcome.fizzle("magic.office.busy");
		int[] held = target == null ? new int[0] : new int[] {target.getId()};
		FxEvent fx = ctx.fx().withTarget(centre).withDuration(life).withEntities(held).withData((float) radius);

		// Twin Prison: a second sphere on the nearest other creature it may take near the first.
		LivingEntity second = target != null && ctx.rank().is(TWIN) ? second(ctx, target) : null;
		if (second != null) {
			Vec3 c2 = second.getBoundingBox().getCenter();
			if (ActiveEffects.add(ctx, new Prison(ctx, c2, radius, life, second))) {
				fx = fx.withEntities(target.getId(), second.getId()).withData((float) radius, (float) c2.x, (float) c2.y, (float) c2.z);
			}
		}
		return CastOutcome.cast(fx);
	}

	/** The nearest creature other than {@code first} the prison may take within {@link #TWIN_RANGE} of it, in its sight. */
	private static @Nullable LivingEntity second(CastContext ctx, LivingEntity first) {
		Vec3 from = first.getBoundingBox().getCenter();
		for (LivingEntity e : Targets.radius(ctx, from, TWIN_RANGE)) {
			if (e != first && takes(ctx.caster(), e) && RankKit.reaches(ctx.level(), from, e.getBoundingBox())) return e;
		}
		return null;
	}

	/**
	 * Whether the prison may take {@code e}: a creature the caster's spells may touch, never a boss (it roots and pulls, a control
	 * effect: {@link TargetFilter#controllable}).
	 */
	public static boolean takes(ServerPlayer caster, Entity e) {
		return e instanceof LivingEntity && TargetFilter.controllable(caster, e);
	}

	/** The nearest creature it may take whose hitbox (inflated by {@link WaterPrisonGeometry#FORGIVENESS}) the ray {@code eye → end} passes through. */
	private static Optional<LivingEntity> foeOnRay(CastContext ctx, Vec3 eye, Vec3 end) {
		AABB sweep = new AABB(eye, end).inflate(WaterPrisonGeometry.FORGIVENESS + 1);
		LivingEntity best = null;
		double bestDist = Double.MAX_VALUE;
		for (LivingEntity e : ctx.level().getEntitiesOfClass(LivingEntity.class, sweep, e -> takes(ctx.caster(), e))) {
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
		private final double radius;
		private final int life;
		private @Nullable LivingEntity held;
		/** The prisoner's air as the prison keeps it (vanilla would refill it each tick out of water). */
		private int air;

		Prison(CastContext ctx, Vec3 centre, double radius, int life, @Nullable LivingEntity target) {
			this.ctx = ctx;
			this.centre = centre;
			this.radius = radius;
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
			if (e != null && WaterPrisonGeometry.inside(centre, e.getBoundingBox().getCenter(), radius)) {
				e.addEffect(new MobEffectInstance(MobEffects.SLOWNESS, SLOWNESS_TICKS, SLOWNESS_AMPLIFIER, false, false, true), ctx.caster());
				e.setDeltaMovement(WaterPrisonGeometry.pull(centre, e.getBoundingBox().getCenter()));
				e.syncVelocity = true;
				e.needsSync = true;
				e.resetFallDistance();
				if (e.isOnFire()) e.extinguishFire();
				breathe(level, e);
				// Crushing Depths: the deep presses in, once a second.
				if (age % 20 == 0 && ctx.rank().is(CRUSHING)) Hurt.damage(ctx, e, CRUSH_DAMAGE * ctx.power());
				// Hurl: the burst flings the prisoner where the caster looks (it was held, so it may be moved).
				if (age >= life && ctx.rank().is(HURL)) Hurt.knock(e, ctx.caster().getLookAngle(), HURL_KNOCK, HURL_UP);
			}
			return age < life;
		}


		/** One tick of held breath: air down by {@link WaterPrisonGeometry#AIR_PER_TICK} (three times vanilla, so a full breath runs out within the prison); drowning only once it is used up; water breathers are immune. */
		private void breathe(ServerLevel level, LivingEntity e) {
			if (e.canBreatheUnderwater() || MobEffectUtil.hasWaterBreathing(e)) return;
			boolean drowns = WaterPrisonGeometry.drowns(air);
			air = WaterPrisonGeometry.air(air);
			e.setAirSupply(air);
			if (drowns) e.hurtServer(level, level.damageSources().drown(), WaterPrisonGeometry.DROWN_DAMAGE);
		}

		/** An empty prison takes the nearest creature that comes inside. */
		private @Nullable LivingEntity waiting(ServerLevel level) {
			double r = radius + WaterPrisonGeometry.HOLD;
			LivingEntity best = null;
			double bestDist = Double.MAX_VALUE;
			for (LivingEntity e : level.getEntitiesOfClass(LivingEntity.class, new AABB(centre, centre).inflate(r + 2), e -> takes(ctx.caster(), e))) {
				Vec3 m = e.getBoundingBox().getCenter();
				if (!WaterPrisonGeometry.inside(centre, m, radius)) continue;
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
