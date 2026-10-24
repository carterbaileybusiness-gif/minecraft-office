package local.carter.office.magic.spell.shadow;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Random;
import java.util.Set;

import org.jspecify.annotations.Nullable;

import local.carter.office.magic.cast.CastService;
import local.carter.office.magic.core.Element;
import local.carter.office.magic.core.Slots;
import local.carter.office.magic.spell.ActiveEffects;
import local.carter.office.magic.spell.CastContext;
import local.carter.office.magic.spell.CastOutcome;
import local.carter.office.magic.spell.HitBudget;
import local.carter.office.magic.spell.Hurt;
import local.carter.office.magic.spell.LingeringEffect;
import local.carter.office.magic.spell.ProjectileSpec;
import local.carter.office.magic.spell.Projectiles;
import local.carter.office.magic.spell.Spell;
import local.carter.office.magic.spell.SpellIds;
import local.carter.office.magic.spell.Targets;
import local.carter.office.magic.spell.shadow.BlackHoleGeometry.Cell;
import local.carter.office.magic.terrain.EditBatch;
import local.carter.office.magic.terrain.EditTier;
import local.carter.office.magic.tree.Knob;
import local.carter.office.magic.tree.SpellRank;

import net.minecraft.core.BlockPos;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

/**
 * Black Hole (Shadow, slot 4, ultimate, hand signs, lingering): a black hole that devours the land around it.
 *
 * <p>It opens {@link BlackHoleGeometry#HEIGHT} above the ground under the look ray's point within
 * {@link BlackHoleGeometry#RANGE} ({@link GravityWell#hover}) and lasts {@link BlackHoleGeometry#LIFE} ticks.
 * <ul>
 * <li><b>Pull</b>, every tick: hostile mobs, animals, items and XP orbs ({@link GravityWell#pulls}: never players, pets,
 * villagers, golems, named mobs or a boss) within {@link BlackHoleGeometry#PULL_RADIUS} that the centre can see get {@link BlackHoleGeometry#pull} as their
 * velocity (0.08 → 0.25 a tick over its life), scaled by knockback resistance; at most 64 of them and {@link #MAX_CLIPS} sight
 * checks a tick. Velocities are synced on even ticks only, and not at all for a pull under {@link #MIN_SYNC}.</li>
 * <li><b>Hurt</b>, every {@link BlackHoleGeometry#HURT_EVERY} ticks: every creature spells may hurt within {@link BlackHoleGeometry#HURT_RADIUS}
 * that it can see take {@link BlackHoleGeometry#HURT}.</li>
 * <li><b>Devour</b>, permanent: one {@link EditTier#ULTIMATE} batch at the centre, open for its whole life (so the edit log and
 * {@code /magic undo} see one cast). Every tick it eats the blocks the growing sphere ({@link BlackHoleGeometry#radius}, 1 → {@code
 * min(12, 6 + (level − 7))}) has reached. The batch refuses claims, spawn protection, block entities and immune blocks (they
 * stay) and holds the cast to 1500 changes. A tenth of the outermost layer is scorched instead ({@link #scorch}); the rest of
 * that layer waits for the collapse.</li>
 * <li><b>Collapse</b>, at the end: the last layer goes, up to {@link BlackHoleGeometry#MAX_DEBRIS} blocks of the crater's lip
 * flung outward through {@link EditBatch#launch} (the core lands them only where the caster could build); creatures within
 * {@link BlackHoleGeometry#BLAST_RADIUS} it can see take {@code 10 × P}; what it pulls is knocked {@link BlackHoleGeometry#KNOCK}
 * outward and {@link BlackHoleGeometry#KNOCK_UP} up.</li>
 * </ul>
 * It never loads a chunk: it closes if its own goes away.
 *
 * <p>Ranks widen the pull (the size knob). Forks: III {@link #HUNGERING} (every creature it kills widens its pull by
 * {@link #HUNGER_GROWTH}, up to {@link #HUNGER_MAX} more) or
 * {@link #HORIZON} (pulls {@link #HORIZON_PULL}× as hard); V {@link #SUPERNOVA} (the collapse's blast twice as wide and twice as
 * hard) or {@link #DARK_RAIN} (the collapse fires {@link #RAIN_BOLTS} shadow bolts outward, {@link #RAIN_DAMAGE}{@code × P} each).
 *
 * <p>Fx: {@code target} = the centre, {@code duration} = {@link BlackHoleGeometry#LIFE}, {@code data} = {the sphere's final radius,
 * the pull radius}. The client rebuilds Dark Rain's bolts with {@link #rain}.
 */
