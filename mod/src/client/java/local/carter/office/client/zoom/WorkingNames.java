package local.carter.office.client.zoom;

import java.util.Set;
import java.util.UUID;

import local.carter.office.net.OfficePayloads.WorkingPlayers;

import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;

import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;

/**
 * "⌨ working" after the names of players who are zoomed into one of their screens, on name tags
 * ({@code EntityRendererNameTagMixin}) and in the tab list ({@code PlayerTabOverlayMixin}) only — never in chat, and
 * nothing on the server (no teams, no scoreboard). The set comes from the server's {@link WorkingPlayers} broadcast
 * (on every change and on join) and is cleared on disconnect.
 */
public final class WorkingNames {
	public static final String SUFFIX = " ⌨ working";

	private static volatile Set<UUID> working = Set.of();

	private WorkingNames() {}

	public static void register() {
		ClientPlayNetworking.registerGlobalReceiver(WorkingPlayers.TYPE, (payload, context) -> set(payload));
		ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> working = Set.of());
	}

	public static void set(final WorkingPlayers payload) {
		working = Set.copyOf(payload.players());
	}

	public static boolean isWorking(final UUID player) {
		return working.contains(player);
	}

	/** {@code name}, plus a gray "⌨ working" when {@code isWorking}; the original component is never modified. */
	public static Component decorate(final Component name, final boolean isWorking) {
		if (!isWorking) {
			return name;
		}
		MutableComponent out = name.copy();
		return out.append(Component.literal(SUFFIX).withStyle(ChatFormatting.GRAY));
	}
}
