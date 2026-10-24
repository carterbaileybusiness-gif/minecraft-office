package local.carter.office.magic.spell.lightning;

import local.carter.office.magic.spell.SpellRegistry;

/** Lightning spells. The lightning lane fills {@link #register()}; {@code MagicMod.init} calls it once. */
public final class LightningSpells {
	private LightningSpells() {}

	public static void register() {
		SpellRegistry.register(new Spark());
		SpellRegistry.register(new ChainLightning());
	}
}
