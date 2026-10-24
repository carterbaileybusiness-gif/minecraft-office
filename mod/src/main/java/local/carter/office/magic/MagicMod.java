package local.carter.office.magic;

import local.carter.office.magic.cast.CastService;
import local.carter.office.magic.command.MagicCommand;
import local.carter.office.magic.data.MagicAttachments;
import local.carter.office.magic.item.MagicItems;
import local.carter.office.magic.net.MagicPayloads;
import local.carter.office.magic.net.MagicServer;
import local.carter.office.magic.spell.Motion;
import local.carter.office.magic.spell.SpellKit;
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
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
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
		FireSpells.register();
		WaterSpells.register();
		WindSpells.register();
		LightningSpells.register();
		EarthSpells.register();
		ShadowSpells.register();
		CommandRegistrationCallback.EVENT.register((dispatcher, context, selection) -> MagicCommand.register(dispatcher));
		registerCastHooks();
		registerTerrainHooks();
		CreativeModeTabEvents.modifyOutputEvent(CreativeModeTabs.TOOLS_AND_UTILITIES).register(output -> {
			output.accept(MagicItems.GRIMOIRE);
			output.accept(MagicItems.REBINDING_SEAL);
		});
	}

	/** Cast pipeline ticking and the hooks that discard a charge with no cast (death, disconnect, dimension change). */
	private static void registerCastHooks() {
		ServerTickEvents.END_SERVER_TICK.register(CastService::tick);
		ServerLifecycleEvents.SERVER_STOPPING.register(server -> CastService.stop());
		ServerLivingEntityEvents.ALLOW_DAMAGE.register(Motion::allowDamage);
		ServerLivingEntityEvents.AFTER_DEATH.register((entity, source) -> {
			if (entity instanceof ServerPlayer p) discardCharge(p);
		});
		ServerEntityLevelChangeEvents.AFTER_PLAYER_CHANGE_LEVEL.register((player, from, to) -> discardCharge(player));
		ServerPlayConnectionEvents.DISCONNECT.register((handler, server) -> {
			discardCharge(handler.player);
			CastService.forget(handler.player);
		});
	}

	/**
	 * The world kit: installed on {@code SERVER_STARTED} (after restoring temporary blocks that expired while the server was
	 * down), ticked at the end of every server tick, and on {@code SERVER_STOPPING} the queue is applied, the logs written and
	 * every temporary block restored (after {@code CastService.stop} ended the lingering effects).
	 */
	private static void registerTerrainHooks() {
		ServerLifecycleEvents.SERVER_STARTED.register(server -> {
			TempBlockData temp = TempBlockData.get(server);
			temp.tick(server);
			MagicServer.setKit(new SpellKit(new WorldTerrainEdits(server, ClaimCheck.forServer(server), temp, EditLog.forServer(server)), temp));
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

	private static void discardCharge(ServerPlayer p) {
		p.stopUsingItem();
		CastService.endCharge(p);
	}
}
