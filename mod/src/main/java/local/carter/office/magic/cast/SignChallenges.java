package local.carter.office.magic.cast;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import org.jspecify.annotations.Nullable;

import local.carter.office.magic.core.Dir;
import local.carter.office.magic.core.HandSigns;
import local.carter.office.magic.data.MagicAttachments;
import local.carter.office.magic.net.MagicPayloads.SignChallenge;
import local.carter.office.magic.net.MagicServer;
import local.carter.office.magic.spell.Spell;

import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;

/**
 * Open hand-sign challenges, at most one per player. {@link CastService#cast} issues one for an ultimate instead of casting it;
 * {@link CastService#answer} takes it back and casts or fizzles; {@link CastService#tick} fizzles the ones nobody answered in
 * time. {@link #discard} (death, disconnect, dimension change) drops one at no cost.
 */
public final class SignChallenges {
	/** What the server remembers about a challenge it sent. */
	record Open(int id, Spell spell, float power, Dir[] expected, long issuedAt, int limitTicks) {
		/** True once {@code now} is past the client limit plus {@link HandSigns#GRACE_TICKS}. */
		boolean late(long now) {
			return now - issuedAt > (long) limitTicks + HandSigns.GRACE_TICKS;
		}
	}

	private static final AtomicInteger IDS = new AtomicInteger();
	private static final Map<UUID, Open> OPEN = new HashMap<>();

	private SignChallenges() {}

	/**
	 * Opens a challenge for {@code spell} and sends it: a fresh id, a seed from the level's {@code RandomSource},
	 * {@link HandSigns#length} of the player's grimoire level and {@link HandSigns#limitTicks} of that. {@code power} is kept for
	 * the record only: an ultimate always casts at charge 1 after the answer-time re-check. Replaces (drops) any open one.
	 */
	public static SignChallenge issue(ServerPlayer p, Spell spell, float power) {
		int length = HandSigns.length(MagicAttachments.get(p).level());
		int limit = HandSigns.limitTicks(length);
		long seed = p.level().getRandom().nextLong();
		SignChallenge c = new SignChallenge(IDS.incrementAndGet(), spell.id(), seed, length, limit);
		OPEN.put(p.getUUID(), new Open(c.id(), spell, power, HandSigns.sequence(seed, length), p.level().getGameTime(), limit));
		MagicServer.sender().challenge(p, c);
		return c;
	}

	/** True while {@code p} has a challenge waiting for an answer. */
	public static boolean pending(ServerPlayer p) {
		return OPEN.containsKey(p.getUUID());
	}

	/** Drops {@code p}'s open challenge without any cost (death, disconnect, dimension change). */
	public static void discard(ServerPlayer p) {
		OPEN.remove(p.getUUID());
	}

	/** Removes and returns {@code p}'s open challenge if its id is {@code id}; any other id leaves it open and returns null. */
	static @Nullable Open take(ServerPlayer p, int id) {
		Open o = OPEN.get(p.getUUID());
		if (o == null || o.id() != id) return null;
		OPEN.remove(p.getUUID());
		return o;
	}

	/** Removes every challenge past its limit plus grace and returns them with their (online) players; the rest stay open. */
	static List<Map.Entry<ServerPlayer, Open>> expire(MinecraftServer server) {
		if (OPEN.isEmpty()) return List.of();
		List<Map.Entry<ServerPlayer, Open>> out = new ArrayList<>();
		var it = OPEN.entrySet().iterator();
		while (it.hasNext()) {
			var e = it.next();
			ServerPlayer p = server.getPlayerList().getPlayer(e.getKey());
			if (p == null) {
				it.remove(); // left without a DISCONNECT (should not happen): nothing to charge
			} else if (e.getValue().late(p.level().getGameTime())) {
				it.remove();
				out.add(Map.entry(p, e.getValue()));
			}
		}
		return out;
	}

	/** Server stopping: forget every challenge. */
	static void clear() {
		OPEN.clear();
	}
}
