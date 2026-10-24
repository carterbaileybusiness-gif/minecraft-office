package local.carter.office.magic.terrain;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;

import local.carter.office.OfficeMod;

import net.minecraft.core.BlockPos;
import net.minecraft.core.GlobalPos;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.saveddata.SavedData;
import net.minecraft.world.level.saveddata.SavedDataType;

/**
 * Temporary blocks (world {@code data/office/magic_temp.dat}): each entry remembers the original block and when to put it
 * back. An expired entry is restored only if its block is still the one placed (anything a player put there since stays).
 * Saved with the world, so a restart restores what expired meanwhile ({@code SERVER_STARTED}) and a stop restores everything
 * ({@code SERVER_STOPPING}). Server thread only.
 *
 * <p>{@link #place} is the low-level primitive: it checks only the cell (air or replaceable). Spells place temporary blocks
 * through {@link EditBatch#placeTemporary}, which also applies the claim, immunity and budget rules.
 */
public final class TempBlockData extends SavedData implements TempBlocks {
	/** Temporary blocks alive at once, server-wide. */
	public static final int MAX_ACTIVE = 4096;
	/** An expired entry in an unloaded chunk is retried this many ticks later (never loads a chunk just to restore it). */
	private static final int UNLOADED_RETRY = 20;

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
	}

	private static final Codec<TempBlockData> CODEC = RecordCodecBuilder.create(i -> i.group(
			Entry.CODEC.listOf().optionalFieldOf("entries", List.of()).forGetter(TempBlockData::entries)).apply(i, TempBlockData::new));

	// Fabric allows a null DataFixTypes for mod saved data.
	public static final SavedDataType<TempBlockData> TYPE = new SavedDataType<>(OfficeMod.id("magic_temp"), TempBlockData::new, CODEC, null);

	private final Map<GlobalPos, Entry> entries = new LinkedHashMap<>();
	private long nextExpire = Long.MAX_VALUE;

	public TempBlockData() {}

	private TempBlockData(List<Entry> loaded) {
		for (Entry e : loaded) put(e);
	}

	public static TempBlockData get(MinecraftServer server) {
		return server.getDataStorage().computeIfAbsent(TYPE);
	}

	/** A copy of every live entry. */
	public List<Entry> entries() {
		return new ArrayList<>(entries.values());
	}

	private void put(Entry e) {
		entries.put(e.key(), e);
		nextExpire = Math.min(nextExpire, e.expireGameTime());
	}

	/**
	 * Places {@code placed} at {@code pos} for {@code ticks}, over air or a replaceable block (water, grass, snow…). Placing again
	 * over a live temporary block of this data replaces it and keeps the later expiry; the original stays the first one.
	 */
	@Override
	public boolean place(ServerLevel level, BlockPos pos, BlockState placed, int ticks) {
		if (ticks <= 0 || placed.hasBlockEntity() || level.isOutsideBuildHeight(pos) || !level.isLoaded(pos)) return false;
		pos = pos.immutable();
		GlobalPos key = GlobalPos.of(level.dimension(), pos);
		BlockState current = level.getBlockState(pos);
		long expire = level.getGameTime() + ticks;
		Entry existing = entries.get(key);
		if (existing != null && !current.equals(existing.placed())) {
			entries.remove(key); // the cell changed since: that entry is dead
			existing = null;
		}
		if (existing == null) {
			if (!(current.isAir() || current.canBeReplaced()) || current.hasBlockEntity()) return false;
			if (entries.size() >= MAX_ACTIVE) return false;
		}
		if (!level.setBlock(pos, placed, Block.UPDATE_ALL)) return false;
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

	/** Restores every expired entry (end of each server tick, and on {@code SERVER_STARTED}). */
	public void tick(MinecraftServer server) {
		long now = server.overworld().getGameTime();
		if (now < nextExpire) return;
		long next = Long.MAX_VALUE;
		List<Entry> retry = new ArrayList<>();
		for (Iterator<Entry> it = entries.values().iterator(); it.hasNext();) {
			Entry e = it.next();
			if (e.expireGameTime() > now) {
				next = Math.min(next, e.expireGameTime());
				continue;
			}
			ServerLevel level = server.getLevel(e.dimension());
			if (level != null && !level.isLoaded(e.pos())) {
				retry.add(new Entry(e.dimension(), e.pos(), e.original(), e.placed(), now + UNLOADED_RETRY));
				continue;
			}
			it.remove();
			setDirty();
			restore(level, e);
		}
		nextExpire = next;
		for (Entry e : retry) put(e);
	}

	/** Restores every entry now, expired or not (loads chunks if needed): the server is stopping. */
	@Override
	public void restoreAll(MinecraftServer server) {
		if (entries.isEmpty()) return;
		List<Entry> all = new ArrayList<>(entries.values());
		entries.clear();
		nextExpire = Long.MAX_VALUE;
		setDirty();
		for (Entry e : all) restore(server.getLevel(e.dimension()), e);
	}

	private static void restore(ServerLevel level, Entry e) {
		if (level == null) return;
		if (level.getBlockState(e.pos()).equals(e.placed())) level.setBlock(e.pos(), e.original(), Block.UPDATE_ALL);
	}
}
