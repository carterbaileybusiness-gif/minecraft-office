package local.carter.office.client.magic.fx.lightning;

import static local.carter.office.client.magic.fx.lightning.LightningLook.BLOOM;
import static local.carter.office.client.magic.fx.lightning.LightningLook.BLUE;
import static local.carter.office.client.magic.fx.lightning.LightningLook.CORE;
import static local.carter.office.client.magic.fx.lightning.LightningLook.YELLOW;

import local.carter.office.client.magic.fx.FxKit;
import local.carter.office.client.magic.fx.GlowMote;
import local.carter.office.magic.net.FxEvent;

import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.server.level.ParticleStatus;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.HumanoidArm;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.Vec3;

/**
 * The Lightning lane's drawing helpers, in {@link LightningLook}'s numbers: arcs and channels, zaps, strikes from the sky, the
 * crackle at the hand, the crawl over a struck body and the scorch at its feet. Everything asks an {@link Allowance} first and the
 * event's {@code FxBudget} second; arcs pay for the quads they really draw ({@link ArcParticle#quads()}).
 */
final class Volts {
	private Volts() {}

	/** {@code e.dir()} normalised (straight ahead if it has none). */
	static Vec3 dir(FxEvent e) {
		Vec3 d = e.dir();
		double l = d.length();
		return l < 1.0E-6 || !Double.isFinite(l) ? new Vec3(0, 0, 1) : d.scale(1 / l);
	}

	/**
	 * Where the caster's lightning leaves them: the off hand (the grimoire's), a little ahead and below the eye, on the side away
	 * from their main arm. Seen through the caster's own eyes it starts further out, low on that side, so the bolt cuts across the
	 * view into the target instead of filling the screen.
	 */
	static Vec3 hand(FxEvent e, ClientLevel level) {
		Vec3 dir = dir(e);
		Vec3 right = dir.cross(new Vec3(0, 1, 0));
		right = right.lengthSqr() < 1.0E-6 ? new Vec3(1, 0, 0) : right.normalize();
		Entity caster = level.getEntity(e.casterId());
		boolean mainRight = !(caster instanceof Player p) || p.getMainArm() == HumanoidArm.RIGHT;
		Vec3 side = mainRight ? right.reverse() : right;
		if (ownFirstPerson(e.casterId())) return e.origin().add(dir.scale(2.4)).add(side.scale(0.75)).add(0, -0.7, 0);
		return e.origin().add(dir.scale(0.85)).add(side.scale(0.42)).add(0, -0.42, 0);
	}

	/** The middle of entity {@code id}'s hitbox on this client, or {@code fallback} if it isn't here. */
	static Vec3 centre(ClientLevel level, int id, Vec3 fallback) {
		Entity en = level.getEntity(id);
		return en == null ? fallback : en.getBoundingBox().getCenter();
	}

	/**
	 * An {@link ArcParticle} {@code from} → {@code to} in {@code style}, struck from {@code seed} (the same on every client). It pays
	 * for the quads it draws, from {@code a} and the event's budget; nothing is drawn if either can't pay.
	 */
	static void arc(FxKit kit, ClientLevel level, Allowance a, Vec3 from, Vec3 to, long seed, LightningLook.Style style) {
		if (from.distanceTo(to) < LightningLook.MIN_ARC) return;
		ArcParticle p = new ArcParticle(level, from, to, seed, style.rgb(), style.width(), (float) style.roughness(), style.forks(), style.life(),
				style.core());
		int cost = ArcPath.cost(p.quads());
		if (kit.budget().particlesLeft() < cost || !a.pay(cost)) return;
		kit.budget().particles(cost);
		Minecraft.getInstance().particleEngine.add(p);
	}

	/** A lightning channel: the {@code main} bolt and a thinner {@code strand} struck from another seed, crossing and parting from it. */
	static void channel(FxKit kit, ClientLevel level, Allowance a, Vec3 from, Vec3 to, long seed, LightningLook.Style main,
			LightningLook.Style strand) {
		arc(kit, level, a, from, to, seed, main);
		arc(kit, level, a, from, to, LightningLook.strandSeed(seed), strand);
	}

