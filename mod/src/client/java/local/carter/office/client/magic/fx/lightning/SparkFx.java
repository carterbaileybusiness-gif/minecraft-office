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
 * Spark's visuals: a small lightning circle snaps open at the caster's off hand and a two-strand bolt (yellow and electric blue)
 * cracks from it to where the zap ended ({@code target}). A struck target ({@code entities[0]}) flashes and crackles with small
 * arcs for half a second; a tick later a second bolt leaps from it to the arc's target ({@code entities[1]}), which crackles too.
 * A miss grounds in a spray of sparks.
 */
public final class SparkFx implements SpellFx {
	/** How long struck bodies crackle, and how often a crawling arc appears. */
	private static final int CRAWL_TICKS = 10;
	private static final int CRAWL_EVERY = 2;

	@Override
	public void play(FxEvent e, ClientLevel level, FxKit kit) {
		Vec3 dir = Volts.dir(e);
		float c = Mth.clamp(e.charge(), 0F, 1F);
		Vec3 hand = Volts.hand(e, level);
		int[] struck = e.entities();
		Vec3 end = struck.length > 0 ? Volts.centre(level, struck[0], e.target()) : e.target();

		kit.circle(hand, dir.reverse(), Element.LIGHTNING.argb(), 0.32F + 0.12F * c, 2 + Math.round(c), 6);
		kit.motes(hand, Volts.CORE, 5, 0.08, dir.scale(0.08), 0.06, GlowMote.Drift.FLICKER, 0.08F, 5);
		Volts.channel(kit, level, hand, end, e.seed(), 6, c >= 0.5F);
		Volts.zap(kit, level, end, struck.length > 0 ? 1F + 0.3F * c : 0.7F);
		level.playLocalSound(hand.x, hand.y, hand.z, SoundEvents.FIREWORK_ROCKET_TWINKLE, SoundSource.PLAYERS, 0.55F, 1.7F + 0.2F * c, false);
		level.playLocalSound(end.x, end.y, end.z, SoundEvents.LIGHTNING_BOLT_IMPACT, SoundSource.PLAYERS, 0.35F + 0.15F * c, 1.9F, false);

		if (struck.length == 0) return;
		Volts.crawl(kit, level, struck[0], e.seed() + 1, CRAWL_EVERY, CRAWL_TICKS);
		if (struck.length < 2) return;
		int arcId = struck[1];
		kit.simulate(1, 2, t -> {
			if (t != 1) return;
			Vec3 from = Volts.centre(level, struck[0], end);
			Vec3 to = Volts.centre(level, arcId, from);
			Volts.channel(kit, level, from, to, e.seed() + 2, 5, false);
			Volts.zap(kit, level, to, 0.75F);
			Volts.crawl(kit, level, arcId, e.seed() + 3, CRAWL_EVERY, CRAWL_TICKS - 2);
			level.playLocalSound(to.x, to.y, to.z, SoundEvents.LIGHTNING_BOLT_IMPACT, SoundSource.PLAYERS, 0.3F, 2F, false);
		});
	}
}
