package local.carter.office.mixin.client;

import org.jspecify.annotations.Nullable;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import local.carter.office.client.pointer.PointerClient;

import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.MultiPlayerGameMode;

/**
 * Left button on a hologram: never break the block behind it. A press on your own Extend hologram becomes a click or
 * drag there ({@link PointerClient}); holding the button (also while a drag turns your head across the room) never
 * mines anything.
 */
@Mixin(Minecraft.class)
public abstract class MinecraftAttackMixin {
	@Shadow
	public @Nullable MultiPlayerGameMode gameMode;

	@Inject(method = "startAttack", at = @At("HEAD"), cancellable = true)
	private void office$attackOnHologram(final CallbackInfoReturnable<Boolean> cir) {
		if (PointerClient.onAttack((Minecraft) (Object) this)) {
			cir.setReturnValue(false);
		}
	}

	@Inject(method = "continueAttack", at = @At("HEAD"), cancellable = true)
	private void office$noMiningOnHologram(final boolean down, final CallbackInfo ci) {
		if (down && PointerClient.blocksAttack((Minecraft) (Object) this)) {
			if (gameMode != null) {
				gameMode.stopDestroyBlock();
			}
			ci.cancel();
		}
	}
}
