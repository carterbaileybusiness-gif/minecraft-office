package local.carter.office.magic.spell;

import local.carter.office.magic.net.FxEvent;
import local.carter.office.magic.tree.SpellRank;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.RandomSource;
import net.minecraft.world.phys.Vec3;

/**
 * One cast in progress. {@code charge} 0..1 (ultimates always 1), {@code power} from {@code ChargeMath} times the rank's damage,
 * {@code seed} shared with the client through {@link FxEvent#seed()}, {@code castId} unique per cast (terrain and entity budgets
 * are per cast), {@code rank} the caster's skill-tree rank of the spell (its size knob, forks and riders; also in the fx).
 */
public record CastContext(ServerPlayer caster, ServerLevel level, Spell spell, float charge, float power, int grimoireLevel, long seed,
		long castId, SpellKit kit, SpellRank rank) {
	/** No rank (tests that build a context by hand). */
	public CastContext(ServerPlayer caster, ServerLevel level, Spell spell, float charge, float power, int grimoireLevel, long seed,
			long castId, SpellKit kit) {
		this(caster, level, spell, charge, power, grimoireLevel, seed, castId, kit, SpellRank.none(spell.id()));
	}

	/** Default fx target distance along the look vector. */
	public static final double DEFAULT_REACH = 8;

	public Vec3 eye() {
		return caster.getEyePosition();
	}

	public Vec3 look() {
		return caster.getLookAngle();
	}

	/** A new generator seeded with {@link #seed()} on every call (the same sequence a client can rebuild): call once and keep it. */
	public RandomSource random() {
		return RandomSource.create(seed);
	}

	/** The cast's fx, pre-filled: spell, caster, origin = eye, dir = look, target = eye + look × 8, charge, power, seed, rank. */
	public FxEvent fx() {
		Vec3 eye = eye();
		Vec3 look = look();
		return new FxEvent(spell.id(), caster.getId(), eye, look, eye.add(look.scale(DEFAULT_REACH)), charge, power, seed, 0,
				new int[0], new float[0], rank.packed());
	}
}
