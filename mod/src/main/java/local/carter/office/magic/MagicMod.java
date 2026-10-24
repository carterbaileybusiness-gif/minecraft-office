package local.carter.office.magic;

import local.carter.office.magic.item.MagicItems;

import net.fabricmc.fabric.api.creativetab.v1.CreativeModeTabEvents;
import net.minecraft.world.item.CreativeModeTabs;

/** Magic setup, called once from {@code OfficeMod.onInitialize}. Items only for now; later tasks add the rest. */
public final class MagicMod {
	private MagicMod() {}

	public static void init() {
		MagicItems.init();
		CreativeModeTabEvents.modifyOutputEvent(CreativeModeTabs.TOOLS_AND_UTILITIES).register(output -> {
			output.accept(MagicItems.GRIMOIRE);
			output.accept(MagicItems.REBINDING_SEAL);
		});
	}
}
