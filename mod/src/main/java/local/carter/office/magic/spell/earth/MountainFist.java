package local.carter.office.magic.spell.earth;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.function.Predicate;

import org.jspecify.annotations.Nullable;

import local.carter.office.magic.cast.CastService;
import local.carter.office.magic.core.Element;
import local.carter.office.magic.core.Shapes.Cell;
import local.carter.office.magic.core.Slots;
import local.carter.office.magic.net.FxEvent;
import local.carter.office.magic.spell.ActiveEffects;
import local.carter.office.magic.spell.CastContext;
import local.carter.office.magic.spell.CastOutcome;
import local.carter.office.magic.spell.Hurt;
import local.carter.office.magic.spell.LingeringEffect;
import local.carter.office.magic.spell.Spell;
import local.carter.office.magic.spell.SpellIds;
import local.carter.office.magic.spell.Targets;
import local.carter.office.magic.terrain.EditBatch;
import local.carter.office.magic.terrain.EditTier;
import local.carter.office.magic.terrain.TempBlockData;
import local.carter.office.magic.terrain.WorldTerrainEdits;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.Fallable;
import net.minecraft.world.level.block.IceBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.CollisionContext;

/**
 * Mountain Fist (Earth, slot 4, ultimate, hand signs, lingering): a giant fist of rock bursts out of the ground where the caster
 * points and punches skyward, raising a mountain spire under it.
 *
 * <p>The target is where the look ray first meets a block within {@link MountainFistGeometry#RANGE} (nudged off the face it hit),
 * moved down to the ground; pointing at nothing, the ground under the ray's end. The target cell is the open cell on that
 * ground.
 * <ul>
 * <li>On {@link MountainFistGeometry#STRIKE_TICK} the fist strikes: creatures within {@link MountainFistGeometry#BLAST} of the
 * target that it can reach (line of sight) take {@code 24 × P} and, if they live, are launched {@link MountainFistGeometry#LAUNCH_UP}
 * up (and a little outward).</li>
 * <li>Permanent spire ({@link MountainFistGeometry}): {@code Shapes.cone(min(6, 3 + (level − 7)), min(12, 8 + 2 × (level − 7)))}
 * on the target cell, 60 % stone, 25 % the target's surface block ({@link #surfaceMaterial}), 15 % andesite. Only air or
 * replaceable cells are filled, bottom-up over {@link MountainFistGeometry#RISE} ticks, at most
 * {@link MountainFistGeometry#MAX_BLOCKS}. A base column over a dip first reaches down to the ground (at most
 * {@link MountainFistGeometry#SKIRT}). A cell holding a player or a protected entity stays open, and so does its column above it;
 * a mob in a cell is lifted onto it if there is room, else its column stops there too.</li>
 * <li>At the eruption {@link MountainFistGeometry#debris} ground blocks round the base are {@code launch}ed outward as flying
 * debris (core lands it only where the caster could build).</li>
 * </ul>
 * One ULTIMATE batch, origin = the target cell (every edit is within 12 of it).
 *
 * <p>Fx: {@code target} = bottom centre of the target cell, {@code dir} = the flat direction from the caster to it,
 * {@code duration} = {@link MountainFistGeometry#RISE}, {@code data} = {base radius, height, debris count}.
 */
public final class MountainFist implements Spell {
	@Override
	public Identifier id() {
		return SpellIds.MOUNTAIN_FIST;
	}

	@Override
	public Element element() {
		return Element.EARTH;
	}

	@Override
	public int slot() {
		return Slots.ULTIMATE;
	}

	@Override
	public CastOutcome cast(CastContext ctx) {
		ServerLevel level = ctx.level();
		ServerPlayer p = ctx.caster();
		int baseRadius = MountainFistGeometry.baseRadius(ctx.grimoireLevel());
		BlockPos target = target(ctx, baseRadius);
		// Nothing to stand a mountain on there, or the way is blocked: refuse before any side effect (nothing is spent).
		if (target == null) return CastOutcome.fizzle(CastService.SPELL_FAILED_KEY);
		Vec3 to = Vec3.atBottomCenterOf(target).subtract(p.position());
		Vec3 dir = to.x * to.x + to.z * to.z < 1.0E-4 ? EarthSpikes.forward(ctx.look(), p.getYRot()) : EarthWallGeometry.flat(to);
		int height = MountainFistGeometry.height(ctx.grimoireLevel());
		BlockState surface = surfaceMaterial(level, target.below());
		if (!ActiveEffects.add(ctx, new Rise(ctx, target, surface, baseRadius, height))) return CastOutcome.fizzle(CastService.BUSY_KEY);
		FxEvent fx = ctx.fx();
		return CastOutcome.cast(new FxEvent(fx.spell(), fx.casterId(), fx.origin(), dir, Vec3.atBottomCenterOf(target), fx.charge(),
				fx.power(), fx.seed(), MountainFistGeometry.RISE, new int[0],
				new float[] {baseRadius, height, MountainFistGeometry.debris(ctx.grimoireLevel())}));
	}

