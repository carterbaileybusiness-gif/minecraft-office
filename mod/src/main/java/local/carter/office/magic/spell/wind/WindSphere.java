package local.carter.office.magic.spell.wind;

import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Random;
import java.util.Set;
import java.util.function.Predicate;

import org.jspecify.annotations.Nullable;

import local.carter.office.magic.cast.CastService;
import local.carter.office.magic.core.Element;
import local.carter.office.magic.core.Slots;
import local.carter.office.magic.spell.ActiveEffects;
import local.carter.office.magic.spell.CastContext;
import local.carter.office.magic.spell.CastOutcome;
import local.carter.office.magic.spell.Hurt;
import local.carter.office.magic.spell.LingeringEffect;
import local.carter.office.magic.spell.Motion;
import local.carter.office.magic.spell.Spell;
import local.carter.office.magic.spell.SpellIds;
import local.carter.office.magic.spell.TargetFilter;
import local.carter.office.magic.spell.Targets;
import local.carter.office.magic.terrain.EditBatch;
import local.carter.office.magic.terrain.EditTier;

import net.minecraft.core.BlockPos;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.monster.Enemy;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.CollisionContext;

/**
 * Wind Sphere (Wind, slot 4, ultimate, hand signs, lingering): a spinning sphere of wind formed in the hand that the caster
 * rams into whatever is ahead, drilling through it.
 *
 * <p><b>Form.</b> For {@link DrillGeometry#WINDUP} ticks the sphere spins up in the hand and the caster is held still
 * (its velocity is zeroed every tick), so the dash leaves from where the cast was made.
 *
 * <p><b>Dash.</b> Then the caster's velocity is set to the cast's look × {@link DrillGeometry#SPEED} every tick for at most
 * {@link DrillGeometry#DASH_TICKS} ticks (synced: the client does the moving), with no fall damage until
 * {@link #NO_FALL_TICKS} after the cast. The plan is the straight line along the look ({@link DrillGeometry#sphere}); the body
 * travels it too. Each tick the server checks what comes next ({@link #step}): the first creature whose box comes within
 * {@link DrillGeometry#TOUCH} of the sphere ({@link TargetFilter#affects}), or the first block collider on the sphere's line or on
 * the body's centre line ({@link DrillGeometry#body}: a knee-high wall the sphere would fly over stops the body). The body
 * really moving is checked as well ({@link #stalled}): if the caster's real progress along the look trails the plan by more
 * than {@link #TRAIL} and their body is pressed against something, the sphere goes off right there, {@link DrillGeometry#LEAD}
 * ahead of where they actually are. On a hit, that tick's velocity is cut short to end the move at it and the next tick stops
 * the caster: the dash never carries them on (the client owns movement, and its collisions keep them out of blocks).
 *
 * <p>The dash ends with nothing set off if the caster is more than {@link #TELEPORT} off the planned path so far (a teleport:
 * measured against the plan, not one tick's move, so a lag spike or bunched movement packets, which leave them behind on the path, never cancel a paid ultimate) or
 * leaves, dies or changes dimension. Running out of dash sets it off in the air at the end.
 *
 * <p><b>Detonation.</b> The creature struck takes {@code 22 × P} and is knocked {@link DrillGeometry#KNOCK} (along the look,
 * a little away, {@link #KNOCK_UP} up); hostile mobs ({@link Enemy}) within {@link DrillGeometry#BLAST} of the blast that it can
 * reach (no block collider in between) take the same. Livestock, golems, pets, villagers and players are never hurt or thrown
 * by the blast (the lane ruling for area effects). Only a <b>block</b> hit drills: one ULTIMATE batch at the drill's start (on
 * the body's centre line) removes {@link DrillGeometry#blocks} (a cone along the look, radius 3 → 1, {@code min(12, 8 + (level −
 * 7))} long, the floor under the path kept), the first {@link DrillGeometry#LAUNCH} of the mouth's off-axis blocks
 * {@code launch}ed spinning out ({@link DrillGeometry#debris}; the core lands them only where the caster could build). A creature
 * or air detonation leaves the land alone.
 *
 * <p>Fx: {@code target} = the predicted detonation point, {@code duration} = the windup plus the predicted dash ticks
 * (rounded up), {@code entities} = the creature predicted to be struck (if any), {@code data} = {@code {drill length,
 * predicted dash ticks (fractional), kind}} with kind 0 = air, 1 = block, 2 = creature. The prediction is the same
 * {@link #step} sweep over the whole dash at cast time; the client repeats {@link #step} and {@link #stalled} live, so the
 * picture goes off where the server sets the blast off.
 */
