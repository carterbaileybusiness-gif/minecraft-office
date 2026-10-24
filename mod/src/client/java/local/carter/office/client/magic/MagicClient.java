package local.carter.office.client.magic;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import local.carter.office.client.magic.hud.ManaBarHud;
import local.carter.office.client.magic.screen.ElementChoiceScreen;
import local.carter.office.client.magic.screen.GrimoireScreen;
import local.carter.office.client.magic.screen.HandSignScreen;
import local.carter.office.client.pointer.PointerClient;
import local.carter.office.client.zoom.ZoomClient;
import local.carter.office.client.zoom.ZoomController;
import local.carter.office.magic.net.MagicPayloads.OpenGrimoire;
import local.carter.office.magic.net.MagicPayloads.SignAnswer;
import local.carter.office.magic.net.MagicPayloads.SignChallenge;

import net.minecraft.client.Minecraft;

import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;

/**
 * Client side of magic. {@link #register()} is called from {@code OfficeClient} after {@code HologramInteractions.register()},
 * so the hologram use and scroll handlers run first; later tasks add their receivers, keys and HUD here.
 */
public final class MagicClient {
	private static final Logger LOGGER = LoggerFactory.getLogger("office");

	private MagicClient() {}

	public static void register() {
		ClientPlayNetworking.registerGlobalReceiver(SignChallenge.TYPE, (payload, ctx) -> onChallenge(ctx.client(), payload));
		ClientPlayNetworking.registerGlobalReceiver(OpenGrimoire.TYPE, (payload, ctx) -> onOpenGrimoire(ctx.client(), payload));
		ManaBarHud.register();
	}

	/**
	 * The grimoire was used: unbound → the element choice scene; bound (main hand) → the grimoire screen. A hand-sign prompt
	 * that is still open keeps priority (closing it would fizzle the ultimate).
	 */
	private static void onOpenGrimoire(Minecraft mc, OpenGrimoire p) {
		if (mc.player == null || mc.gui.screen() instanceof HandSignScreen) return;
		mc.gui.setScreen(p.mode() == OpenGrimoire.Mode.CHOOSE ? new ElementChoiceScreen() : new GrimoireScreen());
	}

	/**
	 * A hand-sign challenge: open the prompt (it rebuilds the arrows from the seed). While the player types into a hologram or
	 * works zoomed into one, magic keys are blocked, so the prompt does not open there and the challenge is given up at once
	 * (an empty answer, which the server fizzles) instead of hanging until it expires.
	 */
	private static void onChallenge(Minecraft mc, SignChallenge c) {
		if (mc.player == null) return;
		if (busyOnHologram()) {
			LOGGER.info("[office] Hand signs skipped: typing or working on a hologram");
			if (ClientPlayNetworking.canSend(SignAnswer.TYPE)) ClientPlayNetworking.send(new SignAnswer(c.id(), new byte[0]));
			return;
		}
		// A container the player opened meanwhile is closed properly (the server is told) before the prompt replaces it.
		if (mc.player.containerMenu != mc.player.inventoryMenu) mc.player.closeContainer();
		mc.gui.setScreen(new HandSignScreen(c));
	}

	/** Typing mode or work mode on a hologram (both block every magic key). */
	private static boolean busyOnHologram() {
		return PointerClient.typing().active() || ZoomClient.controller().state() != ZoomController.State.WORLD;
	}
}
