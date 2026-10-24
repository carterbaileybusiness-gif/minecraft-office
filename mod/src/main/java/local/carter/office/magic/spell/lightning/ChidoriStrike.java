package local.carter.office.magic.spell.lightning;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Random;
import java.util.Set;

import org.jspecify.annotations.Nullable;

import local.carter.office.magic.cast.CastService;
import local.carter.office.magic.core.Element;
import local.carter.office.magic.core.Shapes.Cell;
import local.carter.office.magic.core.Slots;
import local.carter.office.magic.net.FxEvent;
import local.carter.office.magic.spell.ActiveEffects;
import local.carter.office.magic.spell.CastContext;
import local.carter.office.magic.spell.CastOutcome;
import local.carter.office.magic.spell.Hurt;
import local.carter.office.magic.spell.LingeringEffect;
import local.carter.office.magic.spell.Motion;
import local.carter.office.magic.spell.Spell;
import local.carter.office.magic.spell.SpellIds;
import local.carter.office.magic.spell.Targets;
import local.carter.office.magic.spell.lightning.ChidoriGeometry.Column;
import local.carter.office.magic.terrain.EditBatch;
import local.carter.office.magic.terrain.EditTier;

import net.minecraft.core.BlockPos;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.Mth;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.monster.Enemy;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;

/**
 * Chidori Strike (Lightning, slot 4, ultimate, hand signs, lingering): the caster's hand blazes with lightning and they charge
 * through their target in a flash, carving a scorched trench behind them.
 *
 * <p><b>Target.</b> A creature the look ray strikes within {@link ChidoriGeometry#RANGE} ({@link ChidoriGeometry#FORGIVENESS} of
 * aim forgiveness): the charge ends {@link ChidoriGeometry#PASS} past its feet. Else the point the look meets within range (just
 * off the face it hits), down to the ground there. The way is walked first ({@link BlinkPath}, the caster's own body): the charge
 * stops at the first solid block it can't step over (a one-block bump is stepped over), so it never passes through a wall.
 *
 * <p><b>Charge.</b> The caster moves along {@link ChidoriGeometry#point} over {@link ChidoriGeometry#TICKS} ticks, put there each
 * tick with {@link Motion#teleportSafe} (never inside a block), velocity zeroed, no fall damage until
 * {@link #NO_FALL_TICKS} ticks after it ends. A caster who leaves, dies or is teleported away mid-charge is no longer moved; the
 * strike still lands.
 *
 * <p><b>End.</b> The target, if within {@link ChidoriGeometry#REACH} of the end and in reach of the blast (nothing solid between),
 * takes {@code 24 × P}; every other creature within {@link ChidoriGeometry#REACH} the blast reaches takes {@code 8 × P}. The target
 * and hostile mobs among them get Slowness V and Weakness for {@link #EFFECT_TICKS} ticks (control effects are for foes only).
 * Three visual-only vanilla bolts strike round the end, {@link #FLANK} from it: ahead and to either side ({@link VisualBolts}; never on the
 * caster, whose own view would be inside the bolt).
 *
 * <p><b>Permanent.</b> A trench along the path ({@link ChidoriGeometry#trench}: 2 wide, {@link ChidoriGeometry#DEPTH} deep below
 * the ground's surface) whose edges (the lips beside it and its floor) turn 30 % blackstone and sand to glass, one ULTIMATE batch
 * per {@link ChidoriGeometry#SEGMENT}-block segment, cut as the caster passes it; and at the end a bowl of radius
 * {@code min(12, 4 + (level − 7))}: {@link ChidoriGeometry#DEBRIS} rim blocks are {@code launch}ed outward, the rest removed, and its
 * floor scorched the same way. All of it respects claims, spawn protection and immune blocks through the batches.
 *
 * <p>Fx: {@code target} = where the charge ends (feet), {@code dir} = its flat direction, {@code duration} =
 * {@link ChidoriGeometry#TICKS}, {@code entities} = {the target} if one was aimed at (else empty), {@code data} =
 * {@code {eyeHeight, radius}} (the feet started at {@code origin − (0, eyeHeight, 0)}).
 */
