package local.carter.office.magic.spell.earth;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.function.Predicate;

import local.carter.office.magic.cast.CastService;
import local.carter.office.magic.core.Element;
import local.carter.office.magic.core.Slots;
import local.carter.office.magic.spell.ActiveEffects;
import local.carter.office.magic.spell.CastContext;
import local.carter.office.magic.spell.CastOutcome;
import local.carter.office.magic.spell.Hurt;
import local.carter.office.magic.spell.LingeringEffect;
import local.carter.office.magic.spell.RankKit;
import local.carter.office.magic.spell.Spell;
import local.carter.office.magic.spell.SpellIds;
import local.carter.office.magic.spell.TargetFilter;
import local.carter.office.magic.spell.Targets;
import local.carter.office.magic.tree.Knob;
import local.carter.office.magic.tree.SpellRank;

import net.minecraft.core.BlockPos;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

/**
 * Earth Spikes (Earth, slot 2, small, lingering): a line of stone spikes bursts from the ground ahead, one a tick.
 *
 * <p>The line ({@link EarthSpikesGeometry}) starts just ahead of the caster's feet and runs {@code 8 × (1 + charge)} cells along
 * the flat look (8 at a tap, 16 at full charge), following the ground (a step up, a drop down) and ending at a wall or a pit.
 * Spike {@code i} bursts on tick {@code i}: every creature standing in its cell (up to {@link EarthSpikesGeometry#HEIGHT}) takes
 * {@code 4 × P} once; creatures a control effect may take (hostile mobs and animals, never a boss), items and XP orbs are also
 * thrown {@link #UP} up ({@link #liftable}). Small spell: no block changes, the spikes are the client's ghost blocks.
 *
 * <p>Ranks lengthen the line (the size knob). Forks: III {@link #IMPALE} (twice the damage, the line 25 % shorter; knobs) or
 * {@link #SEEKER} (the line bends toward the nearest creature spells may hurt within {@link #SEEKER_ANGLE}° of the flat look and
 * the line's reach, in the caster's sight; the fx's {@code dir} carries the bent way); V {@link #FIELD} (three lines,
 * ±{@link #FIELD_ANGLE}°, from the same first cell; each
 * creature is struck once) or {@link #PETRIFY} (Slowness VI and Weakness, riders).
 *
 * <p>Fx: {@code target} = bottom centre of the first cell, {@code dir} = the flat look, {@code duration} = the line's length in
 * ticks, {@code data} = {length}. The client follows the same ground rule on its own world, and Spike Field's fan with
 * {@link #lines}.
 */
public final class EarthSpikes implements Spell {
	public static final float DAMAGE = 4;
	public static final double UP = 0.6;
	public static final String IMPALE = "impale";
	public static final String SEEKER = "seeker";
	public static final String FIELD = "field";
	public static final String PETRIFY = "petrify";
	public static final float FIELD_ANGLE = 20;
	public static final double SEEKER_ANGLE = 40;

	/** The line's length at {@code charge} and {@code rank} (the size knob, Impale's shortening), at least 1. */
	public static int length(float charge, SpellRank rank) {
		return Math.max(1, (int) Math.round(EarthSpikesGeometry.length(charge) * rank.mul(Knob.SIZE)));
	}

	/** The lines' directions: {@code forward}, and (Spike Field) one {@link #FIELD_ANGLE}° to each side. */
	public static List<Vec3> lines(Vec3 forward, SpellRank rank) {
		if (!rank.is(FIELD)) return List.of(forward);
		float a = (float) Math.toRadians(FIELD_ANGLE);
		return List.of(forward.yRot(a), forward, forward.yRot(-a));
	}

	@Override
	public Identifier id() {
		return SpellIds.EARTH_SPIKES;
	}

	@Override
	public Element element() {
		return Element.EARTH;
	}

	@Override
	public int slot() {
		return Slots.SMALL_B;
	}

	@Override
	public CastOutcome cast(CastContext ctx) {
		ServerPlayer p = ctx.caster();
		Vec3 forward = forward(ctx.look(), p.getYRot());
		int length = length(ctx.charge(), ctx.rank());
		if (ctx.rank().is(SEEKER)) forward = seek(ctx, forward, length);
		BlockPos start = EarthSpikesGeometry.start(p.position(), forward);
		List<List<BlockPos>> paths = new ArrayList<>();
		for (Vec3 f : lines(forward, ctx.rank())) paths.add(EarthSpikesGeometry.path(start, f, length, solid(ctx.level())));
		if (!ActiveEffects.add(ctx, new Burst(ctx, paths, forward))) return CastOutcome.fizzle(CastService.BUSY_KEY);
		int longest = 1;
		for (List<BlockPos> path : paths) longest = Math.max(longest, path.size());
		return CastOutcome.cast(ctx.fx().withDir(forward).withTarget(Vec3.atBottomCenterOf(start)).withDuration(longest).withData(length));
	}

