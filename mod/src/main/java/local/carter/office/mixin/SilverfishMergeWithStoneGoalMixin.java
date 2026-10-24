package local.carter.office.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.llamalad7.mixinextras.sugar.Local;

import local.carter.office.magic.terrain.TempBlockGuard;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.LevelAccessor;
import net.minecraft.world.level.block.state.BlockState;

/** Silverfish never infest a temporary block: infested stone would outlive the spell as a real block ({@link TempBlockGuard}). */
@Mixin(targets = "net.minecraft.world.entity.monster.Silverfish$SilverfishMergeWithStoneGoal")
public abstract class SilverfishMergeWithStoneGoalMixin {
	@WrapOperation(method = "start", at = @At(value = "INVOKE",
			target = "Lnet/minecraft/world/level/block/InfestedBlock;isCompatibleHostBlock(Lnet/minecraft/world/level/block/state/BlockState;)Z"))
	private boolean office$notTempBlocks(final BlockState state, final Operation<Boolean> original,
			@Local(name = "level") final LevelAccessor level, @Local(name = "pos") final BlockPos pos) {
		return original.call(state) && !TempBlockGuard.isTemp(level, pos);
	}
}
