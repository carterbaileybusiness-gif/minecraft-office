package local.carter.office.magic.spell.shadow;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

import local.carter.office.magic.cast.CastService;
import local.carter.office.magic.core.ChargeMath;
import local.carter.office.magic.core.Element;
import local.carter.office.magic.core.ManaMath;
import local.carter.office.magic.core.Slots;
import local.carter.office.magic.data.MagicAttachments;
import local.carter.office.magic.spell.ActiveEffects;
import local.carter.office.magic.spell.CastContext;
import local.carter.office.magic.spell.CastOutcome;
import local.carter.office.magic.spell.HitBudget;
import local.carter.office.magic.spell.Hurt;
import local.carter.office.magic.spell.LingeringEffect;
import local.carter.office.magic.spell.RankKit;
import local.carter.office.magic.spell.Spell;
import local.carter.office.magic.spell.SpellCost;
import local.carter.office.magic.spell.SpellIds;
import local.carter.office.magic.spell.TargetFilter;
import local.carter.office.magic.spell.Targets;
import local.carter.office.magic.tree.Knob;
import local.carter.office.magic.tree.SpellRank;

import net.minecraft.core.BlockPos;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.ExperienceOrb;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.item.ItemEntity;

import net.minecraft.world.level.ClipContext;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.CollisionContext;

/**
 * Gravity Well (Shadow, slot 1, medium, lingering): a dark point that drags everything in.
 *
 * <p>The well opens where the look ray meets a block within {@link WellGeometry#RANGE} (or at that range), stepped back off
 * the surface, then dropped to {@link WellGeometry#HEIGHT} above the ground under it ({@link #centre}). It lasts
 * {@link WellGeometry#life} = 80 + 80 × charge ticks. Every tick, everything pushable within {@link WellGeometry#radius} (6 at a
 * tap, 12 at full charge) of the centre that the centre can see (no block collider between the centre and its middle: the well
 * never pulls through walls)
 * gets the pull {@link WellGeometry#pull} as its velocity, scaled by its knockback resistance (at most
 * {@link Targets#MAX_ENTITIES}, nearest first, and at most {@link #MAX_CLIPS} sight checks a tick). What it drags is
 * {@link #pulls}: items, XP orbs, hostile mobs and animals, never a boss. Every {@link WellGeometry#HURT_EVERY} ticks, every
 * creature within {@link WellGeometry#hurtRadius} that it can see takes {@code HURT × (1 + charge)} (a boss too). No block changes; players,
 * pets, villagers, golems, named mobs and the rest are never moved or hurt.
 *
 * <p>Ranks widen the pull (the size knob). Forks: III {@link #DRAIN} (each time it hurts, every creature it hurt gives the
 * caster {@link #DRAIN_MANA} mana, up to half the well's mana cost per cast) or {@link #COLLECTOR} (at its end every item and XP
 * orb within its pull radius that it can see is sent to the caster's feet, at most {@link #COLLECT_MAX}; only if the caster is in
 * the same level within {@link #COLLECT_RANGE}); V {@link #SINGULARITY} (at its end it implodes:
 * {@link #SINGULARITY_DAMAGE}{@code × P} within {@link #SINGULARITY_RADIUS}) or {@link #REPULSOR} (at its end it flings what it
 * held out, {@link #REPULSOR_KNOCK} and {@link #REPULSOR_UP} up).
 *
 * <p>Fx: {@code target} = the centre, {@code duration} = the life in ticks, {@code data[0]} = the pull radius.
 */
public final class GravityWell implements Spell {
	/** Block clips (line-of-sight checks) per tick, at most. */
	static final int MAX_CLIPS = 128;
	/** Pulled velocities are synced to clients every this many ticks. */
	static final int SYNC_EVERY = 2;
	/** A pull shorter than this (something resting in the core) is not synced. */
	static final double MIN_SYNC = 0.02;
	public static final String DRAIN = "drain";
	public static final String COLLECTOR = "collector";
	public static final String SINGULARITY = "singularity";
	public static final String REPULSOR = "repulsor";
	public static final int DRAIN_MANA = 1;
	public static final int COLLECT_MAX = 64;
	public static final double COLLECT_RANGE = 48;
	public static final double SINGULARITY_RADIUS = 4;
	public static final float SINGULARITY_DAMAGE = 8;
	public static final double REPULSOR_KNOCK = 1.6;
	public static final double REPULSOR_UP = 0.6;

