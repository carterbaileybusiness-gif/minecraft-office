package local.carter.office.magic.spell.earth;

import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Predicate;

import org.jspecify.annotations.Nullable;

import local.carter.office.magic.cast.CastService;
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
import local.carter.office.magic.spell.earth.QuakeGeometry.Column;
import local.carter.office.magic.terrain.EditBatch;
import local.carter.office.magic.terrain.EditTier;

import net.minecraft.core.BlockPos;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.CollisionContext;

/**
 * Quake (Earth, slot 3, medium, lingering): the caster stamps and a shockwave ripples out through the ground.
 *
 * <p>The ring ({@link QuakeGeometry}) grows from the caster's floor block 1 block every 2 ticks to {@code 6 × (1 + charge)}
 * (6 at a tap, 12 at full charge). As it
 * passes a creature that is <b>on the ground</b> (and in line of sight of the stamp; a creature in the air when the ring passes
 * under it is spared), the creature takes {@code 5 × P}; creatures a control effect may take (hostile mobs and animals, never a
 * boss), items and XP orbs are also thrown
 * {@link #UP} up and {@link #KNOCK} outward.
 *
 * <p>Minor permanent change: a quarter of the ring's columns (at most 120, seeded) crack as the ring passes them: on the ground
 * block, grass block → dirt, stone → cobblestone, dirt → coarse dirt ({@link #cracked}); nothing else is touched. MEDIUM batches:
 * one at the centre, and one per 36° arc for blocks more than 6 from it, its origin on the arc ({@link QuakeGeometry#originFor}).
 * Claims, budgets and the log come with the batches.
 *
 * <p>Fx: {@code target} = top centre of the centre block, {@code duration} = ticks the ring runs, {@code data} = {radius}.
 */
public final class Quake implements Spell {
	public static final float DAMAGE = 5;
	public static final double UP = 0.7;
	public static final double KNOCK = 0.6;
	/** How far above or below the centre a creature may stand and still feel the ring. */
	public static final double FEEL = QuakeGeometry.REACH + 1;

	@Override
	public Identifier id() {
		return SpellIds.QUAKE;
	}

	@Override
	public Element element() {
		return Element.EARTH;
	}

	@Override
	public int slot() {
		return Slots.MEDIUM_B;
	}

	@Override
	public CastOutcome cast(CastContext ctx) {
		ServerPlayer p = ctx.caster();
		BlockPos centre = centre(p.position());
		double radius = QuakeGeometry.radius(ctx.charge());
		Ripple ripple = new Ripple(ctx, centre, radius);
		if (!ActiveEffects.add(ctx, ripple)) return CastOutcome.fizzle(CastService.BUSY_KEY);
		return CastOutcome.cast(ctx.fx().withTarget(Vec3.atBottomCenterOf(centre.above())).withDuration(QuakeGeometry.duration(radius))
				.withData((float) radius));
	}

	/** The floor block under {@code feet}. */
	public static BlockPos centre(Vec3 feet) {
		return BlockPos.containing(feet.x, feet.y - 0.01, feet.z);
	}

	/** What a ground block cracks into, or null if it is not one Quake cracks. Shared with the client. */
	public static @Nullable BlockState cracked(BlockState s) {
		if (s.is(Blocks.GRASS_BLOCK)) return Blocks.DIRT.defaultBlockState();
		if (s.is(Blocks.STONE)) return Blocks.COBBLESTONE.defaultBlockState();
		if (s.is(Blocks.DIRT)) return Blocks.COARSE_DIRT.defaultBlockState();
		return null;
	}

	/** Whether the ring throws {@code e}: hostile mobs, animals, items and XP orbs, never a boss ({@link TargetFilter#controllable}; a boss is still hurt). */
	public static boolean pushes(ServerPlayer caster, Entity e) {
		return TargetFilter.controllable(caster, e);
	}

	/** The ring: each tick it reaches new columns (cracking the picked ones) and the creatures standing on them. */
	private static final class Ripple implements LingeringEffect {
		private final CastContext ctx;
		private final BlockPos centre;
		private final Vec3 stamp;
		private final List<Column> disc;
		private final boolean[] cracks;
		private final int duration;
		private final double radius;
		private int next;
		/** Everything the ring has passed (struck or spared), so each is felt once. */
		private final Set<Entity> passed = new HashSet<>();
		/** Creatures struck so far (at most {@code Targets.MAX_ENTITIES} per cast). */
		private int struck;
		private final Map<BlockPos, EditBatch> batches = new HashMap<>();

