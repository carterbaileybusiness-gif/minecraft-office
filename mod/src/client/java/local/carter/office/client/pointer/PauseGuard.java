package local.carter.office.client.pointer;

import net.minecraft.client.Minecraft;

/**
 * While the player types into an app or drags on a hologram, Minecraft is not the focused app; with the player's
 * pauseOnLostFocus it would open the pause menu after half a second. This turns it off in memory only, puts the
 * player's value back once Minecraft has focus again (restoring it while unfocused would pause at once), and makes
 * {@code Options.save} write the player's own value meanwhile. Client thread.
 */
final class PauseGuard {
	private boolean held;
	private boolean playerValue;
	private boolean saving;

	void hold(final Minecraft mc, final boolean want) {
		if (want && !held) {
			playerValue = mc.options.pauseOnLostFocus;
			held = true;
			mc.options.pauseOnLostFocus = false;
		} else if (!want && held && (mc.isWindowActive() || mc.level == null)) {
			mc.options.pauseOnLostFocus = playerValue;
			held = false;
		}
	}

	void beforeSave(final Minecraft mc) {
		if (held && mc != null) {
			mc.options.pauseOnLostFocus = playerValue;
			saving = true;
		}
	}

	void afterSave(final Minecraft mc) {
		if (saving) {
			saving = false;
			mc.options.pauseOnLostFocus = false;
		}
	}
}