public final class WindSphere implements Spell {
	public static final int NO_FALL_TICKS = DrillGeometry.WINDUP + DrillGeometry.DASH_TICKS + 40;
	/** The caster this far off the planned path so far has been teleported: the dash ends. */
	public static final double TELEPORT = 4;
	/** Real progress trailing the plan by more than this, body blocked: the sphere goes off where the caster is. */
	public static final double TRAIL = 2;
	/** How far ahead of the body {@link #stalled} looks for what stopped it. */
	static final double PRESS = 0.3;
	public static final double KNOCK_UP = 0.5;

	/** What set the sphere off. */
	public enum Kind {
		AIR,
		BLOCK,
		CREATURE
	}

	/**
	 * Where and when the sphere goes off: {@code t} = dash ticks (fractional), {@code at} = the point, {@code entity} for a
	 * creature, {@code drill} (where the drill's axis starts, on the body's centre line) for a block hit.
	 */
	public record Strike(Kind kind, double t, Vec3 at, @Nullable Entity entity, @Nullable Vec3 drill) {}

	@Override
	public Identifier id() {
		return SpellIds.WIND_SPHERE;
	}

	@Override
	public @Nullable Element element() {
		return Element.WIND;
	}

	@Override
	public int slot() {
		return Slots.ULTIMATE;
	}

	@Override
	public CastOutcome cast(CastContext ctx) {
		if (ActiveEffects.full(ctx.caster().getUUID())) return CastOutcome.fizzle(CastService.BUSY_KEY);
		ServerPlayer p = ctx.caster();
		Vec3 eye = ctx.eye();
		Vec3 look = DrillGeometry.unit(ctx.look());
		int length = DrillGeometry.length(ctx.grimoireLevel());
		Predicate<Entity> touches = e -> TargetFilter.affects(p, e);
		Strike predicted = predict(ctx.level(), eye, look, touches);
		if (!ActiveEffects.add(ctx, new Dash(ctx, eye, look, length))) return CastOutcome.fizzle(CastService.BUSY_KEY);
		Motion.dash(p, Vec3.ZERO, NO_FALL_TICKS);
		int[] ids = predicted.entity() == null ? new int[0] : new int[] {predicted.entity().getId()};
		return CastOutcome.cast(ctx.fx().withTarget(predicted.at()).withDuration(DrillGeometry.WINDUP + (int) Math.ceil(predicted.t()))
				.withEntities(ids).withData(length, (float) predicted.t(), predicted.kind().ordinal()));
	}

	/** The whole dash swept now: the first {@link #step} that strikes, else the air at the end of the dash. */
	public static Strike predict(Level level, Vec3 eye, Vec3 look, Predicate<Entity> touches) {
		for (int k = 1; k <= DrillGeometry.DASH_TICKS; k++) {
			Strike s = step(level, eye, look, k, touches);
			if (s != null) return s;
		}
		return new Strike(Kind.AIR, DrillGeometry.DASH_TICKS, DrillGeometry.sphere(eye, look, DrillGeometry.DASH_TICKS), null, null);
	}

	/**
	 * What the dash meets on dash tick {@code k} (1..{@link DrillGeometry#DASH_TICKS}): the stretch from {@code sphere(k − 1)} to
	 * {@code sphere(k)} (on the first tick from the eye, so nothing between the eye and the hand is skipped), and the same stretch
	 * of the body's centre line under it. The nearest of: an entity {@code touches} accepts whose box comes within
	 * {@link DrillGeometry#TOUCH} of the sphere, or a block collider on either line (fluids pass). Null if none. Shared by the
	 * server and the client fx.
	 */
	public static @Nullable Strike step(Level level, Vec3 eye, Vec3 look, int k, Predicate<Entity> touches) {
		Vec3 u = DrillGeometry.unit(look);
		Vec3 a = k <= 1 ? eye : DrillGeometry.sphere(eye, u, k - 1);
		Vec3 b = DrillGeometry.sphere(eye, u, k);
		double len = a.distanceTo(b);
		// The sphere's line and the body's: the nearer hit (as a share of the stretch) wins.
		BlockHitResult high = clip(level, a, b);
		BlockHitResult low = clip(level, DrillGeometry.body(a), DrillGeometry.body(b));
		double highAt = high.getType() == HitResult.Type.MISS ? Double.MAX_VALUE : a.distanceTo(high.getLocation());
		double lowAt = low.getType() == HitResult.Type.MISS ? Double.MAX_VALUE : DrillGeometry.body(a).distanceTo(low.getLocation());
		double blockAt = Math.min(highAt, lowAt);
		Entity best = null;
		Vec3 bestAt = null;
		double bestDist = Double.MAX_VALUE;
		double r = DrillGeometry.TOUCH;
		for (Entity e : level.getEntities((Entity) null, new AABB(a, b).inflate(r + 1), touches)) {
			AABB box = e.getBoundingBox().inflate(r);
			Vec3 at;
			if (box.contains(a)) {
				at = a;
			} else {
				Optional<Vec3> c = box.clip(a, b);
				if (c.isEmpty()) continue;
				at = c.get();
			}
			double d = a.distanceTo(at);
			if (d < bestDist) {
				bestDist = d;
				best = e;
				bestAt = at;
			}
		}
		if (best != null && bestDist <= blockAt) return new Strike(Kind.CREATURE, tAt(eye, u, bestAt), bestAt, best, null);
		if (blockAt > len + 1.0E-6) return null;
		// The sphere's point level with the hit, and the drill's start on the body's line under it.
		Vec3 at = a.add(b.subtract(a).scale(len < 1.0E-9 ? 0 : blockAt / len));
		return new Strike(Kind.BLOCK, tAt(eye, u, at), at, null, DrillGeometry.body(at));
	}

