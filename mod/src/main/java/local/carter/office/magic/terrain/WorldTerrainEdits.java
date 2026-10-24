package local.carter.office.magic.terrain;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Predicate;

import org.jspecify.annotations.Nullable;

import local.carter.office.OfficeMod;
import local.carter.office.magic.core.TerrainRules;
import local.carter.office.magic.core.TerrainRules.Verdict;
import local.carter.office.magic.net.MagicServer;
import local.carter.office.magic.spell.CastContext;
import local.carter.office.magic.terrain.EditLog.LoggedCast;
import local.carter.office.magic.terrain.EditLog.LoggedEdit;
import local.carter.office.magic.terrain.EditLog.UndoReport;

import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.tags.TagKey;
import net.minecraft.world.entity.item.FallingBlockEntity;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.LiquidBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;

/**
 * The world's {@link TerrainEdits}: claim-safe, budgeted, batched and logged block changes.
 *
 * <ul>
 * <li>Each call runs {@link TerrainRules#decide}: block entity, {@code #office:magic_immune}, claimed ({@link ClaimCheck}),
 * spawn protection ({@code !mayInteract}), world border, distance from the batch origin ({@code tier.radius()}), and the cast's
 * budget ({@code tier.budget()}, summed over every batch of {@code ctx.castId()}). Setting a state that has a block entity or
 * is immune counts as a block entity / immune change too. A change that would change nothing returns false and is neither
 * applied nor refused. The first claimed or spawn-protected refusal of a cast shows {@code magic.office.protected}.</li>
 * <li>Accepted changes queue for one applier (FIFO across casts, at most {@link #PER_TICK} per {@link #tick}). The block
 * is re-read when the change is applied: {@code before} is captured then, and a block that became a block entity or immune
 * meanwhile is left alone.</li>
 * <li>Applied changes (not temporary blocks) are logged per cast to {@link EditLog}, written once the cast's queued changes
 * are applied and its batches closed.</li>
 * </ul>
 * Server thread only.
 */
public final class WorldTerrainEdits implements TerrainEdits {
	public static final TagKey<Block> IMMUNE = TagKey.create(Registries.BLOCK, OfficeMod.id("magic_immune"));
	/** Block changes applied per tick, server-wide. */
	public static final int PER_TICK = 256;
	/** Falling blocks launched per cast. */
	public static final int MAX_LAUNCH = 48;
	/** The log is written this long after the cast's last activity even if a batch was never closed. */
	private static final int UNCLOSED_FLUSH_TICKS = 100;
	/** A finished cast's budget is forgotten this long after its last activity. */
	private static final int FORGET_TICKS = 1200;
	private static final String PROTECTED_KEY = "magic.office.protected";

	private enum Kind { SET, REMOVE, LAUNCH, TEMP }

	private record Change(Cast cast, ServerLevel level, BlockPos pos, Kind kind, @Nullable BlockState state, @Nullable Vec3 velocity,
			int ticks) {}

	/** One cast's budget, counters and log record. */
	private static final class Cast {
		final long id;
		final UUID player;
		final Identifier spell;
		final ResourceKey<Level> dimension;
		final long gameTime;
		final List<LoggedEdit> edits = new ArrayList<>();
		int used;
		int launched;
		int pending;
		int openBatches;
		boolean warned;
		boolean dirty;
		long lastActive;

		Cast(long id, UUID player, Identifier spell, ResourceKey<Level> dimension, long gameTime) {
			this.id = id;
			this.player = player;
			this.spell = spell;
			this.dimension = dimension;
			this.gameTime = gameTime;
		}
	}

	private final MinecraftServer server;
	private final ClaimCheck claims;
	private final TempBlocks temp;
	private final EditLog log;
	private final ArrayDeque<Change> queue = new ArrayDeque<>();
	private final Map<Long, Cast> casts = new HashMap<>();
	private long ticks;

	public WorldTerrainEdits(MinecraftServer server, ClaimCheck claims, TempBlocks temp, EditLog log) {
		this.server = server;
		this.claims = claims;
		this.temp = temp;
		this.log = log;
	}

