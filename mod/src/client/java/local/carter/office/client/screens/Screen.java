package local.carter.office.client.screens;

import org.jspecify.annotations.Nullable;

import local.carter.office.client.platform.Platform;

/**
 * One of the player's screens in global coordinates (top-left origin; Mac: Quartz points, Windows: physical pixels): a
 * real monitor ({@code slot} 0, {@code main} for the main display) or an Office display (Extend, slot 1-4). {@code id}
 * is opaque (CGDisplay UUID, Windows device name); {@code name} is a short monitor name for the map ("MacBook", "LG")
 * and never leaves this computer.
 */
public record Screen(String id, int slot, boolean main, @Nullable String name, int x, int y, int w, int h) {
	public boolean isMonitor() {
		return slot == 0;
	}

	public Platform.Rect rect() {
		return new Platform.Rect(x, y, w, h);
	}

	/** The same screen with its top-left corner at (x, y). */
	public Screen at(final int nx, final int ny) {
		return new Screen(id, slot, main, name, nx, ny, w, h);
	}
}
