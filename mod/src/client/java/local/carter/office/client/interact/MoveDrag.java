package local.carter.office.client.interact;

import org.jspecify.annotations.Nullable;

import local.carter.office.client.render.HologramPlacement;
import local.carter.office.core.HologramGeometry;
import local.carter.office.core.HologramGeometry.Frame;
import local.carter.office.core.HologramSpec;
import local.carter.office.core.Mount;
import local.carter.office.core.Vec3d;
import local.carter.office.hologram.HologramBlockEntity;
import local.carter.office.net.HologramActions;
import local.carter.office.net.OfficePayloads;

import net.minecraft.core.BlockPos;

import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;

/**
 * Moving a hologram (Shift + hold right-click on its body). Floor/ceiling: the grabbed point follows the crosshair at
 * the distance it was grabbed from and the mouse-wheel turns it in 15° steps. Wall: it slides along the wall (the
 * grabbed point follows where the crosshair meets the wall plane) and never turns. Releasing sends
 * {@code MoveHologram}. Nothing moves until the press is a real drag ({@link DragArming}).
 */
public final class MoveDrag {
	public static final float YAW_STEP = 15f;

	private final HologramBlockEntity be;
	private final BlockPos pos;
	private final HologramSpec start;
	private final Frame startFrame;
	private final Mount mount;
	private final Vec3d emitterCenter;
	private final Vec3d grabPoint;
	private final double grabDistance;
	/** Grab point relative to the hologram centre, in the hologram's local axes (right, up, normal). */
	private final double gx;
	private final double gy;
	private final double gz;
	private final DragArming arming;
	private float yaw;
	private HologramSpec current;

	public MoveDrag(final HologramBlockEntity be, final HologramSpec start, final Mount mount, final Vec3d grabPoint, final double grabDistance,
			final long nowMs) {
		this.be = be;
		this.pos = be.getBlockPos();
		this.start = start;
		this.mount = mount;
		this.current = start;
		this.yaw = start.yaw();
		this.grabPoint = grabPoint;
		this.grabDistance = grabDistance;
		this.emitterCenter = new Vec3d(pos.getX() + 0.5, pos.getY() + 0.5, pos.getZ() + 0.5);
		this.startFrame = HologramPlacement.frame(pos.getX(), pos.getY(), pos.getZ(), start);
		Vec3d rel = grabPoint.sub(startFrame.center());
		this.gx = rel.dot(startFrame.right());
		this.gy = rel.dot(startFrame.up());
		this.gz = rel.dot(startFrame.right().cross(startFrame.up()));
		this.arming = new DragArming(nowMs);
	}

	public HologramBlockEntity blockEntity() {
		return be;
	}

	/** Wall holograms slide along the wall and never turn. */
	public boolean canRotate() {
		return mount != Mount.WALL;
	}

	/** Turns the hologram by {@code steps} × 15° (mouse-wheel notches); ignored on a wall. */
	public void rotate(final int steps) {
		if (canRotate()) {
			yaw = (((yaw + steps * YAW_STEP) % 360f) + 360f) % 360f;
		}
	}

	/** Follows the view ray (call every frame and on release). */
	public void update(final Vec3d eye, final Vec3d look, final long nowMs) {
		double len = look.length();
		if (len < 1e-9) {
			return;
		}
		Vec3d grab;
		if (mount == Mount.WALL) {
			var onWall = HologramGeometry.planePoint(eye, look, startFrame);
			if (onWall.isEmpty()) {
				return;
			}
			grab = onWall.get();
		} else {
			grab = eye.add(look.scale(grabDistance / len));
		}
		if (!arming.update(nowMs, grab.sub(grabPoint).length()) && yaw == start.yaw()) {
			return;
		}
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
		if (HologramPreview.same(current, start)) {
			return null;
		}
		ClientPlayNetworking.send(new OfficePayloads.MoveHologram(pos, current.offX(), current.offY(), current.offZ(), current.yaw()));
		return current;
	}
}
