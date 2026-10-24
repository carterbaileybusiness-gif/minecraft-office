package local.carter.office.magic.spell.water;

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
import local.carter.office.magic.terrain.EditBatch;
import local.carter.office.magic.terrain.EditTier;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.FluidTags;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.material.Fluids;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

/**
 * Water Dragon (Water, slot 4, ultimate with hand signs, lingering): a dragon of water that crashes down and floods the land.
 *
 * <p>The target is where the caster's look meets a block or a water surface within {@link DragonGeometry#RANGE} (a miss drops
 * to the ground below the ray's end). The dragon pours out of the caster's circle and flies the seeded spline of
 * {@link DragonGeometry} for {@link DragonGeometry#FLIGHT} ticks; the server only waits for it. At the impact:
 * <ul>
 * <li>everything spells may touch within {@link DragonGeometry#DAMAGE_RADIUS} takes {@link DragonGeometry#DAMAGE} × P, and
 * creatures a control effect may take ({@link TargetFilter#controllable}: never a boss) are thrown {@link DragonGeometry#KNOCK}
 * outward + {@link DragonGeometry#KNOCK_UP} up;
 * whatever burns there is put out;</li>
 * <li><b>permanent flood</b>: real water sources in the cells {@link DragonGeometry#flood} picks (radius
 * {@code min(12, 6 + (level − 7))}, air or plants and snow water washes away, no higher than the impact, each on solid ground
 * or water, each layer closed under vanilla's two-source rule so every source is logged, at most 600), and
 * {@link DragonGeometry#MUD_CHANCE} of the grass and dirt tops within radius + 3 (at most 12 from the impact) turned to mud.
 * Both go through one {@link EditTier#ULTIMATE} batch at the impact cell: claims, spawn protection, block entities and the edit
 * log apply as for any terrain spell, and {@code /magic undo} takes the water back.</li>
 * </ul>
 *
 * <p>Fx: {@code target} = the impact point, {@code duration} = {@link DragonGeometry#FLIGHT}, {@code data} = {flood radius,
 * mud radius, impact cell y}. The client rebuilds the path from {@code origin}, {@code dir}, {@code target} and {@code seed}.
 */
public final class WaterDragon implements Spell {
	@Override
	public Identifier id() {
		return SpellIds.WATER_DRAGON;
	}

	@Override
	public Element element() {
		return Element.WATER;
	}

	@Override
	public int slot() {
		return Slots.ULTIMATE;
	}

	@Override
	public CastOutcome cast(CastContext ctx) {
		ServerLevel level = ctx.level();
		Vec3 eye = ctx.eye();
		Vec3 look = ctx.look().normalize();
		Vec3 far = eye.add(look.scale(DragonGeometry.RANGE));
		BlockHitResult hit = level.clip(new ClipContext(eye, far, ClipContext.Block.COLLIDER, ClipContext.Fluid.ANY, ctx.caster()));
		Vec3 impact;
		BlockPos cell;
		if (hit.getType() == HitResult.Type.MISS) {
			impact = Targets.ground(level, far);
			cell = BlockPos.containing(impact);
		} else {
			impact = hit.getLocation();
			cell = hit.getBlockPos().relative(hit.getDirection());
		}
		Vec3 start = DragonGeometry.start(eye, look);
		int flood = DragonGeometry.floodRadius(ctx.grimoireLevel());
		int mud = DragonGeometry.mudRadius(ctx.grimoireLevel());
		Flight flight = new Flight(ctx, impact, cell, flood, mud);
		if (!ActiveEffects.add(ctx, flight)) return CastOutcome.fizzle("magic.office.busy");
		return CastOutcome.cast(ctx.fx().withTarget(impact).withDuration(DragonGeometry.FLIGHT)
				.withData(flood, mud, cell.getY()));
	}

	/** The flight: waits for the dragon to land, then strikes and floods. */
	static final class Flight implements LingeringEffect {
		private final CastContext ctx;
		private final Vec3 impact;
		private final BlockPos cell;
		private final int floodRadius;
		private final int mudRadius;

