// Adapted from Wildercord (MIT) https://github.com/defnotean/wildercord @9f5a21fe7964097492cd92ac64e4c4e05ef5236a
package local.carter.office.mixin.client;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import local.carter.office.client.magic.fx.HitStop;

import net.minecraft.client.renderer.entity.EntityRenderDispatcher;
import net.minecraft.client.renderer.entity.state.EntityRenderState;
import net.minecraft.world.entity.Entity;

/** Magic hit-stop ({@link HitStop}): every entity's render state passes through here, so a held one keeps its pose. */
@Mixin(EntityRenderDispatcher.class)
public abstract class HitStopMixin {
	@Inject(method = "extractEntity", at = @At("RETURN"))
	private void office$hitStop(final Entity entity, final float partialTicks, final CallbackInfoReturnable<EntityRenderState> cir) {
		HitStop.extracted(entity, cir.getReturnValue());
	}
}
