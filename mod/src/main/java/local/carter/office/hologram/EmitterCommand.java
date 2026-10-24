package local.carter.office.hologram;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.LongSupplier;

import com.mojang.brigadier.CommandDispatcher;

import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.ItemStack;

/**
 * {@code /emitter}: anyone gets one free emitter while placed holograms plus emitters they already carry stay under the
 * limit, at most once per {@link #COOLDOWN_MS}. Never drops anything: a full inventory just gets a message.
 */
public final class EmitterCommand {
	public static final long COOLDOWN_MS = 10_000L;
	public static final String COOLDOWN_MESSAGE = "Easy there: wait a few seconds before asking for another emitter.";
	public static final String FULL_MESSAGE = "Your inventory is full. Make room, then try /emitter again.";

	public enum Outcome { GIVEN, LIMIT, COOLDOWN, FULL }

	private static final Map<UUID, Long> LAST_GIVEN = new ConcurrentHashMap<>();
	private static volatile LongSupplier clock = () -> System.nanoTime() / 1_000_000L;

	private EmitterCommand() {}

	/** Test seam (milliseconds, monotonic); {@code null} restores the real clock. Also forgets all cooldowns. */
	public static void setClock(final LongSupplier c) {
		clock = c == null ? () -> System.nanoTime() / 1_000_000L : c;
		LAST_GIVEN.clear();
	}

	public static void register(final CommandDispatcher<CommandSourceStack> dispatcher) {
		dispatcher.register(Commands.literal("emitter").executes(ctx -> {
			CommandSourceStack source = ctx.getSource();
			ServerPlayer player = source.getPlayerOrException();
			Outcome outcome = tryGive(player);
			if (outcome == Outcome.GIVEN) {
				source.sendSuccess(() -> Component.literal(message(outcome)), false);
				return 1;
			}
			source.sendFailure(Component.literal(message(outcome)));
			return 0;
		}));
	}

	public static String message(final Outcome outcome) {
		return switch (outcome) {
			case GIVEN -> "Here's a Hologram Emitter.";
			case LIMIT -> HologramEmitterBlock.LIMIT_MESSAGE;
			case COOLDOWN -> COOLDOWN_MESSAGE;
			case FULL -> FULL_MESSAGE;
		};
	}

	/** Gives one emitter if allowed. Items that do not fit are never dropped; the cooldown only starts on success. */
	public static Outcome tryGive(final ServerPlayer player) {
		int owned = HologramIndex.get(player.level().getServer()).countOwnedBy(player.getUUID()) + carried(player);
		if (owned >= HologramEmitterBlock.MAX_HOLOGRAMS) {
			return Outcome.LIMIT;
		}
		long now = clock.getAsLong();
		LAST_GIVEN.values().removeIf(t -> now - t >= COOLDOWN_MS);
		Long last = LAST_GIVEN.get(player.getUUID());
		if (last != null && now - last < COOLDOWN_MS) {
			return Outcome.COOLDOWN;
		}
		ItemStack stack = new ItemStack(OfficeBlocks.HOLOGRAM_EMITTER_ITEM);
		Inventory inv = player.getInventory();
		// Checked up front: add() reports success for creative players even when nothing fits.
		if (inv.getSlotWithRemainingSpace(stack) < 0 && inv.getFreeSlot() < 0) {
			return Outcome.FULL;
		}
		if (!inv.add(stack) || !stack.isEmpty()) {
			return Outcome.FULL;
		}
		LAST_GIVEN.put(player.getUUID(), now);
		return Outcome.GIVEN;
	}

	/** Emitters in the inventory (main, armor, off hand) and on the cursor. */
	static int carried(final ServerPlayer player) {
		int n = 0;
		Inventory inv = player.getInventory();
		for (int i = 0; i < inv.getContainerSize(); i++) {
			ItemStack s = inv.getItem(i);
			if (s.is(OfficeBlocks.HOLOGRAM_EMITTER_ITEM)) {
				n += s.getCount();
			}
		}
		ItemStack cursor = player.containerMenu.getCarried();
		if (cursor.is(OfficeBlocks.HOLOGRAM_EMITTER_ITEM)) {
			n += cursor.getCount();
		}
		return n;
	}
}