		Flight(CastContext ctx, Vec3 impact, BlockPos cell, int floodRadius, int mudRadius) {
			this.ctx = ctx;
			this.impact = impact;
			this.cell = cell.immutable();
			this.floodRadius = floodRadius;
			this.mudRadius = mudRadius;
		}

		@Override
		public boolean tick(ServerLevel level, int age) {
			if (age < DragonGeometry.FLIGHT) return true;
			strike(level);
			flood(level);
			return false;
		}

		private void strike(ServerLevel level) {
			float damage = DragonGeometry.DAMAGE * ctx.power();
			for (LivingEntity e : Targets.radius(ctx, impact, DragonGeometry.DAMAGE_RADIUS)) {
				Hurt.damage(ctx, e, damage);
				if (e.isAlive() && TargetFilter.controllable(ctx.caster(), e)) {
					Vec3 away = e.position().subtract(impact);
					Hurt.knock(e, away.horizontalDistanceSqr() < 1.0E-4 ? ctx.look() : away, DragonGeometry.KNOCK, DragonGeometry.KNOCK_UP);
				}
			}
			for (Entity e : level.getEntities((Entity) null, new AABB(impact, impact).inflate(DragonGeometry.DAMAGE_RADIUS), Entity::isOnFire)) {
				e.extinguishFire();
			}
		}

		private void flood(ServerLevel level) {
			if (!level.isLoaded(cell)) return;
			List<BlockPos> sources = DragonGeometry.flood(cell, floodRadius, p -> open(level, p), p -> holdsWater(level, p),
					p -> level.isLoaded(p) && level.getFluidState(p).isSourceOfType(Fluids.WATER), DragonGeometry.MAX_SOURCES);
			List<BlockPos> muddy = DragonGeometry.mud(cell, mudRadius, p -> dirtTop(level, p), ctx.seed());
			try (EditBatch b = ctx.kit().terrain().begin(ctx, cell, EditTier.ULTIMATE)) {
				BlockState w = Blocks.WATER.defaultBlockState();
				for (BlockPos p : sources) b.set(p, w);
				BlockState m = Blocks.MUD.defaultBlockState();
				for (BlockPos p : muddy) b.set(p, m);
			}
		}

		/**
		 * Water may stand on {@code p}: a block with a solid top, a solid block (vanilla's own test for turning water into a
		 * source above it), or water.
		 */
		private static boolean holdsWater(ServerLevel level, BlockPos p) {
			if (!level.isLoaded(p)) return false;
			BlockState s = level.getBlockState(p);
			return s.getFluidState().is(FluidTags.WATER) || s.isSolid() || s.isFaceSturdy(level, p, Direction.UP);
		}

		/**
		 * The flood may fill {@code p}: air, or something water washes away (short grass, flowers, a snow layer) with no fluid,
		 * no block entity and no tall half (the batch refuses those).
		 */
		private static boolean open(ServerLevel level, BlockPos p) {
			if (!level.isLoaded(p)) return false;
			BlockState s = level.getBlockState(p);
			if (s.isAir()) return true;
			return s.canBeReplaced() && s.getFluidState().isEmpty() && !s.hasBlockEntity()
					&& !s.hasProperty(BlockStateProperties.DOUBLE_BLOCK_HALF);
		}

		/** Grass or dirt with nothing solid on top (air, water or a plant): it may turn to mud. */
		private static boolean dirtTop(ServerLevel level, BlockPos p) {
			if (!level.isLoaded(p)) return false;
			BlockState s = level.getBlockState(p);
			if (!(s.is(Blocks.GRASS_BLOCK) || s.is(Blocks.DIRT) || s.is(Blocks.COARSE_DIRT))) return false;
			BlockPos up = p.above();
			return level.getBlockState(up).getCollisionShape(level, up).isEmpty();
		}

		@Override
		public String toString() {
			return "WaterDragon.Flight[" + ctx.caster().getScoreboardName() + "]";
		}
	}
}
