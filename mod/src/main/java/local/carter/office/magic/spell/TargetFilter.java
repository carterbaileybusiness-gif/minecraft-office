package local.carter.office.magic.spell;

import org.jspecify.annotations.Nullable;

import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.tags.TagKey;
import net.minecraft.world.entity.Display;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.ExperienceOrb;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.OwnableEntity;
import net.minecraft.world.entity.animal.golem.IronGolem;
import net.minecraft.world.entity.boss.enderdragon.EnderDragon;
import net.minecraft.world.entity.boss.wither.WitherBoss;
import net.minecraft.world.entity.decoration.ArmorStand;
import net.minecraft.world.entity.decoration.HangingEntity;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.monster.Enemy;
import net.minecraft.world.entity.monster.warden.Warden;
import net.minecraft.world.entity.npc.villager.AbstractVillager;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.entity.vehicle.VehicleEntity;

/**
 * Who a spell may touch. Spells never hurt or push players (any), tamed pets (anything with an owner), villagers and
 * wandering traders, iron golems, armor stands, display entities, hanging entities, vehicles, or anything a player rides.
 * Push spells also move items and XP orbs. Every {@code Targets} query, {@code Hurt} call and projectile sweep goes through here.
 *
 * <p>Control effects (pulls, lifts, roots, chain hops) are narrower still ({@link #controllable}): hostile mobs, items and XP
 * orbs only, and never a boss ({@link #isBoss}). Bosses still take every spell's damage.
 */
public final class TargetFilter {
	/** The conventional {@code #c:bosses} entity tag (Fabric's convention tags fill it when present). */
	public static final TagKey<EntityType<?>> BOSSES = TagKey.create(Registries.ENTITY_TYPE, Identifier.fromNamespaceAndPath("c", "bosses"));

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

	/**
	 * True for a boss: {@code #c:bosses}, and the Wither, Ender Dragon and Warden by class in case the tag is empty (ruling). Spells
	 * hurt bosses as usual but never pull, lift, root or chain onto them.
	 */
	public static boolean isBoss(Entity e) {
		return e.getType().builtInRegistryHolder().is(BOSSES) || e instanceof WitherBoss || e instanceof EnderDragon || e instanceof Warden;
	}

	/**
	 * True when a control effect (a pull, a lift, a root, a chain hop) from {@code caster} may move or seize {@code e}: items and
	 * XP orbs, and hostile mobs ({@link Enemy}) that {@link #affects} accepts, but never a boss ({@link #isBoss}). The lanes'
	 * ruling: friends' livestock, golems, allays, pets, villagers and players are never dragged, lifted or chained.
	 */
	public static boolean controllable(@Nullable ServerPlayer caster, Entity e) {
		if (!pushable(caster, e)) return false;
		if (e instanceof ItemEntity || e instanceof ExperienceOrb) return true;
		return e instanceof Enemy && affects(caster, e) && !isBoss(e);
	}
}
