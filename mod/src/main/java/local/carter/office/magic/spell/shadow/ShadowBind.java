package local.carter.office.magic.spell.shadow;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import org.jspecify.annotations.Nullable;

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
import local.carter.office.magic.tree.Knob;
import local.carter.office.magic.tree.SpellRank;

import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.phys.Vec3;

/**
 * Shadow Bind (Shadow, slot 3, medium, lingering): shadows root a foe in place.
 *
 * <p>The target is the first creature the look ray meets within {@link BindGeometry#RANGE} (forgiveness
 * {@link BindGeometry#FORGIVENESS}) that the caster can see ({@link ShadowStep#seen}). It takes {@code 4 × P} ({@link Hurt}, so
 * behind {@link TargetFilter}). A creature a control effect may take ({@link TargetFilter#controllable}: hostile mobs and
 * animals, never a boss) is also rooted for {@link BindGeometry#life} = 60 + 40 × charge ticks: Slowness VI (amplifier
 * {@link BindGeometry#SLOWNESS_AMPLIFIER}) and, every tick, held where it stood with its velocity zeroed ({@link BindGeometry#hold},
 * {@link BindGeometry#rooted}: it may fall, never step or jump; its pathing is stopped). A boss is hurt but never held. A
 * teleport (a move of more than {@link BindGeometry#BREAK_DISTANCE} in one tick) breaks the root. From half charge the shadows
 * take a second foe too ({@link BindGeometry#foes}: twice a tap's): the nearest other creature it can root within
 * {@link BindGeometry#SPREAD} of the first and in its sight, hurt and rooted the same way. No block changes.
 *
 * <p>With nothing to bind the shadows still lash out at the ray point (the cast spends as usual, as Water Prison's does).
 *
 * <p>Ranks hold longer (the size knob on the root's life). Forks: III {@link #CHOKING} (rooted foes take {@link #CHOKE_DAMAGE}
 * {@code × P} a second) or {@link #SPREADING} (one more foe: two at a tap, three from half charge); V {@link #LEECH} (rooted foes
 * lose {@link #LEECH_HEALTH} health a second to the caster) or {@link #DREAD} (when the root ends: Blindness and Weakness for
 * {@link #DREAD_TICKS}).
 *
 * <p>Fx: {@code target} = the foe's feet (or the ray point), {@code entities} = {the foe, then the other foes} if any,
 * {@code duration} = the life, {@code data} = {1 if the first is rooted else 0} (a second foe is always rooted).
 */
public final class ShadowBind implements Spell {
	/** Velocity changes are synced every this many ticks (the Gravity Well throttle). */
	static final int SYNC_EVERY = 2;
	/** A velocity change smaller than this is not synced. */
	static final double MIN_SYNC = 0.02;
	public static final String CHOKING = "choking";
	public static final String SPREADING = "spreading";
	public static final String LEECH = "leech";
	public static final String DREAD = "dread";
	public static final float CHOKE_DAMAGE = 1;
	public static final float LEECH_HEALTH = 1;
	public static final int DREAD_TICKS = 100;

	/** The root's life at {@code charge} and {@code rank} (the size knob). */
	public static int life(float charge, SpellRank rank) {
		return Math.round(BindGeometry.life(charge) * rank.mul(Knob.SIZE));
	}

