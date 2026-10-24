package local.carter.office.magic.spell;

import org.jspecify.annotations.Nullable;

import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Display;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.ExperienceOrb;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.OwnableEntity;
import net.minecraft.world.entity.animal.golem.IronGolem;
import net.minecraft.world.entity.decoration.ArmorStand;
import net.minecraft.world.entity.decoration.HangingEntity;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.npc.villager.AbstractVillager;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.entity.vehicle.VehicleEntity;

/**
 * Who a spell may touch. Spells never hurt or push players (any), tamed pets (anything with an owner), villagers and
 * wandering traders, iron golems, armor stands, display entities, hanging entities, vehicles, or anything a player rides.
 * Push spells also move items and XP orbs. Every {@code Targets} query, {@code Hurt} call and projectile sweep goes through here.
 */
public final class TargetFilter {
	private TargetFilter() {}

	/** True when a spell from {@code caster} may hurt or target {@code e}: a living, non-protected entity other than the caster. */
	public static boolean affects(@Nullable ServerPlayer caster, Entity e) {
		return e instanceof LivingEntity l && l.isAlive() && e != caster && !isProtected(e);
	}

	/** True when a push spell from {@code caster} may move {@code e}: anything {@link #affects} plus items and XP orbs. */
	public static boolean pushable(@Nullable ServerPlayer caster, Entity e) {
		if (e.isRemoved()) return false;
		if (e instanceof ItemEntity || e instanceof ExperienceOrb) return true;
		return affects(caster, e);
	}

	/** True for entities no spell may hurt or push, whoever casts. */
	public static boolean isProtected(Entity e) {
		if (e instanceof Player || e.isSpectator()) return true;
		if (e instanceof OwnableEntity o && o.getOwnerReference() != null) return true;
		if (e instanceof AbstractVillager || e instanceof IronGolem || e instanceof ArmorStand) return true;
		if (e instanceof Display || e instanceof HangingEntity || e instanceof VehicleEntity) return true;
		return e.hasPassenger(p -> p instanceof Player);
	}
}
