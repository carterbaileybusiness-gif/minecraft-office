package local.carter.office.magic.spell.wind;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

import org.jspecify.annotations.Nullable;

import local.carter.office.magic.cast.CastService;
import local.carter.office.magic.core.Element;
import local.carter.office.magic.core.Slots;
import local.carter.office.magic.net.FxEvent;
import local.carter.office.magic.spell.ActiveEffects;
import local.carter.office.magic.spell.CastContext;
import local.carter.office.magic.spell.CastOutcome;
import local.carter.office.magic.spell.Hurt;
import local.carter.office.magic.spell.LingeringEffect;
import local.carter.office.magic.spell.Spell;
import local.carter.office.magic.spell.SpellIds;
import local.carter.office.magic.spell.TargetFilter;
import local.carter.office.magic.spell.Targets;

import net.minecraft.core.BlockPos;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.ExperienceOrb;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.monster.Enemy;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.CollisionContext;

/**
 * Tornado (Wind, slot 3, medium, lingering): a whirlwind that wanders forward, lifting what it catches.
 *
 * <p>It starts on the ground under the ray point ({@link Targets#point} within {@link TornadoGeometry#RANGE}, stepped back off
 * the face it hit) and travels along the caster's level look at 0.15 blocks a tick for {@code 120 + 40 × charge} ticks
 * ({@link TornadoGeometry}). Its base hugs the ground as it goes ({@link #follow}: up a step of at most {@link #STEP_UP}, down
 * at most {@link #DROP}; across water it rides the surface). Every tick, everything it {@link #lifts} within its reach (radius
 * 4, from 1 below the base to 9 above) that the axis can see (no block collider between the axis and its middle: wind never
 * blows through walls) gets {@link TornadoGeometry#push} as its velocity: 0.35 round the axis, 0.15 in, 0.25 up, plus the
 * tornado's travel, scaled by knockback resistance. Things carried over the top are flung out and fall. Every 10 ticks,
 * hostile mobs it holds take 1 damage. At most {@link Targets#MAX_ENTITIES} things and {@link #MAX_CLIPS} sight checks a tick.
 * No block changes.
 *
 * <p>Fx: {@code target} = the start (on the ground), {@code dir} = the level travel direction, {@code duration} = the life in
 * ticks. The client rebuilds the path with {@code new TornadoGeometry(target, dir, duration)} and {@link #follow}.
 */
public final class Tornado implements Spell {
	/** Highest step the base climbs in a tick, and deepest drop it follows. */
	public static final int STEP_UP = 2;
	public static final int DROP = 6;
	static final int MAX_CLIPS = 128;
	/** Velocities are synced to clients every this many ticks. */
	static final int SYNC_EVERY = 2;

	@Override
	public Identifier id() {
		return SpellIds.TORNADO;
	}

	@Override
	public @Nullable Element element() {
		return Element.WIND;
	}

	@Override
	public int slot() {
		return Slots.MEDIUM_B;
	}

	@Override
	public CastOutcome cast(CastContext ctx) {
		if (ActiveEffects.full(ctx.caster().getUUID())) return CastOutcome.fizzle(CastService.BUSY_KEY);
		Vec3 start = start(ctx);
		Vec3 dir = AirStep.lookH(ctx.look(), ctx.caster().getYRot());
		TornadoGeometry geo = new TornadoGeometry(start, dir, TornadoGeometry.life(ctx.charge()));
		if (!ActiveEffects.add(ctx, new Twister(ctx, geo))) return CastOutcome.fizzle(CastService.BUSY_KEY);
		FxEvent fx = ctx.fx();
		return CastOutcome.cast(new FxEvent(fx.spell(), fx.casterId(), fx.origin(), geo.dir(), start, fx.charge(), fx.power(), fx.seed(),
				geo.life(), new int[0], new float[0]));
	}

	/** The start: the ray point stepped 0.3 back toward the eye (off a wall's face), moved down to the ground of its column. */
	static Vec3 start(CastContext ctx) {
		Vec3 eye = ctx.eye();
		Vec3 hit = Targets.point(ctx, TornadoGeometry.RANGE);
		Vec3 back = eye.subtract(hit);
		double len = back.length();
		Vec3 p = len < 1.0E-6 ? hit : hit.add(back.scale(Math.min(0.3, len) / len));
		return new Vec3(p.x, Targets.ground(ctx.level(), p).y, p.z);
	}

