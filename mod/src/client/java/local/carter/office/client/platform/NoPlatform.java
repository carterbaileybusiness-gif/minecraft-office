package local.carter.office.client.platform;

import java.util.List;

import local.carter.office.client.interact.WindowListProvider.WindowChoice;
import local.carter.office.core.HologramSpec;

/** A computer without screen support (placeholder until Task 9b's version lands): no windows, zoom does nothing. */
public final class NoPlatform implements Platform {
	@Override
	public boolean supportsExtend() {
		return true; // nothing is greyed out; the hologram shows "Set up screens" instead
	}

	@Override
	public List<WindowChoice> windows() {
		return List.of();
	}

	@Override
	public void zoomIn(final HologramSpec spec, final Rect mcWindow) {}

	@Override
	public void zoomOut() {}

	@Override
	public void onExitRequested(final Runnable r) {}
}