	/**
	 * The open cell on the ground at the look ray's point within range, or null (refused). Never closer to the caster than
	 * {@code baseRadius + }{@link MountainFistGeometry#CLEAR}: looking at their own feet, the aim moves out along the look, and the
	 * moved aim must be in plain sight of the eye (never through a house wall). A target is refused, never snapped elsewhere
	 * ({@link MountainFistGeometry#refused}): a ray that meets nothing, no solid ground under the target, a drop of more than
	 * {@link MountainFistGeometry#MAX_DROP} from the aim (a cliff), or a target out of reach.
	 */
	static @Nullable BlockPos target(CastContext ctx, int baseRadius) {
		ServerLevel level = ctx.level();
		Vec3 eye = ctx.eye();
		Vec3 look = ctx.look().normalize();
		BlockHitResult hit = level.clip(new ClipContext(eye, eye.add(look.scale(MountainFistGeometry.RANGE)), ClipContext.Block.COLLIDER,
				ClipContext.Fluid.NONE, ctx.caster()));
		// A miss refuses: there is no ground to point at. So does a ceiling (the underside of a roof, a cave's top, the sky's
		// barrier): stepping "up off its face" would land inside it.
		if (hit.getType() == HitResult.Type.MISS || hit.getDirection() == Direction.DOWN) return null;
		// Just off the face it struck (a wall's face → the ground in front of the wall), then down to the ground.
		Vec3 aim = hit.getLocation().subtract(look.scale(0.3)).add(0, 0.5, 0);
		BlockPos t = grounded(level, eye, aim);
		if (t == null) return null;
		Vec3 feet = ctx.caster().position();
		double dx = t.getX() + 0.5 - feet.x;
		double dz = t.getZ() + 0.5 - feet.z;
		double min = baseRadius + MountainFistGeometry.CLEAR;
		if (dx * dx + dz * dz >= min * min) return t;
		Vec3 moved = feet.add(EarthSpikes.forward(look, ctx.caster().getYRot()).scale(min)).add(0, 1, 0);
		// Pushed out past something (a house wall two blocks away): refuse, never build behind it.
		if (level.clip(new ClipContext(eye, moved, ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, ctx.caster())).getType()
				!= HitResult.Type.MISS) return null;
		return grounded(level, eye, moved);
	}

	/** The open cell on the ground under {@code aim}, or null when {@link MountainFistGeometry#refused}. */
	private static @Nullable BlockPos grounded(ServerLevel level, Vec3 eye, Vec3 aim) {
		Vec3 ground = Targets.ground(level, aim);
		BlockPos t = BlockPos.containing(aim.x, ground.y + 0.01, aim.z);
		boolean solid = level.isLoaded(t.below()) && EarthSpikes.solidAt(level, t.below());
		double drop = aim.y - ground.y;
		double reach = eye.distanceTo(Vec3.atBottomCenterOf(t));
		return MountainFistGeometry.refused(solid, drop, reach) ? null : t;
	}

	/**
	 * The spire's share of the target's surface block: the block itself if it is a plain solid block; sand becomes sandstone, red
	 * sand red sandstone, other falling blocks cobblestone, leaves mossy cobblestone, ice packed ice; block entities, immune
	 * blocks and anything not a full block become stone.
	 */
	public static BlockState surfaceMaterial(BlockGetter level, BlockPos pos) {
		BlockState s = level.getBlockState(pos);
		if (s.isAir() || s.hasBlockEntity() || s.is(WorldTerrainEdits.IMMUNE) || !s.getFluidState().isEmpty()) return Blocks.STONE.defaultBlockState();
		if (s.getBlock() instanceof Fallable) {
			if (s.is(Blocks.RED_SAND)) return Blocks.RED_SANDSTONE.defaultBlockState();
			if (s.is(BlockTags.SAND)) return Blocks.SANDSTONE.defaultBlockState();
			return Blocks.COBBLESTONE.defaultBlockState();
		}
		if (s.is(BlockTags.LEAVES)) return Blocks.MOSSY_COBBLESTONE.defaultBlockState();
		if (s.getBlock() instanceof IceBlock || s.is(BlockTags.ICE)) return Blocks.PACKED_ICE.defaultBlockState();
		BlockState plain = s.getBlock().defaultBlockState();
		if (!s.isCollisionShapeFullBlock(level, pos) || !plain.isCollisionShapeFullBlock(level, pos)) return Blocks.STONE.defaultBlockState();
		return plain;
	}

