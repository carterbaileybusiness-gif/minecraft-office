package local.carter.office.client.interact;

import org.jspecify.annotations.Nullable;

import local.carter.office.client.render.HologramPlacement;
import local.carter.office.core.HologramGeometry;
import local.carter.office.core.HologramGeometry.Corner;
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
 * Resizing by dragging a corner: the opposite corner stays fixed, a cyan outline shows the new size every frame, and
 * releasing sends {@code ResizeHologram} then {@code MoveHologram} (the new centre).
 *
 * <p>The corner follows the aim <em>relative to where it was grabbed</em> (grabbing 0.3 blocks off the corner does
 * not make it jump), and nothing changes until the press is a real drag ({@link DragArming}); a quick tap is reported
 * by {@link #armed()} == false so the caller can treat it as a click.
 */
public final class CornerDrag {
	/** New size and centre (offsets from the emitter centre) after a drag. */
	public record Result(double width, double offX, double offY, double offZ) {
		public HologramSpec apply(final HologramSpec s) {
			return new HologramSpec(s.owner(), s.shape(), s.mode(), s.privacy(), s.displaySlot(), s.windowKey(), width, offX, offY,
					offZ, s.yaw(), s.mirrorDisplay());
		}
	}

	private static final double EPS = 1e-6;

	private final HologramBlockEntity be;
	private final BlockPos pos;
	private final Corner corner;
	private final HologramSpec start;
	private final Frame startFrame;
	private final Mount mount;
	private final Vec3d grabPoint;
	/** Grab point minus the corner, so the corner moves exactly as much as the aim does. */
	private final Vec3d grabOffset;
	private final DragArming arming;
	private @Nullable Result current;

	/**
	 * @param grabPoint where the aim met the hologram's plane at the press (may be just outside the quad)
	 */
	public CornerDrag(final HologramBlockEntity be, final HologramSpec start, final Corner corner, final Mount mount, final Vec3d grabPoint,
			final long nowMs) {
		this.be = be;
		this.pos = be.getBlockPos();
		this.start = start;
		this.corner = corner;
		this.mount = mount;
		this.startFrame = HologramPlacement.frame(pos.getX(), pos.getY(), pos.getZ(), start);
		this.grabPoint = grabPoint;
		this.grabOffset = grabPoint.sub(HologramGeometry.cornerPoint(startFrame, corner));
		this.arming = new DragArming(nowMs);
	}

	/** Floor-emitter resize (kept for callers and tests that predate mounts). */
	public static Result resize(final Frame f, final Corner dragged, final Vec3d planePoint, final HologramSpec spec) {
		return resize(f, dragged, planePoint, spec, Mount.FLOOR);
	}

	/**
	 * Size after dragging {@code dragged} to {@code planePoint} (on the frame's plane) with the opposite corner fixed.
	 * Width clamps to [0.5, 12]. Floor: the bottom edge never goes below the emitter top unless it already was. Ceiling:
	 * the top edge never goes above the mount unless it already was. Wall: everything happens within the wall plane
	 * (right and up both lie in it), so the hologram can't clip into the wall. Offsets stay within the server's ±16.
	 */
	public static Result resize(final Frame f, final Corner dragged, final Vec3d planePoint, final HologramSpec spec, final Mount mount) {
		double aspect = spec.shape().aspect();
		double w = HologramGeometry.widthFromDrag(f, dragged, planePoint, aspect);
		double h = w / aspect;
		Vec3d center = HologramGeometry.centerForFixedCorner(f, dragged, w, h);
		Vec3d shift = center.sub(f.center());
		double offX = spec.offX() + shift.x();
		double offY = spec.offY() + shift.y();
		double offZ = spec.offZ() + shift.z();

		if (mount == Mount.FLOOR) {
			double oldBottom = spec.offY() - spec.height() / 2;
			double minBottom = Math.min(oldBottom, HologramPlacement.EMITTER_TOP + HologramPlacement.EMITTER_CLEARANCE);
			double bottom = offY - h / 2;
			if (bottom < minBottom) {
				offY += minBottom - bottom;
			}
		} else if (mount == Mount.CEILING) {
			double oldTop = spec.offY() + spec.height() / 2;
			double maxTop = Math.max(oldTop, Mount.CEILING_MOUNT_BOTTOM - HologramPlacement.EMITTER_CLEARANCE);
			double top = offY + h / 2;
			if (top > maxTop) {
				offY -= top - maxTop;
			}
		}
		double max = HologramActions.MAX_OFFSET;
		return new Result(w, HologramPlacement.clamp(offX, -max, max), HologramPlacement.clamp(offY, -max, max),
				HologramPlacement.clamp(offZ, -max, max));
	}

	public HologramBlockEntity blockEntity() {
		return be;
	}

	public HologramSpec startSpec() {
		return start;
	}

	public Corner corner() {
		return corner;
	}

	/** False while the press is still a tap (held under 150 ms, aim barely moved). */
	public boolean armed() {
		return arming.armed();
	}

	/** Recomputes the target size from the view ray (call every frame and on release). */
	public void update(final Vec3d eye, final Vec3d look, final long nowMs) {
		HologramGeometry.planePoint(eye, look, startFrame).ifPresent(p -> {
			if (arming.update(nowMs, p.sub(grabPoint).length())) {
				current = resize(startFrame, corner, p.sub(grabOffset), start, mount);
			}
		});
		arming.update(nowMs, 0.0);
	}

	/** Target spec while dragging (the start spec until the press becomes a drag). */
	public HologramSpec preview() {
		return current == null ? start : current.apply(start);
	}

	/** Sends the resize (and the recentre that keeps the opposite corner fixed) and returns the predicted spec. */
	public @Nullable HologramSpec finish() {
		if (current == null || unchanged(current, start)) {
			return null;
		}
		ClientPlayNetworking.send(new OfficePayloads.ResizeHologram(pos, current.width()));
		ClientPlayNetworking.send(new OfficePayloads.MoveHologram(pos, current.offX(), current.offY(), current.offZ(), start.yaw()));
		return current.apply(start);
	}

	private static boolean unchanged(final Result r, final HologramSpec s) {
		return Math.abs(r.width() - s.width()) < EPS && Math.abs(r.offX() - s.offX()) < EPS && Math.abs(r.offY() - s.offY()) < EPS
				&& Math.abs(r.offZ() - s.offZ()) < EPS;
	}
}
