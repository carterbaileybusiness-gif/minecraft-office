package local.carter.office.client.magic;

import java.util.UUID;

import local.carter.office.magic.net.MagicPayloads.TravelRequest;
import local.carter.office.magic.net.MagicPayloads.TravelRoster;
import local.carter.office.magic.net.MagicPayloads.TravelRosterQuery;

import net.minecraft.util.Util;

import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;

/**
 * Client side of Travel: the last {@link TravelRoster} the server sent (the grimoire's Friends page asks for one about once a
 * second while it shows), and the two requests the page sends.
 */
public final class TravelClient {
	private static TravelRoster roster = TravelRoster.EMPTY;
	private static long receivedAt;
	private static boolean received;

	private TravelClient() {}

	static void register() {
		ClientPlayNetworking.registerGlobalReceiver(TravelRoster.TYPE, (payload, ctx) -> {
			roster = payload;
			receivedAt = Util.getMillis();
			received = true;
		});
		ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> {
			roster = TravelRoster.EMPTY;
			received = false;
		});
	}

	/** The latest roster ({@link TravelRoster#EMPTY} until the first arrives). */
	public static TravelRoster roster() {
		return roster;
	}

	/** True once a roster arrived on this connection. */
	public static boolean received() {
		return received;
	}

	/** {@code Util.getMillis()} when the latest roster arrived (counts down {@code ticksLeft} smoothly between replies). */
	public static long receivedAt() {
		return receivedAt;
	}

	/** Asks the server for a fresh roster. */
	public static void query() {
		if (ClientPlayNetworking.canSend(TravelRosterQuery.TYPE)) ClientPlayNetworking.send(TravelRosterQuery.INSTANCE);
	}

	/** Asks to travel to {@code target}; the server answers with a fresh roster (and an action-bar line). */
	public static void request(UUID target) {
		if (ClientPlayNetworking.canSend(TravelRequest.TYPE)) ClientPlayNetworking.send(new TravelRequest(target));
	}
}
