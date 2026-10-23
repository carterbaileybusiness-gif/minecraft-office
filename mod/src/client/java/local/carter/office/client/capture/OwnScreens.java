package local.carter.office.client.capture;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Supplier;

import org.jspecify.annotations.Nullable;

import local.carter.office.client.interact.HologramTracker;
import local.carter.office.client.render.ScreenTexture;
import local.carter.office.client.render.ScreenTextureRegistry;
import local.carter.office.core.HologramSpec;
import local.carter.office.hologram.HologramBlockEntity;

import net.minecraft.client.Minecraft;

import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;

/**
 * Feeds the local player's own loaded holograms from capture sources: one {@link CaptureSource} + {@link ScreenTexture}
 * per hologram, keyed by hologram id, restarted when the hologram's {@link Target} changes and stopped when it unloads,
 * changes owner, or the player disconnects. Client thread only.
 *
 * <p>Task 7: disabled unless {@code -Doffice.testPattern=true}, and then every own hologram shows a
 * {@link TestPatternSource}. Tasks 9b/11 install the real capture with {@link #setSourceFactory} and {@link #setEnabled}.
 */
public final class OwnScreens {
	public static final String TEST_PATTERN_PROPERTY = "office.testPattern";
	public static final int MAX_FPS = 30;

	private static final Map<UUID, Feed> FEEDS = new HashMap<>();
	private static boolean enabled = Boolean.getBoolean(TEST_PATTERN_PROPERTY);
	private static Supplier<CaptureSource> factory = TestPatternSource::new;

	private record Feed(Target target, CaptureSource source, ScreenTexture texture) {}

	private OwnScreens() {}

	public static void register() {
		ClientTickEvents.END_CLIENT_TICK.register(OwnScreens::tick);
		ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> client.execute(OwnScreens::stopAll));
	}

	public static boolean enabled() {
		return enabled;
	}

	/** Turns feeding own holograms on or off (off stops every source and frees every texture at the next tick). */
	public static void setEnabled(final boolean on) {
		enabled = on;
	}

	/** The source used for each own hologram (default: {@link TestPatternSource}); running feeds restart with it. Client thread. */
	public static void setSourceFactory(final Supplier<CaptureSource> sourceFactory) {
		factory = sourceFactory;
		stopAll();
	}

	/** The texture currently fed for one of the local player's holograms, if any. */
	public static @Nullable ScreenTexture texture(final UUID hologramId) {
		Feed f = FEEDS.get(hologramId);
		return f == null ? null : f.texture();
	}

	static void tick(final Minecraft mc) {
		if (!enabled || mc.player == null || mc.level == null) {
			if (!FEEDS.isEmpty()) {
				stopAll();
			}
			return;
		}
		UUID me = mc.player.getUUID();
		Set<UUID> seen = new HashSet<>();
		for (HologramBlockEntity be : HologramTracker.loaded()) {
			HologramSpec spec = be.spec();
			if (spec == null || !me.equals(spec.owner())) {
				continue;
			}
			UUID id = be.hologramId();
			if (!seen.add(id)) {
				continue;
			}
			Target target = Target.of(spec);
			Feed feed = FEEDS.get(id);
			if (feed != null && feed.target().equals(target)) {
				continue;
			}
			if (feed != null) {
				feed.source().stop();
			}
			ScreenTexture texture = ScreenTexture.create("own");
			CaptureSource source = factory.get();
			ScreenTextureRegistry.register(id, texture); // clears the old texture, if any
			FEEDS.put(id, new Feed(target, source, texture));
			source.start(target, MAX_FPS, texture);
		}
		for (Iterator<Map.Entry<UUID, Feed>> it = FEEDS.entrySet().iterator(); it.hasNext();) {
			Map.Entry<UUID, Feed> e = it.next();
			if (!seen.contains(e.getKey())) {
				e.getValue().source().stop();
				ScreenTextureRegistry.remove(e.getKey());
				it.remove();
			}
		}
	}

	static void stopAll() {
		for (Map.Entry<UUID, Feed> e : FEEDS.entrySet()) {
			e.getValue().source().stop();
			ScreenTextureRegistry.remove(e.getKey());
		}
		FEEDS.clear();
	}
}
