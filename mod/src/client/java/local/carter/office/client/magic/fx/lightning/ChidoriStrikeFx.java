package local.carter.office.client.magic.fx.lightning;

import local.carter.office.client.magic.fx.FxKit;
import local.carter.office.client.magic.fx.GlowMote;
import local.carter.office.client.magic.fx.RankFx;
import local.carter.office.client.magic.fx.Shake;
import local.carter.office.client.magic.fx.SpellFx;
import local.carter.office.magic.core.Element;
import local.carter.office.magic.net.FxEvent;
import local.carter.office.magic.spell.lightning.ChidoriGeometry;
import local.carter.office.magic.spell.lightning.ChidoriStrike;
import local.carter.office.magic.tree.SpellRank;

import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.BlockParticleOption;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.RandomSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;

/**
 * Chidori Strike's visuals, built from the same {@link ChidoriGeometry} the server moves the caster along.
 * <ul>
 * <li><b>Cast:</b> a lightning seal under the caster's feet and a wide warning seal where the charge will end; the off hand
 * blazes, a white-hot ball of light with tiny arcs chirping round it, and a sharp crackle of thunder.</li>
 * <li><b>Charge</b> (six ticks): the hand drives along the path, dragging a heavy yellow channel of lightning with a blue strand
 * round it, throwing arcs into the ground either side; blue afterimages flicker where the body was, sparks skid along the ground,
 * the chirp rises in pitch tick by tick.</li>
 * <li><b>Impact:</b> a white flash, three great arcs from the sky onto the end (the fx's own: the server makes no vanilla bolt),
 * cracks of lightning racing out over the ground past the bowl's edge with a second blue wave a moment later, a ring of light
 * rushing outward, sparks, rock dust and smoke, arcs crawling over the target, deep thunder; the camera shakes hard (at least
 * 0.8 within 32 blocks of the impact), the view kicks wide and the caster and the struck hit-stop for three ticks.</li>
 * <li><b>Aftermath</b> (about two seconds): small arcs jumping in the trench, smoke curling up out of it, embers flickering.</li>
 * </ul>
 * Budget: the ultimate's 1500 is split between the phases ({@link ChidoriLook}); each draws from its own {@link Allowance}.
 */
