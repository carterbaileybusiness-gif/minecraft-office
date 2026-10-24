package local.carter.office.magic.spell.wind;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
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
import local.carter.office.magic.spell.ProjectileSpec;
import local.carter.office.magic.spell.Spell;
import local.carter.office.magic.spell.SpellIds;
import local.carter.office.magic.spell.TargetFilter;
import local.carter.office.magic.spell.Targets;
import local.carter.office.magic.terrain.EditBatch;
import local.carter.office.magic.terrain.EditTier;

import net.minecraft.core.BlockPos;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.LeavesBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.VoxelShape;

/**
 * Blade Wind (Wind, slot 1, medium, lingering): crescent blades fly out in a fan ({@link BladeWindGeometry}: 3 over ±15° at a
 * tap, up to 7 over ±30° at full charge), speed 2.0, radius 0.6, piercing, for at most 14 ticks. Each foe a blade passes takes
 * {@code 5 × P} (once per cast, however many blades cross it). Blades slice through foliage and stop at the first other solid
 * block. Minor permanent change: the foliage in a blade's swath ({@link #cuttable}; the swath reaches from just above the blade
 * down to the caster's feet, so ground grass and flowers go too) is cut, in one MEDIUM batch per blade per 6-block stretch
 * (origin = the stretch's middle), at most 64 blocks per cast.
 *
 * <p>Side effects of a cut, all vanilla: natural leaves cut out of a canopy can leave leaves beyond them without a path to a
 * log, and those decay over the next minutes; vines, glow berries, snow layers and anything else that needs the cut block for
 * support pop off and drop as items. Player-placed (persistent) leaves are never cut, so hedges and leaf builds are safe.
 *
 * <p>The blades are flown by this spell's own lingering effect along {@link BladeWindGeometry#at} (that is {@code Projectiles.step}),
 * not by {@code Projectiles}: those stop at the first block collider, and leaves have one, so a blade could never cut through a
 * hedge. The effect does what {@code Projectiles} would for entities (sweep widened by the radius, {@link TargetFilter#affects},
 * at most {@link Targets#MAX_ENTITIES} hits per cast).
 *
 * <p>Ranks add blades (the size knob, up to {@link BladeWindGeometry#MAX_BLADES}). Forks: III {@link #WIDE} (the fan opens to
 * ±{@link BladeWindGeometry#WIDE_SPREAD}°) or {@link #LONG} (blades fly {@link BladeWindGeometry#LONG_TICKS} ticks); V
 * {@link #CLEAVE} (+80 % damage, two fewer blades) or {@link #TEMPEST} (what a blade hits is thrown {@link #TEMPEST_UP} up and
 * {@link #TEMPEST_BACK} back).
 *
 * <p>Fx: {@code duration} = the blades' flight (14), {@code target} = where the middle blade stops, {@code data[0]} = blade count, {@code data[1..n]} =
 * each blade's flight time in ticks (fractional; less than 14 where a solid block stops it), left to right. The client rebuilds
 * the blades with {@code BladeWindGeometry.specs(origin, dir, charge)}.
 */
public final class BladeWind implements Spell {
	public static final float DAMAGE = 5;
	public static final String WIDE = "wide";
	public static final String LONG = "long";
	public static final String CLEAVE = "cleave";
	public static final String TEMPEST = "tempest";
	public static final double TEMPEST_UP = 0.8;
	public static final double TEMPEST_BACK = 0.6;

	@Override
	public Identifier id() {
		return SpellIds.BLADE_WIND;
	}

	@Override
	public @Nullable Element element() {
		return Element.WIND;
	}

	@Override
	public int slot() {
		return Slots.MEDIUM_A;
	}

	/**
	 * Foliage a blade cuts and flies through: natural leaves (never persistent, player-placed ones), {@code #small_flowers}, pink
	 * petals, wildflowers, cactus flowers, short grass and ferns. Never a two-block plant (the terrain service refuses either
	 * half), chorus flowers, propagules, spore blossoms, flowering azalea or crops. The client uses the same test.
	 */
	public static boolean cuttable(BlockState s) {
		if (s.hasProperty(BlockStateProperties.DOUBLE_BLOCK_HALF)) return false;
		if (s.is(BlockTags.LEAVES)) return s.hasProperty(LeavesBlock.PERSISTENT) && !s.getValue(LeavesBlock.PERSISTENT);
		return s.is(BlockTags.SMALL_FLOWERS) || s.is(Blocks.PINK_PETALS) || s.is(Blocks.WILDFLOWERS) || s.is(Blocks.CACTUS_FLOWER)
				|| s.is(Blocks.SHORT_GRASS) || s.is(Blocks.FERN);
	}

	@Override
	public CastOutcome cast(CastContext ctx) {
		// Decide before any side effect: a full effect list fizzles for free.
		if (ActiveEffects.full(ctx.caster().getUUID())) return CastOutcome.fizzle(CastService.BUSY_KEY);
		List<ProjectileSpec> specs = BladeWindGeometry.specs(ctx.eye(), ctx.look(), ctx.charge(), ctx.rank());
		float[] stops = new float[specs.size()];
		for (int i = 0; i < stops.length; i++) stops[i] = (float) flightTime(ctx.level(), specs.get(i));
		Effect effect = new Effect(ctx, specs, stops);
		if (!ActiveEffects.add(ctx, effect)) return CastOutcome.fizzle(CastService.BUSY_KEY);
		float[] data = new float[stops.length + 1];
		data[0] = stops.length;
		System.arraycopy(stops, 0, data, 1, stops.length);
		int mid = specs.size() / 2;
		Vec3 end = BladeWindGeometry.at(specs.get(mid), stops[mid]);
		return CastOutcome.cast(ctx.fx().withTarget(end).withDuration(BladeWindGeometry.maxTicks(ctx.rank())).withData(data));
	}

