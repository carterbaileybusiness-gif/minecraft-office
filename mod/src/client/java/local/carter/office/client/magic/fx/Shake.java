// Adapted from Wildercord (MIT) https://github.com/defnotean/wildercord @9f5a21fe7964097492cd92ac64e4c4e05ef5236a
package local.carter.office.client.magic.fx;

import local.carter.office.client.pointer.PointerClient;
import local.carter.office.client.zoom.ZoomClient;
import local.carter.office.client.zoom.ZoomController;

import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.util.Mth;

/**
 * Camera shake and field-of-view kicks from big magic (Wildercord's ScreenEffects), applied by {@code CameraShakeMixin}. A shake
 * is a decaying turn of the view (yaw and pitch, as smooth noise, so it reads the same at any frame rate); a kick widens the view
 * quickly and eases back. Both follow vanilla's Screen Effect Scale (FOV kicks also its FOV Effects scale), so players who turned
 * screen effects down get less, and they never run while the camera belongs to the hologram work mode or typing mode, whose
 * camera follow must stay steady.
 */
public final class Shake {
	private Shake() {}

	/** Largest shake strength accepted (about ±7° at full Screen Effect Scale). */
	public static final float MAX = 3F;
	/** Degrees of turn per unit of strength. */
	private static final float DEGREES = 2.2F;
	private static final int KICK_TICKS = 10;

	private static float shake;
	private static int shakeTicks;
	private static int shakeTotal;
	private static float kick;
	private static int kickTicks;
	private static int kickTotal;
	private static float phase;
	private static ClientLevel seen;

	/** Shakes the camera with {@code intensity} (0..{@link #MAX}), decaying to nothing over {@code ticks}. A weaker shake never cuts a stronger one short. */
	public static void shake(float intensity, int ticks) {
		float strength = Mth.clamp(intensity, 0F, MAX);
		if (ticks <= 0 || strength <= 0.001F) {
			return;
		}
		if (strength >= remaining(shake, shakeTicks, shakeTotal)) {
			shake = strength;
			shakeTicks = shakeTotal = ticks;
			phase = (float) (Math.random() * 100);
		}
	}

	/** Widens the field of view by {@code amount} (0.1 = 10 %) for a moment; negative narrows it (a punch). */
	public static void kick(float amount) {
		if (Math.abs(amount) <= 0.001F) {
			return;
		}
		kick = Mth.clamp(amount, -0.3F, 0.3F);
		kickTicks = kickTotal = KICK_TICKS;
	}

	private static float remaining(float strength, int ticks, int total) {
		return total <= 0 ? 0 : strength * ticks / total;
	}

	/** End of client tick: count down; leaving the world drops everything. */
	public static void tick(Minecraft mc) {
		if (mc.level != seen) {
			seen = mc.level;
			shakeTicks = 0;
			kickTicks = 0;
		}
		if (shakeTicks > 0) {
			shakeTicks--;
		}
		if (kickTicks > 0) {
			kickTicks--;
		}
	}

	/** Whether the camera must hold still: work mode on a hologram, typing mode, or the game paused. */
	public static boolean suppressed() {
		Minecraft mc = Minecraft.getInstance();
		return mc.isPaused() || PointerClient.typing().active() || ZoomClient.controller().state() != ZoomController.State.WORLD;
	}

	/** Whether a shake is under way (the game tests read it). */
	public static boolean shaking() {
		return shakeTicks > 0 && shakeTotal > 0;
	}

	private static float screenEffects() {
		Minecraft mc = Minecraft.getInstance();
		return mc.options == null ? 1F : mc.options.screenEffectScale().get().floatValue();
	}

	/**
	 * The view's turn right now, {yaw, pitch} in degrees (both 0 when there is none or it is suppressed): strength × (time left)²,
	 * as two incommensurate sines per axis.
	 */
	public static float[] offset(float partial) {
		if (!shaking() || suppressed()) {
			return null;
		}
		float t = Math.max(0F, (shakeTicks - partial) / shakeTotal);
		float amount = shake * t * t * screenEffects();
		if (amount <= 0.001F) {
			return null;
		}
		float deg = DEGREES * amount;
		float time = (shakeTotal - shakeTicks + partial) + phase;
		float yaw = deg * (0.6F * Mth.sin(time * 2.9F) + 0.4F * Mth.sin(time * 5.3F + 1.7F));
		float pitch = deg * 0.7F * (0.6F * Mth.sin(time * 3.7F + 0.6F) + 0.4F * Mth.sin(time * 6.1F + 2.9F));
		return new float[] {yaw, pitch};
	}

	/** The field of view with any kick in progress (quick out, eased back). */
	public static float fov(float fov, float partial) {
		if (kickTicks <= 0 || kickTotal <= 0 || suppressed()) {
			return fov;
		}
		float t = 1 - (kickTicks - partial) / kickTotal;
		float curve = t < 0.2F ? t / 0.2F : 1 - (t - 0.2F) / 0.8F;
		Minecraft mc = Minecraft.getInstance();
		float scale = mc.options == null ? 1F : mc.options.fovEffectScale().get().floatValue();
		return fov * (1 + kick * Math.max(0F, curve) * scale);
	}
}