	/** The well's life at {@code charge}. */
	public static int life(float charge, SpellRank rank) {
		return WellGeometry.life(charge);
	}

	/** The most mana Drain gives back in one cast: half the well's mana cost at {@code rank}. */
	public static int drainCap(SpellRank rank) {
		return CastService.cost(SpellCost.forSlot(Slots.MEDIUM_A), rank).mana() / 2;
	}

	@Override
	public Identifier id() {
		return SpellIds.GRAVITY_WELL;
	}

	@Override
	public Element element() {
		return Element.SHADOW;
	}

	@Override
	public int slot() {
		return Slots.MEDIUM_A;
	}

	@Override
	public CastOutcome cast(CastContext ctx) {
		Vec3 centre = centre(ctx);
		int life = life(ctx.charge(), ctx.rank());
		double radius = WellGeometry.radius(ctx.charge()) * ctx.rank().mul(Knob.SIZE);
		if (!ActiveEffects.add(ctx, new Well(ctx, centre, life, radius))) return CastOutcome.fizzle("magic.office.busy");
		return CastOutcome.cast(ctx.fx().withTarget(centre).withDuration(life).withData((float) radius));
	}

	/**
	 * Where the well opens: the ray point (where the look meets a block within range, else the end of the range) stepped back
	 * half a block toward the eye, then moved to {@link WellGeometry#HEIGHT} above the ground of that column. If that spot is
	 * inside a block or hidden from the ray point (a low ceiling), the well stays at the stepped-back ray point.
	 */
	static Vec3 centre(CastContext ctx) {
		return hover(ctx, WellGeometry.RANGE, WellGeometry.HEIGHT);
	}

	/**
	 * A point {@code height} above the ground under the look ray's point within {@code range}, as {@link #centre} (Gravity Well and
	 * Black Hole both open this way).
	 */
	static Vec3 hover(CastContext ctx, double range, double height) {
		ServerLevel level = ctx.level();
		Vec3 eye = ctx.eye();
		Vec3 hit = Targets.point(ctx, range);
		Vec3 back = eye.subtract(hit);
		double len = back.length();
		Vec3 p = len < 1.0E-6 ? hit : hit.add(back.scale(Math.min(0.5, len) / len));
		Vec3 ground = Targets.ground(level, p);
		Vec3 c = new Vec3(p.x, ground.y + height, p.z);
		BlockPos cell = BlockPos.containing(c);
		if (!level.getBlockState(cell).getCollisionShape(level, cell).isEmpty() || !clear(level, p, c)) return p;
		return c;
	}

	/**
	 * What the well (and Black Hole) drags: {@link TargetFilter#controllable}, so items, XP orbs, hostile mobs and animals, never a
	 * boss (bosses are hurt, never dragged), and never golems, pets, villagers, named mobs or players.
	 */
	static boolean pulls(CastContext ctx, Entity e) {
		return TargetFilter.controllable(ctx.caster(), e);
	}

	/** True when no block collider stands between {@code a} and {@code b}. */
	static boolean clear(ServerLevel level, Vec3 a, Vec3 b) {
		if (a.distanceToSqr(b) < 1.0E-8) return true;
		return level.clip(new ClipContext(a, b, ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, CollisionContext.empty()))
				.getType() == HitResult.Type.MISS;
	}

	/** The running well: pulls every tick, hurts near the core every {@link WellGeometry#HURT_EVERY} ticks. */
	static final class Well implements LingeringEffect {
		private final CastContext ctx;
		private final Vec3 centre;
		private final int life;
		private final double radius;
		/** At most {@code Targets.MAX_ENTITIES} different creatures hurt over its life (the per-cast budget). */
		private final HitBudget hurtBudget = new HitBudget();
		/** Drain: mana given back so far. */
		private int drained;


		Well(CastContext ctx, Vec3 centre, int life, double radius) {
			this.ctx = ctx;
			this.centre = centre;
			this.life = life;
			this.radius = radius;
		}

