package local.carter.office.client.magic.fx.lightning;

import local.carter.office.client.magic.fx.FxKit;
import local.carter.office.client.magic.fx.GlowMote;
import local.carter.office.magic.net.FxEvent;

import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.particles.ParticleOptions;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.server.level.ParticleStatus;
import net.minecraft.util.Mth;
import net.minecraft.util.RandomSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.HumanoidArm;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

/**
 * The Lightning lane's shared look: element yellow with a white-hot core and a haze of light round it, a thinner electric-blue
 * strand crossing it, impact zaps, strikes from above and the crawling arcs that leave a struck body crackling. Every call draws
 * from the event's {@code FxBudget}; arcs pay for the quads they draw ({@link #QUADS_PER_PARTICLE} to one budget particle).
 */
final class Volts {
	/** Element yellow (Lightning {@code FFFFE94A}), a white-hot core and an electric blue for the second strand. */
	static final int YELLOW = 0xFFE94A;
	static final int CORE = 0xFFF7D6;
	static final int BLUE = 0x8CCFFF;
	/** A soft yellow for wide blooms (additive light: the colour is the brightness). */
	static final int BLOOM = 0x8A7420;

	/** Quads one budget particle pays for (as {@code WindRibbon} does). */
	static final int QUADS_PER_PARTICLE = 50;

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
	 * An {@link ArcParticle} {@code from} → {@code to}: {@code width} thick (its halo 3.5 × that), {@code roughness} jagged, with
	 * {@code forks} branches, flashing over {@code life} ticks, struck from {@code seed} (the same on every client). It pays the budget
	 * for the quads it draws; nothing is drawn if the budget can't.
	 */
	static void arc(FxKit kit, ClientLevel level, Vec3 from, Vec3 to, long seed, int rgb, float width, float roughness, int forks, int life) {
		double l = from.distanceTo(to);
		if (l < 0.05) return;
		int cost = Mth.ceil(ArcParticle.quads(l, width, forks) / (double) QUADS_PER_PARTICLE);
		if (kit.budget().particlesLeft() < cost) return;
		kit.budget().particles(cost);
		Minecraft.getInstance().particleEngine.add(new ArcParticle(level, from, to, seed, rgb, width, roughness, forks, life));
	}

	/**
	 * A lightning channel {@code from} → {@code to}: a yellow bolt with a white-hot core and a haze, and a thinner electric-blue
	 * strand struck from another seed that crosses and parts from it; {@code thick} makes both heavier, with more forks.
	 */
	static void channel(FxKit kit, ClientLevel level, Vec3 from, Vec3 to, long seed, int life, boolean thick) {
		arc(kit, level, from, to, seed, YELLOW, thick ? 0.21F : 0.16F, 0.2F, thick ? 3 : 2, life);
		arc(kit, level, from, to, seed * 7 + 3, BLUE, thick ? 0.09F : 0.065F, 0.28F, 1, Math.max(3, life - 2));
	}

	/** A bolt from the sky onto {@code at}: {@code height} above it, leaning a little, thin and quick. */
	static void skyStrike(FxKit kit, ClientLevel level, Vec3 at, double height, long seed) {
		RandomSource r = RandomSource.create(seed);
		Vec3 top = at.add((r.nextDouble() - 0.5) * height * 0.4, height, (r.nextDouble() - 0.5) * height * 0.4);
		arc(kit, level, top, at, seed, CORE, 0.12F, 0.22F, 1, 5);
	}

	/**
	 * A strike landing at {@code at}: a white-hot flash in a yellow bloom, sparks flying, motes flickering out, scaled by {@code size}
	 * (1 = Spark's).
	 */
	static void zap(FxKit kit, ClientLevel level, Vec3 at, float size) {
		glow(kit, level, at, Vec3.ZERO, CORE, 0.6F * size, 3, GlowMote.Drift.COAST);
		glow(kit, level, at, Vec3.ZERO, BLOOM, 0.95F * size, 4, GlowMote.Drift.COAST);
		kit.motes(at, YELLOW, Math.round(10 * size), 0.12, Vec3.ZERO, 0.32 * size, GlowMote.Drift.COAST, 0.07F, 6);
		kit.motes(at, BLUE, Math.round(5 * size), 0.3, Vec3.ZERO, 0.08, GlowMote.Drift.FLICKER, 0.09F, 9);
		kit.burst(at, ParticleTypes.ELECTRIC_SPARK, Math.round(8 * size), 0.35 * size);
	}

