package local.carter.office.client.interact;

import java.util.Optional;

import local.carter.office.client.render.HologramPlacement;
import local.carter.office.core.HologramGeometry;
import local.carter.office.core.HologramGeometry.Hit;
import local.carter.office.core.HologramSpec;
import local.carter.office.core.Vec3d;
import local.carter.office.hologram.HologramBlockEntity;
import local.carter.office.hologram.OfficeBlocks;

import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

/** Finds the hologram under the crosshair: nearest hit along the view ray, not hidden behind blocks. */
public final class HologramPicker {
	public static final double MAX_DISTANCE = 32.0;

	public record Picked(HologramBlockEntity be, Hit hit) {}

	private HologramPicker() {}

	/** Nearest hologram along the local player's crosshair ray, up to {@link #MAX_DISTANCE} blocks. */
	public static Optional<Picked> pick(final float partialTick) {
		Minecraft mc = Minecraft.getInstance();
		LocalPlayer player = mc.player;
		if (player == null || mc.level == null) {
			return Optional.empty();
		}
		return pick(player.getEyePosition(partialTick), player.getViewVector(partialTick), MAX_DISTANCE);
	}

	public static Optional<Picked> pick(final Vec3 eye, final Vec3 look, final double maxDistance) {
		Vec3d origin = vec(eye);
		Vec3d dir = vec(look);
		Picked best = null;
		for (HologramBlockEntity be : HologramTracker.loaded()) {
			HologramSpec spec = be.spec();
			if (spec == null) {
				continue;
			}
			BlockPos p = be.getBlockPos();
			Optional<Hit> hit = HologramGeometry.hit(origin, dir, HologramPlacement.frame(p.getX(), p.getY(), p.getZ(), spec));
			if (hit.isPresent() && hit.get().distance() <= maxDistance && (best == null || hit.get().distance() < best.hit().distance())) {
				best = new Picked(be, hit.get());
			}
		}
		if (best == null || occluded(eye, look, best.hit().distance())) {
			return Optional.empty();
		}
		return Optional.of(best);
	}

	/** True when a block (other than an emitter) sits between the eye and the hit point. */
	private static boolean occluded(final Vec3 eye, final Vec3 look, final double distance) {
		Minecraft mc = Minecraft.getInstance();
		if (mc.level == null || mc.player == null) {
			return false;
		}
		Vec3 end = eye.add(look.normalize().scale(Math.max(0.0, distance - 0.02)));
		BlockHitResult block = mc.level.clip(new ClipContext(eye, end, ClipContext.Block.OUTLINE, ClipContext.Fluid.NONE, mc.player));
		return block.getType() == HitResult.Type.BLOCK && mc.level.getBlockState(block.getBlockPos()).getBlock() != OfficeBlocks.HOLOGRAM_EMITTER;
	}

	public static Vec3d vec(final Vec3 v) {
		return new Vec3d(v.x, v.y, v.z);
	}
}
