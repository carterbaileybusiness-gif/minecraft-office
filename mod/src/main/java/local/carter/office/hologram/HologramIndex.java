package local.carter.office.hologram;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import org.jspecify.annotations.Nullable;

import local.carter.office.OfficeMod;

import net.minecraft.core.BlockPos;
import net.minecraft.core.UUIDUtil;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.saveddata.SavedData;
import net.minecraft.world.level.saveddata.SavedDataType;

/**
 * Server-wide index (world {@code data/office/holograms.dat}): hologramId -> owner/dimension/pos, so the 8-per-player
 * limit and {@code /emitter} work for holograms in unloaded chunks. Also remembers who already got the starter kit.
 */
public class HologramIndex extends SavedData {
	public record Entry(UUID owner, ResourceKey<Level> dimension, BlockPos pos) {
		static final Codec<Entry> CODEC = RecordCodecBuilder.create(i -> i.group(
				UUIDUtil.STRING_CODEC.fieldOf("owner").forGetter(Entry::owner),
				Level.RESOURCE_KEY_CODEC.fieldOf("dimension").forGetter(Entry::dimension),
				BlockPos.CODEC.fieldOf("pos").forGetter(Entry::pos)).apply(i, Entry::new));
	}

	private static final Codec<HologramIndex> CODEC = RecordCodecBuilder.create(i -> i.group(
			Codec.unboundedMap(UUIDUtil.STRING_CODEC, Entry.CODEC).optionalFieldOf("holograms", Map.of())
					.forGetter(x -> x.holograms),
			UUIDUtil.STRING_CODEC.listOf().optionalFieldOf("starterKitGiven", List.of())
					.forGetter(x -> new ArrayList<>(x.starterKitGiven))).apply(i, HologramIndex::new));

	// Fabric allows a null DataFixTypes for mod saved data.
	public static final SavedDataType<HologramIndex> TYPE = new SavedDataType<>(OfficeMod.id("holograms"), HologramIndex::new, CODEC, null);

	private final Map<UUID, Entry> holograms = new HashMap<>();
	private final Set<UUID> starterKitGiven = new HashSet<>();
	private @Nullable MinecraftServer server;

	public HologramIndex() {}

	private HologramIndex(final Map<UUID, Entry> holograms, final List<UUID> starterKitGiven) {
		this.holograms.putAll(holograms);
		this.starterKitGiven.addAll(starterKitGiven);
	}

	public static HologramIndex get(final MinecraftServer server) {
		HologramIndex index = server.getDataStorage().computeIfAbsent(TYPE);
		index.server = server;
		return index;
	}

	public void put(final UUID hologramId, final UUID owner, final ResourceKey<Level> dimension, final BlockPos pos) {
		Entry e = new Entry(owner, dimension, pos.immutable());
		if (!e.equals(holograms.put(hologramId, e))) {
			setDirty();
		}
	}

	public void remove(final UUID hologramId) {
		if (holograms.remove(hologramId) != null) {
			setDirty();
		}
	}

	/** Live entries for {@code owner}; entries whose (loaded) block no longer holds that hologram are pruned. */
	public List<Entry> entriesOwnedBy(final UUID owner) {
		List<Entry> out = new ArrayList<>();
		for (var it = holograms.entrySet().iterator(); it.hasNext();) {
			Map.Entry<UUID, Entry> me = it.next();
			Entry e = me.getValue();
			if (!e.owner().equals(owner)) {
				continue;
			}
			if (isStale(me.getKey(), e)) {
				it.remove();
				setDirty();
				continue;
			}
			out.add(e);
		}
		return out;
	}

	public int countOwnedBy(final UUID owner) {
		return entriesOwnedBy(owner).size();
	}

	/** First display slot 1-4 not used by the owner's other holograms (1 if all are taken). */
	public int firstFreeSlot(final UUID owner) {
		if (server == null) {
			return 1;
		}
		Set<Integer> used = new HashSet<>();
		for (Entry e : entriesOwnedBy(owner)) {
			ServerLevel level = server.getLevel(e.dimension());
			if (level != null && level.isLoaded(e.pos()) && level.getBlockEntity(e.pos()) instanceof HologramBlockEntity be
					&& be.spec() != null) {
				used.add(be.spec().displaySlot());
			}
		}
		for (int slot = 1; slot <= 4; slot++) {
			if (!used.contains(slot)) {
				return slot;
			}
		}
		return 1;
	}

	private boolean isStale(final UUID hologramId, final Entry e) {
		if (server == null) {
			return false;
		}
		ServerLevel level = server.getLevel(e.dimension());
		if (level == null) {
			return true;
		}
		if (!level.isLoaded(e.pos())) {
			return false; // can't tell without loading the chunk; trust the index
		}
		return !(level.getBlockEntity(e.pos()) instanceof HologramBlockEntity be && be.hologramId().equals(hologramId));
	}

	/** Records that {@code player} got the starter kit; false if they already had it. */
	public boolean markStarterKitGiven(final UUID player) {
		if (starterKitGiven.add(player)) {
			setDirty();
			return true;
		}
		return false;
	}
}
