package local.carter.office.magic.terrain;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.function.BooleanSupplier;

import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.item.FallingBlockEntity;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;

/**
 * Falling blocks a spell launched ({@link EditBatch#launch}), and where they may land. {@code FallingBlockEntityMixin} sends
 * every landing of tagged debris here.
 * <ul>
 * <li>Debris carries the entity tag {@link #TAG} (saved with the entity) and, while it flies, a record of the cast that
 * launched it, keyed by entity UUID.</li>
 * <li>Before vanilla places it, the cast's {@link Landing} checks the landing cell with the edit rules (relative to the
 * caster) and places it, logging the placement in the cast so {@code /magic undo} removes it. A refused landing discards the
 * debris with a puff: no block, no item.</li>
 * <li>Debris without a record (saved in the world before a restart, or flying longer than {@link #MAX_FLIGHT_TICKS}) never
 * lands.</li>
 * <li>Tagged debris never drops an item, whatever {@code dropItem} says.</li>
 * </ul>
 * Server thread only.
 */
public final class LaunchedDebris {
	/** Entity tag of launched debris. */
	public static final String TAG = "office_magic_debris";
	/** A flight record is forgotten this long after the launch (vanilla discards a falling block after 600 ticks in the air). */
	public static final int MAX_FLIGHT_TICKS = 1200;

	/** Decides and performs one landing of debris launched by one cast. */
	@FunctionalInterface
	interface Landing {
		/**
		 * Whether {@code state} may land at {@code pos}; when it may, runs {@code place} (vanilla's placement) and logs what it
		 * changed. True only if the block was placed.
		 */
		boolean land(FallingBlockEntity debris, ServerLevel level, BlockPos pos, BlockState state, BooleanSupplier place);
	}

	private record Flight(Landing landing, int launchedAt) {}

	private static final Map<UUID, Flight> FLIGHTS = new HashMap<>();

	private LaunchedDebris() {}

	/** Tags {@code debris} and records the cast it lands for. */
	static void track(FallingBlockEntity debris, ServerLevel level, Landing landing) {
		int now = level.getServer().getTickCount();
		prune(now);
		debris.addTag(TAG);
		FLIGHTS.put(debris.getUUID(), new Flight(landing, now));
	}

	/**
	 * Forgets flights older than {@link #MAX_FLIGHT_TICKS} (debris vanilla discarded, broke on a torch, or that was killed) at
	 * server tick {@code now}. Runs on every launch and landing, and once per server tick.
	 */
	public static void prune(int now) {
		if (FLIGHTS.isEmpty()) return;
		FLIGHTS.values().removeIf(f -> now - f.launchedAt() > MAX_FLIGHT_TICKS || now < f.launchedAt());
	}

	/** Server stopping: forget every flight (debris saved with the world never lands after a restart). */
	static void clear() {
		FLIGHTS.clear();
	}

	/** Debris still flying with a record. */
	public static int flying() {
		return FLIGHTS.size();
	}

	public static boolean isDebris(Entity e) {
		return e.entityTags().contains(TAG);
	}

	/**
	 * Tagged debris is about to become a block at {@code pos} ({@code place} is vanilla's {@code setBlockAndUpdate}). Returns
	 * what vanilla's call returns. When the landing is refused (or the placement fails), the debris is discarded with a puff.
	 */
	public static boolean land(FallingBlockEntity debris, Level level, BlockPos pos, BlockState state, BooleanSupplier place) {
		if (!(level instanceof ServerLevel server)) return place.getAsBoolean();
		Flight f = FLIGHTS.remove(debris.getUUID());
		prune(server.getServer().getTickCount());
		boolean placed = f != null && f.landing().land(debris, server, pos.immutable(), state, place);
		if (!placed) {
			debris.discard();
			server.sendParticles(ParticleTypes.POOF, debris.getX(), debris.getY() + 0.5, debris.getZ(), 4, 0.2, 0.2, 0.2, 0.01);
		}
		return placed;
	}
}
