package local.carter.office.client.magic.fx.lightning;

import local.carter.office.client.magic.fx.SpellFxRegistry;
import local.carter.office.magic.spell.SpellIds;

/** Lightning spell visuals: one {@code SpellFx} per spell, registered here; spells not yet registered draw {@code DefaultFx}. */
public final class LightningFx {
	private LightningFx() {}

	public static void register() {
		SpellFxRegistry.register(SpellIds.SPARK, new SparkFx());
		SpellFxRegistry.register(SpellIds.CHAIN_LIGHTNING, new ChainLightningFx());
	}
}
