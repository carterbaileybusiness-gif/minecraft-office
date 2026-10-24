package local.carter.office.magic.spell.earth;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.function.Predicate;

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
import local.carter.office.magic.terrain.EditBatch;
import local.carter.office.magic.terrain.EditTier;
import local.carter.office.magic.terrain.TempBlockData;

import net.minecraft.core.BlockPos;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.BlockTags;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

/**
 * Earth Wall (Earth, slot 1, medium, lingering): a wall of earth bursts out of the ground ahead and stands for half a minute.
 *
 * <p>The wall ({@link EarthWallGeometry}) stands 3 ahead of the caster's feet, across the look: 5 columns wide (7 from charge
 * 0.5), 3 high (4 at full charge). If something solid is in the way at eye height it stands just in front of it instead (never
 * through a wall). It rises from the centre outward, column by column over 6 ticks. Each column is made of the block under it
 * if that is a full solid block a temporary block may be ({@link #material}); soil becomes packed mud, sand sandstone, anything
 * else cobblestone. Only air or replaceable cells are filled (one MEDIUM batch, origin = the wall's centre cell); a column stops
 * below the first cell that isn't free. Mobs and items standing in a column are lifted onto its top; a cell holding a player or
 * a protected entity (pets, villagers…) stays open, and so does everything above it. All of it falls back to what was there
 * {@link EarthWallGeometry#DURATION} ticks after the cast (temporary blocks, so a crash or restart never leaves it behind).
 *
 * <p>Fx: {@code target} = bottom centre of the wall's centre cell, {@code duration} = {@link EarthWallGeometry#DURATION},
 * {@code data} = {axis x, axis z, width, height}. The client runs the same geometry on its own world (ground, materials and
 * the cells people stand in) to draw the columns rising.
 */
public final class EarthWall implements Spell {
	/** A lifted mob gets this little hop as it lands on top. */
	public static final double LIFT_HOP = 0.2;

	@Override
	public Identifier id() {
		return SpellIds.EARTH_WALL;
	}

	@Override
	public Element element() {
		return Element.EARTH;
	}

	@Override
	public int slot() {
		return Slots.MEDIUM_A;
	}

	@Override
	public CastOutcome cast(CastContext ctx) {
		Vec3 forward = forward(ctx);
		double ahead = clearAhead(ctx, forward);
		EarthWallGeometry g = EarthWallGeometry.of(ctx.caster().position(), forward, ahead, ctx.charge());
		Rise rise = new Rise(ctx, g);
		if (!ActiveEffects.add(ctx.level(), rise)) return CastOutcome.fizzle("magic.office.busy");
		return CastOutcome.cast(ctx.fx().withTarget(Vec3.atBottomCenterOf(g.origin())).withDuration(EarthWallGeometry.DURATION)
				.withData(g.ax(), g.az(), g.width(), g.height()));
	}

	/** The caster's horizontal facing (from the look, or the body's yaw when looking straight up or down). */
	private static Vec3 forward(CastContext ctx) {
		Vec3 look = ctx.look();
		if (look.x * look.x + look.z * look.z > 1.0E-4) return EarthWallGeometry.flat(look);
		float yaw = ctx.caster().getYRot() * Mth.DEG_TO_RAD;
		return new Vec3(-Mth.sin(yaw), 0, Mth.cos(yaw));
	}

	/** How far ahead the wall may stand: {@link EarthWallGeometry#AHEAD}, or just short of the first solid block at eye height. */
	private static double clearAhead(CastContext ctx, Vec3 forward) {
		Vec3 eye = ctx.eye();
		Vec3 to = eye.add(forward.scale(EarthWallGeometry.AHEAD + 0.5));
		BlockHitResult hit = ctx.level().clip(new ClipContext(eye, to, ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, ctx.caster()));
		if (hit.getType() == HitResult.Type.MISS) return EarthWallGeometry.AHEAD;
		Vec3 d = hit.getLocation().subtract(eye);
		return Math.sqrt(d.x * d.x + d.z * d.z) - 0.7;
	}

	// ---- shared with the client fx -------------------------------------------------------------------------------------------

	/** A cell the wall may fill: air or replaceable, no block entity, not half of a tall plant ({@link TempBlockData#canReplace}). */
	public static boolean open(BlockGetter level, BlockPos p) {
		return TempBlockData.canReplace(level.getBlockState(p));
	}

	/** {@link #open} on loaded cells; unloaded ones count as solid (the wall never loads a chunk). */
	public static Predicate<BlockPos> open(Level level) {
		return p -> level.isLoaded(p) && open((BlockGetter) level, p);
	}

