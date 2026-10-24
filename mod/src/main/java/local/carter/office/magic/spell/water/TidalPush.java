package local.carter.office.magic.spell.water;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.jspecify.annotations.Nullable;

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
import local.carter.office.magic.spell.Targets;
import local.carter.office.magic.terrain.EditBatch;
import local.carter.office.magic.terrain.EditTier;
import local.carter.office.magic.tree.Knob;
import local.carter.office.magic.tree.SpellRank;

import net.minecraft.core.BlockPos;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

/**
 * Tidal Push (Water, slot 1, medium, lingering): a wave that shoves everything in front of you.
 *
 * <p>At the cast, everything pushable in the cone ({@link TidalWaveGeometry#range} = 8 × (1 + charge), half-angle 35°) is
 * knocked {@link #KNOCK} × P horizontally plus {@link #KNOCK_UP} up if the caster can see it; anything burning in sight in the cone (friends included: it only
 * douses) is extinguished; fire blocks in the cone that the caster can see are removed, one MEDIUM batch per 6-block cube
 * ({@link TidalWaveGeometry#fireSegment}).
 *
 * <p>Then the wave ({@link TidalWaveGeometry}) rolls out from the caster's feet, 5 wide, a block every 2 ticks as far as the cone
 * reaches (8 blocks at a tap, 16 at full charge),
 * following the ground column by column ({@link TidalWaveGeometry#step}: it climbs steps, pours down drops, breaks on walls):
 * whatever it reaches is carried once more ({@link #CARRY}) and doused, and fire in its cells is removed through one MEDIUM
 * batch per 4-block segment. Nothing behind a wall is touched. <b>No water is placed</b> (the temp-block ruling: never a temporary liquid); the water you see
 * is the client's, drawn from the same geometry: ghost blocks that live {@link TidalWaveGeometry#CELL_LIFE} ticks in air cells
 * only, spray and foam.
 *
 * <p>Ranks lengthen the cone and the wave (the size knob, up to {@link #MAX_RANGE}). Forks: III {@link #BREAKER} (the wave hits
 * everything it carries for {@link #BREAKER_DAMAGE}{@code × P}) or {@link #SURGE} (shove {@link #SURGE_KNOCK}×); V {@link #TSUNAMI}
 * (range ×1.5, up to {@link #MAX_RANGE}; carries twice as hard) or {@link #FRIGID} (everything it shoves or carries gets Slowness
 * III for {@link #FRIGID_TICKS}).
 *
 * <p>Fx: {@code target} = the wave's start (bottom centre of the caster's feet cell), {@code duration} = the wave's
 * {@link TidalWaveGeometry#duration()}, {@code entities} = what the cone shoved (first 32), {@code data[0]} = the cone's range
 * (the client rebuilds the wave's length from it, {@link TidalWaveGeometry#length(double)}).
 */
public final class TidalPush implements Spell {
	public static final double KNOCK = 1.6;
	public static final double KNOCK_UP = 0.3;
	/** The wave's own push on whatever it reaches (once each). */
	public static final double CARRY = 0.6;
	public static final double CARRY_UP = 0.12;
	public static final String BREAKER = "breaker";
	public static final String SURGE = "surge";
	public static final String TSUNAMI = "tsunami";
	public static final String FRIGID = "frigid";
	/** Longest a ranked cone and wave reach. */
	public static final double MAX_RANGE = 24;
	public static final float BREAKER_DAMAGE = 4;
	public static final float SURGE_KNOCK = 1.6F;
	public static final float TSUNAMI_RANGE = 1.5F;
	public static final int FRIGID_TICKS = 80;

	/** The cone's range at {@code charge} and {@code rank}: {@code 8 × (1 + charge)} times the size knob (and Tsunami), at most {@link #MAX_RANGE}. */
	public static double range(float charge, SpellRank rank) {
		return Math.min(MAX_RANGE, TidalWaveGeometry.range(charge) * rank.mul(Knob.SIZE) * (rank.is(TSUNAMI) ? TSUNAMI_RANGE : 1));
	}

	@Override
	public Identifier id() {
		return SpellIds.TIDAL_PUSH;
	}

	@Override
	public Element element() {
		return Element.WATER;
	}

	@Override
	public int slot() {
		return Slots.MEDIUM_A;
	}

