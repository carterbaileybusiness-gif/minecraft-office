package local.carter.office.client.platform;

import org.jspecify.annotations.Nullable;

import local.carter.office.client.render.HologramPlacement;
import local.carter.office.core.HologramGeometry.Frame;
import local.carter.office.core.HologramSpec;
import local.carter.office.core.Vec3d;

/**
 * Where a newly placed Extend hologram goes so the world matches the display arrangement (spec 5.10 round 2 review):
 * the helper puts Office display N on one side of the laptop screen ("left" when a real monitor already takes the
 * right), and moving the cursor off that edge of the Mirror reaches it. So display N's hologram goes on the same side of
 * the player's Mirror, in slot order outward, bottom edges level, facing the same way, with a small gap — like monitors
 * next to a laptop. Pure.
 */
public final class BesideMirror {
	/** Gap between screens (blocks). */
	public static final double GAP = 0.1;
	/** Only a Mirror this close to the new emitter counts (blocks); farther away the new hologram stays where placed. */
	public static final double NEAR = 8.0;
	/** Offsets the server accepts (HologramActions.MAX_OFFSET). */
	static final double MAX_OFFSET = 16.0;

	private BesideMirror() {}

	/** Where the new hologram goes: offsets from its own emitter block and the yaw. */
	public record Placement(double offX, double offY, double offZ, float yaw) {}

	/**
	 * @param mirrorBlock the Mirror's emitter block (minimum corner)
	 * @param newBlock the new hologram's emitter block (minimum corner)
	 * @param left the Office displays sit left of the main display
	 * @return the placement, or null when the Mirror is too far away (or it would need offsets the server refuses)
	 */
	public static @Nullable Placement place(final Vec3d mirrorBlock, final HologramSpec mirror, final Vec3d newBlock,
			final HologramSpec extend, final boolean left) {
		Frame m = HologramPlacement.frame(mirrorBlock.x(), mirrorBlock.y(), mirrorBlock.z(), mirror);
		Vec3d emitterCentre = newBlock.add(new Vec3d(0.5, 0.5, 0.5));
		if (emitterCentre.sub(m.center()).length() > NEAR) {
			return null;
		}
		int k = Math.max(1, extend.displaySlot()); // display 1 next to the Mirror, display 2 next to it, ...
		double along = m.w() / 2 + GAP + (k - 1) * (extend.width() + GAP) + extend.width() / 2;
		Vec3d dir = left ? m.right().scale(-1) : m.right();
		Vec3d centre = m.center().add(dir.scale(along)).add(new Vec3d(0, -m.h() / 2 + extend.height() / 2, 0));
		Vec3d off = centre.sub(emitterCentre);
		if (Math.abs(off.x()) > MAX_OFFSET || Math.abs(off.y()) > MAX_OFFSET || Math.abs(off.z()) > MAX_OFFSET) {
			return null;
		}
		return new Placement(off.x(), off.y(), off.z(), mirror.yaw());
	}

	/** The placement hint: which edge of the Mirror the cursor crosses to reach this screen. */
	public static String hint(final int slot, final boolean left) {
		String side = left ? "left" : "right";
		return "Office Display " + slot + " is to the " + side + " of your laptop screen: in work mode, move the cursor off the "
				+ "Mirror's " + side + " edge to reach it";
	}
}