public final class BlackHole implements Spell {
	static final int MAX_CLIPS = 128;
	static final int SYNC_EVERY = 2;
	static final double MIN_SYNC = 0.02;
	public static final String HUNGERING = "hungering";
	public static final String HORIZON = "horizon";
	public static final String SUPERNOVA = "supernova";
	public static final String DARK_RAIN = "dark_rain";
	public static final double HUNGER_GROWTH = 1;
	public static final double HUNGER_MAX = 4;
	public static final double HORIZON_PULL = 1.4;
	public static final int RAIN_BOLTS = 8;
	public static final float RAIN_DAMAGE = 6;

	/** The pull's reach at {@code rank}: {@link BlackHoleGeometry#PULL_RADIUS} times the size knob. */
	public static double pullRadius(SpellRank rank) {
		return BlackHoleGeometry.PULL_RADIUS * rank.mul(Knob.SIZE);
	}

	/** The pull's reach after {@code kills} (Hungering grows {@link #HUNGER_GROWTH} a kill, at most {@link #HUNGER_MAX}). */
	public static double pullRadius(SpellRank rank, int kills) {
		return pullRadius(rank) + (rank.is(HUNGERING) ? Math.min(HUNGER_MAX, HUNGER_GROWTH * kills) : 0);
	}

	/** The collapse's blast reach at {@code rank} (Supernova's ×2). */
	public static double blastRadius(SpellRank rank) {
		return BlackHoleGeometry.BLAST_RADIUS * (rank.is(SUPERNOVA) ? 2 : 1);
	}

	/** Dark Rain's bolts from {@code centre}: {@link #RAIN_BOLTS} level directions, evenly round, a little down (shared with the fx). */
	public static List<ProjectileSpec> rain(Vec3 centre, long seed) {
		List<ProjectileSpec> out = new ArrayList<>(RAIN_BOLTS);
		double turn = new Random(seed ^ 0xDA4CL).nextDouble() * Math.PI * 2;
		for (int i = 0; i < RAIN_BOLTS; i++) {
			double a = turn + Math.PI * 2 * i / RAIN_BOLTS;
			Vec3 d = new Vec3(Math.cos(a), -0.15, Math.sin(a)).normalize();
			out.add(new ProjectileSpec(centre.add(d.scale(1.2)), d.scale(ShadowBolt.SPEED), 0, ShadowBolt.RADIUS, ShadowBolt.MAX_TICKS, false));
		}
		return out;
	}

	@Override
	public Identifier id() {
		return SpellIds.BLACK_HOLE;
	}

	@Override
	public Element element() {
		return Element.SHADOW;
	}

	@Override
	public int slot() {
		return Slots.ULTIMATE;
	}

	@Override
	public CastOutcome cast(CastContext ctx) {
		Vec3 centre = GravityWell.hover(ctx, BlackHoleGeometry.RANGE, BlackHoleGeometry.HEIGHT);
		double cap = BlackHoleGeometry.cap(ctx.grimoireLevel());
		if (!ActiveEffects.add(ctx, new Hole(ctx, centre, cap))) return CastOutcome.fizzle(CastService.BUSY_KEY);
		return CastOutcome.cast(ctx.fx().withTarget(centre).withDuration(BlackHoleGeometry.LIFE)
				.withData((float) cap, (float) pullRadius(ctx.rank())));
	}

	/**
	 * The scorched stone for a rim cell of {@code kind}: blackstone, or for {@link BlackHoleGeometry#KIND_CRACKED} cracked polished
	 * blackstone bricks. (The brief's crying obsidian is in {@code #office:magic_immune}, which the terrain core refuses to place;
	 * ruling, fix round 1.)
	 */
	static BlockState scorch(int kind) {
		return (kind == BlackHoleGeometry.KIND_CRACKED ? Blocks.CRACKED_POLISHED_BLACKSTONE_BRICKS : Blocks.BLACKSTONE).defaultBlockState();
	}

