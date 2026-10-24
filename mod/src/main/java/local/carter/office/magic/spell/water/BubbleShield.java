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
import local.carter.office.magic.spell.LingeringEffect;
import local.carter.office.magic.spell.Spell;
import local.carter.office.magic.spell.SpellIds;

import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
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
 * <p>For {@link BubbleShieldGeometry#duration} ticks (120 + 60 × charge) the caster has Resistance I, and a bubble of
 * {@link BubbleShieldGeometry#RADIUS} about their middle follows them: every tick, each projectile not the caster's own that
 * is inside it or about to cross into it ({@link BubbleShieldGeometry#threatens}) is stopped. Stopped projectiles are
 * removed, except a thrown trident, which is turned back and drops by the bubble so nobody loses it. Arrows stuck in the
 * ground, firework rockets boosting someone's elytra, ender pearls, thrown potions and bottles o' enchanting pass through (a
 * friend's pearl, splash heal or XP is never lost to the bubble). No block changes. A new bubble replaces the
 * caster's old one. It ends early if the caster dies, leaves or changes dimension.
 *
 * <p>Fx: {@code target} = the bubble's centre at the cast, {@code duration} = its life, {@code data[0]} = its radius.
 */
public final class BubbleShield implements Spell {
	/** Resistance I. */
	public static final int RESISTANCE_AMPLIFIER = 0;
	/** What is left of a trident's speed when the bubble turns it back. */
	public static final double TRIDENT_BOUNCE = 0.15;

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
		int life = BubbleShieldGeometry.duration(ctx.charge());
		Bubble bubble = new Bubble(ctx, life);
		if (!ActiveEffects.add(ctx.level(), bubble)) return CastOutcome.fizzle("magic.office.busy");
		Bubble old = ACTIVE.put(caster.getUUID(), bubble);
		if (old != null) ActiveEffects.remove(old);
		caster.addEffect(new MobEffectInstance(MobEffects.RESISTANCE, life, RESISTANCE_AMPLIFIER, false, false, true), caster);
		Vec3 centre = BubbleShieldGeometry.centre(caster.position(), caster.getBbHeight());
		return CastOutcome.cast(ctx.fx().withTarget(centre).withDuration(life).withData((float) BubbleShieldGeometry.RADIUS));
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
		int stopped;

		Bubble(CastContext ctx, int life) {
			this.ctx = ctx;
			this.life = life;
		}

		@Override
		public boolean tick(ServerLevel level, int age) {
			ServerPlayer p = ctx.caster();
			if (p.isRemoved() || !p.isAlive() || p.level() != level) return false;
			Vec3 centre = BubbleShieldGeometry.centre(p.position(), p.getBbHeight());
			AABB near = new AABB(centre, centre).inflate(BubbleShieldGeometry.RADIUS + BubbleShieldGeometry.SEARCH);
			for (Projectile pr : level.getEntitiesOfClass(Projectile.class, near, pr -> !pr.isRemoved() && pr.getOwner() != p)) {
				if (stops(pr) && BubbleShieldGeometry.threatens(centre, pr.position(), pr.getDeltaMovement())) {
					stop(pr);
					stopped++;
				}
			}
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
