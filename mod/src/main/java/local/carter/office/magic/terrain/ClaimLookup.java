package local.carter.office.magic.terrain;

import java.util.UUID;

import org.jspecify.annotations.Nullable;

import net.minecraft.server.level.ServerLevel;

/** Who claimed a chunk: the claim owner's UUID, or null for an unclaimed chunk. */
@FunctionalInterface
public interface ClaimLookup {
	@Nullable UUID owner(ServerLevel level, int chunkX, int chunkZ);
}
