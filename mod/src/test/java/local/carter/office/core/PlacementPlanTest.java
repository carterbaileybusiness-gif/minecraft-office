package local.carter.office.core;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.List;

import org.junit.jupiter.api.Test;

/** Every new hologram is a monitor you plug in: Extend on the next free virtual display. */
class PlacementPlanTest {
    private static PlacementPlan.Existing e(Mode m, int slot) {
        return new PlacementPlan.Existing(m, slot);
    }

    @Test
    void firstHologramIsDisplayOne() {
        assertEquals(new PlacementPlan.Choice(Mode.EXTEND, 1), PlacementPlan.next(List.of()));
    }

    @Test
    void eachNewOneTakesTheNextFreeDisplay() {
        assertEquals(new PlacementPlan.Choice(Mode.EXTEND, 2), PlacementPlan.next(List.of(e(Mode.EXTEND, 1))));
        assertEquals(new PlacementPlan.Choice(Mode.EXTEND, 3), PlacementPlan.next(List.of(e(Mode.EXTEND, 1), e(Mode.EXTEND, 2))));
        assertEquals(new PlacementPlan.Choice(Mode.EXTEND, 2), PlacementPlan.next(List.of(e(Mode.EXTEND, 1), e(Mode.EXTEND, 3))),
                "a gap is filled first");
    }

    @Test
    void allDisplaysTakenFallsBackToDisplayOne() {
        assertEquals(new PlacementPlan.Choice(Mode.EXTEND, 1),
                PlacementPlan.next(List.of(e(Mode.EXTEND, 1), e(Mode.EXTEND, 2), e(Mode.EXTEND, 3), e(Mode.EXTEND, 4))));
    }

    @Test
    void mirrorAndWindowHologramsDontUseDisplays() {
        assertEquals(new PlacementPlan.Choice(Mode.EXTEND, 1),
                PlacementPlan.next(List.of(e(Mode.MIRROR, 1), e(Mode.WINDOW, 1), e(Mode.MIRROR, 2))));
    }
}
