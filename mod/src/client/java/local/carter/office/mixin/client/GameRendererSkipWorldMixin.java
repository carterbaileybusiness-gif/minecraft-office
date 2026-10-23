package local.carter.office.mixin.client;

import org.objectweb.asm.Opcodes;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import local.carter.office.client.zoom.ZoomClient;

import net.minecraft.client.DeltaTracker;
import net.minecraft.client.renderer.GameRenderer;
import net.minecraft.client.renderer.state.GameRenderState;

/**
 * While the opaque EXTEND overlay covers the whole window, the world behind it is invisible: skip extracting and
 * rendering it (vanilla's own "no level" path: clear, then GUI only), which frees the GPU/CPU for the capture on small
 * machines. The overlay uploads the screen texture itself, so nothing it needs is lost.
 */
@Mixin(GameRenderer.class)
public abstract class GameRendererSkipWorldMixin {
	@Shadow
	@Final
	private GameRenderState gameRenderState;

	@Inject(method = "extract", at = @At(value = "FIELD",
			target = "Lnet/minecraft/client/renderer/state/GameRenderState;shouldRenderLevel:Z", opcode = Opcodes.PUTFIELD,
			shift = At.Shift.AFTER))
	private void office$skipWorldUnderOverlay(final DeltaTracker deltaTracker, final boolean advanceGameTime, final CallbackInfo ci) {
		if (gameRenderState.shouldRenderLevel && ZoomClient.overlayCoversWindow()) {
			gameRenderState.shouldRenderLevel = false;
		}
	}
}
