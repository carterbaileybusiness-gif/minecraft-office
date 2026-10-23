package local.carter.office.client.interact;

import org.jspecify.annotations.Nullable;

import local.carter.office.client.render.HologramPlacement;
import local.carter.office.core.HologramGeometry;
import local.carter.office.core.HologramGeometry.Corner;
import local.carter.office.core.HologramGeometry.Frame;
import local.carter.office.core.HologramSpec;
import local.carter.office.core.Vec3d;
import local.carter.office.hologram.HologramBlockEntity;
import local.carter.office.net.HologramActions;
import local.carter.office.net.OfficePayloads;

import net.minecraft.core.BlockPos;

import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;

/**
 * Resizing by dragging a corner: the opposite corner stays fixed, a cyan outline shows the new size every frame, and
 * releasing sends {@code ResizeHologram} then {@code MoveHologram} (the new centre).
 */
public final class CornerDrag {
	/** New size and centre (offsets from the emitter centre) after a drag. */
	public record Result(double width, double offX, double offY, double offZ) {
		public HologramSpec apply(final HologramSpec s) {
			return new HologramSpec(s.owner(), s.shape(), s.mode(), s.privacy(), s.displaySlot(), s.windowKey(), width, offX, offY,
					offZ, s.yaw());
		}
	}

	private final HologramBlockEntity be;
	private final BlockPos pos;
	private final Corner corner;
	private final HologramSpec start;
	private final Frame startFrame;
	private @Nullable Result current;

	public CornerDrag(final HologramBlockEntity be, final HologramSpec start, final Corner corner) {
		this.be = be;
		this.pos = be.getBlockPos();
		this.start = start;
		this.corner = corner;
		this.startFrame = HologramPlacement.frame(pos.getX(), pos.getY(), pos.getZ(), start);
	}

	/**
	 * Size after dragging {@code dragged} to {@code planePoint} (on the frame's plane) with the opposite corner fixed.
	 * Width clamps to [0.5, 12]; the bottom edge never goes below the emitter top unless it already was (a hologram hung
	 * under a ceiling emitter keeps its place); offsets stay within the server's ±16 limit.
	 */
	public static Result resize(final Frame f, final Corner dragged, final Vec3d planePoint, final HologramSpec spec) {
		double aspect = spec.shape().aspect();
		double w = HologramGeometry.widthFromDrag(f, dragged, planePoint, aspect);
		double h = w / aspect;
		Vec3d center = HologramGeometry.centerForFixedCorner(f, dragged, w, h);
		Vec3d shift = center.sub(f.center());
		double offX = spec.offX() + shift.x();
		double offY = spec.offY() + shift.y();
		double offZ = spec.offZ() + shift.z();

		double oldBottom = spec.offY() - spec.height() / 2;
		double minBottom = Math.min(oldBottom, HologramPlacement.EMITTER_TOP + HologramPlacement.EMITTER_CLEARANCE);
		double bottom = offY - h / 2;
		if (bottom < minBottom) {
			offY += minBottom - bottom;
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

	/** Recomputes the target size from the view ray (call every frame). */
	public void update(final Vec3d eye, final Vec3d look) {
		HologramGeometry.planePoint(eye, look, startFrame).ifPresent(p -> current = resize(startFrame, corner, p, start));
	}

	/** Target spec while dragging (the start spec until the ray first meets the plane). */
	public HologramSpec preview() {
		return current == null ? start : current.apply(start);
	}

	/** Sends the resize (and the recentre that keeps the opposite corner fixed) and returns the predicted spec. */
	public @Nullable HologramSpec finish() {
		if (current == null || current.apply(start).equals(start)) {
			return null;
		}
		ClientPlayNetworking.send(new OfficePayloads.ResizeHologram(pos, current.width()));
		ClientPlayNetworking.send(new OfficePayloads.MoveHologram(pos, current.offX(), current.offY(), current.offZ(), start.yaw()));
		return current.apply(start);
	}
}
