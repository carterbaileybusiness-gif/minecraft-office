package local.carter.office;

import local.carter.office.hologram.EmitterCommand;
import local.carter.office.hologram.HologramBlockEntity;
import local.carter.office.hologram.HologramEmitterBlock;
import local.carter.office.hologram.OfficeBlocks;
import local.carter.office.hologram.StarterKit;
import local.carter.office.magic.MagicMod;
import local.carter.office.net.HologramActions;
import local.carter.office.net.OfficePayloads;
import local.carter.office.stream.StreamRouter;
import local.carter.office.stream.StreamService;

import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.creativetab.v1.CreativeModeTabEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerBlockEntityEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.event.player.PlayerBlockBreakEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.minecraft.resources.Identifier;
import net.minecraft.world.item.CreativeModeTabs;

public class OfficeMod implements ModInitializer {
	public static final String MOD_ID = "office";

	public static Identifier id(String path) {
		return Identifier.fromNamespaceAndPath(MOD_ID, path);
	}

	@Override
	public void onInitialize() {
		OfficeBlocks.init();
		MagicMod.init();
		OfficePayloads.register();
		HologramActions.registerReceivers();
		StreamService.registerReceivers();
		ServerLifecycleEvents.SERVER_STARTED.register(StreamService::start);
		ServerLifecycleEvents.SERVER_STARTED.register(HologramActions::resetWorking);
		ServerLifecycleEvents.SERVER_STOPPING.register(server -> StreamService.stop());
		ServerTickEvents.END_SERVER_TICK.register(server -> {
			StreamService s = StreamService.get();
			if (s != null) {
				s.tick();
			}
		});

		CommandRegistrationCallback.EVENT.register((dispatcher, context, selection) -> EmitterCommand.register(dispatcher));
		PlayerBlockBreakEvents.BEFORE.register(HologramEmitterBlock::allowBreak);
		ServerPlayConnectionEvents.JOIN.register((handler, sender, server) -> {
			StarterKit.giveIfFirstJoin(handler.player);
			HologramActions.sendWorkingTo(handler.player);
			HologramBlockEntity.setOwnerOnline(server, handler.player.getUUID(), true);
			StreamService s = StreamService.get();
			if (s != null) {
				s.onJoin(handler.player);
			}
		});
		ServerPlayConnectionEvents.DISCONNECT.register((handler, server) -> {
			HologramBlockEntity.setOwnerOnline(server, handler.player.getUUID(), false);
			HologramActions.clearWorking(handler.player);
			StreamService s = StreamService.get();
			if (s != null) {
				s.onLeave(handler.player);
			}
			StreamRouter.signalChange();
		});
		ServerBlockEntityEvents.BLOCK_ENTITY_LOAD.register((blockEntity, level) -> {
			if (blockEntity instanceof HologramBlockEntity be) {
				be.onServerLoad(level);
			}
		});
		CreativeModeTabEvents.modifyOutputEvent(CreativeModeTabs.FUNCTIONAL_BLOCKS)
				.register(output -> output.accept(OfficeBlocks.HOLOGRAM_EMITTER_ITEM));
	}
}