	public EditLog log() {
		return log;
	}

	/** Changes waiting for the applier. */
	public int queued() {
		return queue.size();
	}

	@Override
	public EditBatch begin(CastContext ctx, BlockPos origin, EditTier tier) {
		Cast cast = casts.computeIfAbsent(ctx.castId(),
				id -> new Cast(id, ctx.caster().getUUID(), ctx.spell().id(), ctx.level().dimension(), ctx.level().getGameTime()));
		cast.openBatches++;
		cast.lastActive = ticks;
		origin = origin.immutable();
		return new Batch(cast, ctx.caster(), ctx.level(), origin, tier, claims.forBatch(ctx.caster(), ctx.level(), origin, tier.radius()));
	}

	/** Applies up to {@link #PER_TICK} queued changes, then writes the logs of casts that are done. */
	public void tick() {
		ticks++;
		for (int i = 0; i < PER_TICK && !queue.isEmpty(); i++) apply(queue.poll());
		for (Iterator<Cast> it = casts.values().iterator(); it.hasNext();) {
			Cast c = it.next();
			if (c.pending > 0) continue;
			if (c.dirty && (c.openBatches == 0 || ticks - c.lastActive > UNCLOSED_FLUSH_TICKS)) flush(c);
			if (!c.dirty && ticks - c.lastActive > FORGET_TICKS) it.remove();
		}
	}

	/** Server stopping: applies everything still queued and writes every log. */
	public void stop() {
		while (!queue.isEmpty()) apply(queue.poll());
		for (Cast c : casts.values()) if (c.dirty) flush(c);
		casts.clear();
	}

	/**
	 * {@code /magic undo}: drops the player's queued changes, writes their pending logs, then undoes their newest {@code n}
	 * logged casts ({@link EditLog#undo}).
	 */
	public UndoReport undo(UUID player, int n) {
		queue.removeIf(ch -> {
			if (!ch.cast().player.equals(player)) return false;
			ch.cast().pending--;
			return true;
		});
		for (Cast c : casts.values()) if (c.player.equals(player) && c.dirty) flush(c);
		return log.undo(server, player, n);
	}

	private void flush(Cast c) {
		c.dirty = false;
		if (c.edits.isEmpty()) return;
		log.write(new LoggedCast(c.player, c.spell, c.dimension, c.gameTime, c.edits), c.id);
	}

	private void apply(Change ch) {
		Cast cast = ch.cast();
		cast.pending--;
		cast.lastActive = ticks;
		ServerLevel level = ch.level();
		BlockPos pos = ch.pos();
		if (!level.isLoaded(pos)) return;
		BlockState before = level.getBlockState(pos);
		if (before.hasBlockEntity() || before.is(IMMUNE)) return;
		switch (ch.kind()) {
			case SET -> {
				if (before.equals(ch.state())) return;
				level.setBlock(pos, ch.state(), Block.UPDATE_ALL);
			}
			case REMOVE -> {
				BlockState left = before.getFluidState().createLegacyBlock();
				if (before.equals(left)) return;
				level.setBlock(pos, left, Block.UPDATE_ALL);
			}
			case LAUNCH -> {
				if (before.isAir() || before.getBlock() instanceof LiquidBlock) return;
				FallingBlockEntity falling = FallingBlockEntity.fall(level, pos, before);
				falling.dropItem = false; // lands as a block where it can, never as an item
				falling.setDeltaMovement(ch.velocity());
			}
			case TEMP -> {
				temp.place(level, pos, ch.state(), ch.ticks());
				return; // restores itself: not in the edit log
			}
		}
		BlockState after = level.getBlockState(pos);
		if (after.equals(before)) return;
		cast.edits.add(new LoggedEdit(pos, before, after));
		cast.dirty = true;
	}

	private final class Batch implements EditBatch {
		private final Cast cast;
		private final ServerPlayer caster;
		private final ServerLevel level;
		private final BlockPos origin;
		private final EditTier tier;
		private final Predicate<BlockPos> claimed;
		private int applied;
		private int refused;
		private boolean closed;

