package local.carter.office.client.magic.fx.water;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

import local.carter.office.client.magic.fx.FxKit;
import local.carter.office.client.magic.fx.GlowMote;
import local.carter.office.client.magic.fx.RankFx;
import local.carter.office.client.magic.fx.SpellFx;
import local.carter.office.magic.net.FxEvent;
import local.carter.office.magic.spell.water.BubbleShield;
import local.carter.office.magic.spell.water.BubbleShieldGeometry;

import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.Mth;
import net.minecraft.util.RandomSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.projectile.Projectile;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

/**
 * Bubble Shield: a circle opens at the caster's feet and a bubble swells round them ({@link WaterSphere}, soap-film rim, a
 * glint, a faint blue fill) and follows them for the shield's life, with sparkles running over it. Whatever flies into it
 * splashes on its surface where it meets it ({@link BubbleShieldGeometry#entry}, the server's own test), and the rim shivers.
 * At the end, or if the caster falls, it pops in a spray of droplets. In the caster's own first-person view only a faint ring
 * at their feet and the splashes show.
 */
public final class BubbleShieldFx implements SpellFx {
	/** Particles kept back for the pop. */
	private static final int POP_PARTICLES = 50;

	@Override
	public void play(FxEvent e, ClientLevel level, FxKit kit) {
		int life = Math.max(1, e.duration());
		float[] data = e.data();
		float radius = data.length > 0 && data[0] > 0 ? data[0] : (float) BubbleShieldGeometry.RADIUS;
		Entity caster = level.getEntity(e.casterId());
		Vec3 fallback = e.target();
		RandomSource r = kit.random();
		WaterSphere bubble = new WaterSphere(level, partial -> centre(caster, fallback, partial), radius, life, WaterSphere.Style.BUBBLE);
		int step = bubble.cost();
		int rimTotal = step * (life / 10 + 1);

		Vec3 feet = caster != null ? caster.position() : fallback.subtract(0, 0.9, 0);
		kit.circle(feet.add(0, 0.06, 0), new Vec3(0, 1, 0), WaterFx.ARGB, 1.2F * radius / (float) BubbleShieldGeometry.RADIUS, 3, 16);
		kit.ring(feet.add(0, 0.1, 0), radius * 0.9, ParticleTypes.SPLASH, 14);
		level.playLocalSound(feet.x, feet.y, feet.z, SoundEvents.BUCKET_EMPTY, SoundSource.PLAYERS, 0.7F, 1.35F, false);
		level.playLocalSound(feet.x, feet.y, feet.z, SoundEvents.BUBBLE_COLUMN_UPWARDS_AMBIENT, SoundSource.PLAYERS, 0.8F, 1.2F, false);
		if (kit.budget().particles(step) == step) Minecraft.getInstance().particleEngine.add(bubble);

		boolean mirror = e.spellRank().is(BubbleShield.MIRROR);
		Set<Integer> splashed = new HashSet<>();
		Map<Integer, Vec3> seen = new HashMap<>();
		boolean[] popped = new boolean[1];
		kit.simulate(1, life + 1, t -> {
			if (popped[0]) return;
			Vec3 c = centre(caster, fallback, 1F);
			boolean gone = caster != null && (caster.isRemoved() || !caster.isAlive());
			if (gone || t >= life) {
				popped[0] = true;
				bubble.pop();
				pop(kit, level, c, radius, r);
				// Pop: run out, it bursts outward.
				if (!gone && e.spellRank().is(BubbleShield.POP)) RankFx.blast(kit, level, c, radius, WaterFx.LIGHT, ParticleTypes.SPLASH);
				return;
			}
			if (t > 0 && t % 10 == 0 && kit.budget().particles(step) < step) bubble.dim();
			// What the rim and the pop still need stays theirs.
			int spare = kit.budget().particlesLeft() - (rimTotal - step * (t / 10 + 1)) - POP_PARTICLES;
			if (t % 4 == 0 && spare > 2) {
				Vec3 on = c.add(sphere(r).scale(radius));
				kit.motes(on, t % 8 == 0 ? WaterFx.FOAM : 0xC8B8FF, 1, 0.02, Vec3.ZERO, 0.01, GlowMote.Drift.FLICKER, 0.12F, 8);
			}
			// Whatever flies into the bubble splashes where it meets it: seen coming (the server's own test), or, if the server
			// stopped it before this client saw it cross, where it was last seen heading in.
			for (Projectile p : level.getEntitiesOfClass(Projectile.class, new AABB(c, c).inflate(radius + BubbleShieldGeometry.SEARCH),
					p -> p.getOwner() != caster && !splashed.contains(p.getId()) && BubbleShield.stops(p))) {
				Vec3 hit = BubbleShieldGeometry.entry(c, p.position(), p.getDeltaMovement(), radius);
				if (hit != null) {
					splashed.add(p.getId());
					splash(kit, level, bubble, c, hit, spare, r);
					// Mirror: the server sends the hit back to the shooter (never as a live arrow); a jet of water shows it.
					LivingEntity back = mirror ? BubbleShield.mirrorTarget(p, null) : null;
					if (back != null && spare > 12) mirror(kit, level, hit, back.getBoundingBox().getCenter());
				} else {
					seen.put(p.getId(), p.position());
				}
			}
			for (var it = seen.entrySet().iterator(); it.hasNext(); ) {
				var en = it.next();
				Entity p = level.getEntity(en.getKey());
				if (p != null && !p.isRemoved()) continue;
				it.remove();
				Vec3 last = en.getValue();
				if (splashed.add(en.getKey()) && last.distanceTo(c) < radius + 3.5) {
					splash(kit, level, bubble, c, c.add(last.subtract(c).normalize().scale(radius)), spare, r);
				}
			}
		});
	}

