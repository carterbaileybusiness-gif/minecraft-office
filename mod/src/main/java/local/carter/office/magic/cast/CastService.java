package local.carter.office.magic.cast;

import local.carter.office.magic.core.Dir;
import local.carter.office.magic.spell.Spell;

import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;

/**
 * The server cast pipeline. Final signatures; {@link #cast}, {@link #release} and {@link #tick} land in Task 4,
 * {@link #answer} in Task 6.
 */
public final class CastService {
	public enum Result { CAST, CHALLENGED, FIZZLED, NO_ELEMENT, WRONG_ELEMENT, LOCKED, COOLDOWN, NO_MANA, NO_LEVELS, BUSY }

	private CastService() {}

	/** Casts {@code spell} at {@code charge} (0..1) after every check; ultimates return {@link Result#CHALLENGED}. */
	public static Result cast(ServerPlayer p, Spell spell, float charge) {
		throw new UnsupportedOperationException("CastService.cast lands in Task 4");
	}

	/** The grimoire was released after {@code ticksHeld}: cast the selected spell (tap or charged). */
	public static void release(ServerPlayer p, int ticksHeld) {
		throw new UnsupportedOperationException("CastService.release lands in Task 4");
	}

	/** The player's hand-sign answer for challenge {@code id}. */
	public static Result answer(ServerPlayer p, int id, Dir[] dirs) {
		throw new UnsupportedOperationException("CastService.answer lands in Task 6");
	}

	/** Once per server tick: mana regen, lingering effects, projectiles, challenge expiry. */
	public static void tick(MinecraftServer server) {
		throw new UnsupportedOperationException("CastService.tick lands in Task 4");
	}
}
