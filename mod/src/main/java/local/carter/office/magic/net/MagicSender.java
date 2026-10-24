package local.carter.office.magic.net;

import local.carter.office.magic.net.MagicPayloads.OpenGrimoire;
import local.carter.office.magic.net.MagicPayloads.SignChallenge;

import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;

/**
 * Everything the server tells clients about magic. {@link MagicServer#sender()} is the network one; GameTests swap in a
 * recorder ({@code MagicTestKit.recorder}).
 */
public interface MagicSender {
	/** One {@code CastFx} to every player within {@code range} of {@code e.origin()}, plus the caster. */
	void fx(ServerLevel level, FxEvent e, double range);

	/** Stops the caster's lasting visuals for everyone who may be drawing them. */
	void cancelFx(ServerPlayer caster);

	void challenge(ServerPlayer p, SignChallenge c);

	void open(ServerPlayer p, OpenGrimoire.Mode m);

	void actionBar(ServerPlayer p, Component c);

	void title(ServerPlayer p, Component title, Component subtitle);
}
