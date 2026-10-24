package local.carter.office.client.dock;

import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import com.mojang.blaze3d.platform.NativeImage;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.renderpearl.api.textures.AddressMode;
import com.mojang.renderpearl.api.textures.FilterMode;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import local.carter.office.OfficeMod;
import local.carter.office.client.pointer.PointerPort;

import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.resources.Identifier;

/**
 * The Dock's textures: the real app icons the helper sends (NSWorkspace's icon for the app, as PNG), loaded once each
 * with smooth filtering, and built-in glyphs for YouTube, "All apps" and the bar itself ({@code tools/make_dock_textures.py}).
 * Render / client thread.
 */
public final class DockIcons {
	private static final Logger LOGGER = LoggerFactory.getLogger("office");
	public static final Identifier BAR = OfficeMod.id("textures/misc/dock_bar.png");
	public static final Identifier YOUTUBE = OfficeMod.id("textures/misc/dock_youtube.png");
	public static final Identifier ALL_APPS = OfficeMod.id("textures/misc/dock_allapps.png");

	private static final Map<String, Identifier> LOADED = new HashMap<>();
	private static final Set<String> BROKEN = new HashSet<>();

	private DockIcons() {}

	/** An icon texture with linear filtering (a 128 px icon drawn small stays smooth). */
	private static final class IconTexture extends DynamicTexture {
		IconTexture(final String bundle, final NativeImage image) {
			super(() -> "Office Dock icon " + bundle, image);
			this.sampler = RenderSystem.getSamplerCache().getSampler(AddressMode.CLAMP_TO_EDGE, AddressMode.CLAMP_TO_EDGE, FilterMode.LINEAR,
					FilterMode.LINEAR, false);
		}
	}

	/** The texture for this item, or null while its icon hasn't arrived (it is asked for). */
	public static @Nullable Identifier texture(final DockItem item, final PointerPort port) {
		if (item.allApps()) {
			return ALL_APPS;
		}
		String bundle = item.bundle();
		return bundle == null ? YOUTUBE : app(bundle, port);
	}

	/** An app's icon texture, or null while it hasn't arrived (it is asked for) or couldn't be read. */
	public static @Nullable Identifier app(final String bundle, final PointerPort port) {
		Identifier id = LOADED.get(bundle);
		if (id != null || BROKEN.contains(bundle)) {
			return id;
		}
		byte[] png = port.appIcon(bundle);
		if (png == null) {
			port.requestIcons(List.of(bundle));
			return null;
		}
		try {
			NativeImage image = NativeImage.read(png);
			id = OfficeMod.id("dock/" + bundle.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9._-]", "_"));
			Minecraft.getInstance().getTextureManager().register(id, new IconTexture(bundle, image));
			LOADED.put(bundle, id);
			return id;
		} catch (Exception e) {
			LOGGER.debug("Office Dock: icon of {} unreadable: {}", bundle, e.toString());
			BROKEN.add(bundle);
			return null;
		}
	}
}
