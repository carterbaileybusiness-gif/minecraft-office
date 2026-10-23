package local.carter.office.mixin.client;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import local.carter.office.client.zoom.ZoomClient;

import net.minecraft.client.Minecraft;

/** Advances the zoom animation once per frame (state changes land on the frame they're due, not the next tick). */
@Mixin(Minecraft.class)
public abstract class MinecraftFrameMixin {
	@Inject(method = "renderFrame", at = @At("HEAD"))
	private void office$zoomFrame(final boolean advanceGameTime, final CallbackInfo ci) {
		ZoomClient.frame();
	}
}
