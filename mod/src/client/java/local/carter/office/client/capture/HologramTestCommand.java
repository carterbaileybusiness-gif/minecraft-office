package local.carter.office.client.capture;

import org.jspecify.annotations.Nullable;

import local.carter.office.client.render.ScreenTexture;
import local.carter.office.client.render.ScreenTextureRegistry;
import local.carter.office.core.Mode;
import local.carter.office.core.Shape;

import net.minecraft.network.chat.Component;

import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.fabric.api.client.command.v2.ClientCommands;
import net.fabricmc.loader.api.FabricLoader;

/**
 * {@code /hologramtest} (client-only debug command): toggles a live {@link TestPatternSource} onto every hologram this
 * client may view, through the same ScreenTexture pipeline as real capture. Registered only in a development
 * environment or with {@code -Doffice.testPattern=true}, so it never ships enabled.
 */
public final class HologramTestCommand {
	private static @Nullable TestPatternSource source;
	private static @Nullable ScreenTexture texture;

	private HologramTestCommand() {}

	public static boolean available() {
		return FabricLoader.getInstance().isDevelopmentEnvironment() || Boolean.getBoolean(OwnScreens.TEST_PATTERN_PROPERTY);
	}

	public static void register() {
		if (!available()) {
			return;
		}
		ClientCommandRegistrationCallback.EVENT.register((dispatcher, context) -> dispatcher.register(
				ClientCommands.literal("hologramtest").executes(ctx -> {
					boolean on = toggle();
					ctx.getSource().sendFeedback(Component.literal(on ? "Hologram test pattern on" : "Hologram test pattern off"));
					return 1;
				})));
	}

	/** Client thread. Returns the new state. */
	static boolean toggle() {
		if (source != null) {
			source.stop();
			ScreenTextureRegistry.setOverride(null);
			texture.clear();
			source = null;
			texture = null;
			return false;
		}
		texture = ScreenTexture.create("test");
		source = new TestPatternSource();
		source.start(new Target(Mode.EXTEND, 1, null, Shape.WIDE_16_9), OwnScreens.MAX_FPS, texture);
		ScreenTextureRegistry.setOverride(texture);
		return true;
	}
}
