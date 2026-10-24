package local.carter.office.magic.terrain;

import java.util.function.Predicate;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;

import net.fabricmc.loader.api.FabricLoader;

/**
 * Whether a block lies in land the caster may not change ("claimed"). One predicate per edit batch, so a check can gather its
 * inputs once for the batch's area ({@code radius} around {@code origin}).
 */
@FunctionalInterface
public interface ClaimCheck {
	/** Open Parties and Claims' mod id. */
	String OPAC_MOD_ID = "openpartiesandclaims";

	/** Nothing is claimed. */
	ClaimCheck NONE = (caster, level, origin, radius) -> pos -> false;

	/** The returned predicate is true for a position inside someone else's claim. */
	Predicate<BlockPos> forBatch(ServerPlayer caster, ServerLevel level, BlockPos origin, double radius);

	/**
	 * Open Parties and Claims when it is installed and its API resolves; otherwise {@link FallbackClaims} (nothing within 32
	 * blocks of a hologram emitter or of another online player's home or respawn point).
	 */
	static ClaimCheck forServer(MinecraftServer server) {
		Logger log = LoggerFactory.getLogger("office");
		if (FabricLoader.getInstance().isModLoaded(OPAC_MOD_ID)) {
			try {
				ClaimCheck opac = new OpacClaims(OpacClaims.api(server));
				log.info("Magic terrain: using Open Parties and Claims claims");
				return opac;
			} catch (LinkageError | RuntimeException e) {
				log.warn("Magic terrain: Open Parties and Claims is installed but its API did not resolve; using the fallback claim rule", e);
			}
		}
		return new FallbackClaims(server);
	}
}
