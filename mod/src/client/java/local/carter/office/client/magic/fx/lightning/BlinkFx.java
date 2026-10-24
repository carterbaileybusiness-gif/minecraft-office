package local.carter.office.client.magic.fx.lightning;

import local.carter.office.client.magic.fx.FxKit;
import local.carter.office.client.magic.fx.GlowMote;
import local.carter.office.client.magic.fx.RankFx;
import local.carter.office.client.magic.fx.Shake;
import local.carter.office.client.magic.fx.SpellFx;
import local.carter.office.magic.net.FxEvent;
import local.carter.office.magic.spell.lightning.Blink;
import local.carter.office.magic.tree.SpellRank;

import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.Mth;
import net.minecraft.world.phys.Vec3;

/**
 * Blink's visuals: where the caster stood, a flickering afterimage of light in the shape of a body and a spray of sparks; a bolt
 * (yellow, crossed by a thin blue strand) cracks from there to where they are now, low along the ground a second, thinner one;
 * where they land, a flash, a scorch at the feet and a few arcs crawling over them. The caster's own first-person view skips the
 * last stretch of the bolt, which would otherwise end in their eyes.
 */
public final class BlinkFx implements SpellFx {
	/** Heights (above the feet) and sizes of the afterimage's glows: legs, body, shoulders, head. */
	private static final double[] BODY = {0.35, 0.85, 1.25, 1.6};
	private static final float[] SIZE = {0.42F, 0.55F, 0.5F, 0.36F};

	@Override
	public void play(FxEvent e, ClientLevel level, FxKit kit) {
		float[] data = e.data();
		double eye = data.length > 0 ? data[0] : 1.62;
		Vec3 start = e.origin().subtract(0, eye, 0);
		Vec3 end = e.target();
		float c = Mth.clamp(e.charge(), 0F, 1F);
		Allowance all = Allowance.unlimited();
		long seed = e.seed();

		for (int i = 0; i < BODY.length; i++) {
			Vec3 at = start.add(0, BODY[i], 0);
			Volts.glow(kit, level, at, LightningLook.BLUE, SIZE[i], 10 + i);
			Volts.glow(kit, level, at, LightningLook.BLOOM, SIZE[i] * 1.5F, 7);
		}
		Vec3 body = start.add(0, 0.9, 0);
		kit.motes(body, LightningLook.YELLOW, 12 + Math.round(6 * c), 0.35, Vec3.ZERO, 0.22, GlowMote.Drift.COAST, 0.08F, 8);
		kit.motes(body, LightningLook.BLUE, 8, 0.5, Vec3.ZERO, 0.04, GlowMote.Drift.FLICKER, 0.09F, 12);
		kit.burst(body, ParticleTypes.ELECTRIC_SPARK, 14, 0.3);
		level.playLocalSound(body.x, body.y, body.z, SoundEvents.LIGHTNING_BOLT_IMPACT, SoundSource.PLAYERS, 0.5F, 1.9F, false);

		Vec3 to = end.add(0, 0.9, 0);
		double travelled = body.distanceTo(to);
		if (travelled < 0.3) return;
		Vec3 dir = to.subtract(body).scale(1 / travelled);
		boolean own = Volts.ownFirstPerson(e.casterId());
		// Seen from the caster's own eyes the bolt stops short of them, so it cuts in from behind instead of filling the screen.
		Vec3 boltEnd = own ? to.subtract(dir.scale(Math.min(1.4, travelled * 0.5))) : to;
		Volts.channel(kit, level, all, body, boltEnd, seed, LightningLook.BLINK, LightningLook.BLINK_STRAND);
		Volts.arc(kit, level, all, start.add(0, 0.12, 0), end.add(0, 0.12, 0), seed * 3 + 1, LightningLook.CRAWL_GOLD);
		kit.trail(start.add(0, 0.1, 0), end.add(0, 0.1, 0), ParticleTypes.ELECTRIC_SPARK, (int) Math.min(18, travelled * 1.4));

		Volts.zap(kit, level, all, to, own ? 0.5F : 0.85F + 0.2F * c);
		// The forks: Static Step's shocks at both ends, Thunderclap's ring, Surge's crackle round the caster.
		SpellRank rank = e.spellRank();
		if (rank.is(Blink.STATIC_STEP)) {
			RankFx.blast(kit, level, body, Blink.STATIC_RADIUS, LightningLook.YELLOW, ParticleTypes.ELECTRIC_SPARK);
			RankFx.blast(kit, level, to, Blink.STATIC_RADIUS, LightningLook.YELLOW, ParticleTypes.ELECTRIC_SPARK);
		}
		if (rank.is(Blink.THUNDERCLAP)) RankFx.shockRing(kit, level, end.add(0, 0.1, 0), Blink.CLAP_RADIUS, 6, LightningLook.CORE, ParticleTypes.ELECTRIC_SPARK);
		if (rank.is(Blink.SURGE)) RankFx.aura(kit, level, e.casterId(), LightningLook.YELLOW, Blink.SURGE_TICKS);
		Volts.ground(kit, all, end.add(0, 0.05, 0));
		Volts.crawl(kit, level, all, e.casterId(), seed + 11);
		level.playLocalSound(to.x, to.y, to.z, SoundEvents.FIREWORK_ROCKET_TWINKLE, SoundSource.PLAYERS, 0.55F, 1.6F + 0.2F * c, false);
		level.playLocalSound(to.x, to.y, to.z, SoundEvents.LIGHTNING_BOLT_IMPACT, SoundSource.PLAYERS, 0.35F, 2F, false);
		if (own) Shake.kick(0.06F);
	}
}
