// Adapted from Wildercord (MIT) https://github.com/defnotean/wildercord @9f5a21fe7964097492cd92ac64e4c4e05ef5236a
package local.carter.office.client.magic.fx;

import java.lang.reflect.Method;

import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import net.fabricmc.loader.api.FabricLoader;

/**
 * Magic under an Iris shader pack (Wildercord's ShaderCompat). A shader pack replaces vanilla's particle rendering, so our own
 * blend pipelines ({@link LightPipeline#GLOW}, {@link LightPipeline#DARK}) may draw wrong or not at all there; while a pack is in
 * use, every magic quad goes on vanilla's translucent particle layer instead ({@link Path#PLAIN}): light as its colour with its
 * brightness in alpha, darkness as the dark shade it would leave. Without a pack (or without Iris) it is the additive light
 * ({@link Path#ADDITIVE}).
 *
 * <p>Iris is found by reflection only ({@code net.irisshaders.iris.api.v0.IrisApi.getInstance().isShaderPackInUse()}): no
 * compile or runtime dependency, and if Iris is missing or its API changes, the check quietly says "no pack" for good. The
 * state is re-read every frame ({@link #refresh}, from the magic particle group), and the path is logged once each time it
 * changes.
 */
public final class ShaderCompat {
	private static final Logger LOGGER = LoggerFactory.getLogger("office");
	private static final String IRIS_API = "net.irisshaders.iris.api.v0.IrisApi";

	/** How magic light is drawn. */
	public enum Path {
		/** Our additive (and subtractive) pipelines: brightness premultiplied into the colour, alpha full. */
		ADDITIVE,
		/** Vanilla's translucent particle layer, for shader packs: the colour as is, brightness in alpha. */
		PLAIN
	}

	private ShaderCompat() {}

	/** The decision (pure): a shader pack in use means the plain path. */
	public static Path path(boolean shaderPackInUse) {
		return shaderPackInUse ? Path.PLAIN : Path.ADDITIVE;
	}

	/**
	 * A light's vertex colour on {@code path} (pure): ADDITIVE premultiplies {@code intensity} into the colour and keeps alpha full
	 * (the particle shader discards fragments under a tenth of alpha, which would cut soft glows off); PLAIN keeps the colour and
	 * puts the intensity in alpha, as translucent blending needs.
	 */
	public static int encode(Path path, float intensity, int rgb) {
		float k = Math.max(0F, Math.min(1F, intensity));
		if (path == Path.PLAIN) {
			return (Math.round(k * 255) << 24) | (rgb & 0xFFFFFF);
		}
		int r = Math.round(((rgb >> 16) & 0xFF) * k);
		int g = Math.round(((rgb >> 8) & 0xFF) * k);
		int b = Math.round((rgb & 0xFF) * k);
		return 0xFF000000 | (r << 16) | (g << 8) | b;
	}

	private static volatile Path current = Path.ADDITIVE;
	private static Path logged;
	private static boolean looked;
	private static boolean unavailable;
	private static Object api;
	private static Method inUse;

	private static volatile @Nullable Path override;

	/** Tests: draw as if a shader pack were (PLAIN) or were not (ADDITIVE) in use; null goes back to asking Iris. */
	public static void setOverrideForTesting(@Nullable Path path) {
		override = path;
		refresh();
	}

	/** The path this frame. */
	public static Path current() {
		return current;
	}

	/** Re-reads whether a shader pack is in use (once a frame, render thread) and logs the path when it changes. */
	public static void refresh() {
		Path forced = override;
		Path p = forced != null ? forced : path(shaderPackInUse());
		current = p;
		if (p != logged) {
			logged = p;
			LOGGER.info("[office] Magic fx: {}", p == Path.PLAIN
					? "Iris shader pack in use, drawing magic light on the translucent particle layer"
					: "drawing magic light with the additive glow pipeline");
		}
	}

	/** Whether Iris says a shader pack is in use; false without Iris or if its API cannot be reached (never throws). */
	public static boolean shaderPackInUse() {
		if (unavailable) {
			return false;
		}
		try {
			if (!looked) {
				looked = true;
				if (!FabricLoader.getInstance().isModLoaded("iris")) {
					unavailable = true;
					return false;
				}
				Class<?> c = Class.forName(IRIS_API);
				api = c.getMethod("getInstance").invoke(null);
				inUse = c.getMethod("isShaderPackInUse");
			}
			return Boolean.TRUE.equals(inUse.invoke(api));
		} catch (Throwable t) {
			unavailable = true;
			LOGGER.warn("[office] Magic fx: Iris is present but its API could not be read ({}); assuming no shader pack", t.toString());
			return false;
		}
	}
}
