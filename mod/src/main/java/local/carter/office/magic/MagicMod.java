package local.carter.office.magic;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import local.carter.office.magic.cast.CastService;
import local.carter.office.magic.cast.ElementChoice;
import local.carter.office.magic.cast.RecallSpell;
import local.carter.office.magic.cast.SignChallenges;
import local.carter.office.magic.cast.TeleportChannel;
import local.carter.office.magic.cast.Travel;
import local.carter.office.magic.command.MagicCommand;
import local.carter.office.magic.core.Dir;
import local.carter.office.magic.data.MagicAttachments;
import local.carter.office.magic.item.MagicItems;
import local.carter.office.magic.net.MagicPayloads;
import local.carter.office.magic.net.MagicPayloads.SignAnswer;
import local.carter.office.magic.net.MagicServer;
import local.carter.office.magic.spell.Motion;
import local.carter.office.magic.spell.SpellKit;
import local.carter.office.magic.spell.SpellRegistry;
import local.carter.office.magic.spell.earth.EarthSpells;
import local.carter.office.magic.spell.fire.FireSpells;
import local.carter.office.magic.spell.lightning.LightningSpells;
import local.carter.office.magic.spell.shadow.ShadowSpells;
import local.carter.office.magic.spell.water.WaterSpells;
import local.carter.office.magic.spell.wind.WindSpells;
import local.carter.office.magic.terrain.ClaimCheck;
import local.carter.office.magic.terrain.EditLog;
import local.carter.office.magic.terrain.TempBlockData;
import local.carter.office.magic.terrain.TempBlockGuard;
import local.carter.office.magic.terrain.WorldTerrainEdits;

import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.creativetab.v1.CreativeModeTabEvents;
import net.fabricmc.fabric.api.entity.event.v1.ServerEntityLevelChangeEvents;
import net.fabricmc.fabric.api.entity.event.v1.ServerLivingEntityEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerChunkEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.CreativeModeTabs;

/** Magic setup, called once from {@code OfficeMod.onInitialize} (both sides). */
public final class MagicMod {
	private static final Logger LOGGER = LoggerFactory.getLogger("office");

	private MagicMod() {}

	public static void init() {
		MagicItems.init();
		MagicAttachments.init();
		TempBlockData.init();
		MagicPayloads.register();
		MagicServer.registerReceivers();
		SpellRegistry.register(new RecallSpell());
		CastService.setChannelCheck(TeleportChannel::channeling);
		FireSpells.register();
		WaterSpells.register();
		WindSpells.register();
		LightningSpells.register();
		EarthSpells.register();
		ShadowSpells.register();
		CommandRegistrationCallback.EVENT.register((dispatcher, context, selection) -> MagicCommand.register(dispatcher));
		registerCastHooks();
		registerSignAnswers();
		registerTerrainHooks();
		CreativeModeTabEvents.modifyOutputEvent(CreativeModeTabs.TOOLS_AND_UTILITIES).register(output -> {
			output.accept(MagicItems.GRIMOIRE);
			output.accept(MagicItems.REBINDING_SEAL);
		});
	}

	/**
	 * Cast pipeline ticking and the hooks that discard a charge, an open hand-sign challenge, a Recall or Travel channel or an
	 * open travel request with no cast and no cost (death, disconnect, dimension change; any damage also interrupts a channel).
	 * A player who leaves also closes the travel requests addressed to them and stops channels heading to them.
	 * End-of-tick hooks are {@link #guarded}: one that throws is logged and the server tick goes on.
	 */
	private static void registerCastHooks() {
		ServerTickEvents.END_SERVER_TICK.register(server -> guarded("cast tick", () -> CastService.tick(server)));
		ServerTickEvents.END_SERVER_TICK.register(server -> guarded("travel tick", () -> Travel.tick(server)));
		ServerLifecycleEvents.SERVER_STOPPING.register(server -> {
			CastService.stop();
			Travel.clear();
			MagicServer.forget(null);
		});
		ServerLivingEntityEvents.ALLOW_DAMAGE.register(Motion::allowDamage);
		ServerLivingEntityEvents.AFTER_DAMAGE.register((entity, source, baseDamage, damage, blocked) -> {
			if (entity instanceof ServerPlayer p) TeleportChannel.interrupt(p, true);
		});
		ServerLivingEntityEvents.AFTER_DEATH.register((entity, source) -> {
			if (entity instanceof ServerPlayer p) {
				discardPending(p);
				TeleportChannel.interrupt(p, true);
				Travel.withdraw(p);
				ElementChoice.forget(p);
			}
		});
		ServerEntityLevelChangeEvents.AFTER_PLAYER_CHANGE_LEVEL.register((player, from, to) -> {
			discardPending(player);
			TeleportChannel.interrupt(player, true);
			Travel.withdraw(player);
		});
		ServerPlayConnectionEvents.DISCONNECT.register((handler, server) -> {
			discardPending(handler.player);
			TeleportChannel.interrupt(handler.player, false);
			Travel.left(handler.player);
			CastService.forget(handler.player);
			ElementChoice.forget(handler.player);
			MagicServer.forget(handler.player);
		});
	}

