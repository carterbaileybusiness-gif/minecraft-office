package local.carter.office.magic.spell;

import net.minecraft.server.level.ServerLevel;

/**
 * Server work a spell leaves running after its cast (a wall, a channel, a vortex), ticked by {@link ActiveEffects}.
 * Entity-free: it lives only in memory, so a server stop ends it ({@link #end}).
 */
public interface LingeringEffect {
	/**
	 * One server tick. {@code age} is 1 on the first tick after {@link ActiveEffects#add} and grows by 1 each tick.
	 *
	 * @return true to keep running, false when done
	 */
	boolean tick(ServerLevel level, int age);

	/** Runs exactly once when the effect leaves the list for any reason: done, removed, failed, or the server stopping. */
	default void end(ServerLevel level) {}
}
