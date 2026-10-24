package local.carter.office.client.magic.fx;

import org.jspecify.annotations.Nullable;

import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.particles.ParticleOptions;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.Mth;
import net.minecraft.util.RandomSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.Vec3;

/**
 * Visuals the skill-tree forks share (client only, drawn from the one fx event like every lane's): a blast flash, an
 * expanding shock ring, a lingering zone, a pull vortex, a sky bolt and a buff aura. Each lane calls them where the server's
 * fork does the same thing ({@code RankKit} on the server), so the picture follows the fork. Budgeted by the event's kit.
 */
public final class RankFx {
	private static final Vec3 UP = new Vec3(0, 1, 0);

	private RankFx() {}

	/** A blast of {@code radius} at {@code at}: a ground circle flash, motes thrown outward, {@code p} puffs and a thump. */
	public static void blast(FxKit kit, ClientLevel level, Vec3 at, double radius, int rgb, @Nullable ParticleOptions p) {
		kit.circle(at.add(0, 0.05, 0), UP, 0xFF000000 | rgb, (float) radius, 3, 10);
		int n = 10 + (int) Math.round(radius * 6);
		kit.motes(at, rgb, n, 0.3, Vec3.ZERO, 0.12 + 0.05 * radius, GlowMote.Drift.COAST, 0.14F, 10);
		if (p != null) kit.burst(at, p, 8 + (int) Math.round(radius * 4), 0.08 + 0.04 * radius);
		sound(level, at, SoundEvents.GENERIC_EXPLODE.value(), 0.5F, 1.4F);
	}

	/** A shock ring growing from {@code centre} to {@code radius} over {@code ticks}, motes riding its edge. */
	public static void shockRing(FxKit kit, ClientLevel level, Vec3 centre, double radius, int ticks, int rgb, @Nullable ParticleOptions p) {
		RandomSource r = kit.random();
		sound(level, centre, SoundEvents.WIND_CHARGE_BURST.value(), 1.0F, 0.7F);
		kit.simulate(1, Math.max(1, ticks), t -> {
			double rad = radius * (t + 1) / Math.max(1, ticks);
			int n = 6 + (int) Math.round(rad * 2.2);
			for (int i = 0; i < n; i++) {
				double a = r.nextDouble() * Mth.TWO_PI;
				Vec3 at = centre.add(Math.cos(a) * rad, 0.15 + r.nextDouble() * 0.4, Math.sin(a) * rad);
				kit.motes(at, rgb, 1, 0.05, new Vec3(Math.cos(a) * 0.08, 0.02, Math.sin(a) * 0.08), 0.01, GlowMote.Drift.COAST, 0.12F, 6);
			}
			if (p != null && t % 2 == 0) kit.ring(centre.add(0, 0.1, 0), rad, p, n / 2);
		});
	}

	/**
	 * A lingering zone round {@code a → b}, {@code radius} wide, for {@code life} ticks: a ground circle at each end, and every
	 * few ticks motes (and {@code p}) rising from random points in it.
	 */
	public static void zone(FxKit kit, ClientLevel level, Vec3 a, Vec3 b, double radius, int life, int rgb, @Nullable ParticleOptions p,
			GlowMote.Drift drift) {
		RandomSource r = kit.random();
		kit.circle(a.add(0, 0.05, 0), UP, 0xFF000000 | rgb, (float) radius, 3, life);
		if (a.distanceToSqr(b) > 1) kit.circle(b.add(0, 0.05, 0), UP, 0xFF000000 | rgb, (float) radius, 3, life);
		kit.simulate(3, life, t -> {
			int n = 2 + (int) Math.round(radius);
			for (int i = 0; i < n; i++) {
				Vec3 c = a.lerp(b, r.nextDouble());
				double ang = r.nextDouble() * Mth.TWO_PI;
				double d = radius * Math.sqrt(r.nextDouble());
				Vec3 at = c.add(Math.cos(ang) * d, 0.1, Math.sin(ang) * d);
				kit.motes(at, rgb, 1, 0.05, new Vec3(0, 0.03, 0), 0.01, drift, 0.11F, 14);
				if (p != null && i == 0) level.addParticle(p, at.x, at.y, at.z, 0, 0.03, 0);
			}
		});
	}

	/** A vortex at {@code centre} pulling in from {@code radius} for {@code life} ticks: motes spiralling inward, a ground circle. */
	public static void vortex(FxKit kit, ClientLevel level, Vec3 centre, double radius, int life, int rgb) {
		RandomSource r = kit.random();
		kit.circle(centre.add(0, 0.05, 0), UP, 0xFF000000 | rgb, (float) radius, 4, life);
		sound(level, centre, SoundEvents.BREEZE_WHIRL, 0.8F, 0.6F);
		kit.simulate(2, life, t -> {
			for (int i = 0; i < 4; i++) {
				double a = r.nextDouble() * Mth.TWO_PI;
				double d = radius * (0.5 + 0.5 * r.nextDouble());
				Vec3 at = centre.add(Math.cos(a) * d, 0.2 + r.nextDouble() * 1.5, Math.sin(a) * d);
				// Inward and round: the tangent plus a pull to the middle.
				Vec3 in = centre.subtract(at).normalize().scale(0.18);
				Vec3 tan = new Vec3(-Math.sin(a), 0, Math.cos(a)).scale(0.14);
				kit.motes(at, rgb, 1, 0.05, in.add(tan), 0.01, GlowMote.Drift.COAST, 0.12F, 12);
			}
		});
	}

	/** A bolt from the sky onto {@code at} ({@code seed} picks its shape, the same on every client), a flash and a crack. */
	public static void skyBolt(FxKit kit, ClientLevel level, Vec3 at, long seed, int argb) {
		RandomSource r = RandomSource.create(seed);
		Vec3 top = at.add((r.nextDouble() - 0.5) * 3, 14 + r.nextDouble() * 4, (r.nextDouble() - 0.5) * 3);
		kit.bolt(top, at, seed, argb);
		kit.motes(at.add(0, 0.2, 0), argb & 0xFFFFFF, 10, 0.3, Vec3.ZERO, 0.15, GlowMote.Drift.FLICKER, 0.15F, 8);
		sound(level, at, SoundEvents.TRIDENT_THUNDER.value(), 0.6F, 1.5F + r.nextFloat() * 0.3F);
	}

	/** A buff on {@code e}: motes rising round its body every few ticks for {@code ticks}. */
	public static void aura(FxKit kit, ClientLevel level, int entityId, int rgb, int ticks) {
		RandomSource r = kit.random();
		kit.simulate(4, ticks, t -> {
			Entity e = level.getEntity(entityId);
			if (e == null) return;
			double w = e.getBbWidth() * 0.7;
			Vec3 at = e.position().add((r.nextDouble() - 0.5) * w * 2, r.nextDouble() * e.getBbHeight(), (r.nextDouble() - 0.5) * w * 2);
			kit.motes(at, rgb, 1, 0.05, new Vec3(0, 0.04, 0), 0.01, GlowMote.Drift.RISE, 0.1F, 12);
		});
	}

	/** Where entity {@code id} stands now (its middle), else {@code fallback}. */
	public static Vec3 middle(ClientLevel level, int id, Vec3 fallback) {
		Entity e = level.getEntity(id);
		return e == null ? fallback : e.getBoundingBox().getCenter();
	}

	static void sound(ClientLevel level, Vec3 at, SoundEvent s, float volume, float pitch) {
		if (Minecraft.getInstance().level != level) return;
		level.playLocalSound(at.x, at.y, at.z, s, SoundSource.PLAYERS, volume, pitch, false);
	}
}
