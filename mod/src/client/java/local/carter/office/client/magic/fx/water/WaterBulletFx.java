package local.carter.office.client.magic.fx.water;

import java.util.Optional;

import local.carter.office.client.magic.fx.FxKit;
import local.carter.office.client.magic.fx.GlowMote;
import local.carter.office.client.magic.fx.SpellFx;
import local.carter.office.magic.net.FxEvent;
import local.carter.office.magic.spell.ProjectileSpec;
import local.carter.office.magic.spell.Projectiles;
import local.carter.office.magic.spell.TargetFilter;
import local.carter.office.magic.spell.water.WaterBullet;

import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

/**
 * Water Bullet: a small circle snaps open at the caster's hand, and a glowing orb of water streaks out on the server's own
 * path ({@link Projectiles#step}), shedding a bright wake and drips, until it bursts in a splash ring on the first entity
 * its sweep meets or at the wall the server named ({@code target}).
 */
public final class WaterBulletFx implements SpellFx {
	/** The wake starts this far out, so the caster's view stays clear. */
	private static final double CLEAR = 1.8;

	@Override
	public void play(FxEvent e, ClientLevel level, FxKit kit) {
		Vec3 dir = WaterFx.unit(e.dir());
		Vec3 origin = e.origin();
		float c = Mth.clamp(e.charge(), 0F, 1F);
		float[] data = e.data();
		double speed = data.length > 0 && data[0] > 0 ? data[0] : WaterBullet.SPEED;
		double radius = data.length > 1 ? data[1] : WaterBullet.RADIUS;
		ProjectileSpec spec = new ProjectileSpec(origin, dir.scale(speed), 0, radius, WaterBullet.MAX_TICKS, false);
		double stop = e.target().distanceTo(origin);

		// The muzzle: a quick circle facing the caster, a puff of spray and a ring of light thrown out in its plane.
		Vec3 muzzle = origin.add(dir.scale(1.7)).add(0, -0.15, 0);
		kit.circle(muzzle, dir.reverse(), WaterFx.ARGB, 0.42F + 0.3F * c, 2 + Math.round(2 * c), 9 + Math.round(4 * c));
		kit.burst(muzzle.add(dir.scale(0.25)), ParticleTypes.SPLASH, 8 + Math.round(8 * c), 0.14);
		ringOut(kit, muzzle, dir, 10, 0.12 + 0.06 * c, WaterFx.LIGHT, 0.12F);
		level.playLocalSound(muzzle.x, muzzle.y, muzzle.z, SoundEvents.TRIDENT_THROW.value(), SoundSource.PLAYERS, 0.5F, 1.5F + 0.2F * (1 - c), false);
		level.playLocalSound(muzzle.x, muzzle.y, muzzle.z, SoundEvents.PLAYER_SPLASH, SoundSource.PLAYERS, 0.35F, 1.7F, false);

		float orb = 0.44F + 0.24F * c;
		// A short flight can afford a dense wake; a long one thins it so the splash keeps its share of the budget.
		double spacing = e.duration() <= 8 ? 0.18 : 0.32;
		boolean[] done = {false};
		kit.simulate(1, e.duration() + 1, t -> {
			if (done[0]) return;
			double a = t * speed;
			double b = Math.min((t + 1) * speed, stop);
			Vec3 from = Projectiles.step(spec, t);
			Vec3 to = origin.add(dir.scale(b));
			Optional<Vec3> hit = sweep(level, e.casterId(), from, to, radius);
			boolean wall = (t + 1) * speed >= stop;
			Vec3 head = hit.orElse(to);
			double headAt = hit.map(h -> h.distanceTo(origin)).orElse(b);
			// The wake, drawn only past the clear zone.
			double w0 = Math.max(a, CLEAR);
			if (headAt > w0) {
				int n = Math.max(2, (int) Math.round((headAt - w0) / spacing));
				for (int i = 0; i < n; i++) {
					double at = w0 + (headAt - w0) * (i + level.getRandom().nextDouble()) / n;
					Vec3 p = origin.add(dir.scale(at));
					kit.motes(p, i % 3 == 0 ? WaterFx.LIGHT : WaterFx.BODY, 1, 0.05, dir.scale(0.04), 0.018, GlowMote.Drift.FALL,
							0.11F + 0.06F * c + 0.08F * (float) ((at - w0) / Math.max(0.1, headAt - w0)), 6);
				}
				kit.trail(origin.add(dir.scale(w0)), head, ParticleTypes.FALLING_WATER, 2);
				kit.trail(origin.add(dir.scale(w0)), head, ParticleTypes.SPLASH, 1);
			}
			if (headAt > CLEAR) {
				// The orb: a white-hot heart in a blue shell.
				kit.motes(head, WaterFx.FOAM, 1, 0.02, dir.scale(0.05), 0.0, GlowMote.Drift.COAST, orb, 2);
				kit.motes(head, WaterFx.BODY, 2, 0.1, dir.scale(0.08), 0.02, GlowMote.Drift.COAST, orb * 0.75F, 3);
				kit.motes(head.subtract(dir.scale(0.3)), WaterFx.DEEP, 1, 0.05, dir.scale(0.05), 0.0, GlowMote.Drift.COAST, orb * 1.7F, 2);
			}
			if (hit.isPresent() || wall) {
				done[0] = true;
				impact(kit, level, head, dir, c, hit.isPresent());
			}
		});
	}

