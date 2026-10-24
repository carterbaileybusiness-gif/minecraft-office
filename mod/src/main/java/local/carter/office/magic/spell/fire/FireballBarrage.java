package local.carter.office.magic.spell.fire;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Random;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;

import com.google.common.annotations.VisibleForTesting;
import org.jspecify.annotations.Nullable;

import local.carter.office.magic.cast.CastService;
import local.carter.office.magic.core.Element;
import local.carter.office.magic.core.Shapes;
import local.carter.office.magic.core.Shapes.Cell;
import local.carter.office.magic.core.Slots;
import local.carter.office.magic.net.FxEvent;
import local.carter.office.magic.spell.ActiveEffects;
import local.carter.office.magic.spell.CastContext;
import local.carter.office.magic.spell.CastOutcome;
import local.carter.office.magic.spell.HitBudget;
import local.carter.office.magic.spell.Hurt;
import local.carter.office.magic.spell.LingeringEffect;
import local.carter.office.magic.spell.ProjectileHit;
import local.carter.office.magic.spell.ProjectileSpec;
import local.carter.office.magic.spell.Projectiles;
import local.carter.office.magic.spell.Spell;
import local.carter.office.magic.spell.SpellIds;
import local.carter.office.magic.spell.TargetFilter;
import local.carter.office.magic.spell.Targets;
import local.carter.office.magic.terrain.EditBatch;
import local.carter.office.magic.terrain.EditTier;

import net.minecraft.core.BlockPos;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.CollisionContext;

/**
 * Fireball Barrage (fire, slot 3, medium, lingering): {@link BarrageGeometry#count} fireballs (3 at a tap, 6 at full charge) rain
 * on the spot the caster points at (the look ray's first block within {@link BarrageGeometry#RANGE}, else the point at that
 * range), one every {@link BarrageGeometry#INTERVAL} ticks, each aimed within {@link BarrageGeometry#spread} of it (1.5, twice
 * that for six; seeded) and thrown down from the
 * sky behind it ({@link BarrageGeometry}: speed 1.8, gravity 0.03; the drop is shortened under a roof so it never starts inside
 * one). A fireball stops at the first block or creature it meets. Each impact:
 * <ul>
 * <li>creatures within {@link BarrageGeometry#BLAST} that the blast can reach (nothing solid in between) take {@code 5 × P} with
 * linear falloff (at most {@link #MAX_DAMAGE}{@code × P} each over the whole volley), are knocked {@link #KNOCK} away
 * (+ {@link #KNOCK_UP}) and burn for {@link #FIRE_TICKS} ticks;</li>
 * <li>its own MEDIUM batch at the block it struck (the ground under it, for a creature hit just above the ground) blasts a
 * {@code Shapes.bowl(1.5, 0.6)} crater there and turns {@link #NETHERRACK} of the floor it bared into netherrack.</li>
 * </ul>
 *
 * <p>Ranks add fireballs (the size knob, up to {@link BarrageGeometry#MAX_COUNT}). Forks: III {@link #CARPET} (+2, spread
 * {@link BarrageGeometry#CARPET_SPREAD}×) or {@link #NAPALM} (burns three times as long and slows); V {@link #RAPID} (one every
 * {@link BarrageGeometry#RAPID_INTERVAL} ticks, +2) or {@link #HEAVY} (blast {@link BarrageGeometry#HEAVY_BLAST}, crater
 * {@link BarrageGeometry#HEAVY_CRATER}, one fewer).
 *
 * <p>Fx: {@code target} = the aim point, {@code dir} = the caster's flat facing, {@code duration} = the whole volley's life,
 * {@code data} = {@code {count, lift_0, …, lift_(count−1)}} (each fireball's clear share of its sky drop).
 */
