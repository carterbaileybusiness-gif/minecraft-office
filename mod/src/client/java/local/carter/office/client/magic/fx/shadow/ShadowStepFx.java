package local.carter.office.client.magic.fx.shadow;

import local.carter.office.client.magic.fx.FxKit;
import local.carter.office.client.magic.fx.GlowMote;
import local.carter.office.client.magic.fx.SpellFx;
import local.carter.office.magic.net.FxEvent;

import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.Mth;
import net.minecraft.world.phys.Vec3;

/**
 * Shadow Step: the caster sinks into a pool of shadow where they stood (a column of darkness drawn down into a ground sigil) and
 * rises out of another behind the target; a smear of shadow joins the two for a moment. In first person the caster sees no
 * column round their own body, only the pool under their feet and a quick widening of the view.
 */
public final class ShadowStepFx implements SpellFx {
	private static final Vec3 UP = new Vec3(0, 1, 0);

	@Override
	public void play(FxEvent e, ClientLevel level, FxKit kit) {
		Minecraft mc = Minecraft.getInstance();
		Vec3 land = e.target();
		float[] d = e.data();
		Vec3 from = d.length >= 3 ? land.add(d[0], d[1], d[2]) : e.origin().subtract(0, 1.62, 0);
		boolean own = mc.player != null && mc.player.getId() == e.casterId();
		boolean firstPerson = own && mc.options.getCameraType().isFirstPerson();

		// Where they left: a pool of shadow and the column sinking into it (wider for a charged step).
		float pool = 0.85F * (1F + Mth.clamp(e.charge(), 0F, 1F));
		kit.circle(from.add(0, 0.04, 0), UP, ShadowFx.ARGB, pool, 2, 14);
		spawn(kit, new Column(level, from, false, 12), 20);
		kit.burst(from.add(0, 0.9, 0), ParticleTypes.REVERSE_PORTAL, 10, 0.12);
		kit.motes(from.add(0, 0.1, 0), ShadowFx.VIOLET, 8, 0.45, Vec3.ZERO, 0.03, GlowMote.Drift.SINK, 0.12F, 14);

		// A smear of shadow from there to here, at body height.
		if (from.distanceToSqr(land) > 1) spawn(kit, new Smear(level, from.add(0, 1, 0), land.add(0, 1, 0), 7), 180);

		// Where they arrive: a pool, and (seen from outside) the column rising out of it.
		kit.circle(land.add(0, 0.04, 0), UP, ShadowFx.ARGB, pool, 2, 16);
		if (!firstPerson) spawn(kit, new Column(level, land, true, 12), 20);
		kit.motes(land.add(0, 0.15, 0), ShadowFx.PALE, 6, 0.4, new Vec3(0, 0.04, 0), 0.03, GlowMote.Drift.RISE, 0.1F, 12);
		kit.motes(land.add(0, 0.1, 0), ShadowFx.MAGENTA, 6, 0.5, Vec3.ZERO, 0.04, GlowMote.Drift.SINK, 0.12F, 14);
		if (own) kit.fovKick(0.06F);

		level.playLocalSound(from.x, from.y, from.z, SoundEvents.ENDERMAN_TELEPORT, SoundSource.PLAYERS, 0.6F, 0.55F, false);
		level.playLocalSound(land.x, land.y, land.z, SoundEvents.ILLUSIONER_MIRROR_MOVE, SoundSource.PLAYERS, 0.9F, 0.7F, false);
	}

	private static void spawn(FxKit kit, UmbraParticle p, int quads) {
		int cost = Umbra.budgetCost(quads);
		if (kit.budget().particles(cost) >= cost) Minecraft.getInstance().particleEngine.add(p);
	}

	/** A body-sized column of darkness ringed with violet: rising out of the ground ({@code rise}) or sinking into it. */
	static final class Column extends UmbraParticle {
		private static final int SLICES = 7;
		private static final double HEIGHT = 1.9;
		private final Vec3 feet;
		private final boolean rise;

		Column(ClientLevel level, Vec3 feet, boolean rise, int life) {
			super(level, feet, life);
			this.feet = feet;
			this.rise = rise;
		}

		@Override
		protected Vec3 anchor() {
			return feet.add(0, 1, 0);
		}

		@Override
		public double reach() {
			return 2.2;
		}

		@Override
		protected void draw(double t) {
			double f = Mth.clamp(t / lifetime, 0, 1);
			// How much of the body is shadow: it fills from the feet up as it rises, drains down as it sinks.
			double fill = rise ? 1 - f : 1 - f * 1.4;
			double top = HEIGHT * Mth.clamp(rise ? Math.min(1, t / 3.0) * (1 - f * 0.3) : fill, 0, 1);
			float fade = (float) (rise ? (1 - f) * (1 - f) : 1 - f);
			if (top <= 0.05 || fade <= 0.01F) return;
			for (int i = 0; i < SLICES; i++) {
				double y = top * (i + 0.5) / SLICES;
				float w = (float) (0.42 - 0.08 * Math.abs(i - SLICES * 0.55) / SLICES);
				umbra.dark(feet.add(0, y, 0), w, 0.85F * fade);
			}
			// Violet light streaming off its edge.
			for (int i = 0; i < 12; i++) {
				double a = i * 2.39996 + t * 0.4;
				double y = top * ((i * 0.618) % 1.0);
				Vec3 p = feet.add(Math.cos(a) * 0.38, y + (rise ? 1 : -1) * 0.05 * t, Math.sin(a) * 0.38);
				umbra.light(p, 0.12F, 0.7F * fade, i % 3 == 0 ? ShadowFx.PALE : ShadowFx.VIOLET);
			}
			umbra.dark(feet.add(0, 0.08, 0), 0.75F, 0.6F * fade);
		}
	}

	/** A smear of shadow along the step, fading from the far end. */
	static final class Smear extends UmbraParticle {
		private final Vec3 a;
		private final Vec3 b;

		Smear(ClientLevel level, Vec3 a, Vec3 b, int life) {
			super(level, a.lerp(b, 0.5), life);
			this.a = a;
			this.b = b;
		}

		@Override
		protected Vec3 anchor() {
			return a.lerp(b, 0.5);
		}

		@Override
		public double reach() {
			return a.distanceTo(b) / 2 + 1;
		}

		@Override
		protected void draw(double t) {
			double f = Mth.clamp(t / lifetime, 0, 1);
			// Close, faint, overlapping pieces: one smear of shadow, not a row of beads.
			int n = Mth.clamp((int) (a.distanceTo(b) / 0.12), 6, 140);
			for (int i = 0; i <= n; i++) {
				double s = i / (double) n;
				// The trail drains from where they left toward where they arrived, and thins toward its ends.
				float k = (float) Mth.clamp((s - f) * 3 + 0.2, 0, 1) * (float) (1 - f) * (float) Math.sin(Math.PI * (0.1 + 0.8 * s));
				if (k <= 0.01F) continue;
				Vec3 p = a.lerp(b, s);
				umbra.dark(p, 0.32F, 0.22F * k);
				if (i % 4 == 0) umbra.light(p.add(0, 0.18 * Math.sin(s * 14 + t), 0), 0.07F, 0.4F * k, ShadowFx.MAGENTA);
			}
		}
	}
}
