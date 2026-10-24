package local.carter.office.magic.spell.fire;

import local.carter.office.magic.spell.SpellRegistry;

/** Fire spells. The fire lane fills {@link #register()}; {@code MagicMod.init} calls it once. */
public final class FireSpells {
	private FireSpells() {}

	public static void register() {
		SpellRegistry.register(new FireBolt());
		SpellRegistry.register(new FireWall());
	}
}
