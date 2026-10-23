package local.carter.office.mixin.client;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import local.carter.office.client.zoom.WorkingNames;

import net.minecraft.client.renderer.entity.EntityRenderer;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;

/** "⌨ working" on the name tag above a player who is zoomed into one of their screens (render only, not chat). */
@Mixin(EntityRenderer.class)
public abstract class EntityRendererNameTagMixin {
	@Inject(method = "getNameTag", at = @At("RETURN"), cancellable = true)
	private void office$workingSuffix(final Entity entity, final CallbackInfoReturnable<Component> cir) {
		Component name = cir.getReturnValue();
		if (name != null && entity instanceof Player player && WorkingNames.isWorking(player.getUUID())) {
			cir.setReturnValue(WorkingNames.decorate(name, true));
		}
	}
}
