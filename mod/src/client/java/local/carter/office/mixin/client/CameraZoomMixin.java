package local.carter.office.mixin.client;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import local.carter.office.client.zoom.ZoomClient;
import local.carter.office.client.zoom.ZoomPose.Pose;

import net.minecraft.client.Camera;
import net.minecraft.world.phys.Vec3;

/**
 * Zoom in to work: after vanilla aligns the camera with the player each frame, blend it towards the pose that frames
 * the hologram (and the FOV towards the one that pose is framed for). Untouched in the world, so leaving the zoom puts
 * the camera back exactly where vanilla has it. Runs before the cull frustum and projection are built.
 */
@Mixin(Camera.class)
public abstract class CameraZoomMixin {
	@Shadow
	private boolean detached;

	@Shadow
	protected abstract void setRotation(float yRot, float xRot);

	@Shadow
	protected abstract void setPosition(Vec3 position);

	@Shadow
	public abstract Vec3 position();

	@Shadow
	public abstract float xRot();

	@Shadow
	public abstract float yRot();

	@Inject(method = "alignWithEntity", at = @At("TAIL"))
	private void office$zoomPose(final float partialTicks, final CallbackInfo ci) {
		Pose p = ZoomClient.cameraPose((Camera) (Object) this, position(), yRot(), xRot());
		if (p != null) {
			detached = false; // first-person while zoomed: the player's own body never blocks the screen
			setRotation(p.yaw(), p.pitch());
			setPosition(new Vec3(p.eye().x(), p.eye().y(), p.eye().z()));
		}
	}

	@Inject(method = "calculateFov", at = @At("RETURN"), cancellable = true)
	private void office$zoomFov(final float partialTicks, final CallbackInfoReturnable<Float> cir) {
		float vanilla = cir.getReturnValueF();
		float fov = ZoomClient.fov(vanilla);
		if (fov != vanilla) {
			cir.setReturnValue(fov);
		}
	}
}
