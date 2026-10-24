package local.carter.office.mixin;

import java.util.List;

import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import local.carter.office.magic.terrain.TempBlockGuard;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ServerExplosion;

/** Explosions never drop a temporary block: it leaves the blast list and its original comes back ({@link TempBlockGuard}). */
@Mixin(ServerExplosion.class)
public abstract class ServerExplosionMixin {
	@Shadow
	@Final
	private ServerLevel level;

	@Inject(method = "interactWithBlocks", at = @At("HEAD"))
	private void office$spareTempBlocks(final List<BlockPos> targetBlocks, final CallbackInfo ci) {
		TempBlockGuard.spareFromExplosion(this.level, targetBlocks);
	}
}
