package local.carter.office.mixin.client;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

import net.minecraft.client.KeyMapping;

/**
 * Walk while dragging (PointerClient): a key's down state written directly. {@code KeyMapping.setDown} on a toggle key
 * (Sneak: Toggle) flips it on every call, so pressing it each tick would make the player bob in and out of sneaking.
 */
@Mixin(KeyMapping.class)
public interface KeyMappingAccessor {
	@Accessor("isDown")
	void office$setDownRaw(boolean down);
}
