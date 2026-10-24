package local.carter.office.gametest.mixin;

import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import com.llamalad7.mixinextras.injector.ModifyReturnValue;

import local.carter.office.gametest.PaletteRaceGuard;

import net.minecraft.world.level.chunk.LinearPalette;

/**
 * {@link PaletteRaceGuard}: a read that races the in-place growth of this palette ({@code values[i] = v; size++}, plain stores)
 * waits for the entry instead of throwing {@code MissingPaletteEntryException} (index seen before {@code size}) or returning
 * null (index seen before {@code values[i]}). The common path is untouched but for a null check.
 */
@Mixin(LinearPalette.class)
public abstract class LinearPaletteMixin<T> {
	@Shadow @Final private T[] values;
	@Shadow private int size;

	@Inject(method = "valueFor", at = @At(value = "NEW", target = "net/minecraft/world/level/chunk/MissingPaletteEntryException"), cancellable = true)
	private void officeTest$awaitGrowth(final int index, final CallbackInfoReturnable<T> cir) {
		if (index < 0 || index >= values.length) return;
		T value = PaletteRaceGuard.await(() -> index < size ? values[index] : null, index);
		if (value != null) cir.setReturnValue(value);
	}

	@ModifyReturnValue(method = "valueFor", at = @At("RETURN"))
	private T officeTest$awaitEntry(final T value, final int index) {
		if (value != null) return value;
		T seen = PaletteRaceGuard.await(() -> values[index], index);
		return seen != null ? seen : value;
	}
}
