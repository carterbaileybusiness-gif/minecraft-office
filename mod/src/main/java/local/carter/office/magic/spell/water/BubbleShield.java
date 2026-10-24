package local.carter.office.magic.spell.water;

import java.lang.reflect.Method;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

import org.jspecify.annotations.Nullable;
import org.slf4j.LoggerFactory;

import local.carter.office.magic.core.Element;
import local.carter.office.magic.core.Slots;
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
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.LivingEntity;

import net.minecraft.world.entity.projectile.FireworkRocketEntity;
import net.minecraft.world.entity.projectile.Projectile;
import net.minecraft.world.entity.projectile.arrow.AbstractArrow;
import net.minecraft.world.entity.projectile.arrow.ThrownTrident;
import net.minecraft.world.entity.projectile.throwableitemprojectile.AbstractThrownPotion;
import net.minecraft.world.entity.projectile.throwableitemprojectile.ThrownEnderpearl;
import net.minecraft.world.entity.projectile.throwableitemprojectile.ThrownExperienceBottle;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

/**
 * Bubble Shield (Water, slot 2, small, lingering): a bubble that stops arrows and softens blows.
 *
 * <p>For {@link BubbleShieldGeometry#duration} ticks (120 + 120 × charge) the caster has Resistance I, and a bubble of
 * {@link BubbleShieldGeometry#radius} (2.5 at a tap, 5 at full charge) about their middle follows them: every tick, each projectile not the caster's own that
 * is inside it or about to cross into it ({@link BubbleShieldGeometry#threatens}) is stopped. Stopped projectiles are
 * removed, except a thrown trident, which is turned back and drops by the bubble so nobody loses it. Arrows stuck in the
 * ground, firework rockets boosting someone's elytra, ender pearls, thrown potions and bottles o' enchanting pass through (a
 * friend's pearl, splash heal or XP is never lost to the bubble). No block changes. A new bubble replaces the
 * caster's old one. It ends early if the caster dies, leaves or changes dimension.
 *
 * <p>Ranks grow the bubble (the size knob). Forks: III {@link #MIRROR} (an arrow it stops from a shooter spells may hurt
 * ({@link #mirrorTarget}) is sent back as a hit on that shooter: {@link #MIRROR_DAMAGE}{@code × P} through {@code Hurt.damage},
 * so a reflection can never touch a player, a pet, a villager or anything else protected; the arrow itself is removed, and
 * fireballs, skulls, rockets and potions are only stopped) or {@link #MENDING} (each projectile it stops heals the caster
 * {@link #MENDING_HEAL}); V {@link #FORTRESS} (lasts {@link #FORTRESS_LIFE}× as long and shoves creatures that touch it back out)
 * or {@link #POP} (when it runs out it bursts: {@link #POP_DAMAGE}{@code × P} and a push within its radius). No fork adds a
 * status effect (no self-buff outlasts the cooldown).
 *
 * <p>Fx: {@code target} = the bubble's centre at the cast, {@code duration} = its life, {@code data[0]} = its radius.
 */
public final class BubbleShield implements Spell {
	/** Resistance I. */
	public static final int RESISTANCE_AMPLIFIER = 0;
	/** What is left of a trident's speed when the bubble turns it back. */
	public static final double TRIDENT_BOUNCE = 0.15;
	public static final String MIRROR = "mirror";
	public static final String MENDING = "mending";
	public static final String FORTRESS = "fortress";
	public static final String POP = "pop";
	/** Mirror's return hit on the shooter, × P, and how far away a shooter may be. */
	public static final float MIRROR_DAMAGE = 4;
	public static final double MIRROR_RANGE = 32;
	/** Mending: health back per projectile stopped (one heart). */
	public static final float MENDING_HEAL = 2;
	public static final float FORTRESS_LIFE = 1.5F;
	/** Fortress: how hard (and how high) it shoves out what touches it, every {@link #FORTRESS_EVERY} ticks. */
	public static final double FORTRESS_SHOVE = 0.7;
	public static final double FORTRESS_SHOVE_UP = 0.25;
	public static final int FORTRESS_EVERY = 5;
	public static final float POP_DAMAGE = 4;
	public static final double POP_PUSH = 1.0;

