package local.carter.office.client.magic.fx;

import local.carter.office.client.magic.fx.MagicCircleRenderer.Frame;
import local.carter.office.magic.core.Element;
import local.carter.office.magic.net.FxEvent;

import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.Vec3;

/**
 * Recall's visuals (registered for {@code SpellIds.RECALL}). Duration 0 is Mark Home: a gold circle flashes open at the feet with
 * rising motes and a chime. Otherwise it is the channel: a gold horizontal circle under the caster for {@code e.duration()} ticks,
 * following them, its five rings filling one by one over the channel (each with a rising chime) while motes rise off its rim; at
 * the end it flares as the caster leaves. An {@code FxCancel} (damage) stops it at once: the circle fades and the motes stop.
 * Travel to a friend sends the same channel with {@code data[1] = 1}: the same circle in a paler gold with sky-blue motes.
 */
public final class RecallFx implements SpellFx {
	static final int GOLD = Element.RECALL_ARGB & 0xFFFFFF;
	/** Travel's circle (Recall's gold, paler) and its motes (sky blue). */
	static final int TRAVEL_GOLD = 0xFFE7B8;
	static final int TRAVEL_MOTES = 0xA8DCFF;
	private static final Vec3 UP = new Vec3(0, 1, 0);
	/** How far the caster may move from where the channel began before the circle stays behind (a teleport, a knockback). */
	private static final double FOLLOW = 3.0;

	@Override
	public void play(FxEvent e, ClientLevel level, FxKit kit) {
		Entity caster = level.getEntity(e.casterId());
		Vec3 feet = caster != null ? caster.position() : e.origin();
		float[] data = e.data();
		boolean travel = travel(e);
		if (!travel && (e.duration() <= 0 || data.length > 0 && data[0] == 1F)) {
			markHome(level, kit, feet);
		} else {
			channel(e, level, kit, feet, travel);
		}
	}

	/** Whether {@code e} is a travel-to-friend channel (Recall's fx with {@code data[1] = 1}). */
	public static boolean travel(FxEvent e) {
		float[] data = e.data();
		return data.length > 1 && data[1] == 1F;
	}

	private static void markHome(ClientLevel level, FxKit kit, Vec3 feet) {
		kit.circle(feet.add(0, 0.06, 0), UP, 0xFF000000 | GOLD, MagicCircleRenderer.ringRadius(4), 5, 28);
		kit.motes(feet.add(0, 0.3, 0), MagicCircleRenderer.lighter(GOLD, 0.35F), 26, 0.75, new Vec3(0, 0.05, 0), 0.03, GlowMote.Drift.RISE, 0.11F, 32);
		kit.ring(feet.add(0, 0.15, 0), 1.0, ParticleTypes.END_ROD, 8);
		level.playLocalSound(feet.x, feet.y, feet.z, SoundEvents.AMETHYST_BLOCK_CHIME, SoundSource.PLAYERS, 1.2F, 1.2F, false);
		level.playLocalSound(feet.x, feet.y, feet.z, SoundEvents.BEACON_POWER_SELECT, SoundSource.PLAYERS, 0.5F, 1.8F, false);
	}

	private static void channel(FxEvent e, ClientLevel level, FxKit kit, Vec3 feet, boolean travel) {
		int duration = Math.max(1, e.duration());
		if (kit.budget().particles(1) == 0) return;
		int moteTint = travel ? TRAVEL_MOTES : MagicCircleRenderer.lighter(GOLD, 0.3F);
		Channel circle = new Channel(level, e.casterId(), feet, duration, travel ? TRAVEL_GOLD : GOLD);
		Minecraft.getInstance().particleEngine.add(circle);
		FxKit.lasting(e.casterId(), circle::fadeOut, circle::isAlive);
		int step = Math.max(1, duration / MagicCircleRenderer.MAX_RINGS);
		kit.simulate(2, duration, t -> {
			Vec3 at = circle.centre();
			// Motes rising off the rim, more as the rings fill.
			int rings = Math.min(MagicCircleRenderer.MAX_RINGS, 1 + t / step);
			float r = MagicCircleRenderer.ringRadius(rings - 1);
			double a = kit.random().nextDouble() * Mth.TWO_PI;
			Vec3 rim = at.add(Math.cos(a) * r, 0.05, Math.sin(a) * r);
			kit.motes(rim, moteTint, 1 + rings / 2, 0.08, new Vec3(0, 0.07, 0), 0.01, GlowMote.Drift.RISE, 0.09F, 26);
			if (t % step == 0 && t / step < MagicCircleRenderer.MAX_RINGS) {
				int ring = t / step;
				level.playLocalSound(at.x, at.y, at.z, SoundEvents.AMETHYST_BLOCK_CHIME, SoundSource.PLAYERS, 0.7F, 0.8F + 0.15F * ring, false);
			}
			if (t + 2 >= duration) {
				// The caster leaves: a column of light and a ring of sparks where they stood.
				kit.motes(at.add(0, 0.9, 0), travel ? TRAVEL_MOTES : MagicCircleRenderer.lighter(GOLD, 0.5F), 30, 0.45, new Vec3(0, 0.12, 0), 0.08, GlowMote.Drift.RISE, 0.13F, 24);
				kit.ring(at.add(0, 0.1, 0), 1.0, ParticleTypes.END_ROD, 18);
			}
		});
	}

	/** The channel circle: flat at the caster's feet, following them while they stay near where it began. */
	static final class Channel extends CircleParticle {
		private final Vec3 start;
		private final int duration;
		private final int step;
		private Vec3 at;
		private Vec3 atO;

		Channel(ClientLevel level, int casterId, Vec3 feet, int duration, int rgb) {
			super(level, feet, casterId);
			this.start = feet;
			this.duration = Math.max(1, duration);
			this.step = Math.max(1, this.duration / MagicCircleRenderer.MAX_RINGS);
			this.at = feet;
			this.atO = feet;
			MagicCircleRenderer.orientation(MagicCircleRenderer.basis(UP), frame.plane);
			frame.rgb = rgb;
			frame.glyph = MagicCircleRenderer.Glyph.STAR;
		}

		Vec3 centre() {
			return at;
		}

		private Vec3 follow() {
			Entity caster = level.getEntity(casterId);
			if (caster != null && !caster.isRemoved() && caster.position().distanceTo(start) < FOLLOW) {
				return caster.position();
			}
			return at;
		}

		@Override
		public void tick() {
			super.tick();
			atO = at;
			at = follow();
			anchor(at);
			if (age >= duration && fading < 0) {
				fading = FADE_TICKS + 3;
			}
		}

		@Override
		protected boolean pose(float partial, Frame f) {
			float t = age + partial;
			Vec3 c = atO.lerp(at, partial);
			f.x = c.x;
			f.y = c.y + 0.06;
			f.z = c.z;
			f.rings = MagicCircleRenderer.MAX_RINGS;
			for (int i = 0; i < f.rings; i++) {
				f.ringAge[i] = t - i * step;
			}
			boolean done = age >= duration;
			f.flare = done ? 1F : 0.5F + 0.5F * Mth.sin(t * 0.35F) * Mth.clamp(t / duration, 0F, 1F);
			f.scale = done ? 1F + 0.25F * (1F - leaving(partial)) : 1F;
			f.alpha = 0.75F * leaving(partial);
			f.time = t;
			return f.alpha > 0.01F;
		}
	}
}