	/**
	 * What a column standing on {@code floor} is made of: the floor block itself if it is a full solid block that a temporary
	 * block may be ({@code #office:magic_temp_safe}: stone, deepslate, sandstone, terracotta…); soil (grass, dirt, podzol, mud,
	 * moss…) becomes packed mud, sand sandstone, red sand red sandstone, netherrack and nylium blackstone; anything else
	 * (slabs, stairs, glass panes, wood, ore…) cobblestone.
	 */
	public static BlockState material(BlockGetter level, BlockPos floor) {
		BlockState s = level.getBlockState(floor);
		if (!s.isCollisionShapeFullBlock(level, floor)) return Blocks.COBBLESTONE.defaultBlockState();
		if (TempBlockData.isSafe(s)) return s;
		// #substrate_overworld: dirt, coarse and rooted dirt, grass, podzol, mycelium, mud, moss.
		if (s.is(BlockTags.SUBSTRATE_OVERWORLD)) return Blocks.PACKED_MUD.defaultBlockState();
		if (s.is(Blocks.RED_SAND)) return Blocks.RED_SANDSTONE.defaultBlockState();
		if (s.is(BlockTags.SAND)) return Blocks.SANDSTONE.defaultBlockState();
		if (s.is(Blocks.NETHERRACK) || s.is(BlockTags.NYLIUM)) return Blocks.BLACKSTONE.defaultBlockState();
		return Blocks.COBBLESTONE.defaultBlockState();
	}

	/** True for an entity whose cell the wall leaves open: any player (the caster too) and anything spells must spare. */
	public static boolean keepsCellOpen(Entity e) {
		return e instanceof Player || TargetFilter.isProtected(e);
	}

	/** The cells of a column the wall fills: {@code cells} up to (not including) the first one a player or protected entity is in. */
	public static List<BlockPos> unoccupied(Level level, List<BlockPos> cells) {
		List<BlockPos> out = new ArrayList<>(cells.size());
		for (BlockPos c : cells) {
			if (!level.getEntities((Entity) null, new AABB(c).deflate(0.01), e -> !e.isSpectator() && keepsCellOpen(e)).isEmpty()) break;
			out.add(c);
		}
		return out;
	}

	// ---- the rise ------------------------------------------------------------------------------------------------------------

	/** Places each column at its tick, lifts what stands in it, then ends (the temporary blocks restore themselves). */
	static final class Rise implements LingeringEffect {
		private final CastContext ctx;
		private final EarthWallGeometry g;
		private final Set<Integer> raised = new HashSet<>();
		private @Nullable EditBatch batch;

		Rise(CastContext ctx, EarthWallGeometry g) {
			this.ctx = ctx;
			this.g = g;
		}

		@Override
		public boolean tick(ServerLevel level, int age) {
			for (int k : g.order()) {
				if (g.placeTick(k) == age) raise(level, k, age);
			}
			return age < EarthWallGeometry.LAST_PLACE;
		}

		private void raise(ServerLevel level, int k, int age) {
			if (k != 0 && !raised.contains(EarthWallGeometry.inner(k))) return;
			Predicate<BlockPos> open = open(level);
			BlockPos base = EarthWallGeometry.base(g.nominal(k), open);
			if (base == null) return;
			raised.add(k);
			List<BlockPos> cells = unoccupied(level, g.cells(base, open));
			if (cells.isEmpty()) return;
			BlockState material = material(level, base.below());
			int ticks = Math.max(1, EarthWallGeometry.DURATION - age);
			if (batch == null) batch = ctx.kit().terrain().begin(ctx, g.origin(), EditTier.MEDIUM);
			BlockPos top = null;
			for (BlockPos c : cells) {
				if (!batch.placeTemporary(c, material, ticks)) break;
				top = c;
			}
			if (top != null) lift(level, base, top);
		}

		/** Moves mobs and items standing in the column's filled cells onto its top. */
		private void lift(ServerLevel level, BlockPos base, BlockPos top) {
			AABB column = new AABB(base.getX(), base.getY(), base.getZ(), top.getX() + 1, top.getY() + 1, top.getZ() + 1).deflate(0.01);
			for (Entity e : level.getEntities((Entity) null, column, e -> TargetFilter.pushable(ctx.caster(), e) && !keepsCellOpen(e))) {
				e.teleportTo(e.getX(), top.getY() + 1, e.getZ());
				Vec3 v = e.getDeltaMovement();
				e.setDeltaMovement(v.x, Math.max(v.y, LIFT_HOP), v.z);
				e.resetFallDistance();
				e.syncVelocity = true;
				e.needsSync = true;
			}
		}

		@Override
		public void end(ServerLevel level) {
			if (batch != null) batch.close();
			batch = null;
		}

		@Override
		public String toString() {
			return "EarthWall.Rise[" + ctx.caster().getScoreboardName() + "]";
		}
	}
}