	/** {@code AbstractArrow.isInGround} and {@code FireworkRocketEntity.isAttachedToEntity} are not public; 26.x runs unobfuscated. */
	private static final @Nullable Method IN_GROUND = method(AbstractArrow.class, "isInGround");
	private static final @Nullable Method ATTACHED = method(FireworkRocketEntity.class, "isAttachedToEntity");

	/** Each caster's running bubble (server thread only). */
	private static final Map<UUID, Bubble> ACTIVE = new HashMap<>();

	@Override
	public Identifier id() {
		return SpellIds.BUBBLE_SHIELD;
	}

	@Override
	public Element element() {
		return Element.WATER;
	}

	@Override
	public int slot() {
		return Slots.SMALL_B;
	}

	@Override
	public CastOutcome cast(CastContext ctx) {
		ServerPlayer caster = ctx.caster();
		int life = Math.round(BubbleShieldGeometry.duration(ctx.charge()) * (ctx.rank().is(FORTRESS) ? FORTRESS_LIFE : 1));
		double radius = BubbleShieldGeometry.radius(ctx.charge()) * ctx.rank().mul(Knob.SIZE);
		Bubble bubble = new Bubble(ctx, life, radius);
		// at the per-caster cap, the bubble being refreshed frees its own slot first
		Bubble current = ACTIVE.get(caster.getUUID());
		if (current != null && ActiveEffects.count(caster.getUUID()) >= ActiveEffects.MAX_PER_CASTER) ActiveEffects.remove(current);
		if (!ActiveEffects.add(ctx, bubble)) return CastOutcome.fizzle("magic.office.busy");
		Bubble old = ACTIVE.put(caster.getUUID(), bubble);
		if (old != null) ActiveEffects.remove(old);
		caster.addEffect(new MobEffectInstance(MobEffects.RESISTANCE, life, RESISTANCE_AMPLIFIER, false, false, true), caster);
		Vec3 centre = BubbleShieldGeometry.centre(caster.position(), caster.getBbHeight());
		return CastOutcome.cast(ctx.fx().withTarget(centre).withDuration(life).withData((float) radius));
	}

	/** Whether {@code caster} has a bubble up (the game tests read it). */
	public static boolean active(ServerPlayer caster) {
		Bubble b = ACTIVE.get(caster.getUUID());
		return b != null && ActiveEffects.running(b);
	}

	/**
	 * Whether the bubble stops {@code p} at all: not an arrow stuck in the ground, a rocket boosting an elytra, an ender pearl, a
	 * thrown potion or a bottle o' enchanting (the client fx asks too, for the ripples). A trident is stopped by turning it back,
	 * see {@link #stop}.
	 */
	public static boolean stops(Projectile p) {
		if (p instanceof ThrownEnderpearl || p instanceof AbstractThrownPotion || p instanceof ThrownExperienceBottle) return false;
		if (p instanceof AbstractArrow a && flag(IN_GROUND, a)) return false;
		return !(p instanceof FireworkRocketEntity r && flag(ATTACHED, r));
	}


	/**
	 * Who Mirror sends {@code p} back to: its shooter, when {@code p} is an arrow (not a trident: that is turned back and dropped;
	 * never a fireball, skull, rocket or potion, which would burst as the caster's) shot by a creature spells from
	 * {@code caster} may hurt ({@code TargetFilter.affects}: never a player, pet, villager, golem or named mob) within
	 * {@link #MIRROR_RANGE}. Null otherwise. The client fx asks too (with a null caster) to draw the return.
	 */
	public static @Nullable LivingEntity mirrorTarget(Projectile p, @Nullable ServerPlayer caster) {
		if (!(p instanceof AbstractArrow) || p instanceof ThrownTrident) return null;
		if (!(p.getOwner() instanceof LivingEntity shooter) || shooter.level() != p.level()) return null;
		if (!TargetFilter.affects(caster, shooter) || shooter.distanceToSqr(p) > MIRROR_RANGE * MIRROR_RANGE) return null;
		return shooter;
	}

