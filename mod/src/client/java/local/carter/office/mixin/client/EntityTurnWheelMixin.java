package local.carter.office.mixin.client;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import local.carter.office.client.magic.SpellWheel;

import net.minecraft.client.Minecraft;
import net.minecraft.world.entity.Entity;

/**
 * While the R spell wheel is open the mouse points at a slice instead of turning the camera: the turn vanilla's mouse
 * handler asks of the local player ({@code MouseHandler.turnPlayer} → {@code Entity.turn}) goes to
 * {@link SpellWheel#addMouse} and the camera stays put. Movement keys (WASD) keep working. The turn already carries the player's mouse sensitivity (1 unit per
 * pixel at the default), and the invert-mouse options are undone so the pointer follows the hand on screen.
 *
 * <p>Hooked here rather than on {@code MouseHandler}, which routes the fullscreen key: {@code FullscreenGuardTest} keeps
 * every mixin off the fullscreen path.
 */
@Mixin(Entity.class)
public abstract class EntityTurnWheelMixin {
	@Inject(method = "turn", at = @At("HEAD"), cancellable = true)
	private void office$spellWheel(final double yRot, final double xRot, final CallbackInfo ci) {
		if (!SpellWheel.isOpen()) {
			return;
		}
		Minecraft mc = Minecraft.getInstance();
		if (mc.player != (Object) this) {
			return;
		}
		double dx = mc.options.invertMouseX().get() ? -yRot : yRot;
		double dy = mc.options.invertMouseY().get() ? -xRot : xRot;
		SpellWheel.addMouse(dx, dy);
		ci.cancel();
	}
}
