package local.carter.office.mixin.client;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import local.carter.office.client.magic.fx.Shake;

import net.minecraft.client.Camera;

/**
 * Magic camera shake and FOV kicks ({@link Shake}): after vanilla aligns the camera with the player, turn it by the shake's
 * decaying offset; widen the FOV by any kick. Sits beside {@code CameraZoomMixin} (both at the TAIL of {@code alignWithEntity});
 * {@link Shake} does nothing while work mode or typing mode owns the camera, so the two never fight.
 */
@Mixin(Camera.class)
public abstract class CameraShakeMixin {
	@Shadow
	protected abstract void setRotation(float yRot, float xRot);

	@Shadow
	public abstract float xRot();

	@Shadow
	public abstract float yRot();

	@Inject(method = "alignWithEntity", at = @At("TAIL"))
	private void office$shake(final float partialTicks, final CallbackInfo ci) {
		float[] o = Shake.offset(partialTicks);
		if (o != null) {
			setRotation(yRot() + o[0], xRot() + o[1]);
		}
	}

	@Inject(method = "calculateFov", at = @At("RETURN"), cancellable = true)
	private void office$kick(final float partialTicks, final CallbackInfoReturnable<Float> cir) {
		float fov = cir.getReturnValueF();
		float kicked = Shake.fov(fov, partialTicks);
		if (kicked != fov) {
			cir.setReturnValue(kicked);
		}
	}
}
