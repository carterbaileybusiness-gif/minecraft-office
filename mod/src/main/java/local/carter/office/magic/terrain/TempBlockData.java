package local.carter.office.magic.terrain;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.jspecify.annotations.Nullable;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;

import local.carter.office.OfficeMod;

import net.minecraft.core.BlockPos;
import net.minecraft.core.GlobalPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.BlockTags;
import net.minecraft.tags.TagKey;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Fallable;
import net.minecraft.world.level.block.IceBlock;
import net.minecraft.world.level.block.LevelEvent;
import net.minecraft.world.level.block.LiquidBlock;
import net.minecraft.world.level.block.LiquidBlockContainer;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.gameevent.GameEvent;
import net.minecraft.world.level.saveddata.SavedData;
import net.minecraft.world.level.saveddata.SavedDataType;

/**
 * Temporary blocks (world {@code data/office/magic_temp.dat}): each entry remembers the original block and when to put it
 * back. An entry is restored only if its block is still the one placed (anything a player put there since stays).
 * <ul>
 * <li>Expired entries are restored at the end of each server tick, in loaded chunks only. One whose chunk is not loaded
 * waits (it is not polled) and is restored the moment that chunk loads ({@link #onChunkLoad}).</li>
 * <li>A chunk that unloads gets every one of its entries restored first, due or not ({@link #onChunkUnload}, before the chunk
 * is saved), so a temporary block never reaches the disk in a saved chunk while the server keeps running.</li>
 * <li>{@code SERVER_STARTED} restores what expired while the server was down; {@code SERVER_STOPPING} restores everything.</li>
 * </ul>
 * A temporary block is never a source of items: breaking one by any means (mining, silk touch, explosions, {@code Level.destroyBlock})
 * drops nothing and puts the original block back at once ({@link #breakTemp}, wired by {@link TempBlockGuard}); pistons can't
 * move one, and endermen and silverfish leave it alone.
 * Only inert states ({@link #isSafe}) are placed. {@link #place} is the low-level primitive: spells place temporary blocks
 * through {@link EditBatch#placeTemporary}, which also applies the claim, immunity and budget rules. Server thread only.
 */
public final class TempBlockData extends SavedData implements TempBlocks {
	/** The only states a temporary block may be. */
	public static final TagKey<Block> SAFE = TagKey.create(Registries.BLOCK, OfficeMod.id("magic_temp_safe"));
	/** Temporary blocks alive at once, server-wide. */
	public static final int MAX_ACTIVE = 4096;
	/** Chunk restores skip neighbour updates, shape updates, drops and on-place logic: the chunk is leaving or arriving. */
	private static final int CHUNK_FLAGS = Block.UPDATE_SKIP_ALL_SIDEEFFECTS;

	public record Entry(ResourceKey<Level> dimension, BlockPos pos, BlockState original, BlockState placed, long expireGameTime) {
		public static final Codec<Entry> CODEC = RecordCodecBuilder.create(i -> i.group(
				Level.RESOURCE_KEY_CODEC.fieldOf("dimension").forGetter(Entry::dimension),
				BlockPos.CODEC.fieldOf("pos").forGetter(Entry::pos),
				BlockState.CODEC.fieldOf("original").forGetter(Entry::original),
				BlockState.CODEC.fieldOf("placed").forGetter(Entry::placed),
				Codec.LONG.fieldOf("expire").forGetter(Entry::expireGameTime)).apply(i, Entry::new));

		GlobalPos key() {
			return GlobalPos.of(dimension, pos);
		}

		ChunkKey chunk() {
			return new ChunkKey(dimension, ChunkPos.pack(pos));
		}
	}

	private record ChunkKey(ResourceKey<Level> dimension, long chunk) {}

	private static final Codec<TempBlockData> CODEC = RecordCodecBuilder.create(i -> i.group(
			Entry.CODEC.listOf().optionalFieldOf("entries", List.of()).forGetter(TempBlockData::entries)).apply(i, TempBlockData::new));

	// Fabric allows a null DataFixTypes for mod saved data.
	public static final SavedDataType<TempBlockData> TYPE = new SavedDataType<>(OfficeMod.id("magic_temp"), TempBlockData::new, CODEC, null);

	private final Map<GlobalPos, Entry> entries = new LinkedHashMap<>();
	private final Map<ChunkKey, Set<GlobalPos>> byChunk = new HashMap<>();
	/** Expired entries whose chunk is not loaded: left for {@link #onChunkLoad}. */
	private final Set<GlobalPos> waiting = new HashSet<>();
	private long nextExpire = Long.MAX_VALUE;