public final class ChidoriStrikeFx implements SpellFx {
	@Override
	public void play(FxEvent e, ClientLevel level, FxKit kit) {
		float[] data = e.data();
		double eye = data.length > 0 ? data[0] : 1.62;
		int radius = data.length > 1 ? Math.round(data[1]) : ChidoriGeometry.BASE_RADIUS;
		Vec3 start = e.origin().subtract(0, eye, 0);
		ChidoriGeometry g = new ChidoriGeometry(start, e.target(), radius);
		Vec3 f = g.flatDir();
		Vec3 side = offSide(e, level, f);
		long seed = e.seed();
		int argb = Element.LIGHTNING.argb();

		// ---- the cast ----
		Allowance open = new Allowance(ChidoriLook.OPENING);
		if (open.pay(1)) kit.circle(start.add(0, 0.06, 0), new Vec3(0, 1, 0), argb, 1.4F, 4, 12);
		if (open.pay(1)) kit.circle(g.end().add(0, 0.06, 0), new Vec3(0, 1, 0), argb, (float) (0.6 * radius + 1.2), 5, 24);
		Vec3 hand0 = hand(e, level, g, side, 0);
		ball(kit, level, open, hand0, 1.25F);
		chirp(kit, level, open, hand0, seed, 0, ChidoriLook.CHIRPS_OPENING);
		kit.motes(hand0, LightningLook.YELLOW, open.take(26), 0.2, Vec3.ZERO, 0.18, GlowMote.Drift.COAST, 0.08F, 7);
		kit.burst(hand0, ParticleTypes.ELECTRIC_SPARK, open.take(24), 0.3);
		sound(level, hand0, SoundEvents.LIGHTNING_BOLT_THUNDER, 0.45F, 1.9F);
		sound(level, hand0, SoundEvents.FIREWORK_ROCKET_TWINKLE, 0.8F, 1.8F);
		sound(level, hand0, SoundEvents.FIREWORK_ROCKET_TWINKLE_FAR, 0.6F, 2F);
		kit.shake(0.25F, 6);

		Vec3[] lastHand = {hand0};
		// The impact and its second wave share one allowance (the first wave leaves the second plenty).
		Allowance blast = new Allowance(ChidoriLook.IMPACT);
		kit.simulate(1, ChidoriGeometry.TICKS + ChidoriLook.AFTER_TICKS + 1, t -> {
			if (t == 0) return;
			if (t <= ChidoriGeometry.TICKS) {
				Vec3 now = hand(e, level, g, side, t);
				// Through the caster's own eyes the hand is just ahead of the camera, and last tick's hand behind it: the channel
				// would run through their eyes, and anything left where the hand was is behind them a tick later (they move about
				// 3 blocks a tick). There it spears far ahead of the hand, down the path they are about to run.
				Vec3 from = Volts.ownFirstPerson(e.casterId()) ? now.add(g.flatDir().scale(ChidoriLook.FP_SPEAR)).add(0, -0.2, 0) : lastHand[0];
				charge(kit, level, new Allowance(ChidoriLook.chargeShare(ChidoriGeometry.TICKS)), g, from, now, side, seed, t);
				lastHand[0] = now;
			}
			if (t <= ChidoriGeometry.TICKS && e.spellRank().is(ChidoriStrike.CHIRPING)) {
				// Chirping Path: the hand's lightning lashes out round the body as it passes.
				Entity caster = level.getEntity(e.casterId());
				if (caster != null) kit.ring(caster.position().add(0, 1, 0), ChidoriStrike.CHIRP_RADIUS, ParticleTypes.ELECTRIC_SPARK, 10);
			}
			if (t == ChidoriGeometry.TICKS) {
				impact(e, kit, level, blast, g, seed);
				SpellRank rank = e.spellRank();
				if (rank.is(ChidoriStrike.KIRIN)) {
					int i = 0;
					for (Vec3 p : ChidoriStrike.kirinPoints(g.end(), seed)) {
						RankFx.skyBolt(kit, level, p, seed + 53L * ++i, Element.LIGHTNING.argb());
						RankFx.blast(kit, level, p.add(0, 0.2, 0), ChidoriStrike.KIRIN_RADIUS, LightningLook.CORE, ParticleTypes.ELECTRIC_SPARK);
					}
				}
				if (rank.is(ChidoriStrike.STORM_TRAIL)) {
					RankFx.zone(kit, level, g.start().add(0, 0.2, 0), g.end().add(0, 0.2, 0), ChidoriStrike.TRAIL_RADIUS, ChidoriStrike.TRAIL_TICKS,
							LightningLook.YELLOW, ParticleTypes.ELECTRIC_SPARK, GlowMote.Drift.FLICKER);
				}
			}
			if (t == ChidoriGeometry.TICKS + ChidoriLook.SECOND_WAVE) secondWave(kit, level, blast, g, seed);
			int after = t - ChidoriGeometry.TICKS;
			if (after > 0 && after % ChidoriLook.AFTER_EVERY == 0) aftermath(kit, level, g, seed, after / ChidoriLook.AFTER_EVERY);
		});
	}

	// ---- the charge ----

