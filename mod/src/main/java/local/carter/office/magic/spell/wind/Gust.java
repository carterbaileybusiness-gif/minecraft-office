package local.carter.office.magic.spell.wind;

import java.util.List;

import org.jspecify.annotations.Nullable;

import local.carter.office.magic.core.Element;
import local.carter.office.magic.core.Slots;
import local.carter.office.magic.spell.CastContext;
import local.carter.office.magic.spell.CastOutcome;
import local.carter.office.magic.spell.Hurt;
import local.carter.office.magic.spell.Spell;
import local.carter.office.magic.spell.SpellIds;
import local.carter.office.magic.spell.TargetFilter;
import local.carter.office.magic.spell.Targets;

import net.minecraft.resources.Identifier;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.phys.Vec3;

/**
 * Gust (Wind, slot 0, small): a blast of wind in a cone ahead, range {@code 6 + 2 × charge}, half-angle 30°. Every foe in it
 * takes 1 damage and is thrown away from the caster ({@code 1.4 × P} level, {@code 0.4} up); items and XP orbs are blown along
 * too. No block changes.
 *
 * <p>Fx: {@code target} = the cone's tip (eye + look × range), {@code entities} = what it moved, {@code data[0]} = range.
 */
public final class Gust implements Spell {
	public static final double RANGE = 6;
	public static final double RANGE_PER_CHARGE = 2;
	public static final double HALF_ANGLE = 30;
	public static final float DAMAGE = 1;
	public static final double KNOCK = 1.4;
	public static final double UP = 0.4;

	@Override
	public Identifier id() {
		return SpellIds.GUST;
	}

	@Override
	public @Nullable Element element() {
		return Element.WIND;
	}

	@Override
	public int slot() {
		return Slots.SMALL_A;
	}

	public static double range(float charge) {
		return RANGE + RANGE_PER_CHARGE * charge;
	}

	@Override
	public CastOutcome cast(CastContext ctx) {
		double range = range(ctx.charge());
		Vec3 eye = ctx.eye();
		Vec3 look = ctx.look();
		double strength = KNOCK * ctx.power();
		List<Entity> caught = Targets.cone(ctx, range, HALF_ANGLE, true);
		int[] moved = new int[caught.size()];
		int n = 0;
		for (Entity e : caught) {
			if (e instanceof LivingEntity l && TargetFilter.affects(ctx.caster(), l)) Hurt.damage(ctx, l, DAMAGE);
			// Away from the caster (a blast, not a conveyor belt); something right on top of them goes along the look.
			Vec3 away = e.getBoundingBox().getCenter().subtract(eye);
			Vec3 flat = new Vec3(away.x, 0, away.z);
			if (flat.lengthSqr() < 0.04) flat = look;
			if (Hurt.knock(e, flat, strength, UP)) moved[n++] = e.getId();
		}
		int[] ids = java.util.Arrays.copyOf(moved, n);
		return CastOutcome.cast(ctx.fx().withTarget(eye.add(look.scale(range))).withEntities(ids).withData((float) range));
	}
}
