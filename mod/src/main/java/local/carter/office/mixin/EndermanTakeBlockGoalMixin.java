package local.carter.office.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.llamalad7.mixinextras.sugar.Local;

import local.carter.office.magic.terrain.TempBlockGuard;

import net.minecraft.core.BlockPos;
import net.minecraft.tags.TagKey;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;

/** Endermen never pick up a temporary block, whatever {@code #enderman_holdable} holds ({@link TempBlockGuard}). */
@Mixin(targets = "net.minecraft.world.entity.monster.Enderman$EndermanTakeBlockGoal")
public abstract class EndermanTakeBlockGoalMixin {
	@WrapOperation(method = "tick", at = @At(value = "INVOKE",
			target = "Lnet/minecraft/world/level/block/state/BlockState;is(Lnet/minecraft/tags/TagKey;)Z"))
	private boolean office$notTempBlocks(final BlockState state, final TagKey<Block> tag, final Operation<Boolean> original,
			@Local(name = "level") final Level level, @Local(name = "pos") final BlockPos pos) {
		return original.call(state, tag) && !TempBlockGuard.isTemp(level, pos);
	}
}
