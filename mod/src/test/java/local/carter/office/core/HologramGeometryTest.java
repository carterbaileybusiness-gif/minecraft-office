package local.carter.office.core;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Optional;
import org.junit.jupiter.api.Test;

class HologramGeometryTest {
    // 2 x 1.125 frame at the origin facing +z; right = +x, up = +y.
    private final HologramGeometry.Frame frame = new HologramGeometry.Frame(
            new Vec3d(0, 0, 0), new Vec3d(1, 0, 0), new Vec3d(0, 1, 0), 2.0, 1.125);
    private final Vec3d forward = new Vec3d(0, 0, -1);

    @Test
    void centerRayHitsMiddle() {
        Optional<HologramGeometry.Hit> hit = HologramGeometry.hit(new Vec3d(0, 0, 5), forward, frame);
        assertTrue(hit.isPresent());
        assertEquals(0.5, hit.get().u(), 1e-9);
        assertEquals(0.5, hit.get().v(), 1e-9);
        assertEquals(5.0, hit.get().distance(), 1e-9);
        assertNull(hit.get().corner());
    }

    @Test
    void missingRayIsEmpty() {
        assertTrue(HologramGeometry.hit(new Vec3d(3, 0, 5), forward, frame).isEmpty());
        assertTrue(HologramGeometry.hit(new Vec3d(0, 2, 5), forward, frame).isEmpty());
    }

    @Test
    void parallelRayIsEmpty() {
        assertTrue(HologramGeometry.hit(new Vec3d(0, 0, 5), new Vec3d(1, 0, 0), frame).isEmpty());
    }

    @Test
    void rayPointingAwayIsEmpty() {
        assertTrue(HologramGeometry.hit(new Vec3d(0, 0, 5), new Vec3d(0, 0, 1), frame).isEmpty());
    }

    @Test
    void hitsFromBehindToo() {
        assertTrue(HologramGeometry.hit(new Vec3d(0, 0, -5), new Vec3d(0, 0, 1), frame).isPresent());
    }

    @Test
    void nearTopLeftCorner() {
        // top-left corner is at x=-1, y=0.5625; aim ~0.1 block diagonally inside
        Optional<HologramGeometry.Hit> hit = HologramGeometry.hit(new Vec3d(-0.93, 0.49, 5), forward, frame);
        assertTrue(hit.isPresent());
        assertEquals(HologramGeometry.Corner.TOP_LEFT, hit.get().corner());
    }

    @Test
    void otherCorners() {
        assertEquals(HologramGeometry.Corner.TOP_RIGHT,
                HologramGeometry.hit(new Vec3d(0.9, 0.5, 5), forward, frame).get().corner());
        assertEquals(HologramGeometry.Corner.BOTTOM_LEFT,
                HologramGeometry.hit(new Vec3d(-0.9, -0.5, 5), forward, frame).get().corner());
        assertEquals(HologramGeometry.Corner.BOTTOM_RIGHT,
                HologramGeometry.hit(new Vec3d(0.9, -0.5, 5), forward, frame).get().corner());
    }

    @Test
    void farFromCornerIsNull() {
        assertNull(HologramGeometry.hit(new Vec3d(-0.5, 0.3, 5), forward, frame).get().corner());
    }

    @Test
    void uGrowsRightVGrowsDown() {
        HologramGeometry.Hit hit = HologramGeometry.hit(new Vec3d(0.5, 0.28125, 5), forward, frame).get();
        assertEquals(0.75, hit.u(), 1e-9);
        assertEquals(0.25, hit.v(), 1e-9);
    }

    @Test
    void toPixelRetina() {
        var hit = new HologramGeometry.Hit(0.25, 0.5, 1.0, null);
        assertArrayEquals(new int[] {720, 810}, HologramGeometry.toPixel(hit, 2880, 1620));
    }

    @Test
    void toPixelClampsVertical() {
        var hit = new HologramGeometry.Hit(1.0, 1.0, 1.0, null);
        assertArrayEquals(new int[] {1079, 1919}, HologramGeometry.toPixel(hit, 1080, 1920));
        var origin = new HologramGeometry.Hit(0.0, 0.0, 1.0, null);
        assertArrayEquals(new int[] {0, 0}, HologramGeometry.toPixel(origin, 1080, 1920));
    }

    @Test
    void dragBottomRightOutward() {
        double aspect = 16.0 / 9.0;
        // bottom-right corner at x=1, y=-0.5625; drag 1 block further right
        Vec3d pt = new Vec3d(2.0, -0.5625, 0);
        assertEquals(3.0, HologramGeometry.widthFromDrag(frame, HologramGeometry.Corner.BOTTOM_RIGHT, pt, aspect), 1e-9);
    }

    @Test
    void dragTopLeftOutward() {
        Vec3d pt = new Vec3d(-2.0, 0.5625, 0);
        assertEquals(3.0, HologramGeometry.widthFromDrag(frame, HologramGeometry.Corner.TOP_LEFT, pt, 16.0 / 9.0), 1e-9);
    }

    @Test
    void dragClamps() {
        double aspect = 16.0 / 9.0;
        assertEquals(12.0, HologramGeometry.widthFromDrag(frame, HologramGeometry.Corner.BOTTOM_RIGHT,
                new Vec3d(40, 0, 0), aspect), 1e-9);
        assertEquals(0.5, HologramGeometry.widthFromDrag(frame, HologramGeometry.Corner.BOTTOM_RIGHT,
                new Vec3d(-5, 0, 0), aspect), 1e-9);
    }
}
