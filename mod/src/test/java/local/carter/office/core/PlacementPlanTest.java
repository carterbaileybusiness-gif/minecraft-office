package local.carter.office.core;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.List;

import org.junit.jupiter.api.Test;

/** Laptop + monitors (spec 5.10): the first hologram mirrors your screen; each further one is a new Extend monitor. */
class PlacementPlanTest {
    private static PlacementPlan.Existing e(Mode m, int slot) {
        return new PlacementPlan.Existing(m, slot);
    }

    @Test
    void firstHologramMirrors() {
        assertEquals(Mode.MIRROR, PlacementPlan.next(List.of()).mode());
    }

    @Test
    void afterTheMirrorEachNewOneTakesTheNextFreeDisplay() {
        assertEquals(new PlacementPlan.Choice(Mode.EXTEND, 1), PlacementPlan.next(List.of(e(Mode.MIRROR, 1))));
        assertEquals(new PlacementPlan.Choice(Mode.EXTEND, 2), PlacementPlan.next(List.of(e(Mode.MIRROR, 1), e(Mode.EXTEND, 1))));
        assertEquals(new PlacementPlan.Choice(Mode.EXTEND, 3),
                PlacementPlan.next(List.of(e(Mode.MIRROR, 1), e(Mode.EXTEND, 1), e(Mode.EXTEND, 2))));
        assertEquals(new PlacementPlan.Choice(Mode.EXTEND, 2),
                PlacementPlan.next(List.of(e(Mode.MIRROR, 1), e(Mode.EXTEND, 1), e(Mode.EXTEND, 3))), "a gap is filled first");
    }

    @Test
    void noMirrorLeftTheNextOneMirrorsAgain() {
        assertEquals(new PlacementPlan.Choice(Mode.MIRROR, 3), PlacementPlan.next(List.of(e(Mode.EXTEND, 1), e(Mode.EXTEND, 2))),
                "Extend monitors only: the next one is the laptop's screen (on a free slot for a later switch)");
        assertEquals(Mode.MIRROR, PlacementPlan.next(List.of(e(Mode.WINDOW, 1))).mode(), "a Window hologram is no mirror");
    }

    @Test
    void allDisplaysTakenFallsBackToDisplayOne() {
        assertEquals(new PlacementPlan.Choice(Mode.EXTEND, 1), PlacementPlan.next(List.of(e(Mode.MIRROR, 1),
                e(Mode.EXTEND, 1), e(Mode.EXTEND, 2), e(Mode.EXTEND, 3), e(Mode.EXTEND, 4))));
    }

    @Test
    void mirrorAndWindowHologramsDontUseDisplays() {
        assertEquals(new PlacementPlan.Choice(Mode.EXTEND, 1),
                PlacementPlan.next(List.of(e(Mode.MIRROR, 1), e(Mode.WINDOW, 1), e(Mode.MIRROR, 2))));
    }
}
