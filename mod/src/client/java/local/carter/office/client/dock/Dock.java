package local.carter.office.client.dock;

import java.util.List;

import org.jspecify.annotations.Nullable;

import local.carter.office.client.pointer.PointerPort;
import local.carter.office.core.HologramSpec;
import local.carter.office.core.Mode;

/**
 * The mini Dock on the owner's Extend holograms (spec 5.10): what it shows and what the crosshair hits on it. Only the
 * owner sees and uses it — it is drawn by the owner's own client (the icons come from their Mac), like a real Dock is
 * part of your own screen; friends see the plain screen (my call). Pure.
 */
public final class Dock {
	private Dock() {}

	/** The Dock's items for this computer, or null when there is no Dock (no helper, or its app list hasn't come yet). */
	public static @Nullable List<DockItem> items(final @Nullable PointerPort port) {
		return port == null ? null : DockItem.shown(port.installedApps());
	}

	/** Whether this hologram gets a Dock: Extend (a monitor of its own), seen by its owner. */
	public static boolean on(final HologramSpec spec, final boolean owner) {
		return owner && spec.mode() == Mode.EXTEND;
	}

	public static DockLayout layout(final HologramSpec spec, final int count) {
		return new DockLayout(count, spec.shape().pxW(), spec.shape().pxH());
	}

	/** The Dock item under (u, v) of the hologram's picture, or -1 (off the Dock, or no Dock here). */
	public static int hit(final HologramSpec spec, final boolean owner, final @Nullable List<DockItem> items, final double u, final double v) {
		if (items == null || !on(spec, owner)) {
			return -1;
		}
		return layout(spec, items.size()).hit(u, v);
	}
}
