package local.carter.office.client.platform;

import java.util.List;

import local.carter.office.core.HologramSpec;

/** No own-screen support here (Linux / dev; Windows until Task 11): friends' screens still show. */
public final class NoPlatform implements Platform {
	@Override
	public boolean supportsExtend() {
		return false;
	}

	@Override
	public List<WindowInfo> windows() {
		return List.of();
	}

	@Override
	public void zoomIn(final HologramSpec spec, final Rect mcWindow) {}

	@Override
	public void zoomOut() {}

	@Override
	public void onExitRequested(final Runnable r) {}
}
