package local.carter.office.client.magic;

import java.util.List;
import java.util.Optional;

import local.carter.office.magic.core.Element;
import local.carter.office.magic.data.MagicAttachments;
import local.carter.office.magic.data.MagicData;
import local.carter.office.magic.item.GrimoireItem;
import local.carter.office.magic.item.MagicItems;

import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;

import net.fabricmc.fabric.api.client.item.v1.ItemTooltipCallback;

/** Item tooltips that teach the Grimoire and the Rebinding Seal. The text lives in the lang file; the lines are built here. */
public final class MagicTooltips {
	private MagicTooltips() {}

	public static void register() {
		ItemTooltipCallback.EVENT.register((stack, context, flag, lines) -> {
			if (GrimoireItem.isGrimoire(stack)) {
				Minecraft mc = Minecraft.getInstance();
				// Any carried book is stamped to its holder, so the local player's synced data is the right source.
				MagicData data = mc.player == null ? MagicData.EMPTY : MagicAttachments.get(mc.player);
				lines.addAll(grimoire(data.element(), data.level()));
			} else if (stack.is(MagicItems.REBINDING_SEAL)) {
				lines.addAll(seal());
			}
		});
	}

	/** Unbound: one line. Bound: "Element · Level n" in the element's colour, then how to cast. */
	public static List<Component> grimoire(Optional<Element> element, int level) {
		if (element.isEmpty()) {
			return List.of(Component.translatable("item.office.grimoire.tip_unbound").withStyle(ChatFormatting.GRAY));
		}
		Element e = element.get();
		Component name = Component.translatable("element.office." + e.key());
		return List.of(
				Component.translatable("item.office.grimoire.tip_bound", name, level).withStyle(s -> s.withColor(e.argb() & 0xFFFFFF)),
				Component.translatable("item.office.grimoire.tip_cast").withStyle(ChatFormatting.GRAY));
	}

	public static List<Component> seal() {
		return List.of(Component.translatable("item.office.rebinding_seal.tip").withStyle(ChatFormatting.GRAY));
	}
}
