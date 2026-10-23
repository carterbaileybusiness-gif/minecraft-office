package local.carter.office.core;

/**
 * What a hologram shows. MIRROR (the default for a player's first hologram, spec 5.8): the owner's real main screen with
 * Minecraft's own window left out of the capture. EXTEND: a new virtual display (an extra monitor). WINDOW: one app
 * window. Stored by name (NBT / spec map), so adding a value never changes what an existing hologram shows.
 */
public enum Mode {
    MIRROR,
    EXTEND,
    WINDOW
}