	/** One tick of the charge: the hand drives from {@code from} to {@code to}. */
	private static void charge(FxKit kit, ClientLevel level, Allowance a, ChidoriGeometry g, Vec3 from, Vec3 to, Vec3 side, long seed, int t) {
		Volts.channel(kit, level, a, from, to, seed + 61L * t, ChidoriLook.DRIVE, ChidoriLook.DRIVE_STRAND);
		ball(kit, level, a, to, 1F);
		chirp(kit, level, a, to, seed, t, ChidoriLook.CHIRPS);
		// Arcs thrown into the ground either side, where the trench opens.
		Vec3 feet = g.point(t);
		RandomSource r = RandomSource.create(seed * 37 + t);
		for (int s = -1; s <= 1; s += 2) {
			Vec3 hit = feet.add(side.scale(s * (0.6 + 0.8 * r.nextDouble()))).add(g.flatDir().scale(-0.6 * r.nextDouble())).add(0, 0.05, 0);
			Volts.arc(kit, level, a, to, hit, seed + 71L * t + s, ChidoriLook.GROUNDING);
		}
		// The afterimage where the body was a tick ago.
		Vec3 was = g.point(t - 1);
		for (int i = 0; i < 3; i++) {
			if (a.take(1) == 1) Volts.glow(kit, level, was.add(0, 0.4 + 0.55 * i, 0), LightningLook.BLUE, 0.5F - 0.06F * i, 7);
		}
		kit.motes(to, LightningLook.YELLOW, a.take(8), 0.15, g.flatDir().scale(-0.12), 0.12, GlowMote.Drift.COAST, 0.075F, 6);
		kit.motes(was.add(0, 0.9, 0), LightningLook.BLUE, a.take(5), 0.4, Vec3.ZERO, 0.03, GlowMote.Drift.FLICKER, 0.08F, 10);
		kit.trail(g.point(t - 1).add(0, 0.08, 0), feet.add(0, 0.08, 0), ParticleTypes.ELECTRIC_SPARK, a.take(6));
		kit.burst(to, ParticleTypes.ELECTRIC_SPARK, a.take(8), 0.3);
		sound(level, to, SoundEvents.FIREWORK_ROCKET_TWINKLE, 0.7F, 1.5F + 0.09F * t);
		if ((t & 1) == 1) sound(level, to, SoundEvents.LIGHTNING_BOLT_IMPACT, 0.35F, 1.8F);
		kit.shake(0.15F, 3);
	}

	// ---- the impact ----

	private static void impact(FxEvent e, FxKit kit, ClientLevel level, Allowance a, ChidoriGeometry g, long seed) {
		Vec3 at = g.end();
		// Through the caster's own eyes the impact's heart is drawn a little ahead of them: from inside it the flash, the pillars
		// and the smoke would only fill the screen.
		Vec3 heart = Volts.ownFirstPerson(e.casterId()) ? at.add(g.flatDir().scale(ChidoriLook.FP_AHEAD)) : at;
		Vec3 core = heart.add(0, 1, 0);
		int r = g.radius();

		double d = camera().distanceTo(at);
		Shake.shake(ChidoriLook.shake(d), ChidoriLook.SHAKE_TICKS);
		Shake.kick(0.14F * ChidoriLook.nearness(d));
		kit.hitStop(ChidoriLook.HIT_STOP);

		// The flash: glows kept moderate (huge ones show the sprite's texels as steps).
		if (a.take(1) == 1) Volts.glow(kit, level, core, LightningLook.CORE, 1.6F, 4);
		if (a.take(1) == 1) Volts.glow(kit, level, core, LightningLook.BLOOM, 3F, 7);
		if (a.take(1) == 1) Volts.glow(kit, level, core.add(0, 0.6, 0), LightningLook.BLUE, 2.2F, 6);
		for (int i = 0; i < ChidoriLook.PILLARS; i++) {
			Vec3 top = ChidoriLook.pillarTop(heart, i, seed);
			Volts.channel(kit, level, a, top, heart, seed + 113L * i, ChidoriLook.PILLAR, ChidoriLook.PILLAR_STRAND);
		}
		Vec3 ground = at.add(0, 0.1, 0);
		for (int i = 0; i < ChidoriLook.CRACKS; i++) {
			Vec3 tip = ChidoriLook.crack(ground, i, ChidoriLook.CRACKS, r + 2.5, seed);
			Volts.arc(kit, level, a, ground, tip, seed + 131L * i, ChidoriLook.CRACK);
		}
		// The ring of light rushing out, then the sparks, dust and smoke.
		int ring = a.take(44);
		for (int i = 0; i < ring; i++) {
			double ang = Math.PI * 2 * i / Math.max(1, ring);
			Vec3 out = new Vec3(Math.cos(ang), 0, Math.sin(ang));
			kit.motes(ground.add(out.scale(0.6)), (i & 1) == 0 ? LightningLook.YELLOW : LightningLook.CORE, 1, 0.02, out.scale(0.55 + 0.04 * r), 0.02,
					GlowMote.Drift.COAST, 0.12F, 10);
		}
		kit.ring(ground, r * 0.75, ParticleTypes.ELECTRIC_SPARK, a.take(40));
		kit.motes(core, LightningLook.YELLOW, a.take(60), 0.4, Vec3.ZERO, 0.65, GlowMote.Drift.COAST, 0.1F, 9);
		kit.motes(core, LightningLook.BLUE, a.take(36), 1.6, Vec3.ZERO, 0.05, GlowMote.Drift.FLICKER, 0.11F, 16);
		kit.burst(core, ParticleTypes.ELECTRIC_SPARK, a.take(70), 0.85);
		kit.burst(ground, new BlockParticleOption(ParticleTypes.BLOCK, groundState(level, at)), a.take(50), 0.55);
		kit.burst(core, ParticleTypes.LARGE_SMOKE, a.take(18), 0.14);
		kit.burst(heart.add(0, 0.1, 0), ParticleTypes.CAMPFIRE_COSY_SMOKE, a.take(6), 0.04);
		int[] ids = e.entities();
		if (ids.length > 0) Volts.crawl(kit, level, a, ids[0], seed + 151);

		sound(level, at, SoundEvents.LIGHTNING_BOLT_THUNDER, 2F, 0.8F);
		sound(level, at, SoundEvents.LIGHTNING_BOLT_IMPACT, 1.6F, 0.6F);
		sound(level, at, SoundEvents.GENERIC_EXPLODE.value(), 1.2F, 0.75F);
		sound(level, at, SoundEvents.TRIDENT_THUNDER.value(), 1.2F, 0.9F);
	}

