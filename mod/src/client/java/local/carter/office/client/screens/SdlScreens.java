package local.carter.office.client.screens;

import java.nio.IntBuffer;
import java.util.ArrayList;
import java.util.List;

import org.lwjgl.sdl.SDLStdinc;
import org.lwjgl.sdl.SDLVideo;
import org.lwjgl.sdl.SDL_Rect;
import org.lwjgl.system.MemoryStack;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The screens as SDL3 (Minecraft's window library) sees them: what a Mac shows on the map without a "screens" helper.
 * Bounds are global points, top-left origin, like the helper's. Displays named "Office Display N" are Office displays.
 * Client thread only.
 */
public final class SdlScreens {
	private static final Logger LOGGER = LoggerFactory.getLogger("office");
	private static final String OFFICE_PREFIX = "Office Display ";

	private SdlScreens() {}

	public static List<Screen> list() {
		List<Screen> out = new ArrayList<>();
		try {
			IntBuffer ids = SDLVideo.SDL_GetDisplays();
			if (ids == null) {
				return ScreenList.mainOnly().screens();
			}
			try (MemoryStack stack = MemoryStack.stackPush()) {
				int primary = SDLVideo.SDL_GetPrimaryDisplay();
				SDL_Rect r = SDL_Rect.malloc(stack);
				for (int i = ids.position(); i < ids.limit(); i++) {
					int id = ids.get(i);
					if (!SDLVideo.SDL_GetDisplayBounds(id, r) || r.w() <= 0 || r.h() <= 0) {
						continue;
					}
					String name = SDLVideo.SDL_GetDisplayName(id);
					int slot = officeSlot(name);
					out.add(new Screen("sdl:" + id, slot, slot == 0 && id == primary, slot == 0 ? shortName(name) : null,
							r.x(), r.y(), r.w(), r.h()));
				}
			} finally {
				SDLStdinc.SDL_free(ids);
			}
		} catch (RuntimeException | LinkageError e) {
			LOGGER.debug("Office: SDL display list failed: {}", e.toString());
		}
		return out.isEmpty() ? ScreenList.mainOnly().screens() : out;
	}

	/** 1-4 for "Office Display N", else 0 (a real monitor). */
	static int officeSlot(final String name) {
		if (name == null || !name.startsWith(OFFICE_PREFIX)) {
			return 0;
		}
		try {
			int n = Integer.parseInt(name.substring(OFFICE_PREFIX.length()).trim());
			return n >= 1 && n <= 4 ? n : 0;
		} catch (NumberFormatException e) {
			return 0;
		}
	}

	/** A monitor's name, short for the map: the built-in one is "MacBook", else its first word (at most 12 letters). */
	public static String shortName(final String raw) {
		String s = raw == null ? "" : raw.trim();
		if (s.isEmpty()) {
			return "Screen";
		}
		if (s.toLowerCase(java.util.Locale.ROOT).startsWith("built-in")) {
			return "MacBook";
		}
		String first = s.split("\\s+")[0];
		return first.length() > 12 ? first.substring(0, 12) : first;
	}
}