		Ripple(CastContext ctx, BlockPos centre, double radius) {
			this.ctx = ctx;
			this.centre = centre;
			this.radius = radius;
			this.stamp = Vec3.atBottomCenterOf(centre.above()).add(0, 0.9, 0);
			this.disc = QuakeGeometry.disc(radius);
			this.cracks = QuakeGeometry.cracks(disc.size(), ctx.seed());
			this.duration = QuakeGeometry.duration(radius);
		}

		@Override
		public boolean tick(ServerLevel level, int age) {
			Predicate<BlockPos> solid = EarthSpikes.solid(level);
			while (next < disc.size() && QuakeGeometry.tickOf(disc.get(next).dist()) <= age) {
				if (cracks[next]) crack(level, disc.get(next), solid);
				next++;
			}
			feel(level, Math.min(radius, QuakeGeometry.ringAt(age)));
			return age < duration;
		}

		private void crack(ServerLevel level, Column c, Predicate<BlockPos> solid) {
			BlockPos ground = QuakeGeometry.surface(centre.getX() + c.dx(), centre.getZ() + c.dz(), centre.getY(), solid);
			if (ground == null) return;
			BlockState into = cracked(level.getBlockState(ground));
			if (into == null) return;
			batch(QuakeGeometry.originFor(centre, ground)).set(ground, into);
		}

		private EditBatch batch(BlockPos origin) {
			return batches.computeIfAbsent(origin, o -> ctx.kit().terrain().begin(ctx, o, EditTier.MEDIUM));
		}

		/** Creatures (and items) the ring has now reached: the grounded ones in sight of the stamp are struck. */
		private void feel(ServerLevel level, double ring) {
			AABB area = new AABB(centre).inflate(ring + 1, FEEL, ring + 1);
			ServerPlayer caster = ctx.caster();
			for (Entity e : level.getEntities((Entity) null, area, e -> !passed.contains(e) && TargetFilter.pushable(caster, e))) {
				double d = flatDistance(e);
				if (d > ring) continue;
				passed.add(e);
				if (!e.onGround() || !reaches(level, e)) continue;
				// Only creatures count toward the per-cast entity budget (items and orbs are just thrown).
				if (e instanceof LivingEntity && struck++ >= Targets.MAX_ENTITIES) continue;
				if (e instanceof LivingEntity l) Hurt.damage(ctx, l, DAMAGE * ctx.power());
				if (pushes(caster, e) && !e.isRemoved()) {
					Vec3 out = e.position().subtract(stamp);
					Hurt.knock(e, out, KNOCK, UP);
				}
			}
		}

		/** Horizontal distance from the stamp to the nearest point of {@code e}'s box. */
		private double flatDistance(Entity e) {
			AABB b = e.getBoundingBox();
			double dx = Math.max(0, Math.max(b.minX - stamp.x, stamp.x - b.maxX));
			double dz = Math.max(0, Math.max(b.minZ - stamp.z, stamp.z - b.maxZ));
			return Math.sqrt(dx * dx + dz * dz);
		}

		/** Line of sight from the stamp to the creature's middle or head: a wall between them shelters it. */
		private boolean reaches(ServerLevel level, Entity e) {
			AABB b = e.getBoundingBox();
			Vec3 mid = b.getCenter();
			Vec3 top = new Vec3(mid.x, b.maxY - 0.1, mid.z);
			for (Vec3 to : new Vec3[] {mid, top}) {
				if (level.clip(new ClipContext(stamp, to, ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, CollisionContext.empty()))
						.getType() == HitResult.Type.MISS) return true;
			}
			return false;
		}

		@Override
		public void end(ServerLevel level) {
			for (EditBatch b : batches.values()) b.close();
			batches.clear();
		}

		@Override
		public String toString() {
			return "Quake.Ripple[" + ctx.caster().getScoreboardName() + "]";
		}
	}
}