	@Override
	public CastOutcome cast(CastContext ctx) {
		ServerLevel level = ctx.level();
		Vec3 eye = ctx.eye();
		Vec3 look = ctx.look().normalize();
		double range = range(ctx.charge(), ctx.rank());
		Vec3 start = Vec3.atBottomCenterOf(ctx.caster().blockPosition());
		TidalWaveGeometry wave = new TidalWaveGeometry(start, look, TidalWaveGeometry.length(range));
		Wave effect = new Wave(ctx, wave);
		if (!ActiveEffects.add(ctx, effect)) return CastOutcome.fizzle("magic.office.busy");

		// The shove.
		List<Integer> shoved = new ArrayList<>();
		double strength = KNOCK * ctx.power() * ctx.rank().mul(Knob.KNOCK);
		for (Entity e : Targets.cone(ctx, range, TidalWaveGeometry.HALF_ANGLE, true)) {
			if (!visible(ctx, eye, e.getBoundingBox().getCenter(), null)) continue; // behind a wall
			if (Hurt.knock(e, look, strength, KNOCK_UP)) {
				shoved.add(e.getId());
				effect.carried.add(e.getId()); // already shoved: the wave only douses it
				chill(ctx, e);
			}
		}
		// Douse anything burning in the cone (players and pets too: it only puts them out).
		for (Entity e : level.getEntities((Entity) null, new AABB(eye, eye).inflate(range),
				e -> e.isOnFire() && TidalWaveGeometry.inCone(eye, look, range, e.getBoundingBox().getCenter())
						&& visible(ctx, eye, e.getBoundingBox().getCenter(), null))) {
			e.extinguishFire();
		}
		if (ctx.caster().isOnFire()) ctx.caster().extinguishFire();

		// Fire blocks in the cone the caster can see: one batch per 6-block cube.
		Map<BlockPos, List<BlockPos>> fires = new LinkedHashMap<>();
		for (BlockPos c : TidalWaveGeometry.coneCells(eye, look, range)) {
			if (level.getBlockState(c).is(BlockTags.FIRE) && visible(ctx, eye, Vec3.atCenterOf(c), c)) {
				fires.computeIfAbsent(TidalWaveGeometry.fireSegment(c), k -> new ArrayList<>()).add(c);
			}
		}
		for (Map.Entry<BlockPos, List<BlockPos>> seg : fires.entrySet()) {
			try (EditBatch b = ctx.kit().terrain().begin(ctx, TidalWaveGeometry.fireSegmentOrigin(seg.getKey()), EditTier.MEDIUM)) {
				for (BlockPos c : seg.getValue()) b.remove(c);
			}
		}

		int[] ids = shoved.stream().mapToInt(Integer::intValue).toArray();
		return CastOutcome.cast(ctx.fx().withTarget(start).withDuration(wave.duration()).withEntities(ids)
				.withData((float) range));
	}

	/** Frigid Wave: what the wave moves is slowed (a control effect: never a boss). */
	static void chill(CastContext ctx, Entity e) {
		if (ctx.rank().is(FRIGID) && e instanceof LivingEntity l && TargetFilter.controllable(ctx.caster(), l)) {
			l.addEffect(new MobEffectInstance(MobEffects.SLOWNESS, FRIGID_TICKS, 2), ctx.caster());
		}
	}

	/** True when nothing solid stands between the eye and {@code to} (a hit on {@code cell} itself, if given, counts as clear). */
	private static boolean visible(CastContext ctx, Vec3 eye, Vec3 to, @Nullable BlockPos cell) {
		BlockHitResult hit = ctx.level().clip(new ClipContext(eye, to, ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, ctx.caster()));
		return hit.getType() == HitResult.Type.MISS || hit.getBlockPos().equals(cell);
	}

	/** The rolling wave: carries and douses what its front reaches, puts out fire in its cells. */
	static final class Wave implements LingeringEffect {
		private final CastContext ctx;
		private final TidalWaveGeometry wave;
		final Set<Integer> carried = new HashSet<>();
		/** Breaker: what the wave has struck. */
		private final Set<Integer> struck = new HashSet<>();
		private final Map<Integer, EditBatch> batches = new HashMap<>();
		/** The columns' state, moved by {@link TidalWaveGeometry#step} each tick (the client runs the same rule). */
		private final int[] colY;
		private final boolean[] broken = new boolean[TidalWaveGeometry.WIDTH];

		Wave(CastContext ctx, TidalWaveGeometry wave) {
			this.ctx = ctx;
			this.wave = wave;
			this.colY = wave.columns();
		}

		@Override
		public boolean tick(ServerLevel level, int age) {
			if (!wave.active(age)) return false;
			// Unloaded cells count as walls: the wave never loads a chunk.
			wave.step(age, colY, broken, p -> level.isLoaded(p) && level.getBlockState(p).getCollisionShape(level, p).isEmpty());
			Vec3 c = wave.frontCentre(age);
			AABB box = new AABB(c, c).inflate(TidalWaveGeometry.WIDTH / 2.0 + 1.5, TidalWaveGeometry.MAX_DROP + 4,
					TidalWaveGeometry.WIDTH / 2.0 + 1.5);
			for (Entity e : level.getEntities((Entity) null, box, e -> wave.inFront(age, e.position(), colY, broken))) {
				if (e.isOnFire()) e.extinguishFire();
				if (TargetFilter.pushable(ctx.caster(), e) && carried.add(e.getId())) {
					Hurt.knock(e, wave.dir(), CARRY * ctx.power() * (ctx.rank().is(TSUNAMI) ? 2 : 1), CARRY_UP);
					chill(ctx, e);
				}
				// Breaker: the wave strikes what it reaches (each once, the cone's shoved too).
				if (ctx.rank().is(BREAKER) && e instanceof LivingEntity l && TargetFilter.affects(ctx.caster(), l) && struck.add(e.getId())) {
					Hurt.damage(ctx, l, BREAKER_DAMAGE * ctx.power());
				}
			}
			int seg = wave.segmentOf(wave.at(age));
			for (BlockPos cell : wave.front(age, colY, broken)) {
				if (!level.getBlockState(cell).is(BlockTags.FIRE)) continue;
				EditBatch b = batch(seg);
				if (b != null) b.remove(cell);
			}
			// A segment the front has left is done.
			EditBatch before = seg > 0 ? batches.remove(seg - 1) : null;
			if (before != null) before.close();
			return age < wave.frontTicks();
		}

		private @Nullable EditBatch batch(int seg) {
			return batches.computeIfAbsent(seg, s -> ctx.kit().terrain().begin(ctx, wave.segmentOrigin(s), EditTier.MEDIUM));
		}

		@Override
		public void end(ServerLevel level) {
			for (EditBatch b : batches.values()) b.close();
			batches.clear();
		}

		@Override
		public String toString() {
			return "TidalPush.Wave[" + ctx.caster().getScoreboardName() + "]";
		}
	}
}
