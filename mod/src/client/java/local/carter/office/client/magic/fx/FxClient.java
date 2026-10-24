package local.carter.office.client.magic.fx;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import local.carter.office.magic.net.FxEvent;
import local.carter.office.magic.net.MagicPayloads.CastFx;
import local.carter.office.magic.net.MagicPayloads.FxCancel;

import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;

import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;

/**
 * The client end of magic visuals: {@code CastFx} → {@code SpellFxRegistry.get(spell).play(event, level, new FxKit(...))};
 * {@code FxCancel} → {@link FxKit#cancel}. {@link #register()} also starts the fx core: the glow particle group, circles while
 * charging, grimoire auras, ghost blocks, lingering timers, shake and hit-stop.
 */
public final class FxClient {
	private static final Logger LOGGER = LoggerFactory.getLogger("office");

	private FxClient() {}

	public static void register() {
		LightPipeline.register();
		MagicCircleRenderer.registerReload();
		FxKit.register();
		GhostBlocks.register();
		ChargeCircles.register();
		GrimoireParticles.register();
		ClientTickEvents.END_CLIENT_TICK.register(Shake::tick);
		// Shader pack on or off: re-read every tick too (the magic particle group also re-reads it every frame it draws).
		ClientTickEvents.END_CLIENT_TICK.register(mc -> ShaderCompat.refresh());
		ClientTickEvents.END_CLIENT_TICK.register(FxClient::leaveWorld);
		ClientPlayNetworking.registerGlobalReceiver(CastFx.TYPE, (payload, ctx) -> play(ctx.client(), payload.event()));
		ClientPlayNetworking.registerGlobalReceiver(FxCancel.TYPE, (payload, ctx) -> FxKit.cancel(payload.casterId()));
	}

	/** Draws one cast's visuals on this client (a spell's visuals that throw are logged; the game goes on). */
	public static void play(Minecraft mc, FxEvent e) {
		ClientLevel level = mc.level;
		if (level == null) return;
		try {
			SpellFxRegistry.get(e.spell()).play(e, level, new FxKit(e, level));
		} catch (RuntimeException ex) {
			LOGGER.warn("[office] Visuals for {} failed", e.spell(), ex);
		}
	}

	private static ClientLevel seen;

	private static void leaveWorld(Minecraft mc) {
		if (mc.level != seen) {
			seen = mc.level;
			HitStop.clear();
		}
	}
}
