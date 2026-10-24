package local.carter.office.magic;

import static org.junit.jupiter.api.Assertions.*;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Predicate;

import org.junit.jupiter.api.Test;

import local.carter.office.magic.terrain.ClaimLookup;
import local.carter.office.magic.terrain.OpacClaims;

import net.minecraft.core.BlockPos;

/** {@link OpacClaims} with a fake {@link ClaimLookup}: only other players' claims block, one lookup per chunk per batch. */
class OpacClaimsTest {
	private static final UUID ME = UUID.randomUUID();
	private static final UUID OTHER = UUID.randomUUID();

	private static ClaimLookup fake(Map<Long, UUID> owners, List<String> calls) {
		return (level, cx, cz) -> {
			calls.add(cx + "," + cz);
			return owners.get(key(cx, cz));
		};
	}

	private static long key(int cx, int cz) {
		return ((long) cx << 32) | (cz & 0xffffffffL);
	}

	@Test
	void ownClaimAllowedOthersBlockedUnclaimedAllowed() {
		List<String> calls = new ArrayList<>();
		Map<Long, UUID> owners = Map.of(key(0, 0), ME, key(1, 0), OTHER, key(-1, -1), OTHER);
		Predicate<BlockPos> claimed = new OpacClaims(fake(owners, calls)).forCaster(ME, null);
		assertFalse(claimed.test(new BlockPos(3, 64, 3)), "own claim");
		assertTrue(claimed.test(new BlockPos(16, 64, 0)), "another player's claim");
		assertTrue(claimed.test(new BlockPos(-1, 10, -1)), "another player's claim at negative chunk coordinates");
		assertFalse(claimed.test(new BlockPos(40, 64, 40)), "unclaimed");
	}

	@Test
	void lookupCalledOncePerChunk() {
		List<String> calls = new ArrayList<>();
		Predicate<BlockPos> claimed = new OpacClaims(fake(Map.of(key(1, 0), OTHER), calls)).forCaster(ME, null);
		for (int x = 0; x < 32; x++) {
			for (int y = 60; y < 64; y++) {
				for (int z = 0; z < 16; z++) claimed.test(new BlockPos(x, y, z));
			}
		}
		assertEquals(List.of("0,0", "1,0"), calls, "one call per chunk, in first-seen order");
		claimed.test(new BlockPos(20, 0, 5));
		assertEquals(2, calls.size(), "cached");
	}

	@Test
	void failingLookupCountsAsClaimed() {
		Predicate<BlockPos> claimed = new OpacClaims((level, cx, cz) -> {
			throw new IllegalStateException("claims not ready");
		}).forCaster(ME, null);
		assertTrue(claimed.test(BlockPos.ZERO), "fail safe");
	}
}
