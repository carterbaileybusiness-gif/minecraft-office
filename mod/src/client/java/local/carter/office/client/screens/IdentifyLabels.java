package local.carter.office.client.screens;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import local.carter.office.core.HologramSpec;
import local.carter.office.hologram.HologramBlockEntity;

import net.minecraft.core.BlockPos;

/**
 * What Identify shows, the same letters and numbers as the display map: an Extend hologram its Office display's number,
 * a Mirror the letter of the monitor it really shows right now (A when its chosen one is unplugged), a Window hologram
 * nothing. On the real screens: monitors their letter, Office displays their number.
 */
public final class IdentifyLabels {
	private IdentifyLabels() {}

	/** The labels of the player's own holograms, by emitter position. */
	public static Map<BlockPos, String> forHolograms(final List<HologramBlockEntity> mine, final ScreenList list) {
		Map<BlockPos, HologramSpec> specs = new LinkedHashMap<>();
		for (HologramBlockEntity be : mine) {
			if (be.spec() != null) {
				specs.put(be.getBlockPos(), be.spec());
			}
		}
		return forSpecs(specs, list);
	}

	/** As {@link #forHolograms}, from the specs themselves. Pure. */
	public static Map<BlockPos, String> forSpecs(final Map<BlockPos, HologramSpec> specs, final ScreenList list) {
		Map<BlockPos, String> out = new LinkedHashMap<>();
		Map<String, Character> letters = list.letters();
		for (Map.Entry<BlockPos, HologramSpec> e : specs.entrySet()) {
			HologramSpec spec = e.getValue();
			switch (spec.mode()) {
				case EXTEND -> {
					if (spec.displaySlot() >= 1 && spec.displaySlot() <= 4) {
						out.put(e.getKey(), Integer.toString(spec.displaySlot()));
					}
				}
				case MIRROR -> {
					String shown = list.resolveMirror(spec.mirrorDisplay());
					Character c = letters.get(shown != null ? shown : list.main().id());
					out.put(e.getKey(), c == null ? "A" : c.toString());
				}
				case WINDOW -> {
					// one window: no screen to name
				}
			}
		}
		return out;
	}

	/** Screen id → label for the helper's overlays: monitors A, B…, Office displays 1-4. */
	public static Map<String, String> forScreens(final ScreenList list) {
		Map<String, String> out = new LinkedHashMap<>();
		for (Map.Entry<String, Character> e : list.letters().entrySet()) {
			out.put(e.getKey(), e.getValue().toString());
		}
		for (Screen s : list.offices()) {
			out.put(s.id(), Integer.toString(s.slot()));
		}
		return out;
	}
}