	/**
	 * The ground height for a tornado at level point {@code (x, z)} whose base was at {@code prevY}: the top of the
	 * {@code MOTION_BLOCKING} surface (water counts) nearest to {@code prevY} within {@link #STEP_UP} up and {@link #DROP} down,
	 * or {@code prevY} if there is none (inside a wall it keeps its height). Shared by the server and the client fx.
	 */
	public static double follow(BlockGetter level, double x, double z, double prevY) {
		int bx = Mth.floor(x);
		int bz = Mth.floor(z);
		int y = Mth.floor(prevY + 1.0E-4);
		BlockPos.MutableBlockPos m = new BlockPos.MutableBlockPos();
		for (int d = 0; d <= DROP; d++) {
			if (d <= STEP_UP && d > 0 && surface(level, m, bx, y + d, bz)) return y + d;
			if (surface(level, m, bx, y - d, bz)) return y - d;
		}
		return prevY;
	}

	/** True when the cell under {@code (x, y, z)} blocks motion and the cell itself does not (a surface to stand on at y). */
	private static boolean surface(BlockGetter level, BlockPos.MutableBlockPos m, int x, int y, int z) {
		if (blocks(level, m.set(x, y, z))) return false;
		return blocks(level, m.set(x, y - 1, z));
	}

	private static boolean blocks(BlockGetter level, BlockPos pos) {
		return Heightmap.Types.MOTION_BLOCKING.isOpaque().test(level.getBlockState(pos));
	}

	/**
	 * What the tornado lifts: items, XP orbs, and hostile mobs ({@link Enemy}) that {@link TargetFilter#affects} (the lane ruling:
	 * livestock, golems, allays, pets, villagers and players are never lifted or hurt).
	 */
	static boolean lifts(CastContext ctx, Entity e) {
		if (!TargetFilter.pushable(ctx.caster(), e)) return false;
		return e instanceof ItemEntity || e instanceof ExperienceOrb || e instanceof Enemy && TargetFilter.affects(ctx.caster(), e);
	}

	/** True when no block collider stands between {@code a} and {@code b}. */
	static boolean clear(ServerLevel level, Vec3 a, Vec3 b) {
		if (a.distanceToSqr(b) < 1.0E-8) return true;
		return level.clip(new ClipContext(a, b, ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, CollisionContext.empty()))
				.getType() == HitResult.Type.MISS;
	}

	/** The running whirlwind. */
	static final class Twister implements LingeringEffect {
		private final CastContext ctx;
		private final TornadoGeometry geo;
		private double y;

		Twister(CastContext ctx, TornadoGeometry geo) {
			this.ctx = ctx;
			this.geo = geo;
			this.y = geo.origin().y;
		}

		@Override
		public boolean tick(ServerLevel level, int age) {
			if (age > geo.life()) return false;
			Vec3 flat = geo.at(age);
			// It never loads a chunk: it dies out where its own goes away.
			if (!level.isLoaded(BlockPos.containing(flat.x, y, flat.z))) return false;
			y = follow(level, flat.x, flat.z, y);
			Vec3 base = new Vec3(flat.x, y, flat.z);
			double r = TornadoGeometry.RADIUS;
			AABB reach = new AABB(base.x - r, base.y - TornadoGeometry.BELOW, base.z - r, base.x + r, base.y + TornadoGeometry.HEIGHT, base.z + r);
			List<Entity> caught = new ArrayList<>(level.getEntities((Entity) null, reach,
					e -> lifts(ctx, e) && TornadoGeometry.inReach(base, e.position())));
			caught.sort(Comparator.comparingDouble(e -> e.position().distanceToSqr(base)));
			boolean hurt = age % TornadoGeometry.HURT_EVERY == 0;
			boolean sync = age % SYNC_EVERY == 0;
			int n = 0;
			int clips = 0;
			for (Entity e : caught) {
				if (n >= Targets.MAX_ENTITIES || clips >= MAX_CLIPS) break;
				Vec3 mid = e.getBoundingBox().getCenter();
				clips++;
				if (!clear(level, new Vec3(base.x, mid.y, base.z), mid)) continue;
				n++;
				double scale = 1;
				if (e instanceof LivingEntity l) scale = Math.max(0, 1 - l.getAttributeValue(Attributes.KNOCKBACK_RESISTANCE));
				Vec3 v = e.getDeltaMovement().lerp(geo.push(base, mid), scale);
				e.setDeltaMovement(v);
				if (sync) {
					e.syncVelocity = true;
					e.needsSync = true;
				}
				if (hurt && e instanceof LivingEntity l && l instanceof Enemy) Hurt.damage(ctx, l, TornadoGeometry.HURT);
			}
			return age < geo.life();
		}

		@Override
		public String toString() {
			return "Tornado.Twister[" + ctx.caster().getScoreboardName() + " from " + geo.origin() + "]";
		}
	}
}