	/**
	 * The body stopped short of the plan: when the {@code caster}'s real progress along the look from {@code eye} trails the plan
	 * after {@code plannedT} dash ticks by more than {@link #TRAIL} and their box is pressed against a block collider just ahead
	 * (along the level and upward part of the look: the floor under a downward dash doesn't count), the sphere goes off
	 * {@link DrillGeometry#LEAD} ahead of where they actually are, as a block hit (the drill starts at their body's centre, so it takes what stopped them). Null
	 * otherwise: a body that is merely behind (lag, bunched movement) keeps going. Shared by the server and the client fx.
	 */
	public static @Nullable Strike stalled(Level level, Entity caster, Vec3 eye, Vec3 look, double plannedT) {
		Vec3 u = DrillGeometry.unit(look);
		Vec3 real = caster.getEyePosition();
		double behind = DrillGeometry.SPEED * Math.max(0, plannedT) - real.subtract(eye).dot(u);
		if (behind <= TRAIL) return null;
		Vec3 nudge = new Vec3(u.x, Math.max(0, u.y), u.z).scale(PRESS);
		if (level.noCollision(caster, caster.getBoundingBox().move(nudge))) return null;
		Vec3 at = real.add(u.scale(DrillGeometry.LEAD));
		return new Strike(Kind.BLOCK, plannedT, at, null, DrillGeometry.body(real));
	}

	private static BlockHitResult clip(Level level, Vec3 a, Vec3 b) {
		return level.clip(new ClipContext(a, b, ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, CollisionContext.empty()));
	}

	/** Where the plan has the caster's feet {@code t} dash ticks in, from feet {@code feet} at the cast. */
	public static Vec3 plannedFeet(Vec3 feet, Vec3 look, double t) {
		return feet.add(DrillGeometry.unit(look).scale(DrillGeometry.SPEED * Math.max(0, t)));
	}

	/** The dash time at which the sphere is at {@code p} (on its line), at least 0. */
	static double tAt(Vec3 eye, Vec3 u, Vec3 p) {
		return Math.max(0, (p.subtract(eye).dot(u) - DrillGeometry.LEAD) / DrillGeometry.SPEED);
	}

	/** True when the blast at {@code from} reaches {@code box}: no block collider before its centre, feet or top. */
	static boolean reaches(Level level, Vec3 from, AABB box) {
		Vec3 c = box.getCenter();
		for (Vec3 to : new Vec3[] {c, new Vec3(c.x, box.minY + 0.1, c.z), new Vec3(c.x, box.maxY - 0.1, c.z)}) {
			if (from.distanceToSqr(to) < 1.0E-8) return true;
			if (clip(level, from, to).getType() == HitResult.Type.MISS) return true;
		}
		return false;
	}

	/** The cast in progress: forming, dashing, then one tick to stop the caster after the detonation. */
	static final class Dash implements LingeringEffect {
		private final CastContext ctx;
		private final Vec3 eye;
		private final Vec3 feet;
		private final Vec3 look;
		private final int length;
		private final ServerLevel home;
		private boolean detonated;

		Dash(CastContext ctx, Vec3 eye, Vec3 look, int length) {
			this.ctx = ctx;
			this.eye = eye;
			this.feet = ctx.caster().position();
			this.look = look;
			this.length = length;
			this.home = ctx.level();
		}

