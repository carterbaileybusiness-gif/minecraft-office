package local.carter.office.client.magic.fx;

import local.carter.office.magic.net.FxEvent;

import net.minecraft.client.multiplayer.ClientLevel;

/** Draws one cast on this client from its {@code CastFx} event (one per cast). Everything goes through {@code kit}. */
@FunctionalInterface
public interface SpellFx {
	void play(FxEvent e, ClientLevel level, FxKit kit);
}