	public TempBlockData() {}

	private TempBlockData(List<Entry> loaded) {
		for (Entry e : loaded) put(e);
	}

	public static TempBlockData get(MinecraftServer server) {
		return server.getDataStorage().computeIfAbsent(TYPE);
	}

	/**
	 * Whether {@code state} may be a temporary block: in {@link #SAFE}, and not a liquid, a falling block, melting ice, a block
	 * entity, half of a two-block-tall block, a light source, or something flowing water or lava can wash away or flow into
	 * (checked even if the tag is widened). No light, because a chunk restore ({@link #onChunkUnload}) skips the light engine;
	 * nothing washable, so a fluid never breaks a temporary block (and never drops it).
	 */
	public static boolean isSafe(BlockState state) {
		Block b = state.getBlock();
		return state.is(SAFE) && !(b instanceof LiquidBlock) && !(b instanceof Fallable) && !(b instanceof IceBlock) && !state.hasBlockEntity()
				&& !state.hasProperty(BlockStateProperties.DOUBLE_BLOCK_HALF) && state.getFluidState().isEmpty() && state.getLightEmission() == 0
				&& !state.is(BlockTags.WASHED_AWAY_BY_FLUIDS) && !(b instanceof LiquidBlockContainer);
	}

	/** Whether a temporary block may replace {@code current}: air or replaceable, not a block entity, not half of a tall plant. */
	public static boolean canReplace(BlockState current) {
		return (current.isAir() || current.canBeReplaced()) && !current.hasBlockEntity()
				&& !current.hasProperty(BlockStateProperties.DOUBLE_BLOCK_HALF);
	}

	/** A copy of every live entry. */
	public List<Entry> entries() {
		return new ArrayList<>(entries.values());
	}

	private void put(Entry e) {
		GlobalPos key = e.key();
		entries.put(key, e);
		byChunk.computeIfAbsent(e.chunk(), k -> new LinkedHashSet<>()).add(key);
		waiting.remove(key);
		nextExpire = Math.min(nextExpire, e.expireGameTime());
	}

	private void drop(Entry e) {
		GlobalPos key = e.key();
		entries.remove(key);
		waiting.remove(key);
		Set<GlobalPos> inChunk = byChunk.get(e.chunk());
		if (inChunk != null) {
			inChunk.remove(key);
			if (inChunk.isEmpty()) byChunk.remove(e.chunk());
		}
		setDirty();
	}

	/** Whether {@code pos} holds a live temporary block: an entry here, and its placed state still in the world. */
	public boolean isTemp(Level level, BlockPos pos) {
		if (entries.isEmpty()) return false;
		Entry e = entries.get(GlobalPos.of(level.dimension(), pos));
		return e != null && level.isLoaded(pos) && level.getBlockState(pos).equals(e.placed());
	}

	/**
	 * Breaks the live temporary block at {@code pos}: no items, no XP, and the original block comes back at once (the entry is done,
	 * so the later restore has nothing to undo). With {@code effects}, other players see and hear the block break (the
	 * {@code breaker}, if any, already did on their own client). False, changing nothing, when {@code pos} holds no live temporary block.
	 */
	public boolean breakTemp(ServerLevel level, BlockPos pos, @Nullable Entity breaker, boolean effects) {
		if (!isTemp(level, pos)) return false;
		Entry e = entries.get(GlobalPos.of(level.dimension(), pos));
		BlockState broken = e.placed();
		drop(e);
		if (effects) level.levelEvent(breaker, LevelEvent.PARTICLES_AND_SOUND_DESTROY_BLOCK, pos, Block.getId(broken));
		level.setBlock(pos, e.original(), Block.UPDATE_ALL);
		level.gameEvent(GameEvent.BLOCK_DESTROY, pos, GameEvent.Context.of(breaker, broken));
		return true;
	}

	@Override
	public boolean holds(ServerLevel level, BlockPos pos, BlockState state) {
		Entry e = entries.get(GlobalPos.of(level.dimension(), pos));
		return e != null && e.placed().equals(state) && level.isLoaded(pos) && level.getBlockState(pos).equals(state);
	}

