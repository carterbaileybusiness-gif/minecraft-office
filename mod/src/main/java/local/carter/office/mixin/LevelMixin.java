package local.carter.office.mixin;

import org.jspecify.annotations.Nullable;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import local.carter.office.magic.terrain.TempBlockGuard;

import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.Level;

/** {@code Level.destroyBlock} (the wither, mods, spells) on a temporary block: no drops, the original comes back ({@link TempBlockGuard}). */
@Mixin(Level.class)
public abstract class LevelMixin {
	@Inject(method = "destroyBlock(Lnet/minecraft/core/BlockPos;ZLnet/minecraft/world/entity/Entity;I)Z", at = @At("HEAD"), cancellable = true)
	private void office$breakTempBlock(final BlockPos pos, final boolean dropResources, final @Nullable Entity breaker, final int updateLimit,
			final CallbackInfoReturnable<Boolean> cir) {
		if (TempBlockGuard.breakTemp((Level) (Object) this, pos, breaker, true)) cir.setReturnValue(true);
	}
}
