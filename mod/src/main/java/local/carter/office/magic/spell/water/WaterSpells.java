package local.carter.office.magic.spell.water;

import local.carter.office.magic.spell.SpellRegistry;

/** Water spells. {@code MagicMod.init} calls {@link #register()} once (both sides). */
public final class WaterSpells {
	private WaterSpells() {}

	public static void register() {
		SpellRegistry.register(new WaterBullet());
		SpellRegistry.register(new TidalPush());
		SpellRegistry.register(new BubbleShield());
		SpellRegistry.register(new WaterPrison());
		SpellRegistry.register(new WaterDragon());
	}
}
