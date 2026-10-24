package local.carter.office.mixin.client;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import local.carter.office.client.pointer.PointerClient;

/**
 * Dynamic FPS (in the modpack) throttles an unfocused Minecraft hard — about 1 frame a second by default. In work mode
 * Minecraft is unfocused on purpose (the Mac app has the keyboard) while the player watches their screens in it, so its
 * window observer reports "focused" for as long as work mode lasts: the game renders at its normal rate. Soft: a
 * {@link Pseudo} mixin on Dynamic FPS's own class, skipped when the mod isn't installed ({@code require = 0}).
 * PointerClient asks Dynamic FPS to re-check its state when work mode begins and ends ({@code DynamicFps}). The exact
 * descriptor ({@code isFocused()Z}) means a changed signature skips the hook rather than crashing; then the hook never
 * runs, and {@code DynamicFps} tells the player (once) that work mode will lag.
 */
@Pseudo
@Mixin(targets = "dynamic_fps.impl.feature.state.WindowObserver", remap = false)
public abstract class DynamicFpsFocusMixin {
	@Inject(method = "isFocused()Z", at = @At("HEAD"), cancellable = true, require = 0, remap = false)
	private void office$focusedWhileWorking(final CallbackInfoReturnable<Boolean> cir) {
		local.carter.office.client.pointer.DynamicFps.hookRan(); // in place: PointerClient warns if this never runs
		if (PointerClient.keepsDynamicFpsAwake()) {
			cir.setReturnValue(true);
		}
	}
}
