package local.carter.office.magic.item;

import java.util.List;
import java.util.Optional;

import local.carter.office.magic.core.Element;
import local.carter.office.magic.core.GrimoireTier;

import net.minecraft.core.component.DataComponents;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.CustomModelData;

/**
 * The grimoire's look lives in {@code CUSTOM_MODEL_DATA}: string 0 = {@code t<tier>} (items/grimoire.json selects the tier model),
 * colour 0 = the cover tint (element colour, or leather brown when unbound). Remote clients read the element back from the colour.
 */
public final class GrimoireLook {
	/** Cover tint of an unbound grimoire (cool slate, no element uses it). tools/make_magic_textures.py parses this line
	 *  and writes it as the tint default in items/grimoire.json. */
	public static final int UNBOUND_ARGB = 0xFF6E6E80;

	private GrimoireLook() {}

	public static void stamp(ItemStack stack, Optional<Element> element, int level) {
		int argb = element.map(Element::argb).orElse(UNBOUND_ARGB);
		CustomModelData look = new CustomModelData(List.of(), List.of(), List.of("t" + GrimoireTier.of(level)), List.of(argb));
		if (!look.equals(stack.get(DataComponents.CUSTOM_MODEL_DATA))) {
			stack.set(DataComponents.CUSTOM_MODEL_DATA, look);
		}
	}

	public static Optional<Element> element(ItemStack stack) {
		CustomModelData look = stack.get(DataComponents.CUSTOM_MODEL_DATA);
		if (look == null) return Optional.empty();
		Integer argb = look.getColor(0);
		if (argb == null) return Optional.empty();
		for (Element e : Element.values()) if (e.argb() == argb) return Optional.of(e);
		return Optional.empty();
	}
}
