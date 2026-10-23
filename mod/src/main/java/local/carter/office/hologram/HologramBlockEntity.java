package local.carter.office.hologram;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.mojang.logging.LogUtils;
import com.mojang.serialization.Codec;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;

import local.carter.office.core.HologramSpec;
import local.carter.office.core.Mode;
import local.carter.office.core.Privacy;
import local.carter.office.core.Shape;
import local.carter.office.stream.StreamRouter;

import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.UUIDUtil;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ClientGamePacketListener;
import net.minecraft.network.protocol.game.ClientboundBlockEntityDataPacket;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.storage.ValueInput;
import net.minecraft.world.level.storage.ValueOutput;

/**
 * One placed hologram. {@link #spec()} is null only for an emitter nobody placed (e.g. {@code /setblock}).
 *
 * <p>Spec conventions (for rendering): offsets are in blocks from the emitter block's centre
 * ({@code pos + 0.5}); {@code yaw} is a Minecraft yaw in degrees (0 = the front faces south/+Z, 90 = west) giving the
 * direction the hologram's front faces.
 *
 * <p>Persisted under {@code "spec"} with {@link HologramSpec#toMap()} keys plus {@code "hologramId"}; synced to clients
 * with the block-entity update packet, which also carries {@code "ownerOnline"}.
 */
public class HologramBlockEntity extends BlockEntity {
	private static final Logger LOGGER = LogUtils.getLogger();

	private UUID hologramId = UUID.randomUUID();
	private @Nullable HologramSpec spec;
	private boolean ownerOnline;
	/** Owner's name when placed (for "Owned by …", "Private" and "Offline" panels); empty if unknown. */
	private String ownerName = "";

	public HologramBlockEntity(final BlockPos pos, final BlockState state) {
		super(OfficeBlocks.HOLOGRAM_BE, pos, state);
	}

	public UUID hologramId() {
		return hologramId;
	}

	public @Nullable HologramSpec spec() {
		return spec;
	}

	public boolean ownerOnline() {
		return ownerOnline;
	}

	/** The owner's name as recorded at placement; empty for emitters placed before names were recorded. */
	public String ownerName() {
		return ownerName;
	}

	public boolean isOwner(final UUID player) {
		return spec != null && spec.owner().equals(player);
	}

	/** Unowned emitters (no spec) can be removed by anyone; owned ones only by the owner. */
	public boolean mayRemove(final UUID player) {
		return spec == null || isOwner(player);
	}

	/** Server: fresh identity and spec for a newly placed emitter (discards any data copied onto the item). */
	void initPlaced(final HologramSpec newSpec, final String name, final boolean online) {
		this.hologramId = UUID.randomUUID();
		this.spec = newSpec;
		this.ownerName = name == null ? "" : name;
		this.ownerOnline = online;
		changed();
	}

	/** Server: replace the spec (callers check ownership), persist and sync to clients. */
	public void setSpec(final HologramSpec newSpec) {
		if (spec != null && !spec.owner().equals(newSpec.owner())) {
			throw new IllegalArgumentException("owner cannot change");
		}
		this.spec = newSpec;
		changed();
	}

	private void setOwnerOnlineFlag(final boolean online) {
		if (this.ownerOnline != online) {
			this.ownerOnline = online;
			changed();
		}
	}

	private void changed() {
		setChanged();
		if (level != null && !level.isClientSide()) {
			level.sendBlockUpdated(worldPosition, getBlockState(), getBlockState(), Block.UPDATE_CLIENTS);
		}
	}

	/** Server chunk load: recompute {@code ownerOnline} and make sure the index knows about this hologram. */
	public void onServerLoad(final ServerLevel serverLevel) {
		if (spec == null) {
			return;
		}
		MinecraftServer server = serverLevel.getServer();
		this.ownerOnline = server.getPlayerList().getPlayer(spec.owner()) != null;
		HologramIndex.get(server).put(hologramId, spec.owner(), serverLevel.dimension(), worldPosition);
	}

