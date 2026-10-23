package local.carter.office.mixin.client;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import local.carter.office.client.interact.HologramInteractions;

import net.minecraft.client.Minecraft;

/** Lets hologram clicks (resize, move, settings) take the use key before vanilla places or uses the held item. */
@Mixin(Minecraft.class)
public abstract class MinecraftUseItemMixin {
	@Inject(method = "startUseItem", at = @At("HEAD"), cancellable = true)
	private void office$useOnHologram(final CallbackInfo ci) {
		if (HologramInteractions.onUse((Minecraft) (Object) this)) {
			ci.cancel();
		}
	}
}
