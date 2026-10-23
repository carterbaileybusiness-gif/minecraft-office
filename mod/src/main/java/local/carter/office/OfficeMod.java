package local.carter.office;

import net.fabricmc.api.ModInitializer;
import net.minecraft.resources.Identifier;

public class OfficeMod implements ModInitializer {
	public static final String MOD_ID = "office";

	public static Identifier id(String path) {
		return Identifier.fromNamespaceAndPath(MOD_ID, path);
	}

	@Override
	public void onInitialize() {
	}
}
