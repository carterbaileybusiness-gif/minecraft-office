package local.carter.office.magic.spell;

import org.jspecify.annotations.Nullable;

import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;

/** Where a projectile hit: exactly one of {@code entity} (one that passed {@link TargetFilter#affects}) or {@code block}. */
public record ProjectileHit(Vec3 pos, @Nullable LivingEntity entity, @Nullable BlockHitResult block) {}
