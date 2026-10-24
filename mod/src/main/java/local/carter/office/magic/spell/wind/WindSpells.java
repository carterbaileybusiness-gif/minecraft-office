package local.carter.office.magic.spell.wind;

import local.carter.office.magic.spell.SpellRegistry;

/** Wind spells. The wind lane fills {@link #register()}; {@code MagicMod.init} calls it once. */
public final class WindSpells {
	private WindSpells() {}

	public static void register() {
		SpellRegistry.register(new Gust());
		SpellRegistry.register(new BladeWind());
	}
}