	/** The splash: spray, popping bubbles, a ring of light in the plane facing back along the jet, and a slap. */
	private static void impact(FxKit kit, ClientLevel level, Vec3 at, Vec3 dir, float c, boolean entity) {
		Vec3 back = at.subtract(dir.scale(0.15));
		kit.burst(back, ParticleTypes.SPLASH, 22 + Math.round(16 * c), 0.28 + 0.1 * c);
		kit.burst(back, ParticleTypes.BUBBLE_POP, 8, 0.14);
		kit.burst(back, ParticleTypes.FALLING_WATER, 6, 0.1);
		ringOut(kit, back, dir, 14, 0.22 + 0.1 * c, WaterFx.FOAM, 0.2F);
		kit.motes(back, WaterFx.BODY, 8, 0.25, dir.scale(-0.05), 0.12, GlowMote.Drift.FALL, 0.16F, 12);
		kit.circle(back, dir.reverse(), WaterFx.ARGB, 0.4F + 0.25F * c, 1, 7);
		level.playLocalSound(at.x, at.y, at.z, SoundEvents.GENERIC_SPLASH, SoundSource.PLAYERS, 0.8F, 1.25F + level.getRandom().nextFloat() * 0.2F,
				false);
		if (entity) {
			level.playLocalSound(at.x, at.y, at.z, SoundEvents.PLAYER_SPLASH_HIGH_SPEED, SoundSource.PLAYERS, 0.45F, 1.4F, false);
		}
	}

	/** {@code n} motes thrown outward round {@code centre} in the plane facing {@code normal}. */
	private static void ringOut(FxKit kit, Vec3 centre, Vec3 normal, int n, double speed, int rgb, float size) {
		Vec3[] p = WaterFx.plane(normal);
		for (int i = 0; i < n; i++) {
			double a = Mth.TWO_PI * i / n;
			Vec3 v = p[0].scale(Math.cos(a) * speed).add(p[1].scale(Math.sin(a) * speed));
			kit.motes(centre, rgb, 1, 0.03, v, 0.01, GlowMote.Drift.COAST, size, 8);
		}
	}

	/** Where the jet from {@code from} to {@code to} first meets an entity the spell could hit (the client's own sweep). */
	private static Optional<Vec3> sweep(ClientLevel level, int casterId, Vec3 from, Vec3 to, double radius) {
		Vec3 best = null;
		double bestD = Double.MAX_VALUE;
		for (LivingEntity en : level.getEntitiesOfClass(LivingEntity.class, new AABB(from, to).inflate(radius + 1),
				en -> en.getId() != casterId && TargetFilter.affects(null, en))) {
			AABB box = en.getBoundingBox().inflate(radius);
			Optional<Vec3> at = box.contains(from) ? Optional.of(from) : box.clip(from, to);
			if (at.isPresent() && from.distanceToSqr(at.get()) < bestD) {
				bestD = from.distanceToSqr(at.get());
				best = at.get();
			}
		}
		return Optional.ofNullable(best);
	}
}
