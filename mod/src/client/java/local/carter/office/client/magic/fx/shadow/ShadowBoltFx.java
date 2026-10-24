package local.carter.office.client.magic.fx.shadow;

import java.util.Optional;

import local.carter.office.client.magic.fx.FxKit;
import local.carter.office.client.magic.fx.GlowMote;
import local.carter.office.client.magic.fx.LightPipeline;
import local.carter.office.client.magic.fx.MagicCircleRenderer;
import local.carter.office.client.magic.fx.SpellFx;
import local.carter.office.magic.net.FxEvent;
import local.carter.office.magic.spell.ProjectileSpec;
import local.carter.office.magic.spell.Projectiles;
import local.carter.office.magic.spell.TargetFilter;
import local.carter.office.magic.spell.shadow.ShadowBolt;

import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.particle.ParticleRenderType;
import net.minecraft.client.particle.SingleQuadParticle;
import net.minecraft.client.renderer.state.level.QuadParticleRenderState;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.LightCoordsUtil;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

/**
 * Shadow Bolt: a small violet circle snaps open before the caster and a ball of darkness ringed with violet light streaks out
 * on the server's own path ({@link Projectiles#step}), trailing a black comet tail wound with two violet strands, smoke and
 * portal motes being sucked back into its wake. It bursts on the first entity its own sweep meets, or at the wall the server
 * named ({@code target}): a swelling ball of shadow with a violet shock edge, ink and portal sparks. Out of range it
 * thins away into smoke.
 */
public final class ShadowBoltFx implements SpellFx {
	/** Vanilla trail particles start this far out (up close they are big pixel squares in the caster's face). */
	private static final double TRAIL_CLEAR = 2.6;

	@Override
	public void play(FxEvent e, ClientLevel level, FxKit kit) {
		Vec3 dir = ShadowFx.unit(e.dir());
		Vec3 origin = e.origin();
		float c = Mth.clamp(e.charge(), 0F, 1F);
		float[] data = e.data();
		double speed = data.length > 0 && data[0] > 0 ? data[0] : ShadowBolt.SPEED;
		double radius = data.length > 1 ? data[1] : ShadowBolt.RADIUS;
		ProjectileSpec spec = new ProjectileSpec(origin, dir.scale(speed), 0, radius, ShadowBolt.MAX_TICKS, false);
		double stop = e.target().distanceTo(origin);
		// The server names the wall it meets, or the end of its range: a bolt that runs out just thins away.
		boolean spent = stop >= speed * ShadowBolt.MAX_TICKS - 0.01;

		// The muzzle: a quick circle facing the caster and a puff of violet motes off it (vanilla particles stay out of the
		// caster's face: up close they are big pixel squares).
		Vec3 muzzle = origin.add(dir.scale(1.7)).add(0, -0.15, 0);
		kit.circle(muzzle, dir.reverse(), ShadowFx.ARGB, 0.42F * (1F + c), 2 + Math.round(2 * c), 9 + Math.round(4 * c));
		kit.motes(muzzle, ShadowFx.VIOLET, 8 + Math.round(6 * c), 0.25, dir.scale(0.08), 0.06, GlowMote.Drift.SINK, 0.1F, 10);
		kit.motes(muzzle.add(dir.scale(0.3)), ShadowFx.PALE, 4, 0.1, dir.scale(0.12), 0.05, GlowMote.Drift.COAST, 0.07F, 7);
		level.playLocalSound(muzzle.x, muzzle.y, muzzle.z, SoundEvents.ILLUSIONER_CAST_SPELL, SoundSource.PLAYERS, 0.55F, 0.65F + 0.15F * (1 - c), false);
		level.playLocalSound(muzzle.x, muzzle.y, muzzle.z, SoundEvents.WITHER_SHOOT, SoundSource.PLAYERS, 0.18F + 0.1F * c, 1.7F - 0.3F * c, false);

		// As big as the server's bolt (ShadowBolt.radius): twice a tap's at full charge.
		float size = 0.3F * (1F + c);
		Head head = new Head(level, origin, dir, speed, (float) Math.min(ShadowBolt.MAX_TICKS, stop / speed), size);
		if (kit.budget().particles(Head.cost()) >= Head.cost()) Minecraft.getInstance().particleEngine.add(head);

		boolean[] done = {false};
		Vec3[] last = {origin.add(dir.scale(TRAIL_CLEAR))};
		kit.simulate(1, e.duration() + 1, t -> {
			if (done[0]) return;
			double b = Math.min((t + 1) * speed, stop);
			Vec3 from = Projectiles.step(spec, t);
			Vec3 to = origin.add(dir.scale(b));
			Optional<Vec3> hit = sweep(level, e.casterId(), from, to, radius);
			boolean wall = (t + 1) * speed >= stop;
			Vec3 at = hit.orElse(to);
			double atDist = at.distanceTo(origin);
			if (atDist > TRAIL_CLEAR) {
				// Smoke left hanging in its wake, and portal motes sucked back into it.
				Vec3 tail = last[0];
				kit.trail(tail, at, ParticleTypes.SMOKE, 3);
				kit.burst(at.subtract(dir.scale(0.6)), ParticleTypes.PORTAL, 2, 0.8);
				kit.motes(at.subtract(dir.scale(0.4)), t % 2 == 0 ? ShadowFx.VIOLET : ShadowFx.MAGENTA, 2, 0.12, dir.scale(0.03), 0.02,
						GlowMote.Drift.SINK, 0.09F + 0.04F * c, 9);
				last[0] = at;
			}
			if (hit.isPresent() || wall) {
				done[0] = true;
				head.stopAt((float) (atDist / speed));
				if (hit.isEmpty() && spent) dissipate(kit, at, dir);
				else impact(kit, level, at, dir, c, hit.isPresent());
			}
		});
	}

