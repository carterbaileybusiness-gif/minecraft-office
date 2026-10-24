package local.carter.office.magic.spell.earth;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.function.Function;

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
import local.carter.office.magic.spell.earth.EarthWallGeometry.Cell;
import local.carter.office.magic.terrain.EditBatch;
import local.carter.office.magic.terrain.EditTier;
import local.carter.office.magic.terrain.TempBlockData;

import net.minecraft.core.BlockPos;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
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
 * else cobblestone, with some tuff and mud-brick cells mixed in ({@link #cellMaterial}). One MEDIUM batch, origin = the wall's
 * centre cell.
 *
 * <p>Cells ({@link #cell}): only cells with a collision shape count as ground or steps. Air, water and other replaceable cells
 * are filled. Natural short vegetation (small flowers, short grass, fern) is torn out as the column starts to rise (a logged,
 * permanent MEDIUM edit) and the column rises from the ground under it. Anything else without collision (crops, saplings,
 * torches, rails, redstone, two-block plants) is never touched: that column stays an open gap.
 *
 * <p>Mobs and items standing in a column are lifted onto its top if there is room for them there ({@code noCollision});
 * otherwise their cell stays open, like a cell holding a player or a protected entity (pets, villagers…), and so does
 * everything above it ({@link #fill}). All of it falls back to what was there {@link EarthWallGeometry#DURATION} ticks after
 * the cast (temporary blocks, so a crash or restart never leaves it behind).
 *
 * <p>Fx: {@code target} = bottom centre of the wall's centre cell, {@code duration} = {@link EarthWallGeometry#DURATION},
 * {@code data} = {axis x, axis z, width, height}. The client runs the same rules on its own world (ground, materials and
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

	/** Natural short vegetation the wall tears out: small flowers, short grass, fern. */
	public static boolean shortVegetation(BlockState s) {
		return s.is(BlockTags.SMALL_FLOWERS) || s.is(Blocks.SHORT_GRASS) || s.is(Blocks.FERN);
	}

	/** What {@code p} is to the wall (see {@link Cell}). */
	public static Cell cell(BlockGetter level, BlockPos p) {
		BlockState s = level.getBlockState(p);
		if (!s.getCollisionShape(level, p).isEmpty()) return Cell.SOLID;
		if (shortVegetation(s)) return Cell.CLEAR;
		if (TempBlockData.canReplace(s)) return Cell.OPEN;
		return Cell.GAP;
	}

	/** {@link #cell} on loaded cells; unloaded ones count as solid (the wall never loads a chunk). */
	public static Function<BlockPos, Cell> cells(Level level) {
		return p -> level.isLoaded(p) ? cell(level, p) : Cell.SOLID;
	}

	/**
	 * What a column standing on {@code floor} is made of: the floor block itself if it is a full solid block that a temporary
	 * block may be ({@code #office:magic_temp_safe}: stone, deepslate, sandstone, terracotta…); soil (grass, dirt, podzol, mud,
	 * moss…) becomes packed mud, sand sandstone, red sand red sandstone, netherrack and nylium blackstone; anything else
	 * (slabs, stairs, chests, wood, ore…) cobblestone.
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

	/**
	 * The block one cell of a column made of {@code material} actually is: a packed-mud wall has about 20 % mud-brick and 8 %
	 * tuff cells, a stone or cobblestone wall about 18 % tuff, picked from the cell's position (the same on every side). All of
	 * them are temporary-safe.
	 */
	public static BlockState cellMaterial(BlockState material, BlockPos cell) {
		int r = (int) Math.floorMod((cell.asLong() * 0x9E3779B97F4A7C15L) >>> 37, 100L);
		if (material.is(Blocks.PACKED_MUD)) {
			if (r < 20) return Blocks.MUD_BRICKS.defaultBlockState();
			if (r < 28) return Blocks.TUFF.defaultBlockState();
			return material;
		}
		boolean stony = material.is(BlockTags.BASE_STONE_OVERWORLD) || material.is(Blocks.COBBLESTONE) || material.is(Blocks.MOSSY_COBBLESTONE)
				|| material.is(Blocks.COBBLED_DEEPSLATE);
		if (stony && !material.is(Blocks.TUFF) && r < 18) return Blocks.TUFF.defaultBlockState();
		return material;
	}

	/** True for an entity whose cell the wall leaves open: any player (the caster too) and anything spells must spare. */
	public static boolean keepsCellOpen(Entity e) {
		return e instanceof Player || TargetFilter.isProtected(e);
	}

	/** A mob or item the wall lifts onto its top ({@code caster} may be null on a client). */
	public static boolean liftable(@Nullable ServerPlayer caster, Entity e) {
		return !e.isSpectator() && !keepsCellOpen(e) && TargetFilter.pushable(caster, e);
	}

	/**
	 * The cells of a column the wall fills: {@code cells} up to (not including) the first one a player or protected entity is
	 * in, or a mob or item that would have no room on top of the column ({@code noCollision} with its box moved there).
	 */
	public static List<BlockPos> fill(Level level, List<BlockPos> cells, @Nullable ServerPlayer caster) {
		List<BlockPos> out = new ArrayList<>(cells.size());
		for (BlockPos c : cells) {
			if (!level.getEntities((Entity) null, new AABB(c).deflate(0.01), e -> !e.isSpectator() && keepsCellOpen(e)).isEmpty()) break;
			out.add(c);
		}
		// Every mob in the column must fit on its top; if one doesn't, the column stops below it (and the top drops, so look again).
		boolean changed = true;
		while (changed && !out.isEmpty()) {
			changed = false;
			BlockPos base = out.get(0);
			BlockPos top = out.get(out.size() - 1);
			AABB column = new AABB(base.getX(), base.getY(), base.getZ(), top.getX() + 1, top.getY() + 1, top.getZ() + 1).deflate(0.01);
			for (Entity e : level.getEntities((Entity) null, column, e -> liftable(caster, e))) {
				AABB box = e.getBoundingBox();
				AABB lifted = box.move(0, top.getY() + 1 - box.minY, 0);
				if (level.noCollision(e, lifted)) continue;
				int lowest = Mth.floor(box.minY + 0.01);
				out.removeIf(c -> c.getY() >= lowest);
				changed = true;
				break;
			}
		}
		return out;
	}

	// ---- the rise ------------------------------------------------------------------------------------------------------------

	/**
	 * Each column: as it starts rising, its short vegetation is torn out; at its place tick, its cells are filled and what
	 * stands in them lifted. Then it ends (the temporary blocks restore themselves).
	 */
	static final class Rise implements LingeringEffect {
		private final CastContext ctx;
		private final EarthWallGeometry g;
		/** Columns that found ground when they started rising (a column only rises if the one inside it did). */
		private final Set<Integer> planned = new HashSet<>();
		private @Nullable EditBatch batch;

		Rise(CastContext ctx, EarthWallGeometry g) {
			this.ctx = ctx;
			this.g = g;
		}

		@Override
		public boolean tick(ServerLevel level, int age) {
			for (int k : g.order()) {
				if (Math.max(1, g.riseStart(k)) == age) start(level, k);
			}
			for (int k : g.order()) {
				if (g.placeTick(k) == age) raise(level, k, age);
			}
			return age < EarthWallGeometry.LAST_PLACE;
		}

		private EditBatch batch() {
			if (batch == null) batch = ctx.kit().terrain().begin(ctx, g.origin(), EditTier.MEDIUM);
			return batch;
		}

		/** Column {@code k} starts rising: plan it and tear out the short vegetation in its cells. */
		private void start(ServerLevel level, int k) {
			if (k != 0 && !planned.contains(EarthWallGeometry.inner(k))) return;
			Function<BlockPos, Cell> cells = cells(level);
			BlockPos base = EarthWallGeometry.base(g.nominal(k), cells);
			if (base == null) return;
			planned.add(k);
			for (BlockPos c : g.cells(base, cells)) {
				if (cells.apply(c) == Cell.CLEAR) batch().remove(c);
			}
		}

		private void raise(ServerLevel level, int k, int age) {
			if (!planned.contains(k)) return;
			Function<BlockPos, Cell> cells = cells(level);
			BlockPos base = EarthWallGeometry.base(g.nominal(k), cells);
			if (base == null) return;
			List<BlockPos> fill = fill(level, g.cells(base, cells), ctx.caster());
			if (fill.isEmpty()) return;
			BlockState material = material(level, base.below());
			int ticks = Math.max(1, EarthWallGeometry.DURATION - age);
			BlockPos top = null;
			for (BlockPos c : fill) {
				if (!batch().placeTemporary(c, cellMaterial(material, c), ticks)) break;
				top = c;
			}
			if (top != null) lift(level, base, top);
		}

		/** Moves mobs and items standing in the column's filled cells onto its top ({@link #fill} made sure they fit). */
		private void lift(ServerLevel level, BlockPos base, BlockPos top) {
			AABB column = new AABB(base.getX(), base.getY(), base.getZ(), top.getX() + 1, top.getY() + 1, top.getZ() + 1).deflate(0.01);
			for (Entity e : level.getEntities((Entity) null, column, e -> liftable(ctx.caster(), e))) {
				AABB lifted = e.getBoundingBox().move(0, top.getY() + 1 - e.getY(), 0);
				if (!level.noCollision(e, lifted)) continue; // the column stopped short of it: never lift into a ceiling
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
