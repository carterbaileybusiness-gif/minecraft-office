package local.carter.office.magic.tree;

import java.util.ArrayList;
import java.util.List;

import org.jspecify.annotations.Nullable;

import local.carter.office.magic.core.ChargeMath;

import net.minecraft.resources.Identifier;

/**
 * A spell's rank as one cast sees it: {@code rank} 0–5 and the option picked at each fork ({@code fork3}, {@code fork5}: 0 = none,
 * 1 = A, 2 = B). Ranks I, II and IV are bumps ({@link #bumps}): each adds {@link #BUMP_DAMAGE} damage, {@link #BUMP_SIZE} to the
 * spell's size knob and takes {@link #BUMP_MANA} off its mana. Ranks III and V are the forks, whose knobs and riders come from the
 * spell's {@link SpellTree}. Packed into one int ({@link #packed}) for {@code MagicData} and {@code FxEvent}.
 */
public record SpellRank(Identifier spell, int rank, int fork3, int fork5) {
	public static final int MAX = 5;
	public static final float BUMP_DAMAGE = 0.15F;
	public static final float BUMP_SIZE = 0.15F;
	public static final float BUMP_MANA = 0.10F;
	public static final int A = 1;
	public static final int B = 2;

	public SpellRank {
		rank = Math.clamp(rank, 0, MAX);
		fork3 = rank >= 3 ? Math.clamp(fork3, A, B) : 0;
		fork5 = rank >= 5 ? Math.clamp(fork5, A, B) : 0;
	}

	public static SpellRank none(Identifier spell) {
		return new SpellRank(spell, 0, 0, 0);
	}

	/** The rank packed as {@code rank | fork3 << 3 | fork5 << 5} (0 = rank 0). */
	public int packed() {
		return rank | fork3 << 3 | fork5 << 5;
	}

	public static SpellRank unpack(Identifier spell, int packed) {
		return new SpellRank(spell, packed & 7, packed >> 3 & 3, packed >> 5 & 3);
	}

	/** True for the ranks that are forks (III and V). */
	public static boolean isFork(int rank) {
		return rank == 3 || rank == 5;
	}

	/** The next rank, picking {@code option} if it is a fork (ignored otherwise). */
	public SpellRank next(int option) {
		int r = rank + 1;
		return new SpellRank(spell, r, r == 3 ? option : fork3, r == 5 ? option : fork5);
	}

	/** This rank with the fork at {@code at} (3 or 5) re-picked as {@code option}. */
	public SpellRank repick(int at, int option) {
		return new SpellRank(spell, rank, at == 3 ? option : fork3, at == 5 ? option : fork5);
	}

	/** The option picked at fork {@code at} (3 or 5): 0 = not reached, 1 = A, 2 = B. */
	public int fork(int at) {
		return at == 3 ? fork3 : at == 5 ? fork5 : 0;
	}

	/** Ranks owned among the bumps I, II and IV (0–3). */
	public int bumps() {
		return (rank >= 1 ? 1 : 0) + (rank >= 2 ? 1 : 0) + (rank >= 4 ? 1 : 0);
	}

	public @Nullable SpellTree tree() {
		return Trees.get(spell);
	}

	/** The forks picked so far (III first). */
	public List<Fork> forks() {
		SpellTree t = tree();
		List<Fork> out = new ArrayList<>(2);
		if (t == null) return out;
		if (fork3 > 0) out.add(t.three().get(fork3 - 1));
		if (fork5 > 0) out.add(t.five().get(fork5 - 1));
		return out;
	}

	/** True when a picked fork is called {@code name}. */
	public boolean is(String name) {
		for (Fork f : forks()) if (f.name().equals(name)) return true;
		return false;
	}

	/** The multiplier on {@code k}: the bumps (damage, size, mana) times every picked fork's factor. */
	public float mul(Knob k) {
		float m = switch (k) {
			case DAMAGE -> 1 + BUMP_DAMAGE * bumps();
			case SIZE -> 1 + BUMP_SIZE * bumps();
			case MANA -> 1 - BUMP_MANA * bumps();
			default -> 1;
		};
		for (Fork f : forks()) for (Fork.Mod mod : f.mods()) if (mod.knob() == k) m *= mod.mul();
		return m;
	}

	/** What the picked forks add to {@code k} (extra bolts, hops, arcs, blocks). */
	public float add(Knob k) {
		float a = 0;
		for (Fork f : forks()) for (Fork.Mod mod : f.mods()) if (mod.knob() == k) a += mod.add();
		return a;
	}

	/** {@code base × mul(k) + add(k)}. */
	public double apply(Knob k, double base) {
		return base * mul(k) + add(k);
	}

	/** A count ({@code base}, at least {@code min}) with the knob applied, rounded. */
	public int count(Knob k, double base, int min) {
		return Math.max(min, (int) Math.round(apply(k, base)));
	}

	/** {@code ChargeMath.size(charge) × mul(SIZE)}: the size knob at {@code charge}. */
	public float size(float charge) {
		return ChargeMath.size(charge) * mul(Knob.SIZE);
	}

	/** Every picked fork's riders. */
	public List<Fork.Rider> riders() {
		List<Fork.Rider> out = new ArrayList<>();
		for (Fork f : forks()) out.addAll(f.riders());
		return out;
	}

	/** "I" … "V" ({@code ""} for rank 0). */
	public static String roman(int rank) {
		return switch (rank) {
			case 1 -> "I";
			case 2 -> "II";
			case 3 -> "III";
			case 4 -> "IV";
			case 5 -> "V";
			default -> "";
		};
	}
}
