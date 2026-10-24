package local.carter.office.core;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

/**
 * A placed hologram monitor. {@code windowKey} may be null/empty (MIRROR / EXTEND mode).
 * Offsets are relative to the owning block; width is clamped to [{@link #MIN_WIDTH}, {@link #MAX_WIDTH}].
 * {@code mirrorDisplay}: the real monitor a Mirror copies (an opaque id: CGDisplay UUID on a Mac, the device name on
 * Windows); null = the main display, which is also what every Mirror made before it existed shows.
 */
public record HologramSpec(UUID owner, Shape shape, Mode mode, Privacy privacy, int displaySlot, String windowKey,
                           double width, double offX, double offY, double offZ, float yaw,
                           @Nullable String mirrorDisplay) {
    public static final double MIN_WIDTH = 0.5;
    public static final double MAX_WIDTH = 12.0;

    public HologramSpec {
        if (privacy == null) {
            privacy = Privacy.PUBLIC;
        }
        width = clampWidth(width);
    }

    /** A fresh spec (no chosen monitor). Copies of an existing spec use the full constructor so they keep it. */
    public HologramSpec(UUID owner, Shape shape, Mode mode, Privacy privacy, int displaySlot, String windowKey,
                        double width, double offX, double offY, double offZ, float yaw) {
        this(owner, shape, mode, privacy, displaySlot, windowKey, width, offX, offY, offZ, yaw, null);
    }

    public static double clampWidth(double w) {
        if (Double.isNaN(w)) {
            return MIN_WIDTH;
        }
        return Math.max(MIN_WIDTH, Math.min(MAX_WIDTH, w));
    }

    public double height() {
        return width / shape.aspect();
    }

    public HologramSpec withWidth(double w) {
        return new HologramSpec(owner, shape, mode, privacy, displaySlot, windowKey, w, offX, offY, offZ, yaw,
                mirrorDisplay);
    }

    public HologramSpec withMode(Mode m) {
        return new HologramSpec(owner, shape, m, privacy, displaySlot, windowKey, width, offX, offY, offZ, yaw,
                mirrorDisplay);
    }

    public HologramSpec withPrivacy(Privacy p) {
        return new HologramSpec(owner, shape, mode, p, displaySlot, windowKey, width, offX, offY, offZ, yaw,
                mirrorDisplay);
    }

    public HologramSpec withMirrorDisplay(@Nullable String display) {
        return new HologramSpec(owner, shape, mode, privacy, displaySlot, windowKey, width, offX, offY, offZ, yaw,
                display);
    }

    /**
     * Who sees a new hologram (spec 5.10 round 2): a Mirror shows your whole screen, so it starts Private (only you); an
     * Extend or Window hologram starts Public, as before. Settings change it any time.
     */
    public static Privacy defaultPrivacy(Mode mode) {
        return mode == Mode.MIRROR ? Privacy.PRIVATE : Privacy.PUBLIC;
    }

    /** Plain-types codec (strings, numbers, lists) that the caller maps onto NBT. */
    public Map<String, Object> toMap() {
        Map<String, Object> m = new HashMap<>();
        m.put("owner", owner.toString());
        m.put("shape", shape.name());
        m.put("mode", mode.name());
        m.put("privacy", privacy.level().name());
        List<String> allowed = new ArrayList<>();
        for (UUID u : privacy.allowed()) {
            allowed.add(u.toString());
        }
        allowed.sort(null);
        m.put("allowed", allowed);
        m.put("displaySlot", displaySlot);
        if (windowKey != null) {
            m.put("windowKey", windowKey);
        }
        m.put("width", width);
        m.put("offX", offX);
        m.put("offY", offY);
        m.put("offZ", offZ);
        m.put("yaw", yaw);
        if (mirrorDisplay != null) {
            m.put("mirrorDisplay", mirrorDisplay);
        }
        return m;
    }

    public static HologramSpec fromMap(Map<String, Object> m) {
        Set<UUID> allowed = new java.util.HashSet<>();
        Object raw = m.get("allowed");
        if (raw instanceof Iterable<?> it) {
            for (Object o : it) {
                allowed.add(UUID.fromString(o.toString()));
            }
        }
        Object key = m.get("windowKey");
        Object mirror = m.get("mirrorDisplay");
        return new HologramSpec(
                UUID.fromString((String) m.get("owner")),
                Shape.valueOf((String) m.get("shape")),
                Mode.valueOf((String) m.get("mode")),
                new Privacy(Privacy.Level.valueOf((String) m.get("privacy")), allowed),
                ((Number) m.get("displaySlot")).intValue(),
                key == null ? null : key.toString(),
                ((Number) m.get("width")).doubleValue(),
                ((Number) m.get("offX")).doubleValue(),
                ((Number) m.get("offY")).doubleValue(),
                ((Number) m.get("offZ")).doubleValue(),
                ((Number) m.get("yaw")).floatValue(),
                mirror == null ? null : mirror.toString());
    }
}
