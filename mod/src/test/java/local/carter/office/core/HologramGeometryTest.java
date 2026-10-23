package local.carter.office.core;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
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
                new Vec3d(-5, 0.5, 0), aspect), 1e-9);
    }

    @Test
    void verticalDragOnTallFrameGrowsWidth() {
        double aspect = 1080.0 / 1920.0;
        var tall = new HologramGeometry.Frame(
                new Vec3d(0, 0, 0), new Vec3d(1, 0, 0), new Vec3d(0, 1, 0), 1.125, 2.0);
        // bottom-right corner at (0.5625, -1); drag straight down by 1 block
        Vec3d pt = new Vec3d(0.5625, -2.0, 0);
        assertEquals(1.125 + 1.0 * aspect,
                HologramGeometry.widthFromDrag(tall, HologramGeometry.Corner.BOTTOM_RIGHT, pt, aspect), 1e-9);
        // top-left, drag straight up by 1 block
        Vec3d up = new Vec3d(-0.5625, 2.0, 0);
        assertEquals(1.125 + 1.0 * aspect,
                HologramGeometry.widthFromDrag(tall, HologramGeometry.Corner.TOP_LEFT, up, aspect), 1e-9);
    }

    @Test
    void diagonalDragUsesLargerAxis() {
        double aspect = 16.0 / 9.0;
        var c = HologramGeometry.Corner.BOTTOM_RIGHT;
        // mostly horizontal: +0.5 right, 0.1 down -> horizontal wins (2.5)
        assertEquals(2.5, HologramGeometry.widthFromDrag(frame, c, new Vec3d(1.5, -0.6625, 0), aspect), 1e-9);
        // mostly vertical: +0.1 right, 1 down -> vertical wins ((1.125 + 1) * 16/9)
        assertEquals(2.125 * aspect,
                HologramGeometry.widthFromDrag(frame, c, new Vec3d(1.1, -1.5625, 0), aspect), 1e-9);
    }

    @Test
    void pureHorizontalInwardDragShrinks() {
        double aspect = 16.0 / 9.0;
        // bottom-right corner at (1, -0.5625); drag 0.5 block left, no vertical movement -> 1.5 wide
        assertEquals(1.5, HologramGeometry.widthFromDrag(frame, HologramGeometry.Corner.BOTTOM_RIGHT,
                new Vec3d(0.5, -0.5625, 0), aspect), 1e-9);
        // top-left corner at (-1, 0.5625); drag 0.5 block right -> 1.5 wide
        assertEquals(1.5, HologramGeometry.widthFromDrag(frame, HologramGeometry.Corner.TOP_LEFT,
                new Vec3d(-0.5, 0.5625, 0), aspect), 1e-9);
    }

    @Test
    void pureVerticalDragOnTallFrameGrowsAndShrinks() {
        double aspect = 1080.0 / 1920.0;
        var tall = new HologramGeometry.Frame(
                new Vec3d(0, 0, 0), new Vec3d(1, 0, 0), new Vec3d(0, 1, 0), 1.125, 2.0);
        // bottom-right corner at (0.5625, -1): up by 0.5 -> height 1.5 -> width 1.5 * 9/16
        assertEquals(1.5 * aspect, HologramGeometry.widthFromDrag(tall, HologramGeometry.Corner.BOTTOM_RIGHT,
                new Vec3d(0.5625, -0.5, 0), aspect), 1e-9);
        // and down by 0.5 -> height 2.5
        assertEquals(2.5 * aspect, HologramGeometry.widthFromDrag(tall, HologramGeometry.Corner.BOTTOM_RIGHT,
                new Vec3d(0.5625, -1.5, 0), aspect), 1e-9);
    }

    @Test
    void diagonalInwardDragUsesDominantAxis() {
        double aspect = 16.0 / 9.0;
        var c = HologramGeometry.Corner.BOTTOM_RIGHT;
        // 0.8 left, 0.0375 down: horizontal moved more -> shrink to 1.2 (old max rule kept ~2.07)
        assertEquals(1.2, HologramGeometry.widthFromDrag(frame, c, new Vec3d(0.2, -0.6, 0), aspect), 1e-9);
        // 0.05 left, 0.3 up: vertical moved more -> height 0.825 -> width 0.825 * 16/9
        assertEquals(0.825 * aspect, HologramGeometry.widthFromDrag(frame, c, new Vec3d(0.95, -0.2625, 0), aspect), 1e-9);
    }

    @Test
    void growingBottomRightKeepsTopLeftFixed() {
        var c = HologramGeometry.centerForFixedCorner(frame, HologramGeometry.Corner.BOTTOM_RIGHT, 3.0, 1.6875);
        // top-left corner = center + (-w/2) right + (h/2) up
        assertEquals(-1.0, c.x() - 1.5, 1e-9);
        assertEquals(0.5625, c.y() + 0.84375, 1e-9);
        assertEquals(0.0, c.z(), 1e-9);
    }

    @Test
    void fixedCornerForEveryCorner() {
        double nw = 3.0;
        double nh = 1.6875;
        for (HologramGeometry.Corner d : HologramGeometry.Corner.values()) {
            var c = HologramGeometry.centerForFixedCorner(frame, d, nw, nh);
            boolean dr = d == HologramGeometry.Corner.TOP_RIGHT || d == HologramGeometry.Corner.BOTTOM_RIGHT;
            boolean dt = d == HologramGeometry.Corner.TOP_LEFT || d == HologramGeometry.Corner.TOP_RIGHT;
            double fx = dr ? -1.0 : 1.0; // opposite corner x in the old frame
            double fy = dt ? -0.5625 : 0.5625;
            assertEquals(fx, c.x() + (dr ? -nw / 2 : nw / 2), 1e-9);
            assertEquals(fy, c.y() + (dt ? -nh / 2 : nh / 2), 1e-9);
        }
    }

    @Test
    void planePointOutsideQuad() {
        // aimed 2 blocks beyond the right edge (x = 3), still on the plane
        var p = HologramGeometry.planePoint(new Vec3d(3, 0, 5), forward, frame);
        assertTrue(p.isPresent());
        assertEquals(3.0, p.get().x(), 1e-9);
        assertEquals(0.0, p.get().z(), 1e-9);
        assertTrue(HologramGeometry.hit(new Vec3d(3, 0, 5), forward, frame).isEmpty());
    }

    @Test
    void planePointParallelOrBehindIsEmpty() {
        assertTrue(HologramGeometry.planePoint(new Vec3d(0, 0, 5), new Vec3d(1, 0, 0), frame).isEmpty());
        assertTrue(HologramGeometry.planePoint(new Vec3d(0, 0, 5), new Vec3d(0, 0, 1), frame).isEmpty());
    }

    @Test
    void frontFacingFlag() {
        assertTrue(HologramGeometry.hit(new Vec3d(0, 0, 5), forward, frame).get().frontFacing());
        assertFalse(HologramGeometry.hit(new Vec3d(0, 0, -5), new Vec3d(0, 0, 1), frame).get().frontFacing());
    }

    @Test
    void grabRadiusIsAFifthOfTheShorterSideClamped() {
        assertEquals(0.225, HologramGeometry.grabRadius(2.0, 1.125), 1e-9);
        assertEquals(0.45, HologramGeometry.grabRadius(4.0, 2.25), 1e-9);
        assertEquals(0.2, HologramGeometry.grabRadius(0.5, 0.28125), 1e-9, "small screens still get 0.2");
        assertEquals(0.5, HologramGeometry.grabRadius(12.0, 6.75), 1e-9, "huge screens cap at 0.5");
        assertEquals(0.2, HologramGeometry.grabRadius(0.5625, 1.0), 1e-9, "uses the shorter side (tall 9:16)");
    }

    @Test
    void cornerCanBeGrabbedFromJustOutsideTheQuad() {
        // bottom-right corner is at (1, -0.5625); aim 0.1 right of and ~0.09 below it
        Vec3d origin = new Vec3d(1.1, -0.65, 5);
        assertTrue(HologramGeometry.hit(origin, forward, frame).isEmpty(), "hit() is inside-only");
        Optional<HologramGeometry.Hit> reach = HologramGeometry.reach(origin, forward, frame);
        assertTrue(reach.isPresent());
        assertEquals(HologramGeometry.Corner.BOTTOM_RIGHT, reach.get().corner());
        assertTrue(reach.get().u() > 1 && reach.get().v() > 1, "u/v past the edge");
        assertEquals(5.0, reach.get().distance(), 1e-9);
    }

    @Test
    void reachIgnoresPointsFarOutsideAndMatchesHitInside() {
        assertTrue(HologramGeometry.reach(new Vec3d(1.4, -0.9, 5), forward, frame).isEmpty());
        assertTrue(HologramGeometry.reach(new Vec3d(0, 1.0, 5), forward, frame).isEmpty(), "outside an edge, far from corners");
        var in = HologramGeometry.reach(new Vec3d(-0.93, 0.49, 5), forward, frame).orElseThrow();
        var hit = HologramGeometry.hit(new Vec3d(-0.93, 0.49, 5), forward, frame).orElseThrow();
        assertEquals(hit, in);
    }

    @Test
    void largerHologramsHaveLargerGrabZones() {
        HologramGeometry.Frame big = new HologramGeometry.Frame(new Vec3d(0, 0, 0), new Vec3d(1, 0, 0), new Vec3d(0, 1, 0), 4.0, 2.25);
        // 0.4 from the top-right corner (2, 1.125), inside: within 0.45
        assertEquals(HologramGeometry.Corner.TOP_RIGHT,
                HologramGeometry.hit(new Vec3d(1.72, 0.84, 5), forward, big).orElseThrow().corner());
        // the same offset on the 2-wide frame is outside its 0.225 radius
        assertNull(HologramGeometry.hit(new Vec3d(0.72, 0.28, 5), forward, frame).orElseThrow().corner());
    }

    @Test
    void cornerPointsAreTheFrameCorners() {
        Vec3d tr = HologramGeometry.cornerPoint(frame, HologramGeometry.Corner.TOP_RIGHT);
        assertEquals(1.0, tr.x(), 1e-9);
        assertEquals(0.5625, tr.y(), 1e-9);
        Vec3d bl = HologramGeometry.cornerPoint(frame, HologramGeometry.Corner.BOTTOM_LEFT);
        assertEquals(-1.0, bl.x(), 1e-9);
        assertEquals(-0.5625, bl.y(), 1e-9);
    }
}
