package local.carter.office.magic.tree;

import java.util.List;

import org.jspecify.annotations.Nullable;

import net.minecraft.core.Holder;
import net.minecraft.world.effect.MobEffect;

/**
 * One option of a fork (rank III or V): its {@code name} (unique within the spell; lang {@code fork.office.<spell>.<name>} and
 * {@code .desc}), the knobs it changes and the riders it adds to every hit. Anything else a fork does is the spell's own code,
 * behind {@code ctx.rank().is(name)}.
 */
public record Fork(String name, List<Mod> mods, List<Rider> riders) {
	public Fork {
		mods = List.copyOf(mods);
		riders = List.copyOf(riders);
	}

	/** A fork the spell implements itself (no knobs, no riders). */
	public static Fork of(String name) {
		return new Fork(name, List.of(), List.of());
	}

	public Fork with(Mod m) {
		List<Mod> out = new java.util.ArrayList<>(mods);
		out.add(m);
		return new Fork(name, out, riders);
	}

	public Fork with(Rider r) {
		List<Rider> out = new java.util.ArrayList<>(riders);
		out.add(r);
		return new Fork(name, mods, out);
	}

	/** {@code knob × factor} (applied to the base value; several multiply). */
	public Fork mul(Knob k, float factor) {
		return with(new Mod(k, factor, 0));
	}

	/** {@code knob + amount} (counts: extra bolts, hops, arcs). */
	public Fork add(Knob k, float amount) {
		return with(new Mod(k, 1, amount));
	}

	public Fork effect(Holder<MobEffect> effect, int ticks, int amplifier) {
		return with(Rider.effect(effect, ticks, amplifier));
	}

	public Fork ignite(int ticks) {
		return with(Rider.ignite(ticks));
	}

	public Fork lifesteal(float share) {
		return with(Rider.lifesteal(share));
	}

	/** A knob change: {@code value × mul + add}. */
	public record Mod(Knob knob, float mul, float add) {}

	/**
	 * Something every hit of the spell does once the damage has landed ({@code Hurt.damage}): set the target on fire for
	 * {@code ticks}, give it {@code effect} for {@code ticks} at {@code amplifier}, or heal the caster {@code share} of the damage.
	 */
	public record Rider(Kind kind, int ticks, int amplifier, float share, @Nullable Holder<MobEffect> effect) {
		public enum Kind { IGNITE, EFFECT, LIFESTEAL }

		public static Rider ignite(int ticks) {
			return new Rider(Kind.IGNITE, ticks, 0, 0, null);
		}

		public static Rider effect(Holder<MobEffect> effect, int ticks, int amplifier) {
			return new Rider(Kind.EFFECT, ticks, amplifier, 0, effect);
		}

		public static Rider lifesteal(float share) {
			return new Rider(Kind.LIFESTEAL, 0, 0, share, null);
		}
	}
}
