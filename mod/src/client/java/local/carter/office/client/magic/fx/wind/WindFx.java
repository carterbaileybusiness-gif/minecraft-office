package local.carter.office.client.magic.fx.wind;

import local.carter.office.client.magic.fx.SpellFxRegistry;
import local.carter.office.magic.spell.SpellIds;

/** Wind spell visuals: one {@code SpellFx} per spell, registered once from {@code MagicClient}. */
public final class WindFx {
	private WindFx() {}

	public static void register() {
		SpellFxRegistry.register(SpellIds.GUST, new GustFx());
		SpellFxRegistry.register(SpellIds.BLADE_WIND, new BladeWindFx());
	}
}
