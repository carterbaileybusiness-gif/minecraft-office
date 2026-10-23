package local.carter.office.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class HologramSpecTest {
    private HologramSpec sample(Shape shape, Mode mode, Privacy privacy, String windowKey) {
        return new HologramSpec(UUID.randomUUID(), shape, mode, privacy, 1, windowKey, 3.0, 1.5, 2.0, -4.25, 90f);
    }

    @Test
    void roundTripWindow() {
        HologramSpec s = sample(Shape.WIDE_16_9, Mode.WINDOW, Privacy.PUBLIC, "com.apple.Safari:123");
        assertEquals(s, HologramSpec.fromMap(s.toMap()));
    }

    @Test
    void roundTripExtendNullKey() {
        HologramSpec s = sample(Shape.ULTRA_21_9, Mode.EXTEND, Privacy.PUBLIC, null);
        HologramSpec back = HologramSpec.fromMap(s.toMap());
        assertEquals(s, back);
        assertNull(back.windowKey());
    }

    @Test
    void roundTripMirror() {
        HologramSpec s = sample(Shape.WIDE_16_9, Mode.MIRROR, Privacy.PUBLIC, null);
        Map<String, Object> m = s.toMap();
        assertEquals("MIRROR", m.get("mode"));
        assertEquals(s, HologramSpec.fromMap(m));
    }

    @Test
    void mirrorIsAppendedSoOldOrdinalsKeepTheirMeaningAndLegacyNamesStillLoad() {
        // Wire (readEnum) uses ordinals: a jar without MIRROR must never read EXTEND as MIRROR or the other way round.
        assertEquals(List.of(Mode.EXTEND, Mode.WINDOW, Mode.MIRROR), List.of(Mode.values()));
        HologramSpec ext = sample(Shape.WIDE_16_9, Mode.EXTEND, Privacy.PUBLIC, null);
        Map<String, Object> m = new java.util.HashMap<>(ext.toMap());
        assertEquals(Mode.EXTEND, HologramSpec.fromMap(m).mode()); // a stored "EXTEND" stays Extend
        m.put("mode", "WINDOW");
        assertEquals(Mode.WINDOW, HologramSpec.fromMap(m).mode());
    }

    @Test
    void roundTripEmptyKey() {
        HologramSpec s = sample(Shape.TALL_9_16, Mode.EXTEND, Privacy.PRIVATE, "");
        assertEquals(s, HologramSpec.fromMap(s.toMap()));
    }

    @Test
    void roundTripShowToList() {
        UUID a = UUID.randomUUID();
        UUID b = UUID.randomUUID();
        HologramSpec s = sample(Shape.WIDE_16_9, Mode.EXTEND, new Privacy(Privacy.Level.SHOW_TO, Set.of(a, b)), null);
        Map<String, Object> m = s.toMap();
        assertEquals(Set.of(a.toString(), b.toString()), Set.copyOf((List<?>) m.get("allowed")));
        assertEquals(s, HologramSpec.fromMap(m));
    }

    @Test
    void withWidthClamps() {
        HologramSpec s = sample(Shape.WIDE_16_9, Mode.EXTEND, Privacy.PUBLIC, null);
        assertEquals(12.0, s.withWidth(50).width());
        assertEquals(0.5, s.withWidth(0.01).width());
        assertEquals(4.0, s.withWidth(4.0).width());
    }

    @Test
    void heightFollowsAspect() {
        HologramSpec tall = sample(Shape.TALL_9_16, Mode.EXTEND, Privacy.PUBLIC, null).withWidth(2.0);
        assertEquals(2.0 * 16 / 9, tall.height(), 1e-9);
        HologramSpec wide = sample(Shape.WIDE_16_9, Mode.EXTEND, Privacy.PUBLIC, null).withWidth(1.6);
        assertEquals(0.9, wide.height(), 1e-9);
        HologramSpec ultra = sample(Shape.ULTRA_21_9, Mode.EXTEND, Privacy.PUBLIC, null).withWidth(2.56);
        assertEquals(1.08, ultra.height(), 1e-9);
    }

    @Test
    void shapePixels() {
        assertEquals(1920, Shape.WIDE_16_9.pxW());
        assertEquals(1080, Shape.WIDE_16_9.pxH());
        assertEquals(2560, Shape.ULTRA_21_9.pxW());
        assertEquals(1080, Shape.TALL_9_16.pxW());
        assertEquals(1920, Shape.TALL_9_16.pxH());
    }
}