public final class FireballBarrage implements Spell {
	public static final float DAMAGE = 5;
	public static final double KNOCK = 0.8;
	public static final double KNOCK_UP = 0.3;
	public static final int FIRE_TICKS = 40;
	/** Most one creature can take from one volley's blasts, × P (Meteor's direct hit is 20 × P). */
	public static final float MAX_DAMAGE = 12;
	/** Share of the bared crater floor that turns to netherrack. */
	public static final float NETHERRACK = 0.2F;
	public static final double CRATER_RADIUS = 1.5;
	public static final double CRATER_DEPTH = 0.6;
	public static final String CARPET = "carpet";
	public static final String NAPALM = "napalm";
	public static final String RAPID = "rapid";
	public static final String HEAVY = "heavy";
	public static final int NAPALM_SLOW_TICKS = 60;
	/** A creature struck at most this high above the ground still leaves a crater under it. */
	private static final double CRATER_REACH = 2;

	/** What one fireball did where it landed (for listeners: the game tests). */
	public record Impact(UUID caster, Vec3 at, @Nullable BlockPos crater, int removed) {}

	private static final List<Consumer<Impact>> LISTENERS = new CopyOnWriteArrayList<>();

	/** Calls {@code listener} with every impact until the returned handle is closed (game tests watch their own caster's). */
	@VisibleForTesting
	public static AutoCloseable listenForTesting(Consumer<Impact> listener) {
		LISTENERS.add(listener);
		return () -> LISTENERS.remove(listener);
	}

	@Override
	public Identifier id() {
		return SpellIds.FIREBALL_BARRAGE;
	}

	@Override
	public Element element() {
		return Element.FIRE;
	}

	@Override
	public int slot() {
		return Slots.MEDIUM_B;
	}

	@Override
	public CastOutcome cast(CastContext ctx) {
		ServerLevel level = ctx.level();
		int count = BarrageGeometry.count(ctx.charge(), ctx.rank());
		Vec3 point = Targets.point(ctx, BarrageGeometry.RANGE);
		Vec3 dir = BarrageGeometry.flat(ctx.look().lengthSqr() > 0 && Math.abs(ctx.look().y) < 0.999
				? ctx.look() : FireWallGeometry.forward(ctx.caster().getYRot()));
		float[] lifts = new float[count];
		double spread = BarrageGeometry.spreadMul(ctx.rank());
		int interval = BarrageGeometry.interval(ctx.rank());
		List<Vec3> aims = BarrageGeometry.aims(point, ctx.seed(), count, spread);
		Vec3 drop = BarrageGeometry.drop(dir);
		for (int i = 0; i < count; i++) lifts[i] = (float) lift(level, aims.get(i), drop);
		List<BarrageGeometry.Ball> volley = BarrageGeometry.volley(point, dir, ctx.seed(), count, lifts, spread, interval);
		// The volley flies its own fireballs, so once it is running every one of them flies (nothing else can take their slots).
		if (!ActiveEffects.add(ctx, new Volley(ctx, volley))) return CastOutcome.fizzle(CastService.BUSY_KEY);

		float[] data = new float[1 + count];
		data[0] = count;
		System.arraycopy(lifts, 0, data, 1, count);
		int life = (count - 1) * interval + BarrageGeometry.MAX_TICKS;
		FxEvent fx = ctx.fx();
		return CastOutcome.cast(fx.withDir(dir).withTarget(point).withDuration(life).withData(data));
	}

	/**
	 * The clear share of a fireball's sky drop: from just above its aim up towards its start, how far until a block (a roof, a
	 * cave ceiling, a tree), less a block of room, as a share of the whole drop.
	 */
	static double lift(ServerLevel level, Vec3 aim, Vec3 drop) {
		Vec3 from = aim.add(0, 0.3, 0);
		Vec3 to = aim.add(drop);
		BlockHitResult hit = level.clip(new ClipContext(from, to, ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, CollisionContext.empty()));
		if (hit.getType() == HitResult.Type.MISS) return 1;
		double clear = hit.getLocation().distanceTo(from) - 1;
		return Math.max(BarrageGeometry.MIN_LIFT, clear / drop.length());
	}

