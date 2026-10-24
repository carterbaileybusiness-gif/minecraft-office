package local.carter.office.magic.spell.shadow;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

import local.carter.office.magic.core.Element;
import local.carter.office.magic.core.Slots;
import local.carter.office.magic.spell.ActiveEffects;
import local.carter.office.magic.spell.CastContext;
import local.carter.office.magic.spell.CastOutcome;
import local.carter.office.magic.spell.Hurt;
import local.carter.office.magic.spell.LingeringEffect;
import local.carter.office.magic.spell.Spell;
import local.carter.office.magic.spell.SpellIds;
import local.carter.office.magic.spell.TargetFilter;
import local.carter.office.magic.spell.Targets;

import net.minecraft.core.BlockPos;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ExperienceOrb;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.monster.Enemy;
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
 * {@link WellGeometry#life} = 80 + 40 × charge ticks. Every tick, everything pushable within {@link WellGeometry#RADIUS} of the
 * centre that the centre can see (no block collider between the centre and its middle: the well never pulls through walls)
 * gets the pull {@link WellGeometry#pull} as its velocity, scaled by its knockback resistance (at most
 * {@link Targets#MAX_ENTITIES}, nearest first, and at most {@link #MAX_CLIPS} sight checks a tick). What it drags is
 * {@link #pulls}: items, XP orbs and hostile mobs only. Every {@link WellGeometry#HURT_EVERY} ticks, hostile mobs within
 * {@link WellGeometry#HURT_RADIUS} that it can see take {@link WellGeometry#HURT}. No block changes; players, pets, villagers,
 * livestock, golems and the rest are never moved or hurt.
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
		int life = WellGeometry.life(ctx.charge());
		if (!ActiveEffects.add(ctx.level(), new Well(ctx, centre, life))) return CastOutcome.fizzle("magic.office.busy");
		return CastOutcome.cast(ctx.fx().withTarget(centre).withDuration(life).withData((float) WellGeometry.RADIUS));
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
	 * What the well drags: items and XP orbs, and hostile mobs ({@link Enemy}) that {@link TargetFilter#affects} (the lane
	 * ruling, as Lightning's: friends' livestock, golems and allays are never dragged or hurt).
	 */
	static boolean pulls(CastContext ctx, Entity e) {
		if (!TargetFilter.pushable(ctx.caster(), e)) return false;
		return e instanceof ItemEntity || e instanceof ExperienceOrb || e instanceof Enemy && TargetFilter.affects(ctx.caster(), e);
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

		Well(CastContext ctx, Vec3 centre, int life) {
			this.ctx = ctx;
			this.centre = centre;
			this.life = life;
		}

		@Override
		public boolean tick(ServerLevel level, int age) {
			// The well never loads a chunk: it closes if its own goes away.
			if (age > life || !level.isLoaded(BlockPos.containing(centre))) return false;
			double r = WellGeometry.RADIUS;
			List<Entity> caught = new ArrayList<>(level.getEntities((Entity) null, new AABB(centre, centre).inflate(r),
					e -> pulls(ctx, e) && WellGeometry.inReach(centre, e.getBoundingBox().getCenter())));
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
				for (LivingEntity l : Targets.radius(ctx, centre, WellGeometry.HURT_RADIUS)) {
					if (!(l instanceof Enemy) || clips >= MAX_CLIPS) continue;
					clips++;
					if (clear(level, centre, l.getBoundingBox().getCenter())) Hurt.damage(ctx, l, WellGeometry.HURT);
				}
			}
			return age < life;
		}

		@Override
		public String toString() {
			return "GravityWell.Well[" + ctx.caster().getScoreboardName() + " at " + centre + "]";
		}
	}
}