	/** Out of range: the bolt loses its shape and thins away into smoke. */
	private static void dissipate(FxKit kit, Vec3 at, Vec3 dir) {
		kit.burst(at, ParticleTypes.LARGE_SMOKE, 3, 0.04);
		kit.burst(at, ParticleTypes.REVERSE_PORTAL, 8, 0.08);
		kit.motes(at, ShadowFx.VIOLET, 5, 0.3, dir.scale(0.03), 0.02, GlowMote.Drift.SINK, 0.1F, 12);
	}

	/** The burst: a ball of shadow swelling with a violet shock edge, ink, portal sparks, a short circle, and the sound of it. */
	private static void impact(FxKit kit, ClientLevel level, Vec3 at, Vec3 dir, float c, boolean entity) {
		Vec3 back = at.subtract(dir.scale(0.2));
		ShadowPop pop = new ShadowPop(level, back, 0.75F + 0.35F * c, 9);
		if (kit.budget().particles(ShadowPop.cost()) >= ShadowPop.cost()) Minecraft.getInstance().particleEngine.add(pop);
		kit.burst(back, ParticleTypes.SQUID_INK, 3 + Math.round(3 * c), 0.14 + 0.06 * c);
		kit.burst(back, ParticleTypes.REVERSE_PORTAL, 16 + Math.round(10 * c), 0.28);
		kit.burst(back, ParticleTypes.LARGE_SMOKE, 2, 0.05);
		ringOut(kit, back, dir, 14, 0.2 + 0.08 * c, ShadowFx.VIOLET, 0.16F);
		kit.circle(back, dir.reverse(), ShadowFx.ARGB, 0.45F + 0.25F * c, 1, 8);
		level.playLocalSound(at.x, at.y, at.z, SoundEvents.ENDER_EYE_DEATH, SoundSource.PLAYERS, 0.9F, 0.55F + level.getRandom().nextFloat() * 0.1F, false);
		if (entity) {
			level.playLocalSound(at.x, at.y, at.z, SoundEvents.SHULKER_BULLET_HIT, SoundSource.PLAYERS, 0.7F, 0.6F, false);
		}
	}

	/** {@code n} motes thrown outward round {@code centre} in the plane facing {@code normal}. */
	private static void ringOut(FxKit kit, Vec3 centre, Vec3 normal, int n, double speed, int rgb, float size) {
		Vec3[] p = ShadowFx.plane(normal);
		for (int i = 0; i < n; i++) {
			double a = Mth.TWO_PI * i / n;
			Vec3 v = p[0].scale(Math.cos(a) * speed).add(p[1].scale(Math.sin(a) * speed));
			kit.motes(centre, i % 3 == 0 ? ShadowFx.PALE : rgb, 1, 0.03, v, 0.01, GlowMote.Drift.COAST, size, 9);
		}
	}

	/** Where the bolt from {@code from} to {@code to} first meets an entity the spell could hit (the client's own sweep). */
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

	/**
	 * The bolt itself: a dark orb with a lit rim riding the path, a black comet tail behind it and two violet strands wound
	 * round the tail. Pieces near the camera fade out (the caster sees it leave, not a black screen). It collapses once stopped.
	 */
	static final class Head extends SingleQuadParticle implements LightPipeline.Extent {
		/** Nothing is drawn nearer than this to the start (the caster's own face). */
		static final double CLEAR = 1.4;
		private static final int RIM = 18;
		private static final int TAIL = 14;
		private static final int STRAND = 9;
		private static final int COLLAPSE = 4;

