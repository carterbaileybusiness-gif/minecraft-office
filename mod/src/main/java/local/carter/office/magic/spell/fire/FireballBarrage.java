package local.carter.office.magic.spell.fire;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Random;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;

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
import local.carter.office.magic.spell.Hurt;
import local.carter.office.magic.spell.LingeringEffect;
import local.carter.office.magic.spell.ProjectileHit;
import local.carter.office.magic.spell.Projectiles;
import local.carter.office.magic.spell.Spell;
import local.carter.office.magic.spell.SpellIds;
import local.carter.office.magic.spell.Targets;
import local.carter.office.magic.terrain.EditBatch;
import local.carter.office.magic.terrain.EditTier;

import net.minecraft.core.BlockPos;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerLevel;
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
 * Fireball Barrage (fire, slot 3, medium, lingering): {@code 3 + round(2 × charge)} fireballs rain on the spot the caster points
 * at (the look ray's first block within {@link BarrageGeometry#RANGE}, else the point at that range), one every
 * {@link BarrageGeometry#INTERVAL} ticks, each aimed within {@link BarrageGeometry#SPREAD} of it (seeded) and thrown down from the
 * sky behind it ({@link BarrageGeometry}: speed 1.8, gravity 0.03; the drop is shortened under a roof so it never starts inside
 * one). A fireball stops at the first block or creature it meets. Each impact:
 * <ul>
 * <li>creatures within {@link BarrageGeometry#BLAST} that the blast can reach (nothing solid in between) take {@code 5 × P} with
 * linear falloff, are knocked {@link #KNOCK} away (+ {@link #KNOCK_UP}) and burn for {@link #FIRE_TICKS} ticks;</li>
 * <li>its own MEDIUM batch at the block it struck (the ground under it, for a creature hit just above the ground) blasts a
 * {@code Shapes.bowl(1.5, 0.6)} crater there and turns {@link #NETHERRACK} of the floor it bared into netherrack.</li>
 * </ul>
 *
 * <p>Fx: {@code target} = the aim point, {@code dir} = the caster's flat facing, {@code duration} = the whole volley's life,
 * {@code data} = {@code {count, lift_0, …, lift_(count−1)}} (each fireball's clear share of its sky drop).
 */
public final class FireballBarrage implements Spell {
	public static final float DAMAGE = 5;
	public static final double KNOCK = 0.8;
	public static final double KNOCK_UP = 0.3;
	public static final int FIRE_TICKS = 40;
	/** Share of the bared crater floor that turns to netherrack. */
	public static final float NETHERRACK = 0.2F;
	public static final double CRATER_RADIUS = 1.5;
	public static final double CRATER_DEPTH = 0.6;
	/** A creature struck at most this high above the ground still leaves a crater under it. */
	private static final double CRATER_REACH = 2;

	/** What one fireball did where it landed (for listeners: the game tests). */
	public record Impact(UUID caster, Vec3 at, @Nullable BlockPos crater, int removed) {}

	private static final List<Consumer<Impact>> LISTENERS = new CopyOnWriteArrayList<>();

	/** Calls {@code listener} with every impact until the returned handle is closed (game tests watch their own caster's). */
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
		int count = BarrageGeometry.count(ctx.charge());
		// Every fireball must fly: refuse before anything happens if the server's projectile list can't take them.
		if (Projectiles.count() + count > Projectiles.MAX) return CastOutcome.fizzle(CastService.BUSY_KEY);
		Vec3 point = Targets.point(ctx, BarrageGeometry.RANGE);
		Vec3 dir = BarrageGeometry.flat(ctx.look().lengthSqr() > 0 && Math.abs(ctx.look().y) < 0.999
				? ctx.look() : FireWallGeometry.forward(ctx.caster().getYRot()));
		float[] lifts = new float[count];
		List<Vec3> aims = BarrageGeometry.aims(point, ctx.seed(), count);
		Vec3 drop = BarrageGeometry.drop(dir);
		for (int i = 0; i < count; i++) lifts[i] = (float) lift(level, aims.get(i), drop);
		List<BarrageGeometry.Ball> volley = BarrageGeometry.volley(point, dir, ctx.seed(), count, lifts);
		Volley effect = new Volley(ctx, volley);
		if (!ActiveEffects.add(level, effect)) return CastOutcome.fizzle(CastService.BUSY_KEY);
		effect.launch(0);

		float[] data = new float[1 + count];
		data[0] = count;
		System.arraycopy(lifts, 0, data, 1, count);
		int life = (count - 1) * BarrageGeometry.INTERVAL + BarrageGeometry.MAX_TICKS;
		FxEvent fx = ctx.fx();
		return CastOutcome.cast(new FxEvent(fx.spell(), fx.casterId(), fx.origin(), dir, point, fx.charge(), fx.power(), fx.seed(), life,
				new int[0], data));
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

	/** Throws one fireball every few ticks; impacts are handled by each fireball's own hit. */
	private static final class Volley implements LingeringEffect {
		private final CastContext ctx;
		private final List<BarrageGeometry.Ball> balls;
		private int next;

		Volley(CastContext ctx, List<BarrageGeometry.Ball> balls) {
			this.ctx = ctx;
			this.balls = balls;
		}

		void launch(int age) {
			while (next < balls.size() && balls.get(next).launchTick() <= age) {
				BarrageGeometry.Ball b = balls.get(next++);
				Vec3 dir = b.spec().velocity().normalize();
				Projectiles.launch(ctx, b.spec(), hit -> impact(ctx, dir, hit));
			}
		}

		@Override
		public boolean tick(ServerLevel level, int age) {
			launch(age);
			return next < balls.size();
		}

		@Override
		public String toString() {
			return "FireballBarrage.Volley[" + ctx.caster().getScoreboardName() + " " + next + "/" + balls.size() + "]";
		}
	}

	/** One fireball lands: the blast, then its crater. */
	static void impact(CastContext ctx, Vec3 dir, ProjectileHit hit) {
		ServerLevel level = ctx.level();
		Vec3 at = hit.pos();
		// The blast is judged from just outside what it struck, so a fireball on a wall never hurts what stands behind it.
		Vec3 blast = at.subtract(dir.scale(0.3));
		for (LivingEntity e : Targets.radius(ctx, at, BarrageGeometry.BLAST)) {
			if (!reaches(level, blast, e.getBoundingBox())) continue;
			double f = BarrageGeometry.falloff(Math.sqrt(e.getBoundingBox().distanceToSqr(at)));
			Hurt.damage(ctx, e, (float) (DAMAGE * ctx.power() * f));
			if (e.isAlive()) {
				e.igniteForTicks(FIRE_TICKS);
				Hurt.knock(e, e.getBoundingBox().getCenter().subtract(at), KNOCK, KNOCK_UP);
			}
		}
		BlockPos crater = craterOrigin(level, hit);
		int removed = crater == null ? 0 : crater(ctx, crater);
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
	private static int crater(CastContext ctx, BlockPos origin) {
		ServerLevel level = ctx.level();
		if (level.getBlockState(origin).isAir()) return 0;
		List<BlockPos> bared = new ArrayList<>();
		int removed = 0;
		try (EditBatch b = ctx.kit().terrain().begin(ctx, origin, EditTier.MEDIUM)) {
			for (Cell c : Shapes.bowl(CRATER_RADIUS, CRATER_DEPTH)) {
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
