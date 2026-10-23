package local.carter.office.client;

import local.carter.office.OfficeMod;
import local.carter.office.client.capture.HologramTestCommand;
import local.carter.office.client.capture.OwnScreens;
import local.carter.office.client.interact.HologramInteractions;
import local.carter.office.client.interact.HologramTracker;
import local.carter.office.client.platform.Platform;
import local.carter.office.client.platform.StartupFocus;
import local.carter.office.client.render.HologramRenderer;
import local.carter.office.client.selftest.SelfTestScreen;
import local.carter.office.client.stream.StreamClient;
import local.carter.office.client.zoom.ZoomClient;
import local.carter.office.hologram.OfficeBlocks;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.rendering.v1.BlockEntityRendererRegistry;
import net.fabricmc.loader.api.FabricLoader;

public class OfficeClient implements ClientModInitializer {
	public static final String REPO_URL = "https://github.com/carterbaileybusiness-gif/minecraft-office";
	/** Friends' setup guide, Mac section (helper + permissions). */
	public static final String SETUP_MAC_URL = REPO_URL + "/blob/main/docs/friends/SETUP.md#mac";
	public static final String MOD_VERSION = FabricLoader.getInstance().getModContainer(OfficeMod.MOD_ID)
			.map(c -> c.getMetadata().getVersion().getFriendlyString()).orElse("0.0.0");
	/** This version's GitHub Release (manifest.json, OfficeHelper zip); {@code -Doffice.releaseBase} overrides it. */
	public static final String RELEASE_BASE = System.getProperty("office.releaseBase", REPO_URL + "/releases/download/v" + MOD_VERSION + "/");

	@Override
	public void onInitializeClient() {
		BlockEntityRendererRegistry.register(OfficeBlocks.HOLOGRAM_BE, HologramRenderer::new);
		HologramTracker.register();
		HologramInteractions.register();
		// This computer's own-screen support: Mac = the helper app (setup, /officescreens, leave-world teardown);
		// Windows (beta) = window list + zoom via Win32, Window-mode placement default, zoom undone on CLIENT_STOPPING;
		// otherwise none. Only the Mac platform touches the helper; only Windows loads the webrtc natives.
		Platform.current().register();
		// Mac: bring the game to the front once at startup (a script-launched java process starts behind the Terminal).
		StartupFocus.register();
		// Own holograms' live screens: platform capture (Mac helper / Windows JavaDesktopSource), or the test pattern
		// with -Doffice.testPattern=true. The owner's placeholder look comes from each source's status().
		OwnScreens.register();
		// Click your own hologram to zoom in and work on your real computer; double-Esc back out.
		ZoomClient.register();
		// Shared screens: send own (non-private) screens to nearby friends, show theirs (Task 14).
		StreamClient.register();
		// Debug: /hologramtest (dev environment or -Doffice.testPattern=true only).
		HologramTestCommand.register();
		// "Screen self-test": Esc-menu button + /officeselftest (plain-text report for Carter, no pixels or titles).
		SelfTestScreen.register();
	}
}
