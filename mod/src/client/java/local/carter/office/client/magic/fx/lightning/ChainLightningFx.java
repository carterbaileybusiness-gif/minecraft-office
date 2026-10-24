package local.carter.office.client.magic.fx.lightning;

import local.carter.office.client.magic.fx.FxKit;
import local.carter.office.client.magic.fx.GlowMote;
import local.carter.office.client.magic.fx.SpellFx;
import local.carter.office.magic.core.Element;
import local.carter.office.magic.net.FxEvent;

import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.Mth;
import net.minecraft.world.phys.Vec3;

/**
 * Chain Lightning's visuals: a lightning circle flares at the caster's off hand and a thick three-strand bolt cracks out of it into
 * the first target; then the lightning leaps on, one hop a tick, target to target in chain order ({@code entities}), each hop a
 * two-strand bolt with a flash, a spray of sparks, a ring of sparks at the target's feet and a rising crack of thunder. Struck
 * bodies crackle with crawling arcs. A miss ({@code data[0] == 0}) grounds the bolt at {@code target} in a scorch of sparks.
 */
public final class ChainLightningFx implements SpellFx {
	/** Ticks between hops. */
	static final int HOP_TICKS = 1;
	private static final int CRAWL_TICKS = 14;
	private static final int CRAWL_EVERY = 3;

	@Override
	public void play(FxEvent e, ClientLevel level, FxKit kit) {
		Vec3 dir = Volts.dir(e);
		float c = Mth.clamp(e.charge(), 0F, 1F);
		Vec3 hand = Volts.hand(e, level);
		int[] chain = e.entities();
		Vec3 first = chain.length > 0 ? Volts.centre(level, chain[0], e.target()) : e.target();

		kit.circle(hand, dir.reverse(), Element.LIGHTNING.argb(), 0.45F + 0.2F * c, 3 + Math.round(2 * c), 12 + Math.round(6 * c));
		kit.motes(hand, Volts.CORE, 8 + Math.round(6 * c), 0.12, dir.scale(0.1), 0.08, GlowMote.Drift.FLICKER, 0.09F, 7);
		Volts.channel(kit, level, hand, first, e.seed(), 8, true);
		level.playLocalSound(hand.x, hand.y, hand.z, SoundEvents.LIGHTNING_BOLT_THUNDER, SoundSource.PLAYERS, 0.3F + 0.15F * c, 1.75F, false);
		kit.shake(0.18F + 0.12F * c, 6);

		if (chain.length == 0) {
			Volts.ground(kit, first, 1F);
			level.playLocalSound(first.x, first.y, first.z, SoundEvents.LIGHTNING_BOLT_IMPACT, SoundSource.PLAYERS, 0.6F, 1.4F, false);
			return;
		}
		kit.hitStop(2);
		strike(kit, level, e, chain, 0, first, c);
		Vec3[] last = {first};
		kit.simulate(HOP_TICKS, chain.length * HOP_TICKS, t -> {
			int hop = t / HOP_TICKS;
			if (hop == 0 || hop >= chain.length) return;
			Vec3 from = Volts.centre(level, chain[hop - 1], last[0]);
			Vec3 to = Volts.centre(level, chain[hop], from);
			Volts.channel(kit, level, from, to, e.seed() + 101L * hop, 7, false);
			strike(kit, level, e, chain, hop, to, c);
			last[0] = to;
		});
	}

	/** Hop {@code hop} landing at {@code at} (the middle of {@code chain[hop]}): flash, sparks, a ring at its feet, a crack, a crawl. */
	private static void strike(FxKit kit, ClientLevel level, FxEvent e, int[] chain, int hop, Vec3 at, float c) {
		float size = (1.25F + 0.35F * c) * (float) Math.pow(0.9, hop);
		Volts.zap(kit, level, at, size);
		// Each target struck also takes a bolt from the sky: the flash that marks every hop.
		Volts.skyStrike(kit, level, at, 3.5 + 1.5 * c, e.seed() + 53L * hop);
		var body = level.getEntity(chain[hop]);
		if (body != null) Volts.ground(kit, new Vec3(at.x, body.getY() + 0.05, at.z), 0.8F);
		Volts.crawl(kit, level, chain[hop], e.seed() + 7L * hop + 1, CRAWL_EVERY, CRAWL_TICKS);
		level.playLocalSound(at.x, at.y, at.z, SoundEvents.LIGHTNING_BOLT_IMPACT, SoundSource.PLAYERS, 0.55F, 1.25F + 0.12F * hop, false);
	}
}
