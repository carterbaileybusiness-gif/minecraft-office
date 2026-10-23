package local.carter.office.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;

import java.util.UUID;

import org.junit.jupiter.api.Test;

class MountTest {
    private static final UUID OWNER = UUID.randomUUID();
    private static final double EPS = 1e-9;
    private static final double H = 2.0 / (16.0 / 9.0);

    private static HologramSpec spec(double width, double offX, double offY, double offZ, float yaw) {
        return new HologramSpec(OWNER, Shape.WIDE_16_9, Mode.EXTEND, Privacy.PUBLIC, 1, null, width, offX, offY, offZ, yaw);
    }

    @Test
    void floorDefaultFloatsAboveTheEmitterAsBefore() {
        Vec3d off = Mount.FLOOR.defaultOffsets(0, H);
        assertEquals(0.0, off.x(), EPS);
        assertEquals(0.5 + H / 2 + 0.1, off.y(), EPS);
        assertEquals(0.0, off.z(), EPS);
    }

    @Test
    void wallDefaultIsFlushWithTheWallAndFacesOut() {
        // Facing south (yaw 0): the wall is to the north, its surface 0.5 behind the emitter centre.
        Vec3d south = Mount.WALL.defaultOffsets(0, H);
        assertEquals(0.0, south.x(), EPS);
        assertEquals(0.0, south.y(), EPS, "centred on the clicked face");
        assertEquals(-0.47, south.z(), EPS, "content plane 0.03 off the wall surface (-0.5)");
        assertEquals(1.0, Mount.normal(0).z(), EPS, "front faces out of the wall");
        // Facing east (yaw 270): the wall is to the west.
        Vec3d east = Mount.WALL.defaultOffsets(270, H);
        assertEquals(-0.47, east.x(), EPS);
        assertEquals(0.0, east.z(), EPS);
        assertEquals(1.0, Mount.normal(270).x(), EPS);
        // Facing north (yaw 180) and west (yaw 90).
        assertEquals(0.47, Mount.WALL.defaultOffsets(180, H).z(), EPS);
        assertEquals(0.47, Mount.WALL.defaultOffsets(90, H).x(), EPS);
    }

    @Test
    void ceilingDefaultHangsJustBelowTheMount() {
        Vec3d off = Mount.CEILING.defaultOffsets(0, H);
        assertEquals(Mount.CEILING_MOUNT_BOTTOM - Mount.CEILING_GAP, off.y() + H / 2, EPS, "top edge 0.1 below the mount");
        assertEquals(0.0, off.x(), EPS);
        assertEquals(0.0, off.z(), EPS);
    }

    @Test
    void wallConstraintPullsAHologramBackOntoTheWallPlaneFacingOut() {
        HologramSpec off = spec(3.0, 0.7, 0.3, 1.5, 90f); // pulled 2 blocks off the wall and turned
        HologramSpec on = Mount.WALL.constrain(off, 0f);
        assertEquals(0.7, on.offX(), EPS, "slides along the wall are kept");
        assertEquals(0.3, on.offY(), EPS);
        assertEquals(-0.47, on.offZ(), EPS, "back on the plane");
        assertEquals(0f, on.yaw(), 1e-6f, "faces out of the wall");
        assertEquals(3.0, on.width(), EPS);
        // East-facing wall: x is the normal axis.
        HologramSpec east = Mount.WALL.constrain(spec(2.0, 4.0, 0.0, 1.25, 270f), 270f);
        assertEquals(-0.47, east.offX(), EPS);
        assertEquals(1.25, east.offZ(), EPS);
    }

    @Test
    void floorAndCeilingAreFree() {
        HologramSpec s = spec(3.0, 0.7, 0.3, 1.5, 90f);
        assertSame(s, Mount.FLOOR.constrain(s, 0f));
        assertSame(s, Mount.CEILING.constrain(s, 0f));
    }

    @Test
    void scrollOnTheFloorKeepsTheBottomEdge() {
        HologramSpec s = spec(2.0, 0.25, 1.1625, -0.5, 30f);
        HologramSpec up = Mount.FLOOR.scrollResize(s, 1);
        assertEquals(2.2, up.width(), EPS);
        assertEquals(s.offY() - s.height() / 2, up.offY() - up.height() / 2, EPS, "bottom edge fixed");
        assertEquals(0.25, up.offX(), EPS);
        assertEquals(-0.5, up.offZ(), EPS);
        assertEquals(30f, up.yaw(), 1e-6f);
        HologramSpec down = Mount.FLOOR.scrollResize(s, -1);
        assertEquals(2.0 / 1.1, down.width(), EPS);
        assertEquals(s.offY() - s.height() / 2, down.offY() - down.height() / 2, EPS);
    }

    @Test
    void scrollOnAWallKeepsTheCentre() {
        HologramSpec s = spec(2.0, 0.0, 0.0, -0.47, 0f);
        HologramSpec up = Mount.WALL.scrollResize(s, 1);
        assertEquals(2.2, up.width(), EPS);
        assertEquals(0.0, up.offY(), EPS);
        assertEquals(-0.47, up.offZ(), EPS, "stays on the wall plane");
        assertEquals(0.0, up.offX(), EPS);
    }

    @Test
    void scrollUnderACeilingKeepsTheTopEdge() {
        HologramSpec s = spec(2.0, 0.0, -0.2, 0.0, 0f);
        HologramSpec up = Mount.CEILING.scrollResize(s, 2);
        assertEquals(2.0 * 1.21, up.width(), EPS);
        assertEquals(s.offY() + s.height() / 2, up.offY() + up.height() / 2, EPS, "top edge fixed");
    }

    @Test
    void scrollClampsToTheSizeLimits() {
        HologramSpec big = Mount.FLOOR.scrollResize(spec(11.5, 0, 4, 0, 0f), 1);
        assertEquals(HologramSpec.MAX_WIDTH, big.width(), EPS);
        assertEquals(4 - (11.5 / (16.0 / 9.0)) / 2, big.offY() - big.height() / 2, EPS, "bottom still fixed at the clamp");
        HologramSpec small = Mount.WALL.scrollResize(spec(0.52, 0, 0, -0.47, 0f), -1);
        assertEquals(HologramSpec.MIN_WIDTH, small.width(), EPS);
        assertEquals(HologramSpec.MIN_WIDTH, Mount.WALL.scrollResize(small, -3).width(), EPS);
    }
}