	/** Owner joined or left: update every loaded hologram they own. */
	public static void setOwnerOnline(final MinecraftServer server, final UUID owner, final boolean online) {
		for (HologramIndex.Entry e : HologramIndex.get(server).entriesOwnedBy(owner)) {
			ServerLevel level = server.getLevel(e.dimension());
			if (level != null && level.isLoaded(e.pos()) && level.getBlockEntity(e.pos()) instanceof HologramBlockEntity be
					&& be.isOwner(owner)) {
				be.setOwnerOnlineFlag(online);
			}
		}
	}

	@Override
	public void preRemoveSideEffects(final BlockPos pos, final BlockState state) {
		super.preRemoveSideEffects(pos, state);
		if (level instanceof ServerLevel serverLevel) {
			HologramIndex.get(serverLevel.getServer()).remove(hologramId);
			StreamRouter.signalChange(); // any removal path (hand break, /setblock): end its stream on the next tick
		}
	}

	@Override
	protected void saveAdditional(final ValueOutput output) {
		super.saveAdditional(output);
		output.store("hologramId", UUIDUtil.CODEC, hologramId);
		if (!ownerName.isEmpty()) {
			output.putString("ownerName", ownerName);
		}
		if (spec != null) {
			writeSpec(output.child("spec"), spec);
		}
	}

	@Override
	protected void loadAdditional(final ValueInput input) {
		super.loadAdditional(input);
		hologramId = input.read("hologramId", UUIDUtil.CODEC).orElse(hologramId);
		spec = input.child("spec").map(HologramBlockEntity::readSpec).orElse(null);
		ownerName = input.getStringOr("ownerName", "");
		ownerOnline = input.getBooleanOr("ownerOnline", false);
	}

	@Override
	public Packet<ClientGamePacketListener> getUpdatePacket() {
		return ClientboundBlockEntityDataPacket.create(this);
	}

	@Override
	public CompoundTag getUpdateTag(final HolderLookup.Provider registries) {
		CompoundTag tag = saveCustomOnly(registries);
		tag.putBoolean("ownerOnline", ownerOnline);
		return tag;
	}

	static void writeSpec(final ValueOutput out, final HologramSpec spec) {
		for (Map.Entry<String, Object> e : spec.toMap().entrySet()) {
			String k = e.getKey();
			switch (e.getValue()) {
				case String s -> out.putString(k, s);
				case Integer i -> out.putInt(k, i);
				case Double d -> out.putDouble(k, d);
				case Float f -> out.putFloat(k, f);
				case List<?> list -> {
					ValueOutput.TypedOutputList<String> l = out.list(k, Codec.STRING);
					for (Object o : list) {
						l.add(o.toString());
					}
				}
				default -> throw new IllegalStateException("unexpected spec value for " + k + ": " + e.getValue());
			}
		}
	}

	/** Reads a spec, filling defaults for missing keys; null (logged) if there is no valid owner. */
	static @Nullable HologramSpec readSpec(final ValueInput in) {
		Map<String, Object> m = new HashMap<>();
		in.getString("owner").ifPresent(v -> m.put("owner", v));
		m.put("shape", in.getStringOr("shape", Shape.WIDE_16_9.name()));
		m.put("mode", in.getStringOr("mode", Mode.EXTEND.name()));
		m.put("privacy", in.getStringOr("privacy", Privacy.Level.PUBLIC.name()));
		in.getString("windowKey").ifPresent(v -> m.put("windowKey", v));
		List<String> allowed = new ArrayList<>();
		in.listOrEmpty("allowed", Codec.STRING).stream().forEach(allowed::add);
		m.put("allowed", allowed);
		m.put("displaySlot", in.getIntOr("displaySlot", 1));
		m.put("width", in.getDoubleOr("width", HologramEmitterBlock.DEFAULT_WIDTH));
		m.put("offX", in.getDoubleOr("offX", 0.0));
		m.put("offY", in.getDoubleOr("offY", 1.0));
		m.put("offZ", in.getDoubleOr("offZ", 0.0));
		m.put("yaw", in.getFloatOr("yaw", 0.0F));
		if (!m.containsKey("owner")) {
			return null;
		}
		try {
			return HologramSpec.fromMap(m);
		} catch (RuntimeException ex) {
			LOGGER.warn("Dropping unreadable hologram spec {}: {}", m, ex.toString());
			return null;
		}
	}
}