	/**
	 * Places {@code placed} at {@code pos} for {@code ticks}, over air or a replaceable block ({@link #canReplace}). Placing over a
	 * live temporary block of this data replaces it and keeps the later expiry (so the same state again refreshes the timer);
	 * the original stays the first one. False for an unsafe state ({@link #isSafe}).
	 */
	@Override
	public boolean place(ServerLevel level, BlockPos pos, BlockState placed, int ticks) {
		if (ticks <= 0 || !isSafe(placed) || level.isOutsideBuildHeight(pos) || !level.isLoaded(pos)) return false;
		pos = pos.immutable();
		BlockState current = level.getBlockState(pos);
		long expire = level.getGameTime() + ticks;
		Entry existing = entries.get(GlobalPos.of(level.dimension(), pos));
		if (existing != null && !current.equals(existing.placed())) {
			drop(existing); // the cell changed since: that entry is dead
			existing = null;
		}
		if (existing == null) {
			if (!canReplace(current) || entries.size() >= MAX_ACTIVE) return false;
		}
		if (!current.equals(placed) && !level.setBlock(pos, placed, Block.UPDATE_ALL)) return false;
		BlockState now = level.getBlockState(pos);
		if (existing != null) {
			put(new Entry(existing.dimension(), pos, existing.original(), now, Math.max(existing.expireGameTime(), expire)));
		} else {
			put(new Entry(level.dimension(), pos, current, now, expire));
		}
		setDirty();
		return true;
	}

	@Override
	public int active() {
		return entries.size();
	}

	/** Expired entries waiting for their chunk to load. */
	public int waiting() {
		return waiting.size();
	}

	/** Restores every expired entry in a loaded chunk (end of each server tick, and on {@code SERVER_STARTED}). */
	public void tick(MinecraftServer server) {
		long now = server.overworld().getGameTime();
		if (now < nextExpire) return;
		long next = Long.MAX_VALUE;
		for (Iterator<Entry> it = new ArrayList<>(entries.values()).iterator(); it.hasNext();) {
			Entry e = it.next();
			if (waiting.contains(e.key())) continue;
			if (e.expireGameTime() > now) {
				next = Math.min(next, e.expireGameTime());
				continue;
			}
			ServerLevel level = server.getLevel(e.dimension());
			if (level != null && !level.isLoaded(e.pos())) {
				waiting.add(e.key()); // never load a chunk just to restore it: onChunkLoad does it
				continue;
			}
			drop(e);
			if (level != null && level.getBlockState(e.pos()).equals(e.placed())) level.setBlock(e.pos(), e.original(), Block.UPDATE_ALL);
		}
		nextExpire = next;
	}

	/** {@code CHUNK_UNLOAD} (fired before the chunk is saved): restores every entry in the chunk, due or not. */
	public void onChunkUnload(ServerLevel level, LevelChunk chunk) {
		for (Entry e : inChunk(level, chunk)) restoreInChunk(chunk, e);
	}

	/** {@code CHUNK_LOAD}: restores the chunk's expired entries right away (left behind by a crash, or waiting for this load). */
	public void onChunkLoad(ServerLevel level, LevelChunk chunk) {
		long now = level.getGameTime();
		for (Entry e : inChunk(level, chunk)) if (e.expireGameTime() <= now) restoreInChunk(chunk, e);
	}

	private List<Entry> inChunk(ServerLevel level, LevelChunk chunk) {
		Set<GlobalPos> keys = byChunk.get(new ChunkKey(level.dimension(), chunk.getPos().pack()));
		if (keys == null) return List.of();
		List<Entry> out = new ArrayList<>();
		for (GlobalPos k : keys) out.add(entries.get(k));
		return out;
	}

	private void restoreInChunk(LevelChunk chunk, Entry e) {
		drop(e);
		if (chunk.getBlockState(e.pos()).equals(e.placed())) chunk.setBlockState(e.pos(), e.original(), CHUNK_FLAGS);
	}

	/** Restores every entry now, expired or not (loads chunks if needed): the server is stopping. */
	@Override
	public void restoreAll(MinecraftServer server) {
		if (entries.isEmpty()) return;
		List<Entry> all = new ArrayList<>(entries.values());
		for (Entry e : all) drop(e);
		nextExpire = Long.MAX_VALUE;
		for (Entry e : all) {
			ServerLevel level = server.getLevel(e.dimension());
			if (level != null && level.getBlockState(e.pos()).equals(e.placed())) level.setBlock(e.pos(), e.original(), Block.UPDATE_ALL);
		}
	}
}
