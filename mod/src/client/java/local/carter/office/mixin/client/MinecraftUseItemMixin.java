package local.carter.office.mixin.client;

import org.jspecify.annotations.Nullable;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import local.carter.office.client.interact.ClickGate;
import local.carter.office.client.interact.HologramInteractions;
import local.carter.office.client.magic.MagicInputGate;

import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.MultiPlayerGameMode;
import net.minecraft.client.player.LocalPlayer;

/**
 * Lets hologram clicks (resize, move, settings) take the use key before vanilla places or uses the held item. Keeps
 * vanilla's guards (nothing while destroying a block or with busy hands) and vanilla's 4-tick right-click delay.
 */
@Mixin(Minecraft.class)
public abstract class MinecraftUseItemMixin {
	@Shadow
	private int rightClickDelay;

	@Shadow
	public @Nullable MultiPlayerGameMode gameMode;

	@Shadow
	public @Nullable LocalPlayer player;

	@Inject(method = "startUseItem", at = @At("HEAD"), cancellable = true)
	private void office$useOnHologram(final CallbackInfo ci) {
		if (gameMode == null || player == null || !ClickGate.mayIntercept(gameMode.isDestroying(), player.isHandsBusy())) {
			return; // vanilla's own guards apply; it does nothing (or its usual thing) here
		}
		if (HologramInteractions.onUse((Minecraft) (Object) this)) {
			rightClickDelay = ClickGate.RIGHT_CLICK_DELAY;
			ci.cancel();
			return;
		}
		// Magic (spec §6): nothing while typing, and the off-hand grimoire never casts at a hologram. Anything else stays
		// vanilla, so main-hand block and entity interactions (chests, doors, villagers) run before the off-hand grimoire.
		if (MagicInputGate.blocksUse((Minecraft) (Object) this)) {
			rightClickDelay = ClickGate.RIGHT_CLICK_DELAY;
			ci.cancel();
		}
	}
}