	/**
	 * The volley's fireballs, flown here rather than through {@link Projectiles}: the effect's one slot (taken at the cast) carries
	 * them all, so a busy server can never drop a fireball the clients were told about. Each moves exactly as
	 * {@link Projectiles} would move it ({@link Projectiles#step}, first move the tick after it leaves, block clip ignoring
	 * fluids, creatures swept with hitboxes widened by its radius through {@link TargetFilter#affects}), so the fx, which
	 * rebuilds the same flights, sees the same impacts. Its blasts never take more than {@link #MAX_DAMAGE}{@code × P} from one
	 * creature over the whole volley.
	 */
	private static final class Volley implements LingeringEffect {
		private final CastContext ctx;
		private final List<BarrageGeometry.Ball> balls;
		private final boolean[] done;
		/** Creature id → damage this volley's blasts have dealt it. */
		private final Map<Integer, Float> dealt = new HashMap<>();
		/** At most {@code Targets.MAX_ENTITIES} different creatures over all its blasts (the per-cast budget). */
		private final HitBudget budget = new HitBudget();

		Volley(CastContext ctx, List<BarrageGeometry.Ball> balls) {
			this.ctx = ctx;
			this.balls = balls;
			this.done = new boolean[balls.size()];
		}

		@Override
		public boolean tick(ServerLevel level, int age) {
			boolean flying = false;
			for (int i = 0; i < balls.size(); i++) {
				if (done[i]) continue;
				BarrageGeometry.Ball b = balls.get(i);
				int k = age - b.launchTick() - 1;
				if (k >= 0) done[i] = fly(level, b, k);
				flying |= !done[i];
			}
			return flying;
		}

		/** Ball {@code b}'s move {@code k} → {@code k + 1}; true once it has landed or flown out its time. */
		private boolean fly(ServerLevel level, BarrageGeometry.Ball b, int k) {
			ProjectileSpec spec = b.spec();
			Vec3 from = Projectiles.step(spec, k);
			Vec3 to = Projectiles.step(spec, k + 1);
			if (!level.isLoaded(BlockPos.containing(to))) return true;
			BlockHitResult block = level.clip(new ClipContext(from, to, ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, CollisionContext.empty()));
			boolean blocked = block.getType() != HitResult.Type.MISS;
			Vec3 end = blocked ? block.getLocation() : to;
			Vec3 dir = to.subtract(from).normalize();
			Vec3 struck = struck(level, from, end, spec.radius());
			if (struck != null) {
				impact(ctx, dir, new ProjectileHit(struck, null, null), dealt, budget);
				return true;
			}
			if (blocked) {
				impact(ctx, dir, new ProjectileHit(end, null, block), dealt, budget);
				return true;
			}
			return k + 1 >= spec.maxTicks();
		}

		/** Where the step first meets a creature the spell may hit (hitboxes widened by {@code radius}), or null. */
		private @Nullable Vec3 struck(ServerLevel level, Vec3 from, Vec3 end, double radius) {
			Vec3 best = null;
			double bestD = Double.MAX_VALUE;
			for (LivingEntity e : level.getEntitiesOfClass(LivingEntity.class, new AABB(from, end).inflate(radius),
					e -> TargetFilter.affects(ctx.caster(), e))) {
				AABB box = e.getBoundingBox().inflate(radius);
				if (box.contains(from)) return from;
				Optional<Vec3> at = box.clip(from, end);
				if (at.isPresent() && from.distanceToSqr(at.get()) < bestD) {
					bestD = from.distanceToSqr(at.get());
					best = at.get();
				}
			}
			return best;
		}

		@Override
		public String toString() {
			return "FireballBarrage.Volley[" + ctx.caster().getScoreboardName() + " " + balls.size() + "]";
		}
	}

