package local.carter.office.magic.spell.fire;

import java.util.HashSet;
import java.util.List;
import java.util.Random;
import java.util.Set;

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
import local.carter.office.magic.spell.Spell;
import local.carter.office.magic.spell.SpellIds;
import local.carter.office.magic.spell.Targets;
import local.carter.office.magic.terrain.EditBatch;
import local.carter.office.magic.terrain.EditTier;

import net.minecraft.core.BlockPos;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.CollisionContext;

/**
 * Meteor (fire, slot 4, ultimate, hand signs, lingering): a burning rock called down from the sky.
 *
 * <p>The target is where the look ray first meets a block within {@link MeteorGeometry#RANGE}, moved down to the ground under it;
 * pointing at nothing, the ground {@link MeteorGeometry#MISS_AHEAD} ahead. The rock falls from {@link MeteorGeometry#HEIGHT}
 * above it (and {@link MeteorGeometry#SLANT} beyond it; less under a roof, so it never starts inside one) in a
 * straight line over {@link MeteorGeometry#FALL_TICKS} ticks ({@link MeteorGeometry}). On impact:
 * <ul>
 * <li>creatures within {@link MeteorGeometry#BLAST} that the blast can reach (nothing solid in between) take {@code 20 × P}
 * with linear falloff, then (if they live) are knocked {@link #KNOCK} away plus {@link #KNOCK_UP} up and burn for
 * {@link #FIRE_TICKS} ticks;</li>
 * <li>one ULTIMATE batch at the floor block under the impact leaves a permanent {@code Shapes.bowl(r, 0.6)} crater, with
 * {@code r = min(12, 7 + (level − 7))}: {@link MeteorGeometry#DEBRIS} rim blocks are {@code launch}ed outward as flying debris,
 * the rest is removed, and the bared floor turns 30 % magma block and 30 % blackstone.</li>
 * </ul>
 *
 * <p>Fx: {@code target} = the impact (on the ground), {@code dir} = the flat direction from the caster to it, {@code duration} =
 * {@link MeteorGeometry#FALL_TICKS}, {@code data} = {@code {radius, lift}} ({@code lift}: the clear share of the sky drop).
 */
public final class Meteor implements Spell {
	public static final float DAMAGE = 20;
	public static final double KNOCK = 2.5;
	public static final double KNOCK_UP = 0.8;
	public static final int FIRE_TICKS = 100;

	@Override
	public Identifier id() {
		return SpellIds.METEOR;
	}

	@Override
	public Element element() {
		return Element.FIRE;
	}

	@Override
	public int slot() {
		return Slots.ULTIMATE;
	}

	@Override
	public CastOutcome cast(CastContext ctx) {
		ServerLevel level = ctx.level();
		ServerPlayer p = ctx.caster();
		Vec3 impact = target(ctx);
		Vec3 toward = impact.subtract(p.position());
		Vec3 dir = toward.x * toward.x + toward.z * toward.z < 1.0E-4 ? FireWallGeometry.forward(p.getYRot()) : BarrageGeometry.flat(toward);
		double lift = lift(level, impact, MeteorGeometry.drop(dir));
		int radius = MeteorGeometry.radius(ctx.grimoireLevel());
		MeteorGeometry g = MeteorGeometry.of(impact, dir, lift, radius);
		if (!ActiveEffects.add(level, new Fall(ctx, g))) return CastOutcome.fizzle(CastService.BUSY_KEY);
		FxEvent fx = ctx.fx();
		return CastOutcome.cast(new FxEvent(fx.spell(), fx.casterId(), fx.origin(), dir, impact, fx.charge(), fx.power(), fx.seed(),
				MeteorGeometry.FALL_TICKS, new int[0], new float[] {radius, (float) lift}));
	}

