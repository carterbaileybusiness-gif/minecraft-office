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
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.Vec3;

/**
 * Chain Lightning's visuals: a lightning circle flares at the caster's off hand, tiny arcs crackle round it, and a heavy channel
 * cracks out of it into the first target; then the lightning leaps on, one hop a tick, target to target in chain order
 * ({@code entities}): a lighter channel, a thin bolt from the sky onto the target, a flash and sparks, a scorch at its feet, a rising
 * crack of thunder and arcs crawling over the body. A miss ({@code data[0] == 0}) grounds the bolt at {@code target}.
 *
 * <p>Budget: the opening (circle, hand, first channel) is paid first; what is left of the event's 400 is split evenly between the
 * struck targets ({@link LightningLook#allowance}), and each hop pays its channel first, then the rest, from its own share, so the
 * last link of a long chain looks like the first.
 */
public final class ChainLightningFx implements SpellFx {
	/** Ticks between hops. */
	static final int HOP_TICKS = 1;

	@Override
	public void play(FxEvent e, ClientLevel level, FxKit kit) {
		Vec3 dir = Volts.dir(e);
		float c = Mth.clamp(e.charge(), 0F, 1F);
		Vec3 hand = Volts.hand(e, level);
		int[] chain = e.entities();
		Vec3 first = chain.length > 0 ? Volts.centre(level, chain[0], e.target()) : e.target();
		long seed = e.seed();

		int before = kit.budget().particlesLeft();
		Allowance opening = Allowance.unlimited();
		kit.circle(hand, dir.reverse(), Element.LIGHTNING.argb(), 0.45F * (1F + c), 3 + Math.round(2 * c), 12 + Math.round(6 * c));
		kit.motes(hand, LightningLook.CORE, LightningLook.handMotes(c), 0.12, dir.scale(0.1), 0.08, GlowMote.Drift.FLICKER, 0.09F, 7);
		Volts.channel(kit, level, opening, hand, first, seed, LightningLook.FIRST, LightningLook.FIRST_STRAND);
		// The crackle runs over the next ticks: its whole cost is set aside now, before the hops share out the rest.
		int crackle = LightningLook.crackleCost(hand, seed);
		int spent = before - kit.budget().particlesLeft() + crackle;
		Volts.crackle(kit, level, new Allowance(crackle), hand, seed);
		level.playLocalSound(hand.x, hand.y, hand.z, SoundEvents.LIGHTNING_BOLT_THUNDER, SoundSource.PLAYERS, 0.3F + 0.15F * c, 1.75F, false);
		kit.shake(0.18F + 0.12F * c, 6);

		if (chain.length == 0) {
			Allowance rest = Allowance.unlimited();
			Volts.zap(kit, level, rest, first, 1F);
			Volts.ground(kit, rest, first);
			level.playLocalSound(first.x, first.y, first.z, SoundEvents.LIGHTNING_BOLT_IMPACT, SoundSource.PLAYERS, 0.6F, 1.4F, false);
			return;
		}
		int share = LightningLook.allowance(spent, chain.length);
		kit.hitStop(2);
		strike(kit, level, new Allowance(share), seed, chain, 0, first, c);
		Vec3[] last = {first};
		kit.simulate(HOP_TICKS, chain.length * HOP_TICKS, t -> {
			int hop = t / HOP_TICKS;
			if (hop == 0 || hop >= chain.length) return;
			Allowance a = new Allowance(share);
			Vec3 from = Volts.centre(level, chain[hop - 1], last[0]);
			Vec3 to = Volts.centre(level, chain[hop], from);
			// The channel is paid first: whatever else a hop must drop, the lightning between targets is always drawn.
			Volts.channel(kit, level, a, from, to, LightningLook.hopSeed(seed, hop), LightningLook.HOP, LightningLook.HOP_STRAND);
			strike(kit, level, a, seed, chain, hop, to, c);
			last[0] = to;
		});
	}

	/** Hop {@code hop} landing at {@code at} (the middle of {@code chain[hop]}): flash, a bolt from the sky, a scorch, a crack, a crawl. */
	private static void strike(FxKit kit, ClientLevel level, Allowance a, long seed, int[] chain, int hop, Vec3 at, float c) {
		Volts.zap(kit, level, a, at, LightningLook.zapSize(hop, c));
		Volts.skyStrike(kit, level, a, at, LightningLook.skyHeight(c), LightningLook.skySeed(seed, hop));
		Entity body = level.getEntity(chain[hop]);
		if (body != null) Volts.ground(kit, a, new Vec3(at.x, body.getY() + 0.05, at.z));
		Volts.crawl(kit, level, a, chain[hop], LightningLook.crawlSeed(seed, hop));
		level.playLocalSound(at.x, at.y, at.z, SoundEvents.LIGHTNING_BOLT_IMPACT, SoundSource.PLAYERS, 0.55F, 1.25F + 0.12F * hop, false);
	}
}
