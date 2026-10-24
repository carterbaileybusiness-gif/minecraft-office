package local.carter.office.magic.spell.earth;

import local.carter.office.magic.spell.SpellRegistry;

/** Earth spells. {@code MagicMod.init} calls {@link #register()} once (both sides). */
public final class EarthSpells {
	private EarthSpells() {}

	public static void register() {
		SpellRegistry.register(new StoneBullet());
		SpellRegistry.register(new EarthWall());
		SpellRegistry.register(new EarthSpikes());
		SpellRegistry.register(new Quake());
		SpellRegistry.register(new MountainFist());
	}
}