	/**
	 * Seeker: the flat way from the caster's feet to the nearest creature spells may hurt within {@link #SEEKER_ANGLE}° of
	 * {@code forward} and {@code length} + 1 blocks, in the caster's sight; {@code forward} if there is none.
	 */
	static Vec3 seek(CastContext ctx, Vec3 forward, int length) {
		Vec3 feet = ctx.caster().position();
		double cos = Math.cos(Math.toRadians(SEEKER_ANGLE));
		for (LivingEntity e : Targets.radius(ctx, feet, length + 1)) {
			Vec3 to = e.position().subtract(feet);
			Vec3 flat = new Vec3(to.x, 0, to.z);
			if (flat.lengthSqr() < 1) continue;
			flat = flat.normalize();
			if (flat.dot(forward) >= cos && RankKit.reaches(ctx.level(), ctx.eye(), e.getBoundingBox())) return flat;
		}
		return forward;
	}

	/** The flat look, or the body's facing when looking straight up or down. */
	static Vec3 forward(Vec3 look, float yRot) {
		if (look.x * look.x + look.z * look.z > 1.0E-4) return EarthWallGeometry.flat(look);
		float yaw = yRot * Mth.DEG_TO_RAD;
		return new Vec3(-Mth.sin(yaw), 0, Mth.cos(yaw));
	}

	/** "Has a collision shape" on loaded cells; unloaded ones count as solid (the line never loads a chunk). Shared with the client. */
	public static Predicate<BlockPos> solid(Level level) {
		return p -> !level.isLoaded(p) || solidAt(level, p);
	}

	static boolean solidAt(BlockGetter level, BlockPos p) {
		return !level.getBlockState(p).getCollisionShape(level, p).isEmpty();
	}

	/**
	 * Whether a spike also throws {@code e} up: hostile mobs, animals, items and XP orbs, never players, pets, villagers, golems,
	 * named mobs or a boss ({@link TargetFilter#controllable}; a boss is still hurt).
	 */
	public static boolean liftable(ServerPlayer caster, Entity e) {
		return TargetFilter.controllable(caster, e);
	}

	/** The box a spike in {@code cell} strikes. */
	public static AABB spikeBox(BlockPos cell) {
		return new AABB(cell.getX(), cell.getY(), cell.getZ(), cell.getX() + 1, cell.getY() + EarthSpikesGeometry.HEIGHT, cell.getZ() + 1);
	}

	/** One spike a tick: spike {@code i} on tick {@code i + 1} after the cast (the first one fx tick 0 draws). */
	private static final class Burst implements LingeringEffect {
		private final CastContext ctx;
		/** One line, or Spike Field's three: spike {@code i} of each bursts on the same tick, and each creature is struck once. */
		private final List<List<BlockPos>> paths;
		private final Vec3 forward;
		private final Set<Entity> struck = new HashSet<>();

		Burst(CastContext ctx, List<List<BlockPos>> paths, Vec3 forward) {
			this.ctx = ctx;
			this.paths = paths;
			this.forward = forward;
		}

		@Override
		public boolean tick(ServerLevel level, int age) {
			int i = age - 1;
			boolean more = false;
			double up = UP;

			float damage = DAMAGE * ctx.power();
			for (List<BlockPos> path : paths) {
				if (i >= path.size()) continue;
				AABB box = spikeBox(path.get(i));
				for (Entity e : level.getEntities((Entity) null, box, e -> !struck.contains(e) && TargetFilter.pushable(ctx.caster(), e))) {
					if (struck.size() >= 64) break;
					struck.add(e);
					if (e instanceof LivingEntity l) Hurt.damage(ctx, l, damage);
					if (liftable(ctx.caster(), e)) Hurt.knock(e, forward, 0, up);
				}
				more |= i + 1 < path.size();
			}
			return more;
		}

		@Override
		public String toString() {
			return "EarthSpikes.Burst[" + ctx.caster().getScoreboardName() + "]";
		}
	}
}
