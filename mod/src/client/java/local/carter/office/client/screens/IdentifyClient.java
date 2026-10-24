package local.carter.office.client.screens;

import java.util.Map;

import org.jspecify.annotations.Nullable;

import local.carter.office.net.OfficePayloads.HologramLabel;
import local.carter.office.net.OfficePayloads.IdentifyShown;

import net.minecraft.core.BlockPos;
import net.minecraft.util.Util;

import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;

/**
 * The Identify labels the holograms show right now, for {@link #DURATION_MS}: the player's own (from the settings
 * screen) or another player's ({@code IdentifyShown}). A new Identify replaces the one showing.
 */
public final class IdentifyClient {
	public static final long DURATION_MS = 3000L;
	private static final IdentifyClient INSTANCE = new IdentifyClient();

	private volatile Map<BlockPos, String> labels = Map.of();
	private volatile long until;

	public static IdentifyClient get() {
		return INSTANCE;
	}

	/** Receives other players' Identify and forgets labels on leaving a world; once from the client initializer. */
	public static void register() {
		ClientPlayNetworking.registerGlobalReceiver(IdentifyShown.TYPE, (payload, context) -> {
			Map<BlockPos, String> m = new java.util.LinkedHashMap<>();
			for (HologramLabel l : payload.labels()) {
				if (HologramLabel.validLabel(l.label())) {
					m.put(l.pos(), l.label());
				}
			}
			INSTANCE.show(m, Util.getMillis());
		});
		ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> INSTANCE.show(Map.of(), 0));
	}

	public void show(final Map<BlockPos, String> shown, final long nowMs) {
		labels = Map.copyOf(shown);
		until = nowMs + DURATION_MS;
	}

	/** The label hologram {@code pos} shows now, or null. */
	public @Nullable String labelAt(final BlockPos pos, final long nowMs) {
		return nowMs < until ? labels.get(pos) : null;
	}
}
