package local.carter.office.mixin.client;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import local.carter.office.client.zoom.ZoomClient;

import net.minecraft.client.Options;

/**
 * While zoomed, pauseOnLostFocus is false in memory only: if Minecraft saves its options meanwhile (F11, a resize,
 * the narrator hotkey…), the player's own value is written to options.txt, never the zoom's false.
 */
@Mixin(Options.class)
public abstract class OptionsSaveMixin {
	@Inject(method = "save", at = @At("HEAD"))
	private void office$realPauseOnLostFocus(final CallbackInfo ci) {
		ZoomClient.beforeOptionsSave();
	}

	@Inject(method = "save", at = @At("RETURN"))
	private void office$zoomPauseOnLostFocus(final CallbackInfo ci) {
		ZoomClient.afterOptionsSave();
	}
}