	/** One fireball lands: the blast (capped per creature by {@code dealt}, and to the volley's {@code budget}), then its crater. */
	static void impact(CastContext ctx, Vec3 dir, ProjectileHit hit, Map<Integer, Float> dealt, HitBudget budget) {
		ServerLevel level = ctx.level();
		Vec3 at = hit.pos();
		// The blast is judged from just outside what it struck, so a fireball on a wall never hurts what stands behind it.
		Vec3 blast = at.subtract(dir.scale(0.3));
		float cap = MAX_DAMAGE * ctx.power();
		double radius = BarrageGeometry.blast(ctx.rank());
		boolean napalm = ctx.rank().is(NAPALM);
		for (LivingEntity e : Targets.radius(ctx, at, radius)) {
			if (!reaches(level, blast, e.getBoundingBox()) || !budget.admit(e)) continue;
			double f = BarrageGeometry.falloff(Math.sqrt(e.getBoundingBox().distanceToSqr(at)), radius);
			float already = dealt.getOrDefault(e.getId(), 0F);
			float amount = Math.min((float) (DAMAGE * ctx.power() * f), cap - already);
			if (amount > 0 && Hurt.damage(ctx, e, amount)) dealt.put(e.getId(), already + amount);
			if (e.isAlive()) {
				e.igniteForTicks(Math.max(e.getRemainingFireTicks(), FIRE_TICKS * (napalm ? 3 : 1)));
				if (napalm) e.addEffect(new MobEffectInstance(MobEffects.SLOWNESS, NAPALM_SLOW_TICKS, 0), ctx.caster());
				Hurt.knock(e, e.getBoundingBox().getCenter().subtract(at), KNOCK, KNOCK_UP);
			}
		}
		BlockPos crater = craterOrigin(level, hit);
		int removed = crater == null ? 0 : crater(ctx, crater, ctx.rank().is(HEAVY) ? BarrageGeometry.HEAVY_CRATER : CRATER_RADIUS);
		Impact done = new Impact(ctx.caster().getUUID(), at, crater, removed);
		for (Consumer<Impact> l : LISTENERS) l.accept(done);
	}

	/** True when the blast at {@code from} reaches the box: nothing solid between it and the box's centre, feet or top. */
	static boolean reaches(ServerLevel level, Vec3 from, AABB box) {
		Vec3 c = box.getCenter();
		for (Vec3 to : new Vec3[] {c, new Vec3(c.x, box.minY + 0.1, c.z), new Vec3(c.x, box.maxY - 0.1, c.z)}) {
			if (level.clip(new ClipContext(from, to, ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, CollisionContext.empty())).getType()
					== HitResult.Type.MISS) return true;
		}
		return false;
	}

	/** The block the crater is centred on: the one struck, or the ground under a creature struck just above it. */
	private static @Nullable BlockPos craterOrigin(ServerLevel level, ProjectileHit hit) {
		if (hit.block() != null) return hit.block().getBlockPos();
		Vec3 ground = Targets.ground(level, hit.pos());
		if (hit.pos().y - ground.y > CRATER_REACH) return null; // burst in the air
		return BlockPos.containing(ground.x, ground.y - 0.5, ground.z);
	}

	/** The crater's own MEDIUM batch: the bowl blasted out, a share of the floor it bares turned to netherrack. Returns blocks removed. */
	private static int crater(CastContext ctx, BlockPos origin, double radius) {
		ServerLevel level = ctx.level();
		if (level.getBlockState(origin).isAir()) return 0;
		List<BlockPos> bared = new ArrayList<>();
		int removed = 0;
		try (EditBatch b = ctx.kit().terrain().begin(ctx, origin, EditTier.MEDIUM)) {
			for (Cell c : Shapes.bowl(radius, CRATER_DEPTH)) {
				BlockPos p = origin.offset(c.x(), c.y(), c.z());
				if (!b.remove(p)) continue;
				removed++;
				BlockPos under = p.below();
				BlockState s = level.getBlockState(under);
				if (!s.isAir() && s.getFluidState().isEmpty() && !s.is(Blocks.NETHERRACK)) bared.add(under);
			}
			// Seeded from the cast and the spot, so the same impact always picks the same blocks.
			Random r = new Random(ctx.seed() ^ origin.asLong());
			Collections.shuffle(bared, r);
			int turn = Math.round(NETHERRACK * bared.size());
			for (int i = 0; i < turn; i++) b.set(bared.get(i), Blocks.NETHERRACK.defaultBlockState());
		}
		return removed;
	}
}