public final class ChidoriStrike implements Spell {
	public static final float DAMAGE = 24;
	public static final float BLAST_DAMAGE = 8;
	public static final int EFFECT_TICKS = 60;
	/** Slowness V. */
	public static final int SLOW_AMPLIFIER = 4;
	public static final int WEAKNESS_AMPLIFIER = 0;
	public static final int NO_FALL_TICKS = 20;
	/** How far from the end the three bolts strike (ahead, and {@link #BOLT_SPREAD} radians either side of ahead). */
	public static final double FLANK = 4;
	public static final double BOLT_SPREAD = 1.2;
	/** How far down from the path the ground's surface is looked for, per column. */
	public static final int SURFACE_SCAN = 4;
	/** Moved further than this between two ticks of the charge: someone else teleported the caster, so the charge lets go. */
	public static final double TELEPORTED = 4;

	@Override
	public Identifier id() {
		return SpellIds.CHIDORI_STRIKE;
	}

	@Override
	public @Nullable Element element() {
		return Element.LIGHTNING;
	}

	@Override
	public int slot() {
		return Slots.ULTIMATE;
	}

	@Override
	public CastOutcome cast(CastContext ctx) {
		ServerPlayer p = ctx.caster();
		ServerLevel level = ctx.level();
		FxEvent fx = ctx.fx();
		Vec3 start = p.position();
		Optional<LivingEntity> hit = Targets.ray(ctx, ChidoriGeometry.RANGE, ChidoriGeometry.FORGIVENESS);
		Vec3 aim = hit.map(e -> through(start, e.position(), p.getYRot())).orElseGet(() -> point(ctx));
		Vec3 way = aim.subtract(start);
		BlinkPath.Walk w = BlinkPath.walk(start, way, way.length(), at -> Blink.fits(p, at));
		ChidoriGeometry g = new ChidoriGeometry(start, w.end(), ChidoriGeometry.radius(ctx.grimoireLevel()));
		Charge charge = new Charge(ctx, g, hit.orElse(null));
		if (!ActiveEffects.add(level, charge)) return CastOutcome.fizzle(CastService.BUSY_KEY);
		Motion.dash(p, Vec3.ZERO, ChidoriGeometry.TICKS + NO_FALL_TICKS);
		int[] ids = hit.map(e -> new int[] {e.getId()}).orElse(new int[0]);
		return CastOutcome.cast(new FxEvent(fx.spell(), fx.casterId(), fx.origin(), g.flatDir(), g.end(), fx.charge(), fx.power(), fx.seed(),
				ChidoriGeometry.TICKS, ids, new float[] {p.getEyeHeight(), g.radius()}));
	}

	/** {@link ChidoriGeometry#PASS} past {@code feet}, along the flat way from {@code start} (the facing if it is straight above or below). */
	static Vec3 through(Vec3 start, Vec3 feet, float yRot) {
		double dx = feet.x - start.x;
		double dz = feet.z - start.z;
		double l = Math.sqrt(dx * dx + dz * dz);
		Vec3 f = l < 1.0E-3 ? Vec3.directionFromRotation(0, yRot) : new Vec3(dx / l, 0, dz / l);
		return feet.add(f.scale(ChidoriGeometry.PASS));
	}

	/** Where the look meets a block within range (just off its face), else the range's end, down to the ground there. */
	private static Vec3 point(CastContext ctx) {
		Vec3 look = ctx.look().normalize();
		Vec3 pt = Targets.point(ctx, ChidoriGeometry.RANGE).subtract(look.scale(0.3));
		return new Vec3(pt.x, Targets.ground(ctx.level(), pt.add(0, 0.5, 0)).y, pt.z);
	}

	/** The charge: moves the caster tick by tick, cuts the trench as they pass, then the strike and the bowl. */
	private static final class Charge implements LingeringEffect {
		private final CastContext ctx;
		private final ChidoriGeometry g;
		private final @Nullable LivingEntity target;
		private final Random random;
		private Vec3 placed;
		private boolean moving = true;
		private int cut;
		/** Cells already queued for removal or launch, and cells already scorched (no cell is queued twice). */
		private final Set<BlockPos> removed = new HashSet<>();
		private final Set<BlockPos> scorched = new HashSet<>();

