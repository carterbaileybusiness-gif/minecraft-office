package local.carter.office.client.magic.fx.shadow;

import org.jspecify.annotations.Nullable;

import local.carter.office.client.magic.fx.FxKit;
import local.carter.office.client.magic.fx.GlowMote;
import local.carter.office.client.magic.fx.SpellFx;
import local.carter.office.magic.net.FxEvent;
import local.carter.office.magic.spell.shadow.BindGeometry;

import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.Vec3;

/**
 * Shadow Bind: a sigil flashes before the caster and a ripple of shadow runs along the ground to the foe; a pool of darkness
 * spreads under it and black tendrils edged in violet burst up and wind round it, writhing for as long as the server holds it
 * ({@link BindGeometry#tendril}, the life from the event), then sink away. A foe it cannot hold (or none) gets a short lash.
 */
public final class ShadowBindFx implements SpellFx {
	private static final Vec3 UP = new Vec3(0, 1, 0);
	/** How long the tendrils lash at something they cannot hold. */
	private static final int LASH = 16;

	@Override
	public void play(FxEvent e, ClientLevel level, FxKit kit) {
		Vec3 dir = ShadowFx.unit(e.dir());
		float c = Mth.clamp(e.charge(), 0F, 1F);
		boolean rooted = e.data().length > 0 && e.data()[0] > 0.5F;
		int life = rooted ? Math.max(1, e.duration()) : LASH;
		@Nullable Entity foe = e.entities().length > 0 ? level.getEntity(e.entities()[0]) : null;
		Vec3 feet = foe != null ? foe.position() : e.target();

		// The cast: a sigil before the caster, and shadows running along the ground to the foe.
		Vec3 muzzle = e.origin().add(dir.scale(1.6)).add(0, -0.2, 0);
		kit.circle(muzzle, dir.reverse(), ShadowFx.ARGB, 0.45F + 0.2F * c, 2 + Math.round(2 * c), 10);
		Vec3 start = e.origin().add(dir.scale(2.2));
		Vec3 groundFrom = new Vec3(start.x, Math.min(start.y, feet.y + 0.2), start.z);
		kit.trail(groundFrom, feet.add(0, 0.15, 0), ParticleTypes.REVERSE_PORTAL, 10);
		kit.trail(groundFrom.lerp(feet, 0.35), feet.add(0, 0.1, 0), ParticleTypes.SQUID_INK, 6);

		// The pool under it, the sigil, and the tendrils.
		kit.circle(feet.add(0, 0.05, 0), UP, ShadowFx.ARGB, 1.15F + 0.25F * c, 3, life + 4);
		int cost = Tendrils.cost();
		if (kit.budget().particles(cost) >= cost) {
			Minecraft.getInstance().particleEngine.add(new Tendrils(level, foe, feet, life));
		}
		kit.motes(feet.add(0, 0.6, 0), ShadowFx.VIOLET, 10, 0.5, new Vec3(0, 0.03, 0), 0.05, GlowMote.Drift.SINK, 0.12F, 16);
		kit.burst(feet.add(0, 0.3, 0), ParticleTypes.REVERSE_PORTAL, 8, 0.1);
		level.playLocalSound(feet.x, feet.y, feet.z, SoundEvents.SCULK_BLOCK_SPREAD, SoundSource.PLAYERS, 1.0F, 0.6F, false);
		level.playLocalSound(feet.x, feet.y, feet.z, SoundEvents.CHAIN_PLACE, SoundSource.PLAYERS, 0.9F, 0.5F, false);
		level.playLocalSound(feet.x, feet.y, feet.z, SoundEvents.WARDEN_HEARTBEAT, SoundSource.PLAYERS, 0.8F, 0.8F, false);
	}

	/** The tendrils: dark, tapering coils wound round the foe, lit by violet along their edge, over a pool of darkness. */
	static final class Tendrils extends UmbraParticle {
		private static final int PIECES = 96;
		private final @Nullable Entity foe;
		private Vec3 feet;
		private Vec3 feetO;
		private final double width;
		private final double height;
		private final int life;

		Tendrils(ClientLevel level, @Nullable Entity foe, Vec3 feet, int life) {
			super(level, feet, life);
			this.foe = foe;
			this.feet = feet;
			this.feetO = feet;
			this.width = foe != null ? Mth.clamp(foe.getBbWidth() / 2 + 0.15, 0.35, 1.2) : 0.45;
			this.height = foe != null ? Mth.clamp(foe.getBbHeight(), 0.6, 3.5) : 1.4;
			this.life = life;
		}

		static int cost() {
			return Umbra.budgetCost(BindGeometry.TENDRILS * PIECES * 2 + 8);
		}

		@Override
		public void tick() {
			super.tick();
			feetO = feet;
			// The server holds it in place; the coils follow it if it falls (or if a client sees it a little elsewhere).
			if (foe != null && !foe.isRemoved()) feet = foe.position();
		}

		@Override
		protected Vec3 anchor() {
			return feet.add(0, height / 2, 0);
		}

		@Override
		public double reach() {
			return height + 1.5;
		}

		@Override
		protected void draw(double t) {
			double partial = t - Math.floor(t);
			Vec3 base = feetO.lerp(feet, partial);
			double out = BindGeometry.reach(t, life);
			if (out <= 0.01) return;
			float k = (float) out;
			// The pool of darkness it rises from.
			umbra.dark(base.add(0, 0.05, 0), (float) (width * 2.1), 0.55F * k);
			umbra.dark(base.add(0, 0.05, 0), (float) (width * 1.3), 0.7F * k);
			for (int i = 0; i < BindGeometry.TENDRILS; i++) {
				for (int j = 0; j < PIECES; j++) {
					double s = (j + 0.5) / PIECES;
					if (s > out) break;
					Vec3 p = BindGeometry.tendril(base, width, height, i, s, t, life);
					// Thick at the root, a thin tip. The pieces sit well under half their width apart (a coil is about 5 blocks long),
					// so their soft edges merge into one strand instead of a row of beads.
					float w = (float) (0.2 - 0.13 * s);
					umbra.dark(p, w, 0.9F * k);
					// A thin, continuous vein of violet down the coil (on every piece, faint, so it never beads), a pulse running up it.
					float glint = 0.18F + 0.26F * Mth.sin((float) (t * 0.9 - s * 9 + i * 1.9));
					umbra.light(p, w * 0.5F, Math.max(0.08F, glint) * k, j > PIECES * 0.7 ? ShadowFx.PALE : ShadowFx.MAGENTA);
				}
			}
			// Faint violet light rising round it while it holds.
			for (int i = 0; i < 6; i++) {
				double a = i * 1.047 + t * 0.15;
				double y = height * ((t * 0.04 + i * 0.37) % 1.0);
				umbra.light(base.add(Math.cos(a) * width * 1.4, y, Math.sin(a) * width * 1.4), 0.1F, 0.5F * k, ShadowFx.VIOLET);
			}
		}
	}
}