	/** A thin bolt from the sky onto {@code at}. */
	static void skyStrike(FxKit kit, ClientLevel level, Allowance a, Vec3 at, double height, long seed) {
		arc(kit, level, a, LightningLook.skyTop(at, height, seed), at, seed, LightningLook.SKY);
	}

	/** A strike landing at {@code at}: a white-hot flash in a yellow bloom, motes flying, blue flickers and sparks, scaled by {@code size}. */
	static void zap(FxKit kit, ClientLevel level, Allowance a, Vec3 at, float size) {
		if (a.take(1) == 1) glow(kit, level, at, CORE, 0.6F * size, 3);
		if (a.take(1) == 1) glow(kit, level, at, BLOOM, 0.95F * size, 4);
		kit.motes(at, YELLOW, a.take(LightningLook.zapMotes(size)), 0.12, Vec3.ZERO, 0.32 * size, GlowMote.Drift.COAST, 0.075F, 6);
		kit.motes(at, BLUE, a.take(LightningLook.zapFlickers(size)), 0.3, Vec3.ZERO, 0.08, GlowMote.Drift.FLICKER, 0.09F, 9);
		kit.burst(at, ParticleTypes.ELECTRIC_SPARK, a.take(LightningLook.zapSparks(size)), 0.35 * size);
	}

	/** The crackle at the caster's hand as it fires: a few tiny arcs jumping round {@code hand} for a moment (Chidori). */
	static void crackle(FxKit kit, ClientLevel level, Allowance a, Vec3 hand, long seed) {
		kit.simulate(LightningLook.CRACKLE_EVERY, LightningLook.CRACKLE_TICKS, t -> {
			int step = t / LightningLook.CRACKLE_EVERY;
			Vec3[] arcs = LightningLook.crackle(hand, seed, step);
			for (int i = 0; i < arcs.length; i += 2) arc(kit, level, a, arcs[i], arcs[i + 1], seed + 97L * step + i, LightningLook.CRACKLE);
		});
	}

	/**
	 * Sparks crackling over a struck body: every {@link LightningLook#CRAWL_EVERY} ticks, a short wild arc skating between two points
	 * on entity {@code id}'s hitbox and a flickering mote. Follows the body as it moves; stops if it is gone.
	 */
	static void crawl(FxKit kit, ClientLevel level, Allowance a, int id, long seed) {
		kit.simulate(LightningLook.CRAWL_EVERY, LightningLook.CRAWL_TICKS, t -> {
			Entity en = level.getEntity(id);
			if (en == null || en.isRemoved()) return;
			int step = t / LightningLook.CRAWL_EVERY;
			Vec3[] ab = LightningLook.crawl(en.getBoundingBox().inflate(0.06), seed, step);
			arc(kit, level, a, ab[0], ab[1], seed + 31L * step, (step & 1) == 0 ? LightningLook.CRAWL : LightningLook.CRAWL_GOLD);
			kit.motes(ab[0], CORE, a.take(1), 0.05, Vec3.ZERO, 0.02, GlowMote.Drift.FLICKER, 0.07F, 5);
		});
	}

	/** A scorch where lightning meets the ground: a flat ring of sparks and low flickers. */
	static void ground(FxKit kit, Allowance a, Vec3 at) {
		kit.ring(at, 0.5, ParticleTypes.ELECTRIC_SPARK, a.take(LightningLook.GROUND_SPARKS));
		kit.motes(at, YELLOW, a.take(LightningLook.GROUND_MOTES), 0.3, new Vec3(0, 0.02, 0), 0.03, GlowMote.Drift.FLICKER, 0.08F, 8);
	}

	/** One round glow mote, if the budget grants it and particles are not on Minimal. */
	static void glow(FxKit kit, ClientLevel level, Vec3 at, int rgb, float size, int life) {
		Minecraft mc = Minecraft.getInstance();
		if (mc.options.particles().get() == ParticleStatus.MINIMAL || kit.budget().particles(1) == 0) return;
		mc.particleEngine.add(new GlowMote(level, at, Vec3.ZERO, rgb, size, life, GlowMote.Drift.COAST, false));
	}

	/** Whether this client is {@code casterId} looking out of their own eyes. */
	static boolean ownFirstPerson(int casterId) {
		Minecraft mc = Minecraft.getInstance();
		return mc.player != null && mc.player.getId() == casterId && mc.options.getCameraType().isFirstPerson();
	}
}
