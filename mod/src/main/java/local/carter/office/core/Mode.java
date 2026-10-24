package local.carter.office.core;

/**
 * What a hologram shows. MIRROR (a player's first hologram, spec 5.10): the owner's real main screen with Minecraft's
 * own window left out of the capture, used in-world on a Mac like the other screens. EXTEND (every further one): a new
 * virtual display, an extra monitor. WINDOW: one app window (view-only). Windows clients mirror every hologram, as
 * they have no virtual displays yet.
 *
 * <p>Order matters on the wire ({@code readEnum} sends ordinals): new values are only ever appended, so a jar that
 * doesn't know a value never reads an old one as something else. Everything persisted stores the name.
 */
public enum Mode {
    EXTEND,
    WINDOW,
    MIRROR
}
