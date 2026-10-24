package local.carter.office.gametest.mixin;

import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import local.carter.office.gametest.PaletteRaceGuard;

import net.minecraft.util.CrudeIncrementalIntIdentityHashBiMap;
import net.minecraft.world.level.chunk.HashMapPalette;

/**
 * {@link PaletteRaceGuard}: a read that races the in-place growth of this palette (an unsynchronised
 * {@code CrudeIncrementalIntIdentityHashBiMap.add}) waits for the entry instead of throwing {@code MissingPaletteEntryException}.
 */
@Mixin(HashMapPalette.class)
public abstract class HashMapPaletteMixin<T> {
	@Shadow @Final private CrudeIncrementalIntIdentityHashBiMap<T> values;

	@Inject(method = "valueFor", at = @At(value = "NEW", target = "net/minecraft/world/level/chunk/MissingPaletteEntryException"), cancellable = true)
	private void officeTest$awaitGrowth(final int index, final CallbackInfoReturnable<T> cir) {
		T value = PaletteRaceGuard.await(() -> values.byId(index), index);
		if (value != null) cir.setReturnValue(value);
	}
}
