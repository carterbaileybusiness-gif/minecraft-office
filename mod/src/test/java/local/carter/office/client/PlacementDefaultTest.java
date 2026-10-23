package local.carter.office.client;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.UUID;

import local.carter.office.client.platform.PlacementDefault;
import local.carter.office.core.HologramSpec;
import local.carter.office.core.Mode;
import local.carter.office.core.Privacy;
import local.carter.office.core.Shape;
import org.junit.jupiter.api.Test;

/** Windows clients switch a hologram they just placed from Extend to Window mode, and touch nothing else. */
class PlacementDefaultTest {
	private static final UUID ME = UUID.randomUUID();

	private static HologramSpec spec(final Mode mode) {
		return new HologramSpec(ME, Shape.WIDE_16_9, mode, Privacy.PUBLIC, 1, null, 2.0, 0, 1, 0, 0f);
	}

	@Test
	void justPlacedExtendHologramSwitchesOnce() {
		PlacementDefault p = new PlacementDefault();
		UUID id = UUID.randomUUID();
		p.used(10, 64, 10, 1_000); // clicked the block below; emitter lands on top
		assertTrue(p.shouldSwitch(id, spec(Mode.EXTEND), true, 10, 65, 10, 1_200));
		assertFalse(p.shouldSwitch(id, spec(Mode.EXTEND), true, 10, 65, 10, 1_300)); // judged once
	}

	@Test
	void existingHologramsAreLeftAlone() {
		PlacementDefault p = new PlacementDefault();
		UUID old = UUID.randomUUID();
		assertFalse(p.shouldSwitch(old, spec(Mode.EXTEND), true, 0, 64, 0, 0)); // seen on join, no placement
		p.used(0, 63, 0, 100);
		assertFalse(p.shouldSwitch(old, spec(Mode.EXTEND), true, 0, 64, 0, 200)); // already seen
	}

	@Test
	void onlyMineOnlyExtendOnlyRecentOnlyNearby() {
		PlacementDefault p = new PlacementDefault();
		p.used(0, 64, 0, 1_000);
		assertFalse(p.shouldSwitch(UUID.randomUUID(), spec(Mode.EXTEND), false, 0, 65, 0, 1_100)); // a friend's
		assertFalse(p.shouldSwitch(UUID.randomUUID(), spec(Mode.WINDOW), true, 0, 65, 0, 1_100)); // already Window
		assertFalse(p.shouldSwitch(UUID.randomUUID(), spec(Mode.EXTEND), true, 9, 65, 0, 1_100)); // far away
		assertFalse(p.shouldSwitch(UUID.randomUUID(), spec(Mode.EXTEND), true, 0, 65, 0, 1_000 + PlacementDefault.WINDOW_MS + 1)); // late
		assertTrue(p.shouldSwitch(UUID.randomUUID(), spec(Mode.EXTEND), true, 1, 65, -1, 1_500));
	}

	@Test
	void onePlacementSwitchesOneHologram() {
		PlacementDefault p = new PlacementDefault();
		p.used(0, 64, 0, 0);
		assertTrue(p.shouldSwitch(UUID.randomUUID(), spec(Mode.EXTEND), true, 0, 65, 0, 10));
		assertFalse(p.shouldSwitch(UUID.randomUUID(), spec(Mode.EXTEND), true, 1, 65, 0, 20));
	}
}
