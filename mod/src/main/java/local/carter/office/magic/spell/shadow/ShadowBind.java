package local.carter.office.magic.spell.shadow;

import java.util.Optional;

import local.carter.office.magic.cast.CastService;
import local.carter.office.magic.core.Element;
import local.carter.office.magic.core.Slots;
import local.carter.office.magic.net.FxEvent;
import local.carter.office.magic.spell.ActiveEffects;
import local.carter.office.magic.spell.CastContext;
import local.carter.office.magic.spell.CastOutcome;
import local.carter.office.magic.spell.Hurt;
import local.carter.office.magic.spell.LingeringEffect;
import local.carter.office.magic.spell.Spell;
import local.carter.office.magic.spell.SpellIds;
import local.carter.office.magic.spell.TargetFilter;
import local.carter.office.magic.spell.Targets;

import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.TagKey;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.boss.enderdragon.EnderDragon;
import net.minecraft.world.entity.boss.wither.WitherBoss;
import net.minecraft.world.entity.monster.Enemy;
import net.minecraft.world.entity.monster.warden.Warden;
import net.minecraft.world.phys.Vec3;

/**
 * Shadow Bind (Shadow, slot 3, medium, lingering): shadows root a foe in place.
 *
 * <p>The target is the first creature the look ray meets within {@link BindGeometry#RANGE} (forgiveness
 * {@link BindGeometry#FORGIVENESS}) that the caster can see ({@link ShadowStep#seen}). It takes {@code 4 × P} ({@link Hurt}, so
 * behind {@link TargetFilter}). A hostile mob ({@link Enemy}) is also rooted for {@link BindGeometry#life} = 60 + 40 × charge
 * ticks: Slowness VI (amplifier {@link BindGeometry#SLOWNESS_AMPLIFIER}) and, every tick, held where it stood with its velocity
 * zeroed ({@link BindGeometry#hold}, {@link BindGeometry#rooted}: it may fall, never step or jump; its pathing is stopped). The
 * lane rule: control effects touch only hostile mobs, so livestock and the like are hurt but never held. A teleport (a move of
 * more than {@link BindGeometry#BREAK_DISTANCE} in one tick) breaks the root. No block changes.
 *
 * <p>With nothing to bind the shadows still lash out at the ray point (the cast spends as usual, as Water Prison's does).
 *
 * <p>Fx: {@code target} = the foe's feet (or the ray point), {@code entities} = {the foe} if any, {@code duration} = the life,
 * {@code data} = {1 if rooted else 0}.
 */
public final class ShadowBind implements Spell {
	/** Velocity changes are synced every this many ticks (the Gravity Well throttle). */
	static final int SYNC_EVERY = 2;
	/** A velocity change smaller than this is not synced. */
	static final double MIN_SYNC = 0.02;

	@Override
	public Identifier id() {
		return SpellIds.SHADOW_BIND;
	}

	@Override
	public Element element() {
		return Element.SHADOW;
	}

	@Override
	public int slot() {
		return Slots.MEDIUM_B;
	}

	@Override
	public CastOutcome cast(CastContext ctx) {
		int life = BindGeometry.life(ctx.charge());
		Optional<LivingEntity> target = Targets.ray(ctx, BindGeometry.RANGE, BindGeometry.FORGIVENESS)
				.filter(e -> ShadowStep.seen(ctx.level(), ctx.eye(), e));
		FxEvent fx = ctx.fx().withDuration(life);
		if (target.isEmpty()) {
			return CastOutcome.cast(fx.withTarget(Targets.point(ctx, BindGeometry.RANGE)).withData(0F));
		}
		LivingEntity e = target.get();
		boolean roots = roots(e);
		// The root is the caster's effect (it counts against their cap), though it holds the foe.
		if (roots && !ActiveEffects.add(ctx.level(), ctx.caster().getUUID(), new Root(e, life))) return CastOutcome.fizzle(CastService.BUSY_KEY);
		Hurt.damage(ctx, e, BindGeometry.DAMAGE * ctx.power());
		if (roots && e.isAlive()) {
			e.addEffect(new MobEffectInstance(MobEffects.SLOWNESS, life, BindGeometry.SLOWNESS_AMPLIFIER), ctx.caster());
			// The hit's knockback is cancelled at once.
			Root.hold(e, true);
		}
		return CastOutcome.cast(fx.withTarget(e.position()).withEntities(e.getId()).withData(roots ? 1F : 0F));
	}

	/** The conventional {@code #c:bosses} entity tag (Fabric's convention tags fill it when present). */
	static final TagKey<EntityType<?>> BOSSES = TagKey.create(Registries.ENTITY_TYPE, Identifier.fromNamespaceAndPath("c", "bosses"));

	/**
	 * Whether the bind holds {@code e}: hostile mobs only (the lane's control rule), never a boss (ruling: {@code #c:bosses}, and the
	 * Wither, Ender Dragon and Warden by name in case the tag is empty). A boss still takes the damage.
	 */
	static boolean roots(LivingEntity e) {
		return e instanceof Enemy && !TargetFilter.isProtected(e) && !boss(e);
	}

	static boolean boss(LivingEntity e) {
		return e.getType().builtInRegistryHolder().is(BOSSES) || e instanceof WitherBoss || e instanceof EnderDragon || e instanceof Warden;
	}

	/** The root: holds the foe where it stood for its life, or until it dies, leaves the level or is teleported. */
	static final class Root implements LingeringEffect {
		private final LivingEntity foe;
		private final int life;
		private final ServerLevel level;
		private Vec3 anchor;

		Root(LivingEntity foe, int life) {
			this.foe = foe;
			this.life = life;
			this.level = (ServerLevel) foe.level();
			this.anchor = foe.position();
		}

		@Override
		public boolean tick(ServerLevel level, int age) {
			if (age > life || !foe.isAlive() || foe.isRemoved() || foe.level() != this.level) return false;
			Vec3 now = foe.position();
			if (BindGeometry.breaks(anchor, now)) return false;
			anchor = BindGeometry.hold(anchor, now);
			if (now.distanceToSqr(anchor) > 1.0E-8) foe.setPos(anchor);
			hold(foe, age % SYNC_EVERY == 0);
			return age < life;
		}

		/** Zeroes {@code e}'s velocity (keeping a fall) and stops its pathing; syncs the change when {@code sync} and it is not tiny. */
		static void hold(LivingEntity e, boolean sync) {
			Vec3 was = e.getDeltaMovement();
			Vec3 v = BindGeometry.rooted(e.getDeltaMovement());
			e.setDeltaMovement(v);
			e.setJumping(false);
			if (e instanceof Mob m) m.getNavigation().stop();
			if (sync && was.subtract(v).length() >= MIN_SYNC) {
				e.syncVelocity = true;
				e.needsSync = true;
			}
		}

		@Override
		public String toString() {
			return "ShadowBind.Root[" + foe + "]";
		}
	}
}
