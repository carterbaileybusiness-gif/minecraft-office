package local.carter.office.hologram;

import com.mojang.brigadier.CommandDispatcher;

import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;

/** {@code /emitter}: anyone gets one free emitter while they own fewer than 8 holograms. */
public final class EmitterCommand {
	private EmitterCommand() {}

	public static void register(final CommandDispatcher<CommandSourceStack> dispatcher) {
		dispatcher.register(Commands.literal("emitter").executes(ctx -> {
			CommandSourceStack source = ctx.getSource();
			ServerPlayer player = source.getPlayerOrException();
			if (HologramIndex.get(source.getServer()).countOwnedBy(player.getUUID()) >= HologramEmitterBlock.MAX_HOLOGRAMS) {
				source.sendFailure(Component.literal(HologramEmitterBlock.LIMIT_MESSAGE));
				return 0;
			}
			StarterKit.give(player, new ItemStack(OfficeBlocks.HOLOGRAM_EMITTER_ITEM));
			source.sendSuccess(() -> Component.literal("Here's a Hologram Emitter."), false);
			return 1;
		}));
	}
}