	/** The block a spire cell is, from {@link MountainFistGeometry#material}. */
	public static BlockState cellState(long seed, BlockPos p, BlockState surface) {
		return switch (MountainFistGeometry.material(seed, p.getX(), p.getY(), p.getZ())) {
			case 0 -> Blocks.STONE.defaultBlockState();
			case 1 -> surface;
			default -> Blocks.ANDESITE.defaultBlockState();
		};
	}

	/** The strike, the debris and the spire rising layer by layer; one ULTIMATE batch. */
	private static final class Rise implements LingeringEffect {
		private final CastContext ctx;
		private final BlockPos target;
		private final BlockState surface;
		private final int baseRadius;
		private final int height;
		private final List<Cell> spire;
		/** Columns (x, z offsets) a protected entity or a stuck mob closed: nothing more is placed in them. */
		private final Set<Long> closed = new HashSet<>();
		private @Nullable EditBatch batch;
		private int placed;

		Rise(CastContext ctx, BlockPos target, BlockState surface, int baseRadius, int height) {
			this.ctx = ctx;
			this.target = target;
			this.surface = surface;
			this.baseRadius = baseRadius;
			this.height = height;
			this.spire = MountainFistGeometry.spire(baseRadius, height);
		}

		private EditBatch batch() {
			if (batch == null) batch = ctx.kit().terrain().begin(ctx, target, EditTier.ULTIMATE);
			return batch;
		}

		@Override
		public boolean tick(ServerLevel level, int age) {
			if (age == MountainFistGeometry.skirtTick()) {
				debris(level);
				skirts(level);
			}
			if (age == MountainFistGeometry.STRIKE_TICK) strike(level);
			for (int y = 0; y < height; y++) {
				if (MountainFistGeometry.layerTick(y, height) == age) layer(level, y);
			}
			return age < MountainFistGeometry.RISE;
		}

		/** Ground blocks round the base fly out as debris. */
		private void debris(ServerLevel level) {
			Predicate<BlockPos> solid = EarthSpikes.solid(level);
			int n = MountainFistGeometry.debris(ctx.grimoireLevel());
			for (int i = 0; i < n; i++) {
				int[] o = MountainFistGeometry.debrisCell(i, n, baseRadius, ctx.seed());
				BlockPos ground = QuakeGeometry.surface(target.getX() + o[0], target.getZ() + o[1], target.getY() - 1, solid);
				if (ground == null) continue;
				// Never pull the ground from under a player or anything spells must spare.
				AABB above = new AABB(ground.above()).expandTowards(0, 1, 0);
				if (!level.getEntities((Entity) null, above, e -> !e.isSpectator() && EarthWall.keepsCellOpen(e)).isEmpty()) continue;
				batch().launch(ground, MountainFistGeometry.debrisVelocity(i, o[0], o[1], ctx.seed()));
			}
		}

		/** Base columns over a dip reach down to the ground first (bottom-up), so the spire never floats. */
		private void skirts(ServerLevel level) {
			Predicate<BlockPos> solid = EarthSpikes.solid(level);
			spare(level, 0, -MountainFistGeometry.SKIRT);
			for (Cell c : spire) {
				if (c.y() != 0) break;
				BlockPos top = target.offset(c.x(), 0, c.z());
				if (solid.test(top)) continue;
				List<BlockPos> skirt = new ArrayList<>();
				BlockPos q = top.below();
				for (int i = 0; i < MountainFistGeometry.SKIRT && !solid.test(q); i++, q = q.below()) skirt.add(q);
				if (!solid.test(q)) continue; // no ground within reach: leave it
				for (int i = skirt.size() - 1; i >= 0; i--) place(level, skirt.get(i), c);
			}
		}

		private void layer(ServerLevel level, int y) {
			spare(level, y, y);
			for (Cell c : spire) {
				if (c.y() == y) place(level, target.offset(c.x(), c.y(), c.z()), c);
			}
		}

