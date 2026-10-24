package local.carter.office.magic.spell.shadow;

import local.carter.office.magic.spell.SpellRegistry;

/** Shadow spells. {@code MagicMod.init} calls {@link #register()} once (both sides). */
public final class ShadowSpells {
	private ShadowSpells() {}

	public static void register() {
		SpellRegistry.register(new ShadowBolt());
		SpellRegistry.register(new GravityWell());
	}
}
