package local.carter.office.magic.spell.shadow;

import java.util.Optional;
import java.util.Set;

import local.carter.office.magic.core.ChargeMath;
import local.carter.office.magic.core.Element;
import local.carter.office.magic.core.Slots;
import local.carter.office.magic.net.FxEvent;
import local.carter.office.magic.spell.CastContext;
import local.carter.office.magic.spell.CastOutcome;
import local.carter.office.magic.spell.Motion;
import local.carter.office.magic.spell.Spell;
import local.carter.office.magic.spell.SpellIds;
import local.carter.office.magic.spell.Targets;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.tags.FluidTags;
import net.minecraft.util.Mth;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.CollisionContext;

/**
 * Shadow Step (Shadow, slot 2, small): step out of the shadows behind your target.
 *
 * <p>The target is the first creature the look ray meets within {@link #range} ({@link #RANGE} at a tap, twice that at full
 * charge; {@link Targets#ray}, forgiveness
 * {@link #FORGIVENESS}) that the caster can actually see ({@link #seen}: aim assist never reaches round a wall). The caster lands
 * {@link #BEHIND} behind it, on the far side from where they stood, facing it; with its back to a wall, beside it
 * instead ({@link #behind}); behind something not on the ground, on the ground under that spot ({@link Targets#ground}). With no target the caster steps {@link #ahead(float)} along their flat facing ({@link #AHEAD}, twice that at full charge), stopping short of a wall. Either way
 * the move is {@link Motion#teleportSafe}: it never ends inside a block (it steps back along the path until the body fits).
 * It never lands on lava or deep water ({@link #unsafe}): then it fizzles ({@link #UNSAFE_KEY}) before anything happens, so it
 * costs nothing and starts no cooldown. Then the caster is invisible for {@code INVISIBLE_TICKS × (1 + charge)} ticks. No block changes, no damage.
 *
 * <p>Fx: {@code origin} = the caster's eye before the step, {@code dir} = the look, {@code target} = where their feet landed,
 * {@code entities} = {the target} if there was one, {@code duration} = the invisibility's ticks, {@code data} = {where the feet were
 * before the step, relative to {@code target}: x, y, z}.
 */
public final class ShadowStep implements Spell {
	public static final double RANGE = 16;
	public static final double FORGIVENESS = 1.5;
	public static final double BEHIND = 1.5;
	public static final double AHEAD = 8;
	public static final int INVISIBLE_TICKS = 20;

	/** How far the target ray reaches at {@code charge}: 16, 32 at full charge. */
	public static double range(float charge) {
		return RANGE * ChargeMath.size(charge);
	}

	/** How far a step with no target goes at {@code charge}: 8, 16 at full charge. */
	public static double ahead(float charge) {
		return AHEAD * ChargeMath.size(charge);
	}
	/** How far a landing keeps off a wall (a little over half a player's width). */
	static final double CLEARANCE = 0.35;
	/** The fizzle message when the only landing is lava or deep water. */
	public static final String UNSAFE_KEY = "magic.office.step_unsafe";

	@Override
	public Identifier id() {
		return SpellIds.SHADOW_STEP;
	}

	@Override
	public Element element() {
		return Element.SHADOW;
	}

	@Override
	public int slot() {
		return Slots.SMALL_B;
	}

	@Override
	public CastOutcome cast(CastContext ctx) {
		ServerPlayer p = ctx.caster();
		ServerLevel level = ctx.level();
		Vec3 from = p.position();
		Optional<LivingEntity> target = Targets.ray(ctx, range(ctx.charge()), FORGIVENESS).filter(e -> seen(level, ctx.eye(), e));
		Vec3 dest = target.map(e -> behind(level, from, e)).orElseGet(() -> ahead(level, p, ctx.look(), ahead(ctx.charge())));
		// Behind something airborne (a phantom, a mob mid-jump or knocked up), land on the ground under that spot, not in the air.
		if (target.isPresent() && !target.get().onGround()) dest = Targets.ground(level, dest);
		// Lava or deep water where it would land (or under it): no step, and nothing spent.
		Vec3 spot = Motion.fit(p, dest);
		if (unsafe(level, spot != null ? spot : from)) return CastOutcome.fizzle(UNSAFE_KEY);
		if (target.isPresent()) face(p, dest, target.get());
		Vec3 at = Motion.place(p, spot); // Motion.teleportSafe(p, dest), with the spot already found
		if (target.isPresent() && at.distanceToSqr(dest) > 1.0E-4 && at.distanceToSqr(from) > 1.0E-4) {
			// It stepped back from a spot that did not fit: turn to the target again from where it really landed (same, safe spot).
			face(p, at, target.get());
			p.teleportTo(level, at.x, at.y, at.z, Set.of(), p.getYRot(), p.getXRot(), true);
		}
		int hidden = Math.round(INVISIBLE_TICKS * ChargeMath.size(ctx.charge()));
		p.addEffect(new MobEffectInstance(MobEffects.INVISIBILITY, hidden, 0, false, false, true));
		// Where it left, relative to where it landed (floats cannot hold world coordinates far from the origin).
		Vec3 back = from.subtract(at);
		FxEvent fx = ctx.fx().withTarget(at).withDuration(hidden).withData((float) back.x, (float) back.y, (float) back.z);
		return CastOutcome.cast(target.isPresent() ? fx.withEntities(target.get().getId()) : fx);
	}

