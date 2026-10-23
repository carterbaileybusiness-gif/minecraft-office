package local.carter.office.core;

import java.util.Collection;
import java.util.HashSet;
import java.util.Set;

/**
 * What a newly placed hologram shows (spec 5.8, Carter: "Minecraft is on my real screen; holograms are my other
 * monitors"). The player's first hologram, or any new one while none of theirs mirrors, is MIRROR: their screen minus
 * Minecraft. Every additional one is EXTEND on the next free virtual display (1-4), like plugging in another monitor.
 * Mirror and Window holograms don't use a display; a Mirror hologram still gets the next free display slot so switching
 * it to Extend later lands on an unused display. Pure.
 */
public final class PlacementPlan {
    public static final int MAX_SLOT = 4;

    /** One of the player's existing holograms. */
    public record Existing(Mode mode, int displaySlot) {}

    public record Choice(Mode mode, int displaySlot) {}

    private PlacementPlan() {}

    public static Choice next(final Collection<Existing> existing) {
        boolean mirrored = false;
        Set<Integer> used = new HashSet<>();
        for (Existing e : existing) {
            if (e.mode() == Mode.MIRROR) {
                mirrored = true;
            } else if (e.mode() == Mode.EXTEND) {
                used.add(e.displaySlot());
            }
        }
        int slot = 1;
        for (int s = 1; s <= MAX_SLOT; s++) {
            if (!used.contains(s)) {
                slot = s;
                break;
            }
        }
        return new Choice(mirrored ? Mode.EXTEND : Mode.MIRROR, slot);
    }
}
