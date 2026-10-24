package local.carter.office.magic.spell.fire;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
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
import local.carter.office.magic.spell.TargetFilter;
import local.carter.office.magic.spell.Targets;
import local.carter.office.magic.terrain.EditBatch;
import local.carter.office.magic.terrain.EditTier;

import net.minecraft.core.BlockPos;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.attribute.EnvironmentAttributes;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.projectile.Projectile;
import net.minecraft.world.entity.projectile.arrow.ThrownTrident;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.IceBlock;
import net.minecraft.world.level.block.SnowLayerBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

/**
 * Fire Wall (fire, slot 1, medium, lingering): a curtain of fire on the ground {@link FireWallGeometry#AHEAD} ahead of the caster,
 * across the aim, {@link FireWallGeometry#width} wide (5, or 7 at charge ≥ 0.5), 3 tall, for {@code 100 + 60 × charge} ticks.
 * Every {@link #PULSE_TICKS} ticks every creature in it takes {@link #DAMAGE} and burns for {@link #FIRE_TICKS} ticks; every tick
 * projectiles about to enter it burn up (discarded; thrown tridents are spared, they are a player's weapon, not ammunition).
 * Its one minor permanent change, in one MEDIUM batch at the wall's foot: snow layers in the footprint melt to air and ice to
 * water (to air where water evaporates). It never places fire blocks.
 *
 * <p>Fx: {@code target} = the wall's foot (middle of its width, on the ground), {@code dir} = the caster's flat facing,
 * {@code duration} = the wall's life in ticks, {@code data} = {@code {width, height}} (also rebuilt from {@code charge} by
 * {@link FireWallGeometry#of}).
 */
public final class FireWall implements Spell {
	public static final int PULSE_TICKS = 10;
	public static final float DAMAGE = 2;
	public static final int FIRE_TICKS = 80;
	/** How far below the wall's foot the melt reaches (snow on a frozen lake, the ice under it). */
	public static final int MELT_BELOW = 2;
	/** Where the ground search starts above the aim point (so a step up still counts as ground). */
	private static final double GROUND_LIFT = 1.5;

	@Override
	public Identifier id() {
		return SpellIds.FIRE_WALL;
	}

	@Override
	public Element element() {
		return Element.FIRE;
	}

	@Override
	public int slot() {
		return Slots.MEDIUM_A;
	}

	@Override
	public CastOutcome cast(CastContext ctx) {
		ServerLevel level = ctx.level();
		Vec3 forward = FireWallGeometry.forward(ctx.caster().getYRot());
		Vec3 aim = FireWallGeometry.aimPoint(ctx.caster().position(), forward);
		Vec3 base = Targets.ground(level, aim.add(0, GROUND_LIFT, 0));
		FireWallGeometry g = FireWallGeometry.of(base, forward, ctx.charge());
		Burning effect = new Burning(ctx, g);
		if (!ActiveEffects.add(level, effect)) return CastOutcome.fizzle(CastService.BUSY_KEY);
		melt(ctx, g);
		FxEvent fx = ctx.fx();
		return CastOutcome.cast(new FxEvent(fx.spell(), fx.casterId(), fx.origin(), g.forward(), base, fx.charge(), fx.power(), fx.seed(),
				g.duration(), new int[0], new float[] {(float) g.width(), (float) g.height()}));
	}

	/** Snow layers to air, ice to water, in the wall's footprint from {@link #MELT_BELOW} under its foot to its top. */
	private static void melt(CastContext ctx, FireWallGeometry g) {
		ServerLevel level = ctx.level();
		BlockPos origin = BlockPos.containing(g.base());
		List<BlockPos> snow = new ArrayList<>();
		List<BlockPos> ice = new ArrayList<>();
		for (Cell c : g.footprint()) {
			for (int dy = -MELT_BELOW; dy < (int) Math.ceil(g.height()); dy++) {
				BlockPos pos = origin.offset(c.x(), dy, c.z());
				BlockState s = level.getBlockState(pos);
				if (s.getBlock() instanceof SnowLayerBlock) snow.add(pos);
				else if (s.getBlock() instanceof IceBlock) ice.add(pos);
			}
		}
		if (snow.isEmpty() && ice.isEmpty()) return;
		try (EditBatch batch = ctx.kit().terrain().begin(ctx, origin, EditTier.MEDIUM)) {
			for (BlockPos pos : snow) batch.remove(pos);
			for (BlockPos pos : ice) {
				boolean evaporates = level.environmentAttributes().getValue(EnvironmentAttributes.WATER_EVAPORATES, pos);
				batch.set(pos, evaporates ? Blocks.AIR.defaultBlockState() : IceBlock.meltsInto());
			}
		}
	}

	/** The burning zone: pulses on creatures inside, burns up projectiles crossing it. */
	private static final class Burning implements LingeringEffect {
		private final CastContext ctx;
		private final FireWallGeometry g;
		private final AABB near;
		private final Set<Integer> struck = new HashSet<>();

		Burning(CastContext ctx, FireWallGeometry g) {
			this.ctx = ctx;
			this.g = g;
			// Fast projectiles are checked a tick ahead, so look a little beyond the wall.
			this.near = g.bounds().inflate(4);
		}

		@Override
		public boolean tick(ServerLevel level, int age) {
			if (age > g.duration()) return false;
			burnProjectiles(level);
			if ((age - 1) % PULSE_TICKS == 0) burnCreatures(level);
			return age < g.duration();
		}

		private void burnCreatures(ServerLevel level) {
			List<LivingEntity> inside = level.getEntitiesOfClass(LivingEntity.class, g.bounds(),
					e -> TargetFilter.affects(ctx.caster(), e) && g.touches(e.getBoundingBox()));
			for (LivingEntity e : inside) {
				// At most MAX_ENTITIES different creatures over the wall's whole life (the per-cast entity budget).
				if (!struck.contains(e.getId())) {
					if (struck.size() >= Targets.MAX_ENTITIES) continue;
					struck.add(e.getId());
				}
				Hurt.damage(ctx, e, DAMAGE);
				if (e.isAlive()) e.igniteForTicks(FIRE_TICKS);
			}
		}

		private void burnProjectiles(ServerLevel level) {
			for (Projectile p : level.getEntitiesOfClass(Projectile.class, near,
					p -> !(p instanceof ThrownTrident) && !p.isRemoved() && g.crosses(p.getBoundingBox(), p.getDeltaMovement()))) {
				p.discard();
			}
		}

		@Override
		public String toString() {
			return "FireWall[" + g.base() + " w=" + g.width() + " t=" + g.duration() + "]";
		}
	}
}
