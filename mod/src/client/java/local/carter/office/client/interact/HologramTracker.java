package local.carter.office.client.interact;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import local.carter.office.hologram.HologramBlockEntity;

import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientBlockEntityEvents;

/** Hologram block entities currently loaded on the client (client thread only). Used for picking. */
public final class HologramTracker {
	private static final Set<HologramBlockEntity> LOADED = new LinkedHashSet<>();

	private HologramTracker() {}

	public static void register() {
		ClientBlockEntityEvents.BLOCK_ENTITY_LOAD.register((be, level) -> {
			if (be instanceof HologramBlockEntity h) {
				LOADED.add(h);
			}
		});
		ClientBlockEntityEvents.BLOCK_ENTITY_UNLOAD.register((be, level) -> {
			if (be instanceof HologramBlockEntity h) {
				LOADED.remove(h);
			}
		});
	}

	/** Snapshot of the loaded, not-removed holograms. */
	public static List<HologramBlockEntity> loaded() {
		LOADED.removeIf(HologramBlockEntity::isRemoved);
		return new ArrayList<>(LOADED);
	}
}
