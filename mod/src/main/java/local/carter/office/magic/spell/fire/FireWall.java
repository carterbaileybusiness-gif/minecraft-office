package local.carter.office.magic.spell.fire;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.jspecify.annotations.Nullable;
import org.slf4j.LoggerFactory;

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
import net.minecraft.tags.FluidTags;
import net.minecraft.world.attribute.EnvironmentAttributes;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.projectile.FireworkRocketEntity;
import net.minecraft.world.entity.projectile.Projectile;
import net.minecraft.world.entity.projectile.arrow.AbstractArrow;
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
 * A creature that touches it (or is thrown through it between two ticks) takes {@link #DAMAGE} and burns for {@link #FIRE_TICKS}
 * ticks at once, then again every {@link #PULSE_TICKS} ticks while it stays in. Every tick, projectiles about to enter it burn up
 * (discarded), except thrown tridents (a player's weapon, not ammunition), arrows already stuck in the ground and rockets boosting
 * an elytra. Cast on water (its foot cell or the cell under it) it fails before doing anything.
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
	/** How far below the wall's foot the melt reaches (the ice a snow layer lies on, a frozen pond's surface). */
	public static final int MELT_BELOW = 1;
	/** Where the ground search starts above the aim point (so a step up still counts as ground). */
	private static final double GROUND_LIFT = 1.5;

	/** {@code AbstractArrow.isInGround} and {@code FireworkRocketEntity.isAttachedToEntity} are not public; 26.x runs unobfuscated. */
	private static final @Nullable Method IN_GROUND = method(AbstractArrow.class, "isInGround");
	private static final @Nullable Method ATTACHED = method(FireworkRocketEntity.class, "isAttachedToEntity");

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
		BlockPos foot = BlockPos.containing(base);
		// Fire on water fails, before any side effect (so nothing is spent).
		if (level.getFluidState(foot).is(FluidTags.WATER) || level.getFluidState(foot.below()).is(FluidTags.WATER)) {
			return CastOutcome.fizzle(CastService.SPELL_FAILED_KEY);
		}
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

	/**
	 * Whether the wall burns up {@code p}: not a thrown trident, not an arrow already stuck in the ground, not a firework rocket
	 * boosting someone's elytra. The client fx uses it too, for the puffs.
	 */
	public static boolean burns(Projectile p) {
		if (p instanceof ThrownTrident) return false;
		if (p instanceof AbstractArrow a && flag(IN_GROUND, a)) return false;
		return !(p instanceof FireworkRocketEntity r && flag(ATTACHED, r));
	}

	private static @Nullable Method method(Class<?> owner, String name) {
		try {
			Method m = owner.getDeclaredMethod(name);
			m.setAccessible(true);
			return m;
		} catch (ReflectiveOperationException | RuntimeException e) {
			LoggerFactory.getLogger("office").warn("[office] {}.{} not found; Fire Wall may burn up stuck arrows or elytra rockets",
					owner.getSimpleName(), name, e);
			return null;
		}
	}

	private static boolean flag(@Nullable Method m, Object target) {
		try {
			return m != null && (boolean) m.invoke(target);
		} catch (ReflectiveOperationException | RuntimeException e) {
			return false;
		}
	}

	/**
	 * The burning zone. Every tick it looks for creatures in it, or that passed through it since the last tick (so one thrown
	 * through at speed is still caught): each burns on first contact, then again every {@link #PULSE_TICKS} ticks while it stays
	 * in. Every tick it burns up projectiles about to fly into it.
	 */
	private static final class Burning implements LingeringEffect {
		private final CastContext ctx;
		private final FireWallGeometry g;
		private final AABB near;
		/** Creature id → the age from which it may burn again; its keys are the creatures this cast touched. */
		private final Map<Integer, Integer> nextHit = new HashMap<>();

		Burning(CastContext ctx, FireWallGeometry g) {
			this.ctx = ctx;
			this.g = g;
			// Fast creatures and projectiles are swept along their motion, so look a little beyond the wall.
			this.near = g.bounds().inflate(FireWallGeometry.MAX_SWEEP);
		}

		@Override
		public boolean tick(ServerLevel level, int age) {
			if (age > g.duration()) return false;
			burnProjectiles(level);
			burnCreatures(level, age);
			return age < g.duration();
		}

		private void burnCreatures(ServerLevel level, int age) {
			for (LivingEntity e : level.getEntitiesOfClass(LivingEntity.class, near, e -> TargetFilter.affects(ctx.caster(), e) && inWall(e))) {
				Integer next = nextHit.get(e.getId());
				if (next == null) {
					// At most MAX_ENTITIES different creatures over the wall's whole life (the per-cast entity budget).
					if (nextHit.size() >= Targets.MAX_ENTITIES) continue;
				} else if (age < next) {
					continue;
				}
				nextHit.put(e.getId(), age + PULSE_TICKS);
				Hurt.damage(ctx, e, DAMAGE);
				if (e.isAlive()) e.igniteForTicks(FIRE_TICKS);
			}
		}

		/** In the slab now, or crossed it on the way here this tick (a teleport across it is no crossing: {@link FireWallGeometry#sweeps}). */
		private boolean inWall(LivingEntity e) {
			AABB box = e.getBoundingBox();
			if (g.touches(box)) return true;
			Vec3 back = new Vec3(e.xo - e.getX(), e.yo - e.getY(), e.zo - e.getZ());
			return FireWallGeometry.sweeps(back) && g.crosses(box, back);
		}

		private void burnProjectiles(ServerLevel level) {
			for (Projectile p : level.getEntitiesOfClass(Projectile.class, near,
					p -> !p.isRemoved() && burns(p) && g.crosses(p.getBoundingBox(), p.getDeltaMovement()))) {
				p.discard();
			}
		}

		@Override
		public String toString() {
			return "FireWall[" + g.base() + " w=" + g.width() + " t=" + g.duration() + "]";
		}
	}
}