		/**
		 * Before layer {@code y} (and the skirt, {@code y0}..{@code y}) goes in: a player or protected entity in any of its cells
		 * closes that column and the 8 round it, so nobody is ever left standing in a one-block shaft.
		 */
		private void spare(ServerLevel level, int y, int y0) {
			int lo = Math.min(y, y0);
			int hi = Math.max(y, y0);
			AABB area = new AABB(target.getX() - baseRadius, target.getY() + lo, target.getZ() - baseRadius, target.getX() + baseRadius + 1,
					target.getY() + hi + 1, target.getZ() + baseRadius + 1);
			for (Entity e : level.getEntities((Entity) null, area, e -> !e.isSpectator() && EarthWall.keepsCellOpen(e))) {
				AABB b = e.getBoundingBox();
				for (int x = (int) Math.floor(b.minX); x <= (int) Math.floor(b.maxX - 1.0E-6); x++) {
					for (int z = (int) Math.floor(b.minZ); z <= (int) Math.floor(b.maxZ - 1.0E-6); z++) {
						for (int ox = -1; ox <= 1; ox++) {
							for (int oz = -1; oz <= 1; oz++) closed.add(column(x + ox - target.getX(), z + oz - target.getZ()));
						}
					}
				}
			}
		}

		private static long column(int x, int z) {
			return ((long) x << 32) ^ (z & 0xFFFFFFFFL);
		}

		private void place(ServerLevel level, BlockPos pos, Cell c) {
			if (placed >= MountainFistGeometry.MAX_BLOCKS) return;
			long column = column(c.x(), c.z());
			if (closed.contains(column) || !level.isLoaded(pos)) return;
			if (!TempBlockData.canReplace(level.getBlockState(pos))) return;
			if (!makeRoom(level, pos)) {
				closed.add(column);
				return;
			}
			if (batch().set(pos, cellState(ctx.seed(), pos, surface))) placed++;
		}

		/**
		 * Whether {@code pos} may be filled: a player or protected entity in it closes it; a mob or item in it is lifted onto it if
		 * there is room, else it closes it too.
		 */
		private boolean makeRoom(ServerLevel level, BlockPos pos) {
			AABB cell = new AABB(pos).deflate(0.01);
			for (Entity e : level.getEntities((Entity) null, cell, e -> !e.isSpectator())) {
				if (EarthWall.keepsCellOpen(e)) return false;
			}
			for (Entity e : level.getEntities((Entity) null, cell, e -> EarthWall.liftable(ctx.caster(), e))) {
				AABB lifted = e.getBoundingBox().move(0, pos.getY() + 1 - e.getY(), 0);
				if (!level.noCollision(e, lifted)) return false;
				e.teleportTo(e.getX(), pos.getY() + 1, e.getZ());
				e.resetFallDistance();
			}
			return true;
		}

		/** The fist strikes: creatures within reach take 24 × P, and survivors are launched. */
		private void strike(ServerLevel level) {
			Vec3 at = Vec3.atBottomCenterOf(target);
			Vec3 core = at.add(0, 1, 0);
			float damage = MountainFistGeometry.DAMAGE * ctx.power();
			for (LivingEntity e : Targets.radius(ctx, at, MountainFistGeometry.BLAST)) {
				if (!reaches(level, core, e)) continue;
				Hurt.damage(ctx, e, damage);
				if (e.isAlive()) Hurt.knock(e, e.position().subtract(at), MountainFistGeometry.LAUNCH_OUT, MountainFistGeometry.LAUNCH_UP);
			}
		}

		private static boolean reaches(ServerLevel level, Vec3 from, LivingEntity e) {
			AABB b = e.getBoundingBox();
			Vec3 mid = b.getCenter();
			for (Vec3 to : new Vec3[] {mid, new Vec3(mid.x, b.minY + 0.1, mid.z), new Vec3(mid.x, b.maxY - 0.1, mid.z)}) {
				if (level.clip(new ClipContext(from, to, ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, CollisionContext.empty()))
						.getType() == HitResult.Type.MISS) return true;
			}
			return false;
		}

		@Override
		public void end(ServerLevel level) {
			if (batch != null) batch.close();
			batch = null;
		}

		@Override
		public String toString() {
			return "MountainFist.Rise[" + ctx.caster().getScoreboardName() + " at " + target + "]";
		}
	}
}
