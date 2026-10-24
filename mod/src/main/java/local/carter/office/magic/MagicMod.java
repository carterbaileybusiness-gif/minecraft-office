package local.carter.office.magic;

import local.carter.office.magic.command.MagicCommand;
import local.carter.office.magic.data.MagicAttachments;
import local.carter.office.magic.item.MagicItems;
import local.carter.office.magic.net.MagicPayloads;
import local.carter.office.magic.net.MagicServer;
import local.carter.office.magic.spell.earth.EarthSpells;
import local.carter.office.magic.spell.fire.FireSpells;
import local.carter.office.magic.spell.lightning.LightningSpells;
import local.carter.office.magic.spell.shadow.ShadowSpells;
import local.carter.office.magic.spell.water.WaterSpells;
import local.carter.office.magic.spell.wind.WindSpells;

import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.creativetab.v1.CreativeModeTabEvents;
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
		CreativeModeTabEvents.modifyOutputEvent(CreativeModeTabs.TOOLS_AND_UTILITIES).register(output -> {
			output.accept(MagicItems.GRIMOIRE);
			output.accept(MagicItems.REBINDING_SEAL);
		});
	}
}
