package local.carter.office.magic;

import local.carter.office.magic.cast.CastService;
import local.carter.office.magic.cast.ElementChoice;
import local.carter.office.magic.cast.RecallSpell;
import local.carter.office.magic.cast.SignChallenges;
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
	private MagicMod() {}

	public static void init() {
		MagicItems.init();
		MagicAttachments.init();
		MagicPayloads.register();
		MagicServer.registerReceivers();
		SpellRegistry.register(new RecallSpell());
		CastService.setChannelCheck(RecallSpell::channeling);
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
	 * Cast pipeline ticking and the hooks that discard a charge, an open hand-sign challenge or a Recall channel with no cast
	 * and no cost (death, disconnect, dimension change; any damage also interrupts a Recall channel).
	 */
	private static void registerCastHooks() {
		ServerTickEvents.END_SERVER_TICK.register(CastService::tick);
		ServerLifecycleEvents.SERVER_STOPPING.register(server -> CastService.stop());
		ServerLivingEntityEvents.ALLOW_DAMAGE.register(Motion::allowDamage);
		ServerLivingEntityEvents.AFTER_DAMAGE.register((entity, source, baseDamage, damage, blocked) -> {
			if (entity instanceof ServerPlayer p) RecallSpell.interrupt(p, true);
		});
		ServerLivingEntityEvents.AFTER_DEATH.register((entity, source) -> {
			if (entity instanceof ServerPlayer p) {
				discardPending(p);
				RecallSpell.interrupt(p, true);
			}
		});
		ServerEntityLevelChangeEvents.AFTER_PLAYER_CHANGE_LEVEL.register((player, from, to) -> {
			discardPending(player);
			RecallSpell.interrupt(player, true);
		});
		ServerPlayConnectionEvents.DISCONNECT.register((handler, server) -> {
			discardPending(handler.player);
			RecallSpell.interrupt(handler.player, false);
			CastService.forget(handler.player);
			ElementChoice.forget(handler.player);
		});
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
	 * effects). A chunk's temporary blocks are restored before it unloads (and saves), and expired ones when it loads.
	 */
	private static void registerTerrainHooks() {
		ServerLifecycleEvents.SERVER_STARTED.register(server -> {
			TempBlockData temp = TempBlockData.get(server);
			temp.tick(server);
			EditLog log = EditLog.forServer(server);
			log.deleteStaleTemp();
			MagicServer.setKit(new SpellKit(new WorldTerrainEdits(server, ClaimCheck.forServer(server), temp, log), temp));
		});
		ServerChunkEvents.CHUNK_UNLOAD.register((level, chunk) -> {
			if (MagicServer.kit().temp() instanceof TempBlockData temp) temp.onChunkUnload(level, chunk);
		});
		ServerChunkEvents.CHUNK_LOAD.register((level, chunk, generated) -> {
			if (MagicServer.kit().temp() instanceof TempBlockData temp) temp.onChunkLoad(level, chunk);
		});
		ServerTickEvents.END_SERVER_TICK.register(server -> {
			if (MagicServer.kit().terrain() instanceof WorldTerrainEdits edits) edits.tick();
			if (MagicServer.kit().temp() instanceof TempBlockData temp) temp.tick(server);
		});
		ServerLifecycleEvents.SERVER_STOPPING.register(MagicMod::stopTerrain);
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