		Charge(CastContext ctx, ChidoriGeometry g, @Nullable LivingEntity target) {
			this.ctx = ctx;
			this.g = g;
			this.target = target;
			this.random = new Random(ctx.seed());
			this.placed = g.start();
		}

		@Override
		public boolean tick(ServerLevel level, int age) {
			if (age <= ChidoriGeometry.TICKS) move(level, age);
			int due = g.segmentsPassed(age);
			while (cut < due) cutSegment(level, cut++);
			if (age < ChidoriGeometry.TICKS) return true;
			strike(level);
			bowl(level);
			return false;
		}

		private void move(ServerLevel level, int age) {
			if (!moving) return;
			ServerPlayer p = ctx.caster();
			if (p.isRemoved() || !p.isAlive() || p.level() != level || p.position().distanceTo(placed) > TELEPORTED) {
				moving = false;
				return;
			}
			placed = Motion.teleportSafe(p, g.point(age));
			p.setDeltaMovement(Vec3.ZERO);
			p.syncVelocity = true;
			p.resetFallDistance();
		}

		// ---- the end ----

		private void strike(ServerLevel level) {
			Vec3 end = g.end();
			Vec3 core = end.add(0, 1, 0);
			if (target != null && target.isAlive() && target.getBoundingBox().distanceToSqr(end) <= ChidoriGeometry.REACH * ChidoriGeometry.REACH
					&& Arcs.reaches(ctx, core, target.getBoundingBox())) {
				Hurt.damage(ctx, target, DAMAGE * ctx.power());
				weaken(target);
			}
			for (LivingEntity e : Targets.radius(ctx, end, ChidoriGeometry.REACH)) {
				if (e == target || !Arcs.reaches(ctx, core, e.getBoundingBox())) continue;
				Hurt.damage(ctx, e, BLAST_DAMAGE * ctx.power());
				if (e instanceof Enemy) weaken(e);
			}
			// The bolts ring the caster, ahead and to either side, never on them: a vanilla bolt is a wide column, and from inside it
			// (the caster's own view) it would fill the screen.
			Vec3 f = g.flatDir();
			for (int k = -1; k <= 1; k++) {
				double a = k * BOLT_SPREAD + (random.nextDouble() - 0.5) * 0.3;
				Vec3 dir = new Vec3(f.x * Math.cos(a) - f.z * Math.sin(a), 0, f.x * Math.sin(a) + f.z * Math.cos(a));
				Vec3 at = end.add(dir.scale(FLANK * (0.9 + 0.2 * random.nextDouble())));
				VisualBolts.strike(level, Targets.ground(level, at.add(0, 0.5, 0)));
			}
		}

		private void weaken(LivingEntity e) {
			if (!e.isAlive()) return;
			e.addEffect(new MobEffectInstance(MobEffects.SLOWNESS, EFFECT_TICKS, SLOW_AMPLIFIER), ctx.caster());
			e.addEffect(new MobEffectInstance(MobEffects.WEAKNESS, EFFECT_TICKS, WEAKNESS_AMPLIFIER), ctx.caster());
		}

		// ---- the trench ----

		private void cutSegment(ServerLevel level, int k) {
			Vec3 mid = g.segmentMiddle(k);
			int sy = surface(level, Mth.floor(mid.x), Mth.floor(mid.z), mid.y, false);
			BlockPos origin = BlockPos.containing(mid.x, sy == Integer.MIN_VALUE ? mid.y - 1 : sy, mid.z);
			try (EditBatch b = ctx.kit().terrain().begin(ctx, origin, EditTier.ULTIMATE)) {
				for (Column c : g.trench()) {
					if (g.segment(c) != k) continue;
					int top = surface(level, c.x(), c.z(), g.heightAt(c.along()), c.along() < g.flatLength() - 1);
					if (top == Integer.MIN_VALUE) continue;
					for (int d = 0; d < ChidoriGeometry.DEPTH; d++) {
						BlockPos pos = new BlockPos(c.x(), top - d, c.z());
						if (removed.add(pos)) b.remove(pos);
					}
					scorch(level, b, new BlockPos(c.x(), top - ChidoriGeometry.DEPTH, c.z()));
				}
				for (Column c : g.lips()) {
					if (g.segment(c) != k) continue;
					int top = surface(level, c.x(), c.z(), g.heightAt(c.along()), false);
					if (top == Integer.MIN_VALUE) continue;
					for (int d = 0; d < ChidoriGeometry.DEPTH; d++) scorch(level, b, new BlockPos(c.x(), top - d, c.z()));
				}
			}
		}

