package local.carter.office.core;

import java.util.Collection;
import java.util.HashSet;
import java.util.Set;

/**
 * What a newly placed hologram shows (spec 5.10, Carter: "like hooking up a laptop to monitors"). The player's first
 * hologram, or any new one while none of theirs mirrors, is MIRROR: the laptop's own screen (minus Minecraft), usable
 * in-world. Every additional one is EXTEND on the next free virtual display (1-4; 1 again when all are taken), like
 * plugging in monitor #2, #3. Extend and Mirror holograms hold their display slot (a Mirror keeps one free, so switching
 * it to Extend later never collides with another monitor); Window holograms don't. Windows clients, which can't make virtual displays yet, switch a
 * hologram they just placed to Mirror ({@code PlacementDefault}). Pure.
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
                used.add(e.displaySlot()); // reserved for a later switch to Extend
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