		Batch(Cast cast, ServerPlayer caster, ServerLevel level, BlockPos origin, EditTier tier, Predicate<BlockPos> claimed) {
			this.cast = cast;
			this.caster = caster;
			this.level = level;
			this.origin = origin;
			this.tier = tier;
			this.claimed = claimed;
		}

		@Override
		public boolean set(BlockPos pos, BlockState state) {
			return offer(pos, Kind.SET, state, null, 0);
		}

		@Override
		public boolean remove(BlockPos pos) {
			return offer(pos, Kind.REMOVE, null, null, 0);
		}

		@Override
		public boolean launch(BlockPos pos, Vec3 velocity) {
			return offer(pos, Kind.LAUNCH, null, velocity, 0);
		}

		@Override
		public boolean placeTemporary(BlockPos pos, BlockState state, int ticks) {
			if (tier != EditTier.MEDIUM || ticks <= 0) return refuse(null);
			return offer(pos, Kind.TEMP, state, null, ticks);
		}

		private boolean offer(BlockPos pos, Kind kind, @Nullable BlockState state, @Nullable Vec3 velocity, int ticks) {
			if (closed || level.isOutsideBuildHeight(pos) || !level.isLoaded(pos)) return refuse(null);
			pos = pos.immutable();
			BlockState current = level.getBlockState(pos);
			// What the change would leave; null when it would change nothing.
			BlockState target = switch (kind) {
				case SET -> current.equals(state) ? null : state;
				case REMOVE -> {
					BlockState left = current.getFluidState().createLegacyBlock();
					yield current.equals(left) ? null : left;
				}
				case LAUNCH -> current.isAir() || current.getBlock() instanceof LiquidBlock ? null : current.getFluidState().createLegacyBlock();
				case TEMP -> current.isAir() || current.canBeReplaced() ? (current.equals(state) ? null : state) : null;
			};
			boolean blockEntity = current.hasBlockEntity() || level.getBlockEntity(pos) != null || (target != null && target.hasBlockEntity());
			boolean immune = current.is(IMMUNE) || (target != null && target.is(IMMUNE));
			boolean insideBorder = level.getWorldBorder().isWithinBounds(pos);
			// mayInteract also fails outside the border: keep that a border refusal, not "protected".
			boolean spawnProtected = insideBorder && !level.mayInteract(caster, pos);
			double distance = Math.sqrt(pos.distSqr(origin));
			Verdict v = TerrainRules.decide(blockEntity, immune, claimed.test(pos), spawnProtected, insideBorder, distance, tier.radius(),
					cast.used, tier.budget());
			if (v == Verdict.ALLOW && kind == Kind.LAUNCH && target != null && cast.launched >= MAX_LAUNCH) v = Verdict.OVER_BUDGET;
			if (v != Verdict.ALLOW) return refuse(v);
			if (target == null) {
				// Nothing to change (already that block, nothing to launch, or not air/replaceable for a temporary block).
				if (kind == Kind.TEMP && !(current.isAir() || current.canBeReplaced())) return refuse(null);
				return false;
			}
			cast.used++;
			if (kind == Kind.LAUNCH) cast.launched++;
			cast.pending++;
			cast.lastActive = WorldTerrainEdits.this.ticks;
			queue.add(new Change(cast, level, pos, kind, state, velocity, ticks));
			applied++;
			return true;
		}

		private boolean refuse(@Nullable Verdict v) {
			refused++;
			if ((v == Verdict.CLAIMED || v == Verdict.SPAWN_PROTECTED) && !cast.warned) {
				cast.warned = true;
				MagicServer.sender().actionBar(caster, Component.translatable(PROTECTED_KEY));
			}
			return false;
		}

		/** Changes this batch queued (they reach the world through the applier). */
		@Override
		public int applied() {
			return applied;
		}

		@Override
		public int refused() {
			return refused;
		}

		@Override
		public void close() {
			if (closed) return;
			closed = true;
			cast.openBatches--;
			cast.lastActive = WorldTerrainEdits.this.ticks;
		}
	}
}
