package local.carter.office.mixin.client;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import local.carter.office.client.pointer.PointerClient;
import local.carter.office.client.zoom.ZoomClient;

import net.minecraft.client.Minecraft;

/**
 * Once per frame: advances the zoom animation (state changes land on the frame they're due, not the next tick) and the
 * in-world pointer (head turn from a helper-driven drag, where the crosshair points, before the frame is drawn).
 */
@Mixin(Minecraft.class)
public abstract class MinecraftFrameMixin {
	@Inject(method = "renderFrame", at = @At("HEAD"))
	private void office$zoomFrame(final boolean advanceGameTime, final CallbackInfo ci) {
		ZoomClient.frame();
		PointerClient.frame();
	}
}
