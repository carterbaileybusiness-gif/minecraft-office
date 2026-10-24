package local.carter.office.magic.terrain;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.function.Predicate;

import local.carter.office.hologram.HologramIndex;
import local.carter.office.magic.data.MagicAttachments;

import net.minecraft.core.BlockPos;
import net.minecraft.core.GlobalPos;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.Level;

/**
 * The claim rule without Open Parties and Claims: nothing within {@link #RADIUS} blocks of any hologram emitter in the batch's
 * dimension (anyone's, the caster's too), of another online player's Recall home ({@code MagicData.home}), or of another
 * online player's explicit respawn point (bed, anchor, {@code /spawnpoint}). Offline players are not read.
 */
public final class FallbackClaims implements ClaimCheck {
	public static final double RADIUS = 32;

	private final MinecraftServer server;

	public FallbackClaims(MinecraftServer server) {
		this.server = server;
	}

	@Override
	public Predicate<BlockPos> forBatch(ServerPlayer caster, ServerLevel level, BlockPos origin, double radius) {
		List<BlockPos> centres = protectedCentres(caster.getUUID(), level.dimension(), origin, radius + RADIUS + 1);
		if (centres.isEmpty()) return pos -> false;
		double r2 = RADIUS * RADIUS;
		return pos -> {
			for (BlockPos c : centres) if (c.distSqr(pos) <= r2) return true;
			return false;
		};
	}

	/** Every protection centre in {@code dimension} within {@code reach} of {@code origin}. */
	private List<BlockPos> protectedCentres(UUID caster, ResourceKey<Level> dimension, BlockPos origin, double reach) {
		double reach2 = reach * reach;
		List<BlockPos> out = new ArrayList<>();
		for (HologramIndex.Entry e : HologramIndex.get(server).entriesIn(dimension)) add(out, e.pos(), origin, reach2);
		for (ServerPlayer p : server.getPlayerList().getPlayers()) {
			if (p.getUUID().equals(caster)) continue;
			GlobalPos home = MagicAttachments.get(p).home().orElse(null);
			if (home != null && home.dimension().equals(dimension)) add(out, home.pos(), origin, reach2);
			ServerPlayer.RespawnConfig respawn = p.getRespawnConfig();
			if (respawn != null && respawn.respawnData().dimension().equals(dimension)) add(out, respawn.respawnData().pos(), origin, reach2);
		}
		return out;
	}

	private static void add(List<BlockPos> out, BlockPos centre, BlockPos origin, double reach2) {
		if (centre.distSqr(origin) <= reach2) out.add(centre.immutable());
	}
}