	/** The running hole. */
	static final class Hole implements LingeringEffect {
		private final CastContext ctx;
		private final Vec3 centre;
		private final double cap;
		private final BlockPos origin;
		private final List<Cell> cells;
		/** The outer layer's cells waiting for the collapse. */
		private final List<Cell> held = new ArrayList<>();
		private int next;
		/** Changes this cast's batch accepted. */
		private int used;
		private @Nullable EditBatch batch;
		/** At most {@code Targets.MAX_ENTITIES} different creatures hurt over its life and the blast (the per-cast budget). */
		private final HitBudget hurtBudget = new HitBudget();
		/** Hungering: creatures its hurt has killed. */
		int kills;


		Hole(CastContext ctx, Vec3 centre, double cap) {
			this.ctx = ctx;
			this.centre = centre;
			this.cap = cap;
			this.origin = BlockPos.containing(centre);
			this.cells = BlackHoleGeometry.sphere(centre, cap);
		}

		@Override
		public boolean tick(ServerLevel level, int age) {
			if (!level.isLoaded(origin)) return false;
			int clips = pull(level, age);
			if (age % BlackHoleGeometry.HURT_EVERY == 0) hurt(level, clips);
			devour(level, age);
			if (age >= BlackHoleGeometry.LIFE) {
				collapse(level);
				return false;
			}
			return true;
		}

		/** Sets the pull on everything in reach that the centre can see; returns the sight checks it used. */
		private int pull(ServerLevel level, int age) {
			double r = pullRadius(ctx.rank(), kills);
			double strength = ctx.rank().is(HORIZON) ? HORIZON_PULL : 1;
			List<Entity> caught = new ArrayList<>(level.getEntities((Entity) null, new AABB(centre, centre).inflate(r),
					e -> GravityWell.pulls(ctx, e) && e.getBoundingBox().getCenter().distanceToSqr(centre) <= r * r));
			caught.sort(Comparator.comparingDouble(e -> e.getBoundingBox().getCenter().distanceToSqr(centre)));
			int n = 0;
			int clips = 0;
			boolean sync = age % SYNC_EVERY == 0;
			for (Entity e : caught) {
				if (n >= Targets.MAX_ENTITIES || clips >= MAX_CLIPS) break;
				Vec3 mid = e.getBoundingBox().getCenter();
				clips++;
				if (!GravityWell.clear(level, centre, mid)) continue;
				n++;
				Vec3 pull = BlackHoleGeometry.pull(centre, mid, age).scale(resistance(e) * strength);
				e.setDeltaMovement(pull);
				e.resetFallDistance();
				if (sync && pull.length() >= MIN_SYNC) {
					e.syncVelocity = true;
					e.needsSync = true;
				}
			}
			return clips;
		}

		private void hurt(ServerLevel level, int clips) {
			for (LivingEntity l : Targets.radius(ctx, centre, BlackHoleGeometry.HURT_RADIUS)) {
				if (clips >= MAX_CLIPS) break;
				clips++;
				if (GravityWell.clear(level, centre, l.getBoundingBox().getCenter()) && hurtBudget.admit(l)
						&& Hurt.damage(ctx, l, BlackHoleGeometry.HURT) && !l.isAlive()) {
					kills++;
				}
			}
		}

		/** Eats every cell the sphere has reached by {@code age}. */
		private void devour(ServerLevel level, int age) {
			double r = BlackHoleGeometry.radius(age, cap);
			if (next >= cells.size() || cells.get(next).dist() > r) return;
			EditBatch b = batch();
			while (next < cells.size() && cells.get(next).dist() <= r) {
				Cell c = cells.get(next++);
				BlockPos pos = new BlockPos(c.x(), c.y(), c.z());
				if (!BlackHoleGeometry.isRim(c, cap)) {
					// Eating keeps the reserve back for the rim and the collapse.
					if (used < EditTier.ULTIMATE.budget() - BlackHoleGeometry.RESERVE && b.remove(pos)) used++;
					continue;
				}
				int kind = BlackHoleGeometry.rimKind(c, cap, ctx.seed());
				// Scorched only in the bowl, over ground that stays: never a block hung in the air or in a tree.
				if (kind != BlackHoleGeometry.KIND_NONE && BlackHoleGeometry.scorchable(c, centre, cap) && solid(level, pos)
						&& solid(level, pos.below())) {
					if (b.set(pos, scorch(kind))) used++;
				} else {
					held.add(c);
				}
			}
		}