	/** Foes bound at {@code charge} and {@code rank} (Spreading Dark's one more). */
	public static int foes(float charge, SpellRank rank) {
		return BindGeometry.foes(charge) + (rank.is(SPREADING) ? 1 : 0);
	}

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
		int life = life(ctx.charge(), ctx.rank());
		Optional<LivingEntity> target = Targets.ray(ctx, BindGeometry.RANGE, BindGeometry.FORGIVENESS)
				.filter(e -> ShadowStep.seen(ctx.level(), ctx.eye(), e));
		FxEvent fx = ctx.fx().withDuration(life);
		if (target.isEmpty()) {
			return CastOutcome.cast(fx.withTarget(Targets.point(ctx, BindGeometry.RANGE)).withData(0F));
		}
		LivingEntity e = target.get();
		boolean roots = roots(ctx, e);
		// The root is the caster's effect (it counts against their cap), though it holds the foe.
		if (roots && !ActiveEffects.add(ctx.level(), ctx.caster().getUUID(), new Root(ctx, e, life))) return CastOutcome.fizzle(CastService.BUSY_KEY);
		// A charged bind's other foes, picked before the first hit lands (a foe it kills still passes the shadows on).
		List<LivingEntity> more = new ArrayList<>();
		int foes = foes(ctx.charge(), ctx.rank());
		while (more.size() < foes - 1) {
			LivingEntity next = second(ctx, e, more);
			if (next == null) break;
			more.add(next);
		}
		bind(ctx, e, roots, life);
		List<Integer> ids = new ArrayList<>(List.of(e.getId()));
		for (LivingEntity m : more) {
			if (!ActiveEffects.add(ctx.level(), ctx.caster().getUUID(), new Root(ctx, m, life))) break;
			bind(ctx, m, true, life);
			ids.add(m.getId());
		}
		return CastOutcome.cast(fx.withTarget(e.position()).withEntities(ids.stream().mapToInt(Integer::intValue).toArray())
				.withData(roots ? 1F : 0F));
	}

	/** Hurts {@code e} and, when {@code roots}, slows it and cancels the hit's knockback at once. */
	private static void bind(CastContext ctx, LivingEntity e, boolean roots, int life) {
		Hurt.damage(ctx, e, BindGeometry.DAMAGE * ctx.power());
		if (roots && e.isAlive()) {
			e.addEffect(new MobEffectInstance(MobEffects.SLOWNESS, life, BindGeometry.SLOWNESS_AMPLIFIER), ctx.caster());
			Root.hold(e, true);
		}
	}

	/**
	 * The nearest other creature (not {@code first}, none of {@code taken}) the bind can root within {@link BindGeometry#SPREAD} of
	 * {@code first}, in its sight; null if none.
	 */
	private static @Nullable LivingEntity second(CastContext ctx, LivingEntity first, List<LivingEntity> taken) {
		Vec3 from = first.getBoundingBox().getCenter();
		for (LivingEntity e : Targets.radius(ctx, from, BindGeometry.SPREAD)) {
			if (e == first || taken.contains(e) || !roots(ctx, e)) continue;
			if (ShadowStep.seen(ctx.level(), from, e)) return e;
		}
		return null;
	}

	/**
	 * Whether the bind holds {@code e}: a creature a control effect may take ({@link TargetFilter#controllable}: hostile mobs and
	 * animals), never a boss. A boss still takes the damage.
	 */
	static boolean roots(CastContext ctx, LivingEntity e) {
		return TargetFilter.controllable(ctx.caster(), e);
	}

	/** The root: holds the foe where it stood for its life, or until it dies, leaves the level or is teleported. */
	static final class Root implements LingeringEffect {
		private final CastContext ctx;
		private final LivingEntity foe;
		private final int life;
		private final ServerLevel level;
		private Vec3 anchor;

		Root(CastContext ctx, LivingEntity foe, int life) {
			this.ctx = ctx;
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
			if (age % 20 == 0) {
				if (ctx.rank().is(CHOKING)) Hurt.damage(ctx, foe, CHOKE_DAMAGE * ctx.power());
				if (ctx.rank().is(LEECH) && Hurt.damage(ctx, foe, LEECH_HEALTH) && ctx.caster().isAlive()) ctx.caster().heal(LEECH_HEALTH);
			}
			return age < life;
		}

		@Override
		public void end(ServerLevel level) {
			// Dread: the dark it leaves behind.
			if (ctx.rank().is(DREAD) && foe.isAlive() && !foe.isRemoved()) {
				foe.addEffect(new MobEffectInstance(MobEffects.BLINDNESS, DREAD_TICKS, 0), ctx.caster());
				foe.addEffect(new MobEffectInstance(MobEffects.WEAKNESS, DREAD_TICKS, 0), ctx.caster());
			}
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