		@Override
		public boolean tick(ServerLevel level, int age) {
			ServerPlayer p = ctx.caster();
			if (detonated) {
				// The tick after the hit: stop the caster (unless they left in the meantime).
				if (present(p)) Motion.dash(p, Vec3.ZERO, NO_FALL_TICKS - age);
				return false;
			}
			if (!present(p)) return false;
			int k = age - DrillGeometry.WINDUP;
			// Measured against the plan (where it has them now, before this tick's move), never one tick's move: anywhere on the
			// path so far is lag or bunched packets; far off it is a teleport.
			if (DrillGeometry.offPath(p.position(), feet, plannedFeet(feet, look, k - 1)) > TELEPORT) return false;
			if (k <= 0) {
				Motion.dash(p, Vec3.ZERO, NO_FALL_TICKS - age);
				return true;
			}
			Predicate<Entity> touches = e -> TargetFilter.affects(p, e);
			Strike s = stalled(level, p, eye, look, k - 1);
			boolean stopped = s != null;
			if (s == null) s = step(level, eye, look, k, touches);
			if (s == null && k < DrillGeometry.DASH_TICKS) {
				Motion.dash(p, look.scale(DrillGeometry.SPEED), NO_FALL_TICKS - age);
				return true;
			}
			if (s == null) s = new Strike(Kind.AIR, k, DrillGeometry.sphere(eye, look, k), null, null);
			// This tick's move ends at the hit (no move at all when the body is already stopped).
			double share = stopped ? 0 : Math.max(0, Math.min(1, s.t() - (k - 1)));
			Motion.dash(p, look.scale(DrillGeometry.SPEED * share), NO_FALL_TICKS - age);
			detonate(level, s);
			detonated = true;
			return true;
		}

		private boolean present(ServerPlayer p) {
			return p.isAlive() && !p.isRemoved() && !p.hasDisconnected() && p.level() == home;
		}

		private void detonate(ServerLevel level, Strike s) {
			blast(level, s);
			if (s.kind() == Kind.BLOCK && s.drill() != null) drill(s.drill());
		}

		private void blast(ServerLevel level, Strike s) {
			float damage = DrillGeometry.DAMAGE * ctx.power();
			Set<Entity> done = new HashSet<>();
			if (s.entity() instanceof LivingEntity struck && TargetFilter.affects(ctx.caster(), struck)) {
				done.add(struck);
				hit(struck, s.at(), damage);
			}
			Vec3 from = s.at().subtract(look.scale(0.3));
			List<LivingEntity> near = Targets.radius(ctx, s.at(), DrillGeometry.BLAST);
			for (LivingEntity e : near) {
				if (done.size() >= Targets.MAX_ENTITIES) break; // the struck one and the blast share the per-cast budget
				if (done.contains(e) || !(e instanceof Enemy) || !reaches(level, from, e.getBoundingBox())) continue;
				done.add(e);
				hit(e, s.at(), damage);
			}
		}

		private void hit(LivingEntity e, Vec3 at, float damage) {
			Hurt.damage(ctx, e, damage);
			if (!e.isAlive()) return;
			Vec3 away = e.getBoundingBox().getCenter().subtract(at);
			Vec3 dir = new Vec3(look.x, 0, look.z).normalize().add(new Vec3(away.x, 0, away.z).scale(0.5));
			if (dir.lengthSqr() < 1.0E-6) dir = away;
			Hurt.knock(e, dir, DrillGeometry.KNOCK, KNOCK_UP);
		}

		private void drill(Vec3 start) {
			List<BlockPos> blocks = DrillGeometry.blocks(start, look, length);
			List<Integer> thrown = DrillGeometry.thrown(blocks, start, look, DrillGeometry.LAUNCH);
			Random r = new Random(ctx.seed());
			boolean[] launched = new boolean[blocks.size()];
			try (EditBatch b = ctx.kit().terrain().begin(ctx, BlockPos.containing(start), EditTier.ULTIMATE)) {
				for (int i : thrown) {
					if (b.launch(blocks.get(i), DrillGeometry.debris(blocks.get(i), start, look, r))) launched[i] = true;
				}
				for (int i = 0; i < blocks.size(); i++) {
					if (!launched[i]) b.remove(blocks.get(i));
				}
			}
		}

		@Override
		public String toString() {
			return "WindSphere.Dash[" + ctx.caster().getScoreboardName() + " from " + eye + "]";
		}
	}
}
