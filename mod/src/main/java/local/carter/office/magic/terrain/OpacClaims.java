package local.carter.office.magic.terrain;

import java.util.UUID;
import java.util.function.Predicate;

import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import it.unimi.dsi.fastutil.longs.Long2BooleanOpenHashMap;

import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;

import xaero.pac.common.claims.player.api.IPlayerChunkClaimAPI;
import xaero.pac.common.server.api.OpenPACServerAPI;

/**
 * Claims from Open Parties and Claims: a chunk claimed by any UUID but the caster's (another player, a party mate, the
 * server) is protected. Lookups are cached per chunk for one batch. A lookup that throws counts as claimed.
 *
 * <p>OPAC classes are touched only by {@link Api}, which is loaded only when {@link #api} runs (after the mod is known to be
 * installed), so this class itself loads without OPAC.
 */
public final class OpacClaims implements ClaimCheck {
	private static final Logger LOGGER = LoggerFactory.getLogger("office");
	private static volatile boolean warned;

	private final ClaimLookup lookup;

	public OpacClaims(ClaimLookup lookup) {
		this.lookup = lookup;
	}

	@Override
	public Predicate<BlockPos> forBatch(ServerPlayer caster, ServerLevel level, BlockPos origin, double radius) {
		return forCaster(caster.getUUID(), level);
	}

	/** The batch predicate for {@code caster} in {@code level} (the level is only passed on to the lookup). */
	public Predicate<BlockPos> forCaster(UUID caster, @Nullable ServerLevel level) {
		Long2BooleanOpenHashMap cache = new Long2BooleanOpenHashMap();
		return pos -> {
			int cx = pos.getX() >> 4;
			int cz = pos.getZ() >> 4;
			long key = ((long) cx << 32) | (cz & 0xffffffffL);
			if (cache.containsKey(key)) return cache.get(key);
			boolean claimed;
			try {
				UUID owner = lookup.owner(level, cx, cz);
				claimed = owner != null && !owner.equals(caster);
			} catch (RuntimeException e) {
				if (!warned) {
					warned = true;
					LOGGER.warn("Magic terrain: claim lookup failed; treating the chunk as claimed", e);
				}
				claimed = true;
			}
			cache.put(key, claimed);
			return claimed;
		};
	}

	/** The OPAC-backed lookup; throws (a {@link LinkageError} or a runtime exception) when the API does not resolve. */
	static ClaimLookup api(MinecraftServer server) {
		return new Api(server);
	}

	private static final class Api implements ClaimLookup {
		private final MinecraftServer server;

		Api(MinecraftServer server) {
			this.server = server;
			if (OpenPACServerAPI.get(server).getServerClaimsManager() == null) throw new IllegalStateException("no OPAC claims manager");
		}

		@Override
		public @Nullable UUID owner(ServerLevel level, int chunkX, int chunkZ) {
			IPlayerChunkClaimAPI claim = OpenPACServerAPI.get(server).getServerClaimsManager().get(level.dimension().identifier(), chunkX, chunkZ);
			return claim == null ? null : claim.getPlayerId();
		}
	}
}