	/**
	 * Runs one end-of-tick hook; one that throws is logged (with {@code what}) and the server tick goes on, so a magic bug never
	 * crashes the server. The hooks guard their own items too (each change, restore, effect and projectile), so one bad
	 * item never holds up the rest.
	 */
	static void guarded(String what, Runnable hook) {
		try {
			hook.run();
		} catch (RuntimeException e) {
			LOGGER.error("[office] Magic {} failed; skipped this tick", what, e);
		}
	}

	/** The {@code SignAnswer} receiver: bytes that are not arrows make a miss (an empty answer). */
	private static void registerSignAnswers() {
		ServerPlayNetworking.registerGlobalReceiver(SignAnswer.TYPE, (payload, ctx) -> {
			Dir[] dirs = payload.toDirs();
			CastService.answer(ctx.player(), payload.id(), dirs == null ? new Dir[0] : dirs);
		});
	}

	/**
	 * The world kit: installed on {@code SERVER_STARTED} (after restoring temporary blocks that expired while the server was
	 * down and deleting half-written edit logs), ticked at the end of every server tick, and on {@code SERVER_STOPPING} the queue
	 * is applied, the logs written and every temporary block restored (after {@code CastService.stop} ended the lingering
	 * effects). A chunk's temporary blocks are restored before it unloads (and saves), and expired ones when it loads. The chunk
	 * hooks use the server's own {@link TempBlockData} even before the kit is installed, so a chunk loaded during startup still
	 * has its saved entries adopted.
	 * {@link TempBlockGuard}: a temporary block broken by a player drops nothing and gets its original back at once (explosions,
	 * pistons, endermen and {@code Level.destroyBlock} are handled by the server mixins).
	 */
	private static void registerTerrainHooks() {
		ServerLifecycleEvents.SERVER_STARTED.register(server -> {
			TempBlockData temp = TempBlockData.get(server);
			temp.tick(server);
			EditLog log = EditLog.forServer(server);
			log.deleteStaleTemp();
			MagicServer.setKit(new SpellKit(new WorldTerrainEdits(server, ClaimCheck.forServer(server), temp, log), temp));
		});
		ServerChunkEvents.CHUNK_UNLOAD.register((level, chunk) -> TempBlockData.get(level.getServer()).onChunkUnload(level, chunk));
		ServerChunkEvents.CHUNK_LOAD.register((level, chunk, generated) -> TempBlockData.get(level.getServer()).onChunkLoad(level, chunk));
		ServerTickEvents.END_SERVER_TICK.register(server -> {
			guarded("terrain applier", () -> {
				if (MagicServer.kit().terrain() instanceof WorldTerrainEdits edits) edits.tick();
			});
			guarded("temporary blocks", () -> {
				if (MagicServer.kit().temp() instanceof TempBlockData temp) temp.tick(server);
			});
		});
		ServerLifecycleEvents.SERVER_STOPPING.register(MagicMod::stopTerrain);
		TempBlockGuard.register();
	}

	private static void stopTerrain(MinecraftServer server) {
		SpellKit kit = MagicServer.kit();
		MagicServer.setKit(SpellKit.NONE);
		if (kit.terrain() instanceof WorldTerrainEdits edits) edits.stop();
		kit.temp().restoreAll(server);
	}

	private static void discardPending(ServerPlayer p) {
		p.stopUsingItem();
		CastService.endCharge(p);
		SignChallenges.discard(p);
	}
}