	/**
	 * Sparks crackling over a struck body: every {@code every} ticks for {@code ticks}, a short wild arc skating between two points
	 * on entity {@code id}'s hitbox and a flickering mote. Follows the body as it moves; stops if it is gone.
	 */
	static void crawl(FxKit kit, ClientLevel level, int id, long seed, int every, int ticks) {
		RandomSource r = RandomSource.create(seed);
		kit.simulate(every, ticks, t -> {
			Entity en = level.getEntity(id);
			if (en == null || en.isRemoved()) return;
			AABB box = en.getBoundingBox().inflate(0.06);
			Vec3 a = onBox(box, r);
			Vec3 b = onBox(box, r);
			if (a.distanceToSqr(b) < 0.25) b = box.getCenter().add(box.getCenter().subtract(a));
			arc(kit, level, a, b, seed + 31L * t, (t / every & 1) == 0 ? BLUE : YELLOW, 0.045F, 0.35F, 0, 3);
			kit.motes(a, CORE, 1, 0.05, Vec3.ZERO, 0.02, GlowMote.Drift.FLICKER, 0.07F, 5);
		});
	}

	/** A random point on a side face of {@code box}, so arcs skate over the skin rather than through the middle. */
	private static Vec3 onBox(AABB box, RandomSource r) {
		double x = Mth.lerp(r.nextDouble(), box.minX, box.maxX);
		double y = Mth.lerp(r.nextDouble(), box.minY, box.maxY);
		double z = Mth.lerp(r.nextDouble(), box.minZ, box.maxZ);
		switch (r.nextInt(4)) {
			case 0 -> x = box.minX;
			case 1 -> x = box.maxX;
			case 2 -> z = box.minZ;
			default -> z = box.maxZ;
		}
		return new Vec3(x, y, z);
	}

	/** A scorch where lightning meets the ground: a flat ring of sparks, low flickers and a puff of smoke. */
	static void ground(FxKit kit, Vec3 at, float size) {
		kit.ring(at, 0.6 * size, ParticleTypes.ELECTRIC_SPARK, Math.round(10 * size));
		kit.motes(at, YELLOW, Math.round(4 * size), 0.35 * size, new Vec3(0, 0.02, 0), 0.03, GlowMote.Drift.FLICKER, 0.08F, 8);
		kit.burst(at, ParticleTypes.SMOKE, Math.round(3 * size), 0.03);
	}

	/** One round glow mote, if the budget grants it and particles are not on Minimal. */
	static void glow(FxKit kit, ClientLevel level, Vec3 at, Vec3 v, int rgb, float size, int life, GlowMote.Drift drift) {
		Minecraft mc = Minecraft.getInstance();
		if (mc.options.particles().get() == ParticleStatus.MINIMAL || kit.budget().particles(1) == 0) return;
		mc.particleEngine.add(new GlowMote(level, at, v, rgb, size, life, drift, false));
	}

	/** One vanilla particle with its own velocity, if the budget grants it. */
	static void add(FxKit kit, ClientLevel level, ParticleOptions p, Vec3 at, Vec3 v) {
		if (kit.budget().particles(1) == 0) return;
		level.addParticle(p, at.x, at.y, at.z, v.x, v.y, v.z);
	}

	/** Whether this client is {@code casterId} looking out of their own eyes. */
	static boolean ownFirstPerson(int casterId) {
		Minecraft mc = Minecraft.getInstance();
		return mc.player != null && mc.player.getId() == casterId && mc.options.getCameraType().isFirstPerson();
	}
}
