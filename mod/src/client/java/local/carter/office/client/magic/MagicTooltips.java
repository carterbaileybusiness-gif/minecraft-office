package local.carter.office.client.magic;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.BiFunction;

import local.carter.office.magic.core.Element;
import local.carter.office.magic.data.MagicAttachments;
import local.carter.office.magic.data.MagicData;
import local.carter.office.magic.item.GrimoireItem;
import local.carter.office.magic.item.MagicItems;
import local.carter.office.magic.tree.RankCost;

import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.FormattedText;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.network.chat.Style;
import net.minecraft.world.item.Item;

import net.fabricmc.fabric.api.client.item.v1.ItemTooltipCallback;

/**
 * Item tooltips that teach the Grimoire, the Rebinding Seal and the Skill tree's materials ("Used to rank up Fire spells", on the
 * items your own element's ranks take). The text lives in the lang file; the lines are built here.
 */
public final class MagicTooltips {
	private MagicTooltips() {}

	public static void register() {
		ItemTooltipCallback.EVENT.register((stack, context, flag, lines) -> {
			Minecraft mc = Minecraft.getInstance();
			List<Component> ours;
			if (GrimoireItem.isGrimoire(stack)) {
				// Any carried book is stamped to its holder, so the local player's synced data is the right source.
				MagicData data = mc.player == null ? MagicData.EMPTY : MagicAttachments.get(mc.player);
				ours = grimoire(data.element(), data.level());
			} else if (stack.is(MagicItems.REBINDING_SEAL)) {
				ours = seal();
			} else {
				Optional<Element> e = mc.player == null ? Optional.empty() : MagicAttachments.get(mc.player).element();
				if (e.isEmpty() || !materials(e.get()).contains(stack.getItem())) return;
				ours = material(e.get());
			}
			int max = Math.min(MAX_WIDTH, mc.getWindow().getGuiScaledWidth() / 2);
			lines.addAll(wrap(ours, max, (text, width) -> mc.font.getSplitter().splitLines(text, width, Style.EMPTY)));
		});
	}

	/** Widest a tooltip line of ours gets (GUI pixels), so the long tips wrap instead of running off the screen. */
	private static final int MAX_WIDTH = 200;

	/** Each line split to {@code maxWidth} by {@code splitter} (the font's, in game), the pieces as styled Components. */
	static List<Component> wrap(List<Component> lines, int maxWidth, BiFunction<FormattedText, Integer, List<FormattedText>> splitter) {
		List<Component> out = new ArrayList<>();
		for (Component line : lines) {
			for (FormattedText piece : splitter.apply(line, maxWidth)) {
				MutableComponent c = Component.empty();
				piece.visit((style, text) -> {
					c.append(Component.literal(text).withStyle(style));
					return Optional.empty();
				}, Style.EMPTY);
				out.add(c);
			}
		}
		return out;
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

	private static final Map<Element, Set<Item>> MATERIALS = new EnumMap<>(Element.class);

	/** What {@code e}'s ranks take (built once). */
	static Set<Item> materials(Element e) {
		return MATERIALS.computeIfAbsent(e, RankCost::materials);
	}

	/** "Used to rank up Fire spells" in the element's colour. */
	public static List<Component> material(Element e) {
		return List.of(Component.translatable("magic.office.material_tip", Component.translatable("element.office." + e.key()))
				.withStyle(s -> s.withColor(e.argb() & 0xFFFFFF)));
	}

	public static List<Component> seal() {
		return List.of(Component.translatable("item.office.rebinding_seal.tip").withStyle(ChatFormatting.GRAY));
	}
}