	/**
	 * Whether standing at {@code feet} means lava or deep water: lava in the body's two cells, or, under the feet, lava or water at
	 * least two blocks deep before the first block with a collider (searched down at most {@link Targets#GROUND_SCAN} blocks; one
	 * block of water over a floor is fine).
	 */
	static boolean unsafe(ServerLevel level, Vec3 feet) {
		BlockPos cell = BlockPos.containing(feet);
		if (level.getFluidState(cell).is(FluidTags.LAVA) || level.getFluidState(cell.above()).is(FluidTags.LAVA)) return true;
		BlockPos.MutableBlockPos m = cell.mutable();
		for (int i = 0; i < Targets.GROUND_SCAN && m.getY() >= level.getMinY(); i++, m.move(Direction.DOWN)) {
			BlockState s = level.getBlockState(m);
			if (s.getFluidState().is(FluidTags.LAVA)) return true;
			if (s.getFluidState().is(FluidTags.WATER)) return level.getFluidState(m.below()).is(FluidTags.WATER);
			if (!s.getCollisionShape(level, m).isEmpty()) return false;
		}
		return false;
	}

	/** Whether the caster's eye can see any of {@code e}'s middle, eyes or feet (no block collider in between). */
	static boolean seen(ServerLevel level, Vec3 eye, LivingEntity e) {
		AABB box = e.getBoundingBox();
		Vec3 mid = box.getCenter();
		return clear(level, eye, mid) || clear(level, eye, e.getEyePosition()) || clear(level, eye, new Vec3(mid.x, box.minY + 0.1, mid.z));
	}

	/**
	 * Where to land behind {@code e}: {@link #BEHIND} past its feet, away from {@code from} (the caster's feet) along the ground; if a
	 * wall stands closer than that behind it, just short of the wall. It never puts the landing on the far side of a wall.
	 */
	static Vec3 behind(ServerLevel level, Vec3 from, LivingEntity e) {
		Vec3 feet = e.position();
		Vec3 flat = new Vec3(feet.x - from.x, 0, feet.z - from.z);
		if (flat.lengthSqr() < 1.0E-6) flat = Vec3.directionFromRotation(0, e.getYRot());
		flat = new Vec3(flat.x, 0, flat.z).normalize();
		Vec3 body = feet.add(0, Math.min(0.9, e.getBbHeight() / 2), 0);
		// Behind it; with its back to a wall, beside it (left, then right); failing all three, as far behind as there is room.
		double need = e.getBbWidth() / 2 + CLEARANCE;
		Vec3 side = new Vec3(-flat.z, 0, flat.x);
		for (Vec3 d : new Vec3[] {flat, side, side.reverse()}) {
			double room = room(level, body, d, BEHIND);
			if (room >= need) return feet.add(d.scale(room));
		}
		return feet.add(flat.scale(room(level, body, flat, BEHIND)));
	}

	/** Where to step with no target: {@code want} along the flat facing (the look's own if level), stopping short of a wall. */
	static Vec3 ahead(ServerLevel level, ServerPlayer p, Vec3 look, double want) {
		Vec3 flat = new Vec3(look.x, 0, look.z);
		if (flat.lengthSqr() < 1.0E-4) flat = Vec3.directionFromRotation(0, p.getYRot());
		flat = new Vec3(flat.x, 0, flat.z).normalize();
		double room = room(level, p.position().add(0, 0.9, 0), flat, want);
		return p.position().add(flat.scale(room));
	}

	/** How far one can go from {@code at} along unit {@code dir}, up to {@code want}, keeping {@link #CLEARANCE} off a block. */
	static double room(ServerLevel level, Vec3 at, Vec3 dir, double want) {
		Vec3 to = at.add(dir.scale(want + CLEARANCE));
		BlockHitResult hit = level.clip(new ClipContext(at, to, ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, CollisionContext.empty()));
		if (hit.getType() == HitResult.Type.MISS) return want;
		return Mth.clamp(hit.getLocation().distanceTo(at) - CLEARANCE, 0, want);
	}

	/** Turns {@code p} (to stand at {@code feet}) to look at {@code e}'s eyes. */
	static void face(ServerPlayer p, Vec3 feet, LivingEntity e) {
		Vec3 eye = feet.add(0, p.getEyeHeight(), 0);
		Vec3 d = e.getEyePosition().subtract(eye);
		double horizontal = Math.sqrt(d.x * d.x + d.z * d.z);
		float yaw = Mth.wrapDegrees((float) (Mth.atan2(d.z, d.x) * Mth.RAD_TO_DEG) - 90F);
		float pitch = Mth.clamp((float) -(Mth.atan2(d.y, horizontal) * Mth.RAD_TO_DEG), -90F, 90F);
		p.setYRot(yaw);
		p.setXRot(pitch);
		p.setYHeadRot(yaw);
		p.setYBodyRot(yaw);
	}

	private static boolean clear(ServerLevel level, Vec3 a, Vec3 b) {
		return level.clip(new ClipContext(a, b, ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, CollisionContext.empty()))
				.getType() == HitResult.Type.MISS;
	}
}
