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
import local.carter.office.magic.spell.Hurt;
import local.carter.office.magic.spell.LingeringEffect;
import local.carter.office.magic.spell.Spell;
import local.carter.office.magic.spell.SpellIds;
import local.carter.office.magic.spell.Targets;
import local.carter.office.magic.spell.shadow.BlackHoleGeometry.Cell;
import local.carter.office.magic.terrain.EditBatch;
import local.carter.office.magic.terrain.EditTier;

import net.minecraft.core.BlockPos;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.monster.Enemy;
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
 * <li><b>Pull</b>, every tick: hostile mobs, items and XP orbs ({@link GravityWell#pulls}: never players, pets, villagers or
 * livestock) within {@link BlackHoleGeometry#PULL_RADIUS} that the centre can see get {@link BlackHoleGeometry#pull} as their
 * velocity (0.08 → 0.25 a tick over its life), scaled by knockback resistance; at most 64 of them and {@link #MAX_CLIPS} sight
 * checks a tick. Velocities are synced on even ticks only, and not at all for a pull under {@link #MIN_SYNC}.</li>
 * <li><b>Hurt</b>, every {@link BlackHoleGeometry#HURT_EVERY} ticks: hostile mobs within {@link BlackHoleGeometry#HURT_RADIUS}
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
 * <p>Fx: {@code target} = the centre, {@code duration} = {@link BlackHoleGeometry#LIFE}, {@code data} = {the sphere's final radius,
 * the pull radius}.
 */
public final class BlackHole implements Spell {
	static final int MAX_CLIPS = 128;
	static final int SYNC_EVERY = 2;
	static final double MIN_SYNC = 0.02;

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
		if (!ActiveEffects.add(ctx.level(), new Hole(ctx, centre, cap))) return CastOutcome.fizzle(CastService.BUSY_KEY);
		return CastOutcome.cast(ctx.fx().withTarget(centre).withDuration(BlackHoleGeometry.LIFE)
				.withData((float) cap, (float) BlackHoleGeometry.PULL_RADIUS));
	}

	/**
	 * The scorched stone for a rim cell of {@code kind}: blackstone, or for {@link BlackHoleGeometry#KIND_CRYING} amethyst. Crying
	 * obsidian (the brief's pick) is in {@code #office:magic_immune}, which the terrain core refuses to place, so amethyst stands in
	 * for its violet.
	 */
	static BlockState scorch(int kind) {
		return (kind == BlackHoleGeometry.KIND_CRYING ? Blocks.AMETHYST_BLOCK : Blocks.BLACKSTONE).defaultBlockState();
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
		private @Nullable EditBatch batch;

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
			double r = BlackHoleGeometry.PULL_RADIUS;
			List<Entity> caught = new ArrayList<>(level.getEntities((Entity) null, new AABB(centre, centre).inflate(r),
					e -> GravityWell.pulls(ctx, e) && BlackHoleGeometry.inReach(centre, e.getBoundingBox().getCenter())));
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
				Vec3 pull = BlackHoleGeometry.pull(centre, mid, age).scale(resistance(e));
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
				if (!(l instanceof Enemy) || clips >= MAX_CLIPS) continue;
				clips++;
				if (GravityWell.clear(level, centre, l.getBoundingBox().getCenter())) Hurt.damage(ctx, l, BlackHoleGeometry.HURT);
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
					b.remove(pos);
					continue;
				}
				int kind = BlackHoleGeometry.rimKind(c, cap, ctx.seed());
				if (kind == BlackHoleGeometry.KIND_NONE) {
					held.add(c);
				} else if (solid(level, pos)) {
					// Scorched only where there is ground: never a block hung in the air.
					b.set(pos, scorch(kind));
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

			for (LivingEntity l : Targets.radius(ctx, centre, BlackHoleGeometry.BLAST_RADIUS)) {
				if (GravityWell.clear(level, centre, l.getBoundingBox().getCenter())) Hurt.damage(ctx, l, BlackHoleGeometry.BLAST * ctx.power());
			}
			double r = BlackHoleGeometry.BLAST_RADIUS;
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
