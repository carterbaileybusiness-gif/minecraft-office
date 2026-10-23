package local.carter.office.client.interact;

import org.jspecify.annotations.Nullable;

import local.carter.office.core.HologramSpec;
import local.carter.office.core.Mount;
import local.carter.office.hologram.HologramBlockEntity;
import local.carter.office.hologram.HologramEmitterBlock;
import local.carter.office.net.OfficePayloads;

import net.minecraft.util.Util;

import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;

/**
 * Mouse-wheel resizing (emitter in the main hand, aiming at your own hologram): each notch ×1.1 / ÷1.1, anchored per
 * {@link Mount#scrollResize}. The new size shows immediately (via {@link HologramPreview}); the server gets
 * {@code ResizeHologram} + {@code MoveHologram} at most every 100 ms and always the final value.
 */
public final class ScrollResize {
	private static @Nullable HologramBlockEntity target;
	private static @Nullable HologramSpec pending;
	private static final SendThrottle THROTTLE = new SendThrottle();

	private ScrollResize() {}

	/** One wheel event of {@code notches} (positive = bigger) on {@code be}. Returns the new predicted spec. */
	public static @Nullable HologramSpec scroll(final HologramBlockEntity be, final int notches) {
		if (target != null && target != be && THROTTLE.pending()) {
			send(); // switching holograms: finish the previous one first
		}
		HologramSpec base = HologramPreview.effective(be);
		if (base == null || notches == 0) {
			return null;
		}
		Mount mount = HologramEmitterBlock.mount(be.getBlockState());
		HologramSpec next = mount.scrollResize(base, notches);
		target = be;
		pending = next;
		HologramPreview.expect(be.getBlockPos(), next);
		if (THROTTLE.offer(Util.getMillis())) {
			send();
		}
		return next;
	}

	/** Every client tick: sends the held-back final value once 100 ms have passed. */
	public static void tick() {
		if (THROTTLE.poll(Util.getMillis())) {
			send();
		}
	}

	private static void send() {
		HologramBlockEntity be = target;
		HologramSpec s = pending;
		if (be == null || s == null || be.isRemoved()) {
			return;
		}
		HologramPreview.expect(be.getBlockPos(), s);
		ClientPlayNetworking.send(new OfficePayloads.ResizeHologram(be.getBlockPos(), s.width()));
		ClientPlayNetworking.send(new OfficePayloads.MoveHologram(be.getBlockPos(), s.offX(), s.offY(), s.offZ(), s.yaw()));
	}

	public static void clear() {
		target = null;
		pending = null;
	}
}
