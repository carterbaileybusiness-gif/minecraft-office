package local.carter.office.client.interact;

import org.jspecify.annotations.Nullable;

import local.carter.office.client.render.HologramPlacement;
import local.carter.office.core.HologramGeometry.Frame;
import local.carter.office.core.HologramSpec;
import local.carter.office.core.Vec3d;
import local.carter.office.hologram.HologramBlockEntity;
import local.carter.office.net.HologramActions;
import local.carter.office.net.OfficePayloads;

import net.minecraft.core.BlockPos;

import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;

/**
 * Moving a hologram (Shift + hold right-click on its body): the grabbed point follows the crosshair at the distance it
 * was grabbed from; mouse-wheel turns it in 15° steps; releasing sends {@code MoveHologram}.
 */
public final class MoveDrag {
	public static final float YAW_STEP = 15f;

	private final HologramBlockEntity be;
	private final BlockPos pos;
	private final HologramSpec start;
	private final Vec3d emitterCenter;
	private final double grabDistance;
	/** Grab point relative to the hologram centre, in the hologram's local axes (right, up, normal). */
	private final double gx;
	private final double gy;
	private final double gz;
	private float yaw;
	private HologramSpec current;

	public MoveDrag(final HologramBlockEntity be, final HologramSpec start, final Vec3d grabPoint, final double grabDistance) {
		this.be = be;
		this.pos = be.getBlockPos();
		this.start = start;
		this.current = start;
		this.yaw = start.yaw();
		this.grabDistance = grabDistance;
		this.emitterCenter = new Vec3d(pos.getX() + 0.5, pos.getY() + 0.5, pos.getZ() + 0.5);
		Frame f = HologramPlacement.frame(pos.getX(), pos.getY(), pos.getZ(), start);
		Vec3d rel = grabPoint.sub(f.center());
		this.gx = rel.dot(f.right());
		this.gy = rel.dot(f.up());
		this.gz = rel.dot(f.right().cross(f.up()));
	}

	public HologramBlockEntity blockEntity() {
		return be;
	}

	/** Turns the hologram by {@code steps} × 15° (mouse-wheel notches). */
	public void rotate(final int steps) {
		yaw = (((yaw + steps * YAW_STEP) % 360f) + 360f) % 360f;
	}

	/** Follows the view ray (call every frame). */
	public void update(final Vec3d eye, final Vec3d look) {
		double len = look.length();
		if (len < 1e-9) {
			return;
		}
		Vec3d grab = eye.add(look.scale(grabDistance / len));
		Vec3d right = HologramPlacement.right(yaw);
		Vec3d up = new Vec3d(0, 1, 0);
		Vec3d normal = right.cross(up);
		Vec3d center = grab.sub(right.scale(gx)).sub(up.scale(gy)).sub(normal.scale(gz));
		Vec3d off = center.sub(emitterCenter);
		double max = HologramActions.MAX_OFFSET;
		current = new HologramSpec(start.owner(), start.shape(), start.mode(), start.privacy(), start.displaySlot(), start.windowKey(),
				start.width(), HologramPlacement.clamp(off.x(), -max, max), HologramPlacement.clamp(off.y(), -max, max),
				HologramPlacement.clamp(off.z(), -max, max), yaw);
	}

	public HologramSpec preview() {
		return current;
	}

	/** Sends the move and returns the predicted spec (null if nothing changed). */
	public @Nullable HologramSpec finish() {
		if (current.equals(start)) {
			return null;
		}
		ClientPlayNetworking.send(new OfficePayloads.MoveHologram(pos, current.offX(), current.offY(), current.offZ(), current.yaw()));
		return current;
	}
}
