package local.carter.office.client;

import local.carter.office.client.capture.HologramTestCommand;
import local.carter.office.client.capture.OwnScreens;
import local.carter.office.client.interact.HologramInteractions;
import local.carter.office.client.interact.HologramTracker;
import local.carter.office.client.render.HologramRenderer;
import local.carter.office.hologram.OfficeBlocks;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.rendering.v1.BlockEntityRendererRegistry;

public class OfficeClient implements ClientModInitializer {
	@Override
	public void onInitializeClient() {
		BlockEntityRendererRegistry.register(OfficeBlocks.HOLOGRAM_BE, HologramRenderer::new);
		HologramTracker.register();
		HologramInteractions.register();
		// Own holograms' live screens (Task 7: test pattern only with -Doffice.testPattern=true; Tasks 9b/11: capture).
		OwnScreens.register();
		// Debug: /hologramtest (dev environment or -Doffice.testPattern=true only).
		HologramTestCommand.register();
	}
}