		/** A scorched edge cell: sand fuses to glass; other solid ground turns to blackstone {@link ChidoriGeometry#BLACKSTONE} of the time. */
		private void scorch(ServerLevel level, EditBatch b, BlockPos pos) {
			if (removed.contains(pos) || !scorched.add(pos)) return;
			BlockState s = level.getBlockState(pos);
			if (ThunderSpear.sand(s)) {
				b.set(pos, Blocks.GLASS.defaultBlockState());
			} else if (ChidoriGeometry.blackstone(random) && solid(s)) {
				b.set(pos, Blocks.BLACKSTONE.defaultBlockState());
			}
		}

		// ---- the bowl ----

		private void bowl(ServerLevel level) {
			Vec3 end = g.end();
			int fx = Mth.floor(end.x);
			int fz = Mth.floor(end.z);
			int sy = surface(level, fx, fz, end.y, false);
			if (sy == Integer.MIN_VALUE) return;
			BlockPos origin = new BlockPos(fx, sy, fz);
			List<Cell> bowl = g.bowl();
			try (EditBatch b = ctx.kit().terrain().begin(ctx, origin, EditTier.ULTIMATE)) {
				Set<Cell> thrown = new HashSet<>();
				for (Cell c : ChidoriGeometry.rim(bowl, g.radius())) {
					if (thrown.size() >= ChidoriGeometry.DEBRIS) break;
					BlockPos pos = origin.offset(c.x(), c.y(), c.z());
					if (removed.contains(pos)) continue;
					if (b.launch(pos, ChidoriGeometry.debrisVelocity(c, random))) {
						thrown.add(c);
						removed.add(pos);
					}
				}
				for (Cell c : bowl) {
					BlockPos pos = origin.offset(c.x(), c.y(), c.z());
					if (!thrown.contains(c) && removed.add(pos)) b.remove(pos);
				}
				List<Cell> floor = new ArrayList<>(ChidoriGeometry.floor(bowl, g.radius()));
				Collections.shuffle(floor, random);
				for (Cell c : floor) scorch(level, b, origin.offset(c.x(), c.y(), c.z()));
			}
		}

		// ---- ground ----

		/**
		 * The ground's surface in column {@code (x, z)} under feet height {@code y}: the first solid block below the feet, searched
		 * down {@link #SURFACE_SCAN} blocks ({@code Integer.MIN_VALUE} if none, over a drop). With {@code bumps}, a block at the
		 * feet' own level counts too (a bump the charge stepped over is cut away with the trench); without, the trench never cuts
		 * into anything standing at the feet' level, such as the wall the charge ran up against.
		 */
		private static int surface(ServerLevel level, int x, int z, double y, boolean bumps) {
			BlockPos.MutableBlockPos m = new BlockPos.MutableBlockPos(x, Mth.floor(y - 0.01) + (bumps ? 1 : 0), z);
			for (int i = 0; i <= SURFACE_SCAN + 1; i++, m.move(0, -1, 0)) {
				if (m.getY() < level.getMinY()) break;
				if (solid(level.getBlockState(m))) return m.getY();
			}
			return Integer.MIN_VALUE;
		}

		private static boolean solid(BlockState s) {
			return !s.isAir() && s.isSolid() && s.getFluidState().isEmpty();
		}

		@Override
		public String toString() {
			return "ChidoriStrike.Charge[" + ctx.caster().getScoreboardName() + " " + g.start() + " -> " + g.end() + "]";
		}
	}
}
