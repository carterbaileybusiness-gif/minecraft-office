package local.carter.office;

import local.carter.office.hologram.EmitterCommand;
import local.carter.office.hologram.HologramBlockEntity;
import local.carter.office.hologram.HologramEmitterBlock;
import local.carter.office.hologram.OfficeBlocks;
import local.carter.office.hologram.StarterKit;
import local.carter.office.net.HologramActions;
import local.carter.office.net.OfficePayloads;

import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.creativetab.v1.CreativeModeTabEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerBlockEntityEvents;
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
		OfficePayloads.register();
		HologramActions.registerReceivers();

		CommandRegistrationCallback.EVENT.register((dispatcher, context, selection) -> EmitterCommand.register(dispatcher));
		PlayerBlockBreakEvents.BEFORE.register(HologramEmitterBlock::allowBreak);
		ServerPlayConnectionEvents.JOIN.register((handler, sender, server) -> {
			StarterKit.giveIfFirstJoin(handler.player);
			HologramBlockEntity.setOwnerOnline(server, handler.player.getUUID(), true);
		});
		ServerPlayConnectionEvents.DISCONNECT.register((handler, server) -> {
			HologramBlockEntity.setOwnerOnline(server, handler.player.getUUID(), false);
			HologramActions.clearWorking(handler.player.getUUID());
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
