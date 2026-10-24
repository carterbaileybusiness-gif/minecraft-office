package local.carter.office.mixin;

import org.jspecify.annotations.Nullable;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;

import local.carter.office.magic.terrain.LaunchedDebris;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.item.FallingBlockEntity;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.level.ItemLike;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;

/**
 * Spell debris ({@link LaunchedDebris}): its landing is checked like a terrain edit before vanilla places it (refused: it
 * vanishes), and it never drops an item. Plain falling blocks (sand, gravel, anvils) are untouched.
 */
@Mixin(FallingBlockEntity.class)
public abstract class FallingBlockEntityMixin {
	@WrapOperation(method = "tick", at = @At(value = "INVOKE",
			target = "Lnet/minecraft/world/level/Level;setBlockAndUpdate(Lnet/minecraft/core/BlockPos;Lnet/minecraft/world/level/block/state/BlockState;)Z"))
	private boolean office$landDebris(final Level level, final BlockPos pos, final BlockState state, final Operation<Boolean> original) {
		FallingBlockEntity self = (FallingBlockEntity) (Object) this;
		if (!LaunchedDebris.isDebris(self)) return original.call(level, pos, state);
		return LaunchedDebris.land(self, level, pos, state, () -> original.call(level, pos, state));
	}

	@WrapOperation(method = "tick", at = @At(value = "INVOKE",
			target = "Lnet/minecraft/world/entity/item/FallingBlockEntity;spawnAtLocation(Lnet/minecraft/server/level/ServerLevel;Lnet/minecraft/world/level/ItemLike;)Lnet/minecraft/world/entity/item/ItemEntity;"))
	private @Nullable ItemEntity office$noDebrisDrops(final FallingBlockEntity self, final ServerLevel level, final ItemLike item,
			final Operation<ItemEntity> original) {
		return LaunchedDebris.isDebris(self) ? null : original.call(self, level, item);
	}
}