	/** A projectile meets the bubble at {@code hit}: a splash on its surface, and the rim shivers. */
	private static void splash(FxKit kit, ClientLevel level, WaterSphere bubble, Vec3 c, Vec3 hit, int spare, RandomSource r) {
		bubble.ripple(1F);
		if (spare > 8) {
			kit.burst(hit, ParticleTypes.SPLASH, 8, 0.22);
			kit.motes(hit, WaterFx.FOAM, 4, 0.12, hit.subtract(c).normalize().scale(0.07), 0.05, GlowMote.Drift.FALL, 0.18F, 10);
		}
		level.playLocalSound(hit.x, hit.y, hit.z, SoundEvents.BUBBLE_COLUMN_BUBBLE_POP, SoundSource.PLAYERS, 1F, 0.8F + 0.4F * r.nextFloat(), false);
		level.playLocalSound(hit.x, hit.y, hit.z, SoundEvents.PLAYER_SPLASH, SoundSource.PLAYERS, 0.35F, 1.6F, false);
	}

	/** Mirror's return: a streak of water from where the arrow met the bubble to its shooter, and a splash on them. */
	private static void mirror(FxKit kit, ClientLevel level, Vec3 from, Vec3 to) {
		Vec3 d = to.subtract(from);
		int n = Math.min(12, 2 + (int) (d.length() * 0.8));
		for (int i = 1; i <= n; i++) {
			Vec3 at = from.add(d.scale(i / (double) n));
			kit.motes(at, i % 2 == 0 ? WaterFx.FOAM : WaterFx.LIGHT, 1, 0.03, d.normalize().scale(0.1), 0.01, GlowMote.Drift.COAST, 0.14F, 6);
		}
		kit.burst(to, ParticleTypes.SPLASH, 6, 0.2);
		level.playLocalSound(to.x, to.y, to.z, SoundEvents.PLAYER_SPLASH_HIGH_SPEED, SoundSource.PLAYERS, 0.5F, 1.4F, false);
	}

	/** The bubble's middle: the caster's (interpolated), or where it was cast if they can't be seen. */

	private static Vec3 centre(Entity caster, Vec3 fallback, float partial) {
		if (caster == null || caster.isRemoved()) return fallback;
		return BubbleShieldGeometry.centre(caster.getPosition(partial), caster.getBbHeight());
	}

	/** The bubble bursts: droplets flung off its surface, and a pop. */
	private static void pop(FxKit kit, ClientLevel level, Vec3 c, float radius, RandomSource r) {
		for (int i = 0; i < 16; i++) {
			Vec3 d = sphere(r);
			Vec3 on = c.add(d.scale(radius));
			kit.burst(on, ParticleTypes.SPLASH, 1, 0.12);
			kit.motes(on, i % 2 == 0 ? WaterFx.FOAM : WaterFx.LIGHT, 1, 0.05, d.scale(0.12), 0.04, GlowMote.Drift.FALL, 0.15F, 10);
		}
		kit.burst(c.add(0, -0.4, 0), ParticleTypes.FALLING_WATER, 10, 0.05);
		level.playLocalSound(c.x, c.y, c.z, SoundEvents.BUBBLE_COLUMN_BUBBLE_POP, SoundSource.PLAYERS, 1.2F, 0.6F, false);
		level.playLocalSound(c.x, c.y, c.z, SoundEvents.PLAYER_SPLASH, SoundSource.PLAYERS, 0.5F, 1.3F, false);
	}

	private static Vec3 sphere(RandomSource r) {
		double z = r.nextDouble() * 2 - 1;
		double a = r.nextDouble() * Mth.TWO_PI;
		double s = Math.sqrt(1 - z * z);
		return new Vec3(s * Math.cos(a), z, s * Math.sin(a));
	}
}