	/**
	 * Ticks the blade flies before a solid block (anything with a collider that is not {@link #cuttable}) or an unloaded chunk
	 * stops it; {@code maxTicks} if nothing does. Fractional: the blade ends exactly on the block's face.
	 */
	static double flightTime(ServerLevel level, ProjectileSpec spec) {
		for (int t = 0; t < spec.maxTicks(); t++) {
			Vec3 a = BladeWindGeometry.at(spec, t);
			Vec3 b = BladeWindGeometry.at(spec, t + 1);
			if (!level.isLoaded(BlockPos.containing(b))) return t;
			double len = a.distanceTo(b);
			Double hit = BlockGetter.traverseBlocks(a, b, level, (lvl, pos) -> {
				BlockState s = lvl.getBlockState(pos);
				if (s.isAir() || cuttable(s)) return null;
				VoxelShape shape = s.getCollisionShape(lvl, pos, CollisionContext.empty());
				if (shape.isEmpty()) return null;
				BlockHitResult r = shape.clip(a, b, pos);
				return r == null ? null : a.distanceTo(r.getLocation()) / len;
			}, lvl -> null);
			if (hit != null) return t + hit;
		}
		return spec.maxTicks();
	}

	/** The blades in flight: moves them, hurts what they cross, cuts foliage. */
	static final class Effect implements LingeringEffect {
		private final CastContext ctx;
		private final List<ProjectileSpec> specs;
		private final List<BladeWindGeometry.Frame> frames = new ArrayList<>();
		private final float[] stops;
		/** Per blade: stretch index → its open batch. */
		private final List<Map<Integer, EditBatch>> batches = new ArrayList<>();
		/** Hit once per cast. */
		private final Set<Integer> hit = new HashSet<>();
		private int cuts;

		Effect(CastContext ctx, List<ProjectileSpec> specs, float[] stops) {
			this.ctx = ctx;
			this.specs = specs;
			this.stops = stops;
			for (ProjectileSpec s : specs) {
				frames.add(BladeWindGeometry.frame(s.velocity()));
				batches.add(new HashMap<>());
			}
		}

		@Override
		public boolean tick(ServerLevel level, int age) {
			boolean flying = false;
			for (int i = 0; i < specs.size(); i++) {
				double t0 = age - 1;
				if (t0 >= stops[i]) continue;
				double t1 = Math.min(age, stops[i]);
				Vec3 a = BladeWindGeometry.at(specs.get(i), t0);
				Vec3 b = BladeWindGeometry.at(specs.get(i), t1);
				strike(level, a, b);
				cut(level, i, a, b);
				if (t1 < stops[i]) flying = true;
			}
			return flying;
		}

		private void strike(ServerLevel level, Vec3 a, Vec3 b) {
			double r = BladeWindGeometry.RADIUS;
			boolean tempest = ctx.rank().is(TEMPEST);
			List<LivingEntity> crossed = new ArrayList<>();
			Map<LivingEntity, Double> at = new HashMap<>();
			for (LivingEntity e : level.getEntitiesOfClass(LivingEntity.class, new AABB(a, b).inflate(r),
					e -> TargetFilter.affects(ctx.caster(), e) && !hit.contains(e.getId()))) {
				AABB box = e.getBoundingBox().inflate(r);
				if (box.contains(a)) {
					at.put(e, 0.0);
				} else {
					Optional<Vec3> p = box.clip(a, b);
					if (p.isEmpty()) continue;
					at.put(e, a.distanceToSqr(p.get()));
				}
				crossed.add(e);
			}
			crossed.sort(Comparator.comparingDouble(at::get));
			for (LivingEntity e : crossed) {
				if (hit.size() >= Targets.MAX_ENTITIES) return;
				hit.add(e.getId());
				Hurt.damage(ctx, e, DAMAGE * ctx.power());
				// Tempest Blades: thrown up and back along the blade.
				if (tempest && e.isAlive() && TargetFilter.controllable(ctx.caster(), e)) Hurt.knock(e, b.subtract(a), TEMPEST_BACK, TEMPEST_UP);
			}
		}

		private void cut(ServerLevel level, int blade, Vec3 a, Vec3 b) {
			ProjectileSpec spec = specs.get(blade);
			for (BlockPos pos : BladeWindGeometry.cuts(a, b, frames.get(blade))) {
				if (cuts >= BladeWindGeometry.MAX_CUTS) return;
				if (!level.isLoaded(pos) || !cuttable(level.getBlockState(pos))) continue;
				int k = BladeWindGeometry.segment(BladeWindGeometry.along(spec, Vec3.atCenterOf(pos)));
				EditBatch batch = batches.get(blade).computeIfAbsent(k,
						s -> ctx.kit().terrain().begin(ctx, BlockPos.containing(BladeWindGeometry.segmentOrigin(spec, s)), EditTier.MEDIUM));
				if (batch.remove(pos)) cuts++;
			}
		}

		@Override
		public void end(ServerLevel level) {
			for (Map<Integer, EditBatch> m : batches) {
				for (EditBatch b : m.values()) b.close();
				m.clear();
			}
		}
	}
}