		/** The end: the last layer, its lip flung outward; the blast; everything it pulls thrown back out. */
		private void collapse(ServerLevel level) {
			EditBatch b = batch();
			List<Cell> lip = new ArrayList<>();
			for (Cell c : held) {
				BlockPos pos = new BlockPos(c.x(), c.y(), c.z());
				if (BlackHoleGeometry.isLip(c, centre) && solid(level, pos) && level.getBlockState(pos.above()).isAir()) lip.add(c);
			}
			Collections.shuffle(lip, new Random(ctx.seed()));
			Set<Cell> thrown = new HashSet<>();
			for (Cell c : lip) {
				if (thrown.size() >= BlackHoleGeometry.MAX_DEBRIS) break;
				if (b.launch(new BlockPos(c.x(), c.y(), c.z()), BlackHoleGeometry.debrisVelocity(centre, c, ctx.seed()))) thrown.add(c);
			}
			for (Cell c : held) {
				if (!thrown.contains(c)) b.remove(new BlockPos(c.x(), c.y(), c.z()));
			}

			double blast = blastRadius(ctx.rank());
			float damage = BlackHoleGeometry.BLAST * ctx.power() * (ctx.rank().is(SUPERNOVA) ? 2 : 1);
			for (LivingEntity l : Targets.radius(ctx, centre, blast)) {
				if (GravityWell.clear(level, centre, l.getBoundingBox().getCenter()) && hurtBudget.admit(l)) Hurt.damage(ctx, l, damage);
			}
			// Dark Rain: shadow bolts fly out of the collapse.
			if (ctx.rank().is(DARK_RAIN)) {
				for (ProjectileSpec s : rain(centre, ctx.seed())) {
					Projectiles.launch(ctx, s, hit -> {
						if (hit.entity() != null) Hurt.damage(ctx, hit.entity(), RAIN_DAMAGE * ctx.power());
					});
				}
			}
			double r = blast;
			int n = 0;
			for (Entity e : level.getEntities((Entity) null, new AABB(centre, centre).inflate(r),
					e -> GravityWell.pulls(ctx, e) && e.getBoundingBox().distanceToSqr(centre) <= r * r)) {
				if (n >= Targets.MAX_ENTITIES) break;
				Vec3 mid = e.getBoundingBox().getCenter();
				if (!GravityWell.clear(level, centre, mid)) continue;
				n++;
				Hurt.knock(e, outward(mid, e.getId()), BlackHoleGeometry.KNOCK, BlackHoleGeometry.KNOCK_UP);
			}
		}

		/** Away from the centre along the ground; for something sitting in the core (the pull brings things there), a direction of its own. */
		private Vec3 outward(Vec3 at, int id) {
			Vec3 d = new Vec3(at.x - centre.x, 0, at.z - centre.z);
			if (d.lengthSqr() >= 0.01) return d;
			double a = BlackHoleGeometry.unit(ctx.seed(), id, 0, 0, 4) * Math.PI * 2;
			return new Vec3(Math.cos(a), 0, Math.sin(a));
		}

		private EditBatch batch() {
			if (batch == null) batch = ctx.kit().terrain().begin(ctx, origin, EditTier.ULTIMATE);
			return batch;
		}

		@Override
		public void end(ServerLevel level) {
			if (batch != null) batch.close();
		}

		@Override
		public String toString() {
			return "BlackHole.Hole[" + ctx.caster().getScoreboardName() + " at " + centre + "]";
		}
	}

	private static double resistance(Entity e) {
		if (e instanceof LivingEntity l) return Math.max(0, 1 - l.getAttributeValue(Attributes.KNOCKBACK_RESISTANCE));
		return 1;
	}

	/** A block with a collision shape and no fluid (a real piece of ground). */
	static boolean solid(ServerLevel level, BlockPos pos) {
		BlockState s = level.getBlockState(pos);
		return !s.isAir() && s.getFluidState().isEmpty() && !s.getCollisionShape(level, pos).isEmpty();
	}
}