	/** A blue wave of cracks a moment after the first. */
	private static void secondWave(FxKit kit, ClientLevel level, Allowance a, ChidoriGeometry g, long seed) {
		Vec3 ground = g.end().add(0, 0.1, 0);
		for (int i = 0; i < ChidoriLook.CRACKS_BLUE; i++) {
			Vec3 tip = ChidoriLook.crack(ground, i, ChidoriLook.CRACKS_BLUE, g.radius() + 1.5, seed + 5);
			Volts.arc(kit, level, a, ground, tip, seed + 173L * i, ChidoriLook.CRACK_BLUE);
		}
		kit.burst(ground.add(0, 0.5, 0), ParticleTypes.ELECTRIC_SPARK, a.take(20), 0.5);
		sound(level, ground, SoundEvents.LIGHTNING_BOLT_IMPACT, 0.8F, 1.3F);
	}

	// ---- the aftermath ----

	private static void aftermath(FxKit kit, ClientLevel level, ChidoriGeometry g, long seed, int step) {
		Allowance a = new Allowance(ChidoriLook.afterShare());
		RandomSource r = RandomSource.create(seed * 41 + step);
		double len = g.flatLength();
		for (int i = 0; i < 2; i++) {
			Vec3 p = g.at(r.nextDouble() * len).add((r.nextDouble() - 0.5) * 1.4, -1.2, (r.nextDouble() - 0.5) * 1.4);
			Vec3 q = p.add((r.nextDouble() - 0.5) * 2.2, 0.3 + 0.5 * r.nextDouble(), (r.nextDouble() - 0.5) * 2.2);
			Volts.arc(kit, level, a, p, q, seed + 191L * step + i, ChidoriLook.EMBER_ARC);
		}
		for (int i = 0; i < 3; i++) {
			Vec3 p = g.at(r.nextDouble() * len).add((r.nextDouble() - 0.5), -1, (r.nextDouble() - 0.5));
			kit.burst(p, ParticleTypes.SMOKE, a.take(2), 0.02);
			kit.motes(p.add(0, 0.6, 0), LightningLook.YELLOW, a.take(2), 0.7, new Vec3(0, 0.015, 0), 0.02, GlowMote.Drift.FLICKER, 0.09F, 16);
		}
		Vec3 bowl = g.end().add((r.nextDouble() - 0.5) * g.radius(), -0.8, (r.nextDouble() - 0.5) * g.radius());
		kit.motes(bowl, LightningLook.YELLOW, a.take(4), g.radius() * 0.4, new Vec3(0, 0.02, 0), 0.02, GlowMote.Drift.FLICKER, 0.08F, 10);
		if (step % 3 == 1) sound(level, bowl, SoundEvents.FIREWORK_ROCKET_TWINKLE_FAR, 0.4F, 1.9F);
	}