	/** Where it lands: the ground under the look ray's first block within range, else the ground 32 ahead. */
	static Vec3 target(CastContext ctx) {
		ServerLevel level = ctx.level();
		Vec3 eye = ctx.eye();
		Vec3 look = ctx.look().normalize();
		BlockHitResult hit = level.clip(new ClipContext(eye, eye.add(look.scale(MeteorGeometry.RANGE)), ClipContext.Block.COLLIDER,
				ClipContext.Fluid.NONE, ctx.caster()));
		Vec3 aim;
		if (hit.getType() == HitResult.Type.MISS) {
			Vec3 dir = FlameDash.direction(look, ctx.caster().getYRot());
			aim = ctx.caster().position().add(dir.scale(MeteorGeometry.MISS_AHEAD)).add(0, 4, 0);
		} else {
			// Just off the face it struck (a wall's face → the ground in front of the wall), then down to the ground.
			aim = hit.getLocation().subtract(look.scale(0.3)).add(0, 0.5, 0);
		}
		Vec3 ground = Targets.ground(level, aim);
		return new Vec3(aim.x, ground.y, aim.z);
	}

	/** The clear share of the sky drop above {@code impact}, less two blocks of room (at least {@link MeteorGeometry#MIN_LIFT}). */
	static double lift(ServerLevel level, Vec3 impact, Vec3 drop) {
		Vec3 from = impact.add(0, 1, 0);
		Vec3 to = impact.add(drop);
		BlockHitResult hit = level.clip(new ClipContext(from, to, ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, CollisionContext.empty()));
		if (hit.getType() == HitResult.Type.MISS) return 1;
		return Math.max(MeteorGeometry.MIN_LIFT, (hit.getLocation().distanceTo(from) - 2) / drop.length());
	}

	/** The fall: nothing until the rock lands, then the blast and the crater. */
	private static final class Fall implements LingeringEffect {
		private final CastContext ctx;
		private final MeteorGeometry g;

		Fall(CastContext ctx, MeteorGeometry g) {
			this.ctx = ctx;
			this.g = g;
		}

		@Override
		public boolean tick(ServerLevel level, int age) {
			if (!MeteorGeometry.landed(age)) return true;
			blast(level);
			crater(level);
			return false;
		}

		private void blast(ServerLevel level) {
			Vec3 at = g.impact();
			Vec3 core = at.add(0, 1, 0);
			for (LivingEntity e : Targets.radius(ctx, at, MeteorGeometry.BLAST)) {
				if (!FireballBarrage.reaches(level, core, e.getBoundingBox())) continue;
				double f = MeteorGeometry.falloff(Math.sqrt(e.getBoundingBox().distanceToSqr(at)));
				Hurt.damage(ctx, e, (float) (DAMAGE * ctx.power() * f));
				if (e.isAlive()) {
					Hurt.knock(e, e.getBoundingBox().getCenter().subtract(at), KNOCK, KNOCK_UP);
					e.igniteForTicks(FIRE_TICKS);
				}
			}
		}

		private void crater(ServerLevel level) {
			Vec3 at = g.impact();
			BlockPos origin = BlockPos.containing(at.x, at.y - 0.5, at.z);
			List<Cell> bowl = g.bowl();
			Random r = new Random(ctx.seed());
			try (EditBatch b = ctx.kit().terrain().begin(ctx, origin, EditTier.ULTIMATE)) {
				// The rim first: flying debris.
				Set<Cell> thrown = new HashSet<>();
				for (Cell c : MeteorGeometry.rim(bowl, g.radius())) {
					if (thrown.size() >= MeteorGeometry.DEBRIS) break;
					if (b.launch(origin.offset(c.x(), c.y(), c.z()), MeteorGeometry.debrisVelocity(c, r))) thrown.add(c);
				}
				for (Cell c : bowl) {
					if (!thrown.contains(c)) b.remove(origin.offset(c.x(), c.y(), c.z()));
				}
				// The scorched floor.
				for (Cell f : MeteorGeometry.floor(bowl, g.radius())) {
					int kind = MeteorGeometry.floorKind(r);
					BlockPos pos = origin.offset(f.x(), f.y(), f.z());
					BlockState s = level.getBlockState(pos);
					if (kind == 0 || s.isAir() || !s.getFluidState().isEmpty()) continue;
					b.set(pos, (kind == 1 ? Blocks.MAGMA_BLOCK : Blocks.BLACKSTONE).defaultBlockState());
				}
			}
		}

		@Override
		public String toString() {
			return "Meteor.Fall[" + ctx.caster().getScoreboardName() + " at " + g.impact() + "]";
		}
	}
}
