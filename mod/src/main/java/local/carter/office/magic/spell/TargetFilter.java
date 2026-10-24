package local.carter.office.magic.spell;

import org.jspecify.annotations.Nullable;

import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.tags.ItemTags;
import net.minecraft.tags.TagKey;
import net.minecraft.world.entity.Display;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.ExperienceOrb;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.OwnableEntity;
import net.minecraft.world.entity.ambient.AmbientCreature;
import net.minecraft.world.entity.animal.AgeableWaterCreature;
import net.minecraft.world.entity.animal.Animal;
import net.minecraft.world.entity.animal.fish.WaterAnimal;
import net.minecraft.world.entity.animal.golem.AbstractGolem;
import net.minecraft.world.entity.boss.enderdragon.EnderDragon;
import net.minecraft.world.entity.boss.wither.WitherBoss;
import net.minecraft.world.entity.decoration.ArmorStand;
import net.minecraft.world.entity.decoration.HangingEntity;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.monster.Enemy;
import net.minecraft.world.entity.monster.warden.Warden;
import net.minecraft.world.entity.npc.Npc;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.entity.vehicle.VehicleEntity;
import net.minecraft.world.item.trading.Merchant;

/**
 * Who a spell may touch: the one predicate every {@code Targets} query, {@code Hurt} call, projectile sweep and control effect
 * goes through. Spells hurt and move <b>fair game</b> ({@link #isFairGame}): hostile mobs and animals (Carter's play-test ruling,
 * 2026-10-24: cows, pigs, sheep, chickens, bats, fish, squid…). They never touch a protected entity ({@link #isProtected}):
 * players (any), tamed pets and horses (anything with an owner), villagers and wandering traders, golems, anything with a custom
 * name (a name tag), armor stands, display and hanging entities, vehicles (saddled or harnessed animals too, ridden or not), or
 * anything a player rides. Push spells also move
 * items and XP orbs.
 *
 * <p>Control effects (pulls, lifts, roots, throws, chain hops; {@link #controllable}) take the same creatures, but never a boss
 * ({@link #isBoss}). Bosses still take every spell's damage.
 */
public final class TargetFilter {
	/** The conventional {@code #c:bosses} entity tag (Fabric's convention tags fill it when present). */
	public static final TagKey<EntityType<?>> BOSSES = TagKey.create(Registries.ENTITY_TYPE, Identifier.fromNamespaceAndPath("c", "bosses"));

	private TargetFilter() {}

	/** True when a spell from {@code caster} may hurt or target {@code e}: living fair game ({@link #isFairGame}) other than the caster. */
	public static boolean affects(@Nullable ServerPlayer caster, Entity e) {
		return e instanceof LivingEntity l && l.isAlive() && e != caster && isFairGame(e) && !isProtected(e);
	}

	/** True when a push spell from {@code caster} may move {@code e}: anything {@link #affects} plus items and XP orbs. */
	public static boolean pushable(@Nullable ServerPlayer caster, Entity e) {
		if (e.isRemoved()) return false;
		if (e instanceof ItemEntity || e instanceof ExperienceOrb) return true;
		return affects(caster, e);
	}

	/**
	 * True for the creatures spells are for: hostile mobs ({@link Enemy}) and animals (land animals, bats and other ambient
	 * creatures, fish, squid, dolphins and other water creatures). Allays, golems and anything else living are not.
	 */
	public static boolean isFairGame(Entity e) {
		return e instanceof Enemy || e instanceof Animal || e instanceof AmbientCreature || e instanceof WaterAnimal
				|| e instanceof AgeableWaterCreature;
	}

	/** True for entities no spell may hurt or push, whoever casts. */
	public static boolean isProtected(Entity e) {
		if (e instanceof Player || e.isSpectator()) return true;
		// Tamed pets, and tamed horses, donkeys, llamas and camels (an AbstractHorse is an OwnableEntity, owned once tamed).
		if (e instanceof OwnableEntity o && o.getOwnerReference() != null) return true;
		if (e instanceof Npc || e instanceof Merchant || e instanceof ArmorStand) return true;
		// Iron, snow and copper golems (a shulker is a golem by class, but hostile: fair game).
		if (e instanceof AbstractGolem && !(e instanceof Enemy)) return true;
		if (e instanceof LivingEntity && e.hasCustomName()) return true;
		if (e instanceof Display || e instanceof HangingEntity || e instanceof VehicleEntity) return true;
		// A saddled or harnessed animal is someone's mount: a vehicle, ridden or not (ruling, Task 25 review).
		if (e instanceof Animal a && (a.isSaddled() || a.getItemBySlot(EquipmentSlot.BODY).is(ItemTags.HARNESSES))) return true;
		return e.hasPassenger(p -> p instanceof Player);
	}

	/**
	 * True for a boss: {@code #c:bosses}, and the Wither, Ender Dragon and Warden by class in case the tag is empty (ruling). Spells
	 * hurt bosses as usual but never pull, lift, root, throw or chain onto them.
	 */
	public static boolean isBoss(Entity e) {
		return e.getType().builtInRegistryHolder().is(BOSSES) || e instanceof WitherBoss || e instanceof EnderDragon || e instanceof Warden;
	}

	/**
	 * True when a control effect (a pull, a lift, a root, a throw, a chain hop) from {@code caster} may move or seize {@code e}:
	 * items and XP orbs, and every creature {@link #affects} accepts (hostile mobs and animals), but never a boss ({@link #isBoss}).
	 */
	public static boolean controllable(@Nullable ServerPlayer caster, Entity e) {
		if (!pushable(caster, e)) return false;
		if (e instanceof ItemEntity || e instanceof ExperienceOrb) return true;
		return !isBoss(e);
	}
}
