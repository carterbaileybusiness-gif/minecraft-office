package local.carter.office.client.screens;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import local.carter.office.core.HologramSpec;
import local.carter.office.core.Mode;
import local.carter.office.core.Privacy;
import local.carter.office.core.Shape;

import net.minecraft.core.BlockPos;

/** Identify's labels: the map's letters and numbers, on the holograms and on the real screens. */
class IdentifyLabelsTest {
	private static final UUID ME = UUID.randomUUID();
	private static final Screen MAIN = new Screen("MAIN", 0, true, "MacBook", 0, 0, 1440, 900);
	private static final Screen LG = new Screen("LG", 0, false, "LG", -1920, -180, 1920, 1080);
	private static final Screen DELL = new Screen("DELL", 0, false, "Dell", 1440, -1080, 1920, 1080);
	private static final Screen O1 = new Screen("O1", 1, false, null, 1440, 0, 1920, 1080);
	private static final Screen O2 = new Screen("O2", 2, false, null, 3360, 0, 1920, 1080);
	private static final BlockPos P = new BlockPos(1, 2, 3);

	private static HologramSpec spec(final Mode mode, final int slot, final String mirror) {
		return new HologramSpec(ME, Shape.WIDE_16_9, mode, Privacy.PUBLIC, slot, null, 2, 0, 1, 0, 0, mirror);
	}

	private static ScreenList list(final Screen... s) {
		return new ScreenList(List.of(s), ScreenList.Abilities.ALL);
	}

	@Test
	void holograms() {
		ScreenList all = list(MAIN, LG, O1);
		assertEquals(Map.of(P, "2"), IdentifyLabels.forSpecs(Map.of(P, spec(Mode.EXTEND, 2, null)), all), "Extend: its number");
		assertEquals(Map.of(P, "B"), IdentifyLabels.forSpecs(Map.of(P, spec(Mode.MIRROR, 1, "LG")), all), "Mirror of LG: B");
		assertEquals(Map.of(P, "A"), IdentifyLabels.forSpecs(Map.of(P, spec(Mode.MIRROR, 1, "LG")), list(MAIN, O1)),
				"LG unplugged: it shows the main display, A");
		assertEquals(Map.of(P, "A"), IdentifyLabels.forSpecs(Map.of(P, spec(Mode.MIRROR, 1, null)), all));
		assertFalse(IdentifyLabels.forSpecs(Map.of(P, spec(Mode.WINDOW, 1, null)), all).containsKey(P), "a window: no label");
	}

	@Test
	void screens() {
		assertEquals(Map.of("MAIN", "A", "LG", "B", "DELL", "C", "O1", "1", "O2", "2"),
				IdentifyLabels.forScreens(list(O2, DELL, MAIN, O1, LG)));
	}
}