		private final Vec3 origin;
		private final Vec3 dir;
		private final Vec3 u;
		private final Vec3 v;
		private final double speed;
		private final float size;
		private float stop;
		/** Daylight where it flies (re-read every tick): the wide shadow round it is stronger by day. */
		private float day;
		private final Umbra umbra = new Umbra();

		Head(ClientLevel level, Vec3 origin, Vec3 dir, double speed, float stop, float size) {
			super(level, origin.x, origin.y, origin.z, MagicCircleRenderer.sprite("glow"));
			this.origin = origin;
			this.dir = dir;
			Vec3[] p = ShadowFx.plane(dir);
			this.u = p[0];
			this.v = p[1];
			this.speed = speed;
			this.stop = stop;
			this.size = size;
			this.day = Umbra.daylight(origin);
			this.lifetime = Mth.ceil(stop) + COLLAPSE + 1;
			this.hasPhysics = false;
			this.gravity = 0;
			this.xd = 0;
			this.yd = 0;
			this.zd = 0;
		}

		/** Fx-budget particles it costs (its pieces a frame at their most, per 50). */
		static int cost() {
			return Umbra.budgetCost(Umbra.orbPieces(RIM) + TAIL + 2 * STRAND);
		}

		/** Stops the bolt {@code ticks} into its flight (where the client saw it hit); it collapses there. */
		void stopAt(float ticks) {
			if (ticks < stop) {
				stop = Math.max(0, ticks);
				lifetime = Math.min(lifetime, Mth.ceil(stop) + COLLAPSE + 1);
			}
		}

		private Vec3 at(double t) {
			return origin.add(dir.scale(speed * Math.min(t, stop)));
		}

		@Override
		public void tick() {
			age++;
			Vec3 p = at(age);
			xo = x;
			yo = y;
			zo = z;
			x = p.x;
			y = p.y;
			z = p.z;
			day = Umbra.daylight(p);
			if (age >= lifetime) remove();
		}

		@Override
		public void extract(QuadParticleRenderState state, Camera camera, float partial) {
			float now = age + partial;
			float past = Math.max(0, now - stop);
			float shrink = 1 - Mth.clamp(past / COLLAPSE, 0F, 1F);
			if (shrink <= 0.01F) return;
			umbra.begin(state, camera);
			Vec3 cam = umbra.camera();
			double travelled = speed * Math.min(now, stop);
			Vec3 head = at(now);
			// Fade what is close to the camera: the caster sees the bolt leave, not a black screen.
			float near = (float) Mth.clamp((head.distanceTo(cam) - 1.2) / 1.6, 0, 1);
			if (travelled < CLEAR || near <= 0.01F) return;
			float swell = 1 + 0.08F * Mth.sin(now * 1.7F);
			// The tail first: a black comet tail tapering behind the head (pieces overlap into one smoky streak), never back past
			// the clear zone.
			double tailLength = Math.min(3.0, travelled - CLEAR);
			for (int k = TAIL - 1; k >= 1; k--) {
				double back = tailLength * k / (TAIL - 1);
				float f = 1 - (float) k / TAIL;
				umbra.dark(head.subtract(dir.scale(back)), size * (0.5F + 1.3F * f) * shrink, 0.75F * f * near);
			}
			// Two violet strands wound round it.
			for (int s = 0; s < 2; s++) {
				for (int k = 0; k < STRAND; k++) {
					double back = tailLength * k / STRAND;
					double phi = k * 0.8 - now * 0.9 + s * Math.PI;
					double r = size * (1.05 - 0.55 * k / STRAND);
					Vec3 p = head.subtract(dir.scale(back)).add(u.scale(Math.cos(phi) * r)).add(v.scale(Math.sin(phi) * r));
					float f = 1 - (float) k / STRAND;
					umbra.light(p, size * 0.4F * (0.5F + 0.5F * f) * shrink, 0.55F * f * near, s == 0 ? ShadowFx.VIOLET : ShadowFx.MAGENTA);
				}
			}
			umbra.orb(head, size * swell * shrink, near, now * 0.35, RIM, 0.06F + 0.34F * day);
		}

		@Override
		public int getLightCoords(float partial) {
			return LightCoordsUtil.FULL_BRIGHT;
		}

		@Override
		protected Layer getLayer() {
			return LightPipeline.glow();
		}

		@Override
		public ParticleRenderType getGroup() {
			return LightPipeline.GROUP;
		}

		@Override
		public double centreX() {
			return x;
		}

		@Override
		public double centreY() {
			return y;
		}

		@Override
		public double centreZ() {
			return z;
		}

		@Override
		public double reach() {
			return 3.5 + speed;
		}
	}
}
