package local.carter.office.client;

import local.carter.office.client.interact.HologramInteractions;
import local.carter.office.client.interact.HologramTracker;
import local.carter.office.client.render.HologramRenderer;
import local.carter.office.client.render.ScreenTextureRegistry;
import local.carter.office.hologram.OfficeBlocks;

import net.minecraft.network.chat.Component;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.fabric.api.client.command.v2.ClientCommands;
import net.fabricmc.fabric.api.client.rendering.v1.BlockEntityRendererRegistry;

public class OfficeClient implements ClientModInitializer {
	@Override
	public void onInitializeClient() {
		BlockEntityRendererRegistry.register(OfficeBlocks.HOLOGRAM_BE, HologramRenderer::new);
		HologramTracker.register();
		HologramInteractions.register();
		// Look check before live capture exists (Task 7 replaces this with its TestPatternSource).
		ClientCommandRegistrationCallback.EVENT.register((dispatcher, context) -> dispatcher.register(
				ClientCommands.literal("hologramtest").executes(ctx -> {
					boolean on = ScreenTextureRegistry.toggleTestPattern();
					ctx.getSource().sendFeedback(Component.literal(on ? "Hologram test pattern on" : "Hologram test pattern off"));
					return 1;
				})));
	}
}