	// ---- pieces ----

	/** The ball of lightning in the hand: a white-hot core in a yellow bloom. */
	private static void ball(FxKit kit, ClientLevel level, Allowance a, Vec3 at, float size) {
		if (a.take(1) == 1) Volts.glow(kit, level, at, LightningLook.CORE, 0.42F * size, 3);
		if (a.take(1) == 1) Volts.glow(kit, level, at, LightningLook.BLOOM, 0.95F * size, 3);
	}

	/** The chirp: {@code count} tiny white arcs leaping round the hand. */
	private static void chirp(FxKit kit, ClientLevel level, Allowance a, Vec3 hand, long seed, int step, int count) {
		Vec3[] arcs = ChidoriLook.chirp(hand, seed, step, count);
		for (int i = 0; i < arcs.length; i += 2) Volts.arc(kit, level, a, arcs[i], arcs[i + 1], seed + 83L * step + i, ChidoriLook.CHIRP);
	}

	/**
	 * Where the caster's driving hand is at tick {@code t}: thrust ahead of the body on the off-hand side along the path. Through the
	 * caster's own eyes it is low in front of the view, where their real hand is.
	 */
	private static Vec3 hand(FxEvent e, ClientLevel level, ChidoriGeometry g, Vec3 side, int t) {
		if (Volts.ownFirstPerson(e.casterId())) {
			LocalPlayer p = Minecraft.getInstance().player;
			Vec3 look = p.getLookAngle();
			return p.getEyePosition().add(look.scale(1.1)).add(side.scale(0.4)).add(0, -0.45, 0);
		}
		if (t == 0) return Volts.hand(e, level);
		return g.point(t).add(0, ChidoriLook.HAND_HEIGHT, 0).add(g.flatDir().scale(ChidoriLook.HAND_AHEAD)).add(side.scale(ChidoriLook.HAND_SIDE));
	}

	/** The off-hand side of a caster charging along {@code f}. */
	private static Vec3 offSide(FxEvent e, ClientLevel level, Vec3 f) {
		Vec3 right = f.cross(new Vec3(0, 1, 0));
		right = right.lengthSqr() < 1.0E-6 ? new Vec3(1, 0, 0) : right.normalize();
		boolean mainRight = !(level.getEntity(e.casterId()) instanceof net.minecraft.world.entity.player.Player p)
				|| p.getMainArm() == net.minecraft.world.entity.HumanoidArm.RIGHT;
		return mainRight ? right.reverse() : right;
	}

	private static BlockState groundState(ClientLevel level, Vec3 at) {
		BlockState s = level.getBlockState(BlockPos.containing(at.x, at.y - 0.5, at.z));
		return s.isAir() ? Blocks.STONE.defaultBlockState() : s;
	}

	private static Vec3 camera() {
		return Minecraft.getInstance().gameRenderer.mainCamera().position();
	}

	private static void sound(ClientLevel level, Vec3 at, net.minecraft.sounds.SoundEvent s, float volume, float pitch) {
		level.playLocalSound(at.x, at.y, at.z, s, SoundSource.PLAYERS, volume, pitch, false);
	}
}