		@Override
		public boolean tick(ServerLevel level, int age) {
			// The well never loads a chunk: it closes if its own goes away.
			if (age > life || !level.isLoaded(BlockPos.containing(centre))) return false;
			List<Entity> caught = new ArrayList<>(level.getEntities((Entity) null, new AABB(centre, centre).inflate(radius),
					e -> pulls(ctx, e) && WellGeometry.inReach(centre, e.getBoundingBox().getCenter(), radius)));
			caught.sort(Comparator.comparingDouble(e -> e.getBoundingBox().getCenter().distanceToSqr(centre)));
			int n = 0;
			int clips = 0;
			boolean sync = age % SYNC_EVERY == 0;
			for (Entity e : caught) {
				if (n >= Targets.MAX_ENTITIES || clips >= MAX_CLIPS) break;
				Vec3 mid = e.getBoundingBox().getCenter();
				clips++;
				if (!clear(level, centre, mid)) continue;
				n++;
				double scale = 1;
				if (e instanceof LivingEntity l) scale = Math.max(0, 1 - l.getAttributeValue(Attributes.KNOCKBACK_RESISTANCE));
				Vec3 pull = WellGeometry.pull(centre, mid).scale(scale);
				e.setDeltaMovement(pull);
				e.resetFallDistance();
				// Velocity packets every other tick, and none for something already resting in the core.
				if (sync && pull.length() >= MIN_SYNC) {
					e.syncVelocity = true;
					e.needsSync = true;
				}
			}
			if (age % WellGeometry.HURT_EVERY == 0) {
				for (LivingEntity l : Targets.radius(ctx, centre, WellGeometry.hurtRadius(ctx.charge()))) {
					if (clips >= MAX_CLIPS) break;
					clips++;
					if (clear(level, centre, l.getBoundingBox().getCenter()) && hurtBudget.admit(l)
							&& Hurt.damage(ctx, l, WellGeometry.HURT * ChargeMath.size(ctx.charge()) * ctx.rank().mul(Knob.DAMAGE))) {
						drain();
					}
				}
			}
			if (age >= life) {
				if (ctx.rank().is(COLLECTOR)) collect(level);
				if (ctx.rank().is(SINGULARITY)) {
					RankKit.blast(ctx, centre, SINGULARITY_RADIUS, SINGULARITY_DAMAGE * ctx.power(), null, 0, 0, 0);
				}
				if (ctx.rank().is(REPULSOR)) RankKit.blast(ctx, centre, radius, 0, null, 0, REPULSOR_KNOCK, REPULSOR_UP);
			}
			return age < life;
		}

		/** Drain: one hurt creature's mana to the caster, up to {@link #drainCap} per cast. */
		private void drain() {
			if (!ctx.rank().is(DRAIN) || drained >= drainCap(ctx.rank())) return;
			ServerPlayer p = ctx.caster();
			if (p.isRemoved() || !p.isAlive()) return;
			int max = ManaMath.max(MagicAttachments.get(p).level());
			int now = MagicAttachments.mana(p);
			int add = Math.min(DRAIN_MANA, Math.min(drainCap(ctx.rank()) - drained, max - now));
			if (add <= 0) return;
			MagicAttachments.setMana(p, now + add);
			drained += add;
		}

		/** Collector: what it held (items, XP orbs) goes to the caster's feet. */
		private void collect(ServerLevel level) {
			ServerPlayer p = ctx.caster();
			if (p.isRemoved() || p.level() != level || p.distanceToSqr(centre) > COLLECT_RANGE * COLLECT_RANGE) return;
			int n = 0;
			for (Entity e : level.getEntities((Entity) null, new AABB(centre, centre).inflate(radius),
					e -> (e instanceof ItemEntity || e instanceof ExperienceOrb) && !e.isRemoved()
							&& e.getBoundingBox().getCenter().distanceToSqr(centre) <= radius * radius)) {
				if (n++ >= COLLECT_MAX) break;
				if (!clear(level, centre, e.getBoundingBox().getCenter())) continue;
				e.teleportTo(p.getX(), p.getY() + 0.2, p.getZ());
				e.setDeltaMovement(Vec3.ZERO);
				e.syncVelocity = true;
				e.needsSync = true;
			}
		}

		@Override
		public String toString() {
			return "GravityWell.Well[" + ctx.caster().getScoreboardName() + " at " + centre + "]";
		}
	}
}
