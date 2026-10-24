package local.carter.office.client.magic.fx.fire;

import local.carter.office.client.magic.fx.SpellFxRegistry;
import local.carter.office.magic.spell.SpellIds;

/** Fire spell visuals: one {@code SpellFx} per spell, registered here; spells not yet registered draw {@code DefaultFx}. */
public final class FireFx {
	private FireFx() {}

	public static void register() {
		SpellFxRegistry.register(SpellIds.FIRE_BOLT, new FireBoltFx());
		SpellFxRegistry.register(SpellIds.FIRE_WALL, new FireWallFx());
		SpellFxRegistry.register(SpellIds.FLAME_DASH, new FlameDashFx());
		SpellFxRegistry.register(SpellIds.FIREBALL_BARRAGE, new FireballBarrageFx());
		SpellFxRegistry.register(SpellIds.METEOR, new MeteorFx());
		MeteorRock.register();
	}
}