	/** Stops {@code p}: a trident is turned back (it drops by the bubble), anything else is removed. */
	static void stop(Projectile p) {

		if (p instanceof ThrownTrident) {
			p.setDeltaMovement(p.getDeltaMovement().scale(-TRIDENT_BOUNCE));
			p.syncVelocity = true;
			p.needsSync = true;
		} else {
			p.discard();
		}
	}

	private static @Nullable Method method(Class<?> owner, String name) {
		try {
			Method m = owner.getDeclaredMethod(name);
			m.setAccessible(true);
			return m;
		} catch (ReflectiveOperationException | RuntimeException e) {
			LoggerFactory.getLogger("office").warn("[office] {}.{} not found; Bubble Shield may stop stuck arrows or elytra rockets",
					owner.getSimpleName(), name, e);
			return null;
		}
	}

	private static boolean flag(@Nullable Method m, Object target) {
		try {
			return m != null && (boolean) m.invoke(target);
		} catch (ReflectiveOperationException | RuntimeException e) {
			return false;
		}
	}

	/** The bubble: follows the caster and stops what flies at them. */
	static final class Bubble implements LingeringEffect {
		private final CastContext ctx;
		private final int life;
		private final double radius;
		int stopped;

		Bubble(CastContext ctx, int life, double radius) {
			this.ctx = ctx;
			this.life = life;
			this.radius = radius;
		}

		@Override
		public boolean tick(ServerLevel level, int age) {
			ServerPlayer p = ctx.caster();
			if (p.isRemoved() || !p.isAlive() || p.level() != level) return false;
			Vec3 centre = BubbleShieldGeometry.centre(p.position(), p.getBbHeight());
			AABB near = new AABB(centre, centre).inflate(radius + BubbleShieldGeometry.SEARCH);
			for (Projectile pr : level.getEntitiesOfClass(Projectile.class, near, pr -> !pr.isRemoved() && pr.getOwner() != p)) {
				if (stops(pr) && BubbleShieldGeometry.threatens(centre, pr.position(), pr.getDeltaMovement(), radius)) {
					// Mirror: read the shooter before the arrow goes; the return is a hit through Hurt.damage, never a live projectile.
					LivingEntity back = ctx.rank().is(MIRROR) ? mirrorTarget(pr, p) : null;
					stop(pr);
					stopped++;
					if (back != null) Hurt.damage(ctx, back, MIRROR_DAMAGE * ctx.power());
					if (ctx.rank().is(MENDING) && p.isAlive()) p.heal(MENDING_HEAL);
				}
			}
			// Fortress: what touches the bubble is shoved back out (a control effect: never a boss).
			if (ctx.rank().is(FORTRESS) && age % FORTRESS_EVERY == 0) {
				for (LivingEntity e : Targets.radius(ctx, centre, radius + 0.3)) {
					if (TargetFilter.controllable(p, e) && Hurt.admit(ctx, e)) RankKit.throwFrom(e, centre, FORTRESS_SHOVE, FORTRESS_SHOVE_UP);
				}
			}
			// Pop: run out, it bursts outward.
			if (age >= life && ctx.rank().is(POP)) RankKit.blast(ctx, centre, radius, POP_DAMAGE * ctx.power(), null, 0, POP_PUSH, 0.3);
			return age < life;
		}

		@Override
		public void end(ServerLevel level) {
			ACTIVE.remove(ctx.caster().getUUID(), this);
		}

		@Override
		public String toString() {
			return "BubbleShield.Bubble[" + ctx.caster().getScoreboardName() + "]";
		}
	}
}
