package local.carter.office.client.platform;

import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

import local.carter.office.core.HologramSpec;
import local.carter.office.core.Mode;

/**
 * Decides when a hologram the local player just placed should be switched from Extend (the server's default for every
 * hologram after the first, spec 5.8) to Mirror, on clients that can't make virtual displays (Windows beta). "Just placed" = the player used an emitter item
 * within {@link #REACH} blocks of the hologram's block less than {@link #WINDOW_MS} ms before it first appeared. Each
 * hologram is judged once, the first time it is seen, so existing holograms and later Extend choices are left alone.
 * Pure logic, client thread.
 */
public final class PlacementDefault {
	public static final long WINDOW_MS = 5_000;
	public static final int REACH = 2;

	private boolean used;
	private long usedAt;
	private int ux;
	private int uy;
	private int uz;
	private final Set<UUID> seen = new HashSet<>();

	/** The player used an emitter item on block (x, y, z) at {@code ms} (monotonic). */
	public void used(final int x, final int y, final int z, final long ms) {
		used = true;
		usedAt = ms;
		ux = x;
		uy = y;
		uz = z;
	}

	/** True exactly once for a hologram that the local player has just placed and that is in Extend mode. */
	public boolean shouldSwitch(final UUID hologramId, final HologramSpec spec, final boolean mine, final int x, final int y, final int z,
			final long ms) {
		if (!seen.add(hologramId)) {
			return false;
		}
		if (!mine || spec.mode() != Mode.EXTEND || !used) {
			return false;
		}
		long age = ms - usedAt;
		if (age < 0 || age > WINDOW_MS) {
			return false;
		}
		boolean near = Math.abs(x - ux) <= REACH && Math.abs(y - uy) <= REACH && Math.abs(z - uz) <= REACH;
		if (near) {
			used = false; // one placement, one switch
		}
		return near;
	}
}
