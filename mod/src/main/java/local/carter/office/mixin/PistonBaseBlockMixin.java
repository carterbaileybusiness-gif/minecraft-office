package local.carter.office.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import local.carter.office.magic.terrain.TempBlockGuard;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.piston.PistonBaseBlock;
import net.minecraft.world.level.block.state.BlockState;

/** Temporary blocks are immovable, like obsidian: a piston never turns one into a moved real block ({@link TempBlockGuard}). */
@Mixin(PistonBaseBlock.class)
public abstract class PistonBaseBlockMixin {
	@Inject(method = "isPushable", at = @At("HEAD"), cancellable = true)
	private static void office$tempBlocksImmovable(final BlockState state, final Level level, final BlockPos pos, final Direction direction,
			final boolean allowDestroyable, final Direction connectionDirection, final CallbackInfoReturnable<Boolean> cir) {
		if (!state.isAir() && TempBlockGuard.immovable(level, pos, direction)) cir.setReturnValue(false);
	}
}
