package local.carter.office.magic.spell.earth;

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
import local.carter.office.magic.spell.Spell;
import local.carter.office.magic.spell.SpellIds;
import local.carter.office.magic.spell.TargetFilter;

import net.minecraft.core.BlockPos;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.monster.Enemy;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

/**
 * Earth Spikes (Earth, slot 2, small, lingering): a line of stone spikes bursts from the ground ahead, one a tick.
 *
 * <p>The line ({@link EarthSpikesGeometry}) starts just ahead of the caster's feet and runs {@code 8 + 4 × charge} cells along the
 * flat look, following the ground (a step up, a drop down) and ending at a wall or a pit. Spike {@code i} bursts on tick
 * {@code i}: every creature standing in its cell (up to {@link EarthSpikesGeometry#HEIGHT}) takes {@code 4 × P} once; hostile mobs
 * ({@link Enemy}) and items are also thrown {@link #UP} up (lifts move only enemies and items). Small spell: no block changes,
 * the spikes are the client's ghost blocks.
 *
 * <p>Fx: {@code target} = bottom centre of the first cell, {@code dir} = the flat look, {@code duration} = the line's length in
 * ticks, {@code data} = {length}. The client follows the same ground rule on its own world.
 */
public final class EarthSpikes implements Spell {
	public static final float DAMAGE = 4;
	public static final double UP = 0.6;

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
		BlockPos start = EarthSpikesGeometry.start(p.position(), forward);
		int length = EarthSpikesGeometry.length(ctx.charge());
		List<BlockPos> path = EarthSpikesGeometry.path(start, forward, length, solid(ctx.level()));
		if (!ActiveEffects.add(ctx, new Burst(ctx, path, forward))) return CastOutcome.fizzle(CastService.BUSY_KEY);
		return CastOutcome.cast(ctx.fx().withTarget(Vec3.atBottomCenterOf(start)).withDuration(Math.max(1, path.size()))
				.withData(length));
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
	 * Whether a spike also throws {@code e} up: hostile mobs, items and XP orbs, never players, pets, villagers or a boss
	 * ({@link TargetFilter#controllable}; a boss is still hurt).
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
		private final List<BlockPos> path;
		private final Vec3 forward;
		private final Set<Entity> struck = new HashSet<>();

		Burst(CastContext ctx, List<BlockPos> path, Vec3 forward) {
			this.ctx = ctx;
			this.path = path;
			this.forward = forward;
		}

		@Override
		public boolean tick(ServerLevel level, int age) {
			int i = age - 1;
			if (i >= path.size()) return false;
			AABB box = spikeBox(path.get(i));
			float damage = DAMAGE * ctx.power();
			for (Entity e : level.getEntities((Entity) null, box, e -> !struck.contains(e) && TargetFilter.pushable(ctx.caster(), e))) {
				if (struck.size() >= 64) break;
				struck.add(e);
				if (e instanceof LivingEntity l) Hurt.damage(ctx, l, damage);
				if (liftable(ctx.caster(), e)) Hurt.knock(e, forward, 0, UP);
			}
			return i + 1 < path.size();
		}

		@Override
		public String toString() {
			return "EarthSpikes.Burst[" + ctx.caster().getScoreboardName() + "]";
		}
	}
}
