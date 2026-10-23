package local.carter.office.mixin.client;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import local.carter.office.client.zoom.WorkingNames;

import net.minecraft.client.gui.components.PlayerTabOverlay;
import net.minecraft.client.multiplayer.PlayerInfo;
import net.minecraft.network.chat.Component;

/** "⌨ working" after a working player's name in the tab list. */
@Mixin(PlayerTabOverlay.class)
public abstract class PlayerTabOverlayMixin {
	@Inject(method = "getNameForDisplay", at = @At("RETURN"), cancellable = true)
	private void office$workingSuffix(final PlayerInfo info, final CallbackInfoReturnable<Component> cir) {
		if (WorkingNames.isWorking(info.getProfile().id())) {
			cir.setReturnValue(WorkingNames.decorate(cir.getReturnValue(), true));
		}
	}
}
