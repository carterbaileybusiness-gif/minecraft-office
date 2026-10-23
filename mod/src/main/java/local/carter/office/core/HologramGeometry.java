package local.carter.office.core;

import java.util.Optional;

/**
 * Ray / quad math for hologram monitors. The frame is a rectangle centred at {@code center},
 * spanned by the unit vectors {@code right} and {@code up}. (u,v) are texture coordinates:
 * u grows along {@code right}, v grows downward (v=0 is the top edge).
 */
public final class HologramGeometry {
    /** Smallest and largest corner grab radius (blocks); see {@link #grabRadius(double, double)}. */
    public static final double MIN_GRAB_RADIUS = 0.2;
    public static final double MAX_GRAB_RADIUS = 0.5;

    private static final double EPS = 1e-9;

    private HologramGeometry() {}

    public enum Corner { TOP_LEFT, TOP_RIGHT, BOTTOM_LEFT, BOTTOM_RIGHT }

    public record Frame(Vec3d center, Vec3d right, Vec3d up, double w, double h) {}

    /**
     * {@code corner} is null unless the hit is within {@link #grabRadius} of a corner.
     * {@code frontFacing} is true when the ray hit the side the normal {@code right x up} faces.
     */
    public record Hit(double u, double v, double distance, Corner corner, boolean frontFacing) {
        public Hit(double u, double v, double distance, Corner corner) {
            this(u, v, distance, corner, true);
        }
    }

    /** Ray parameter t of the (unbounded) plane intersection; empty if parallel or behind the origin. */
    private static Optional<Double> planeT(Vec3d origin, Vec3d dir, Frame f) {
        Vec3d normal = f.right().cross(f.up());
        double denom = dir.dot(normal);
        if (Math.abs(denom) < EPS) {
            return Optional.empty();
        }
        double t = f.center().sub(origin).dot(normal) / denom;
        return t < 0 ? Optional.empty() : Optional.of(t);
    }

    /** Where the ray meets the frame's infinite plane, even outside the quad (for drags). Empty if parallel or behind. */
    public static Optional<Vec3d> planePoint(Vec3d origin, Vec3d dir, Frame f) {
        return planeT(origin, dir, f).map(t -> origin.add(dir.scale(t)));
    }

    /** Two-sided ray test; {@code dir} need not be normalised. Empty on miss, parallel, or hit behind origin. */
    public static Optional<Hit> hit(Vec3d origin, Vec3d dir, Frame f) {
        return reach(origin, dir, f).filter(h -> h.u() >= 0 && h.u() <= 1 && h.v() >= 0 && h.v() <= 1);
    }

    /**
     * Like {@link #hit} but also accepts points slightly <em>outside</em> the quad when they are within
     * {@link #grabRadius} of a corner (so a corner can be grabbed from just beyond the edge). u/v may then lie outside
     * [0, 1]; {@code corner} is set for those.
     */
    public static Optional<Hit> reach(Vec3d origin, Vec3d dir, Frame f) {
        Optional<Double> maybeT = planeT(origin, dir, f);
        if (maybeT.isEmpty()) {
            return Optional.empty();
        }
        double t = maybeT.get();
        Vec3d rel = origin.add(dir.scale(t)).sub(f.center());
        double x = rel.dot(f.right());
        double y = rel.dot(f.up());
        double u = x / f.w() + 0.5;
        double v = 0.5 - y / f.h();
        boolean inside = u >= 0 && u <= 1 && v >= 0 && v <= 1;
        Corner corner = cornerAt(x, y, f);
        if (!inside && corner == null) {
            return Optional.empty();
        }
        boolean front = dir.dot(f.right().cross(f.up())) < 0;
        return Optional.of(new Hit(u, v, t * dir.length(), corner, front));
    }

    /**
     * Corner grab radius for a w x h hologram: 20 % of the shorter side, clamped to [0.2, 0.5] blocks — easy to hit on a
     * small screen, not swallowing the middle of a big one.
     */
    public static double grabRadius(double w, double h) {
        return Math.max(MIN_GRAB_RADIUS, Math.min(MAX_GRAB_RADIUS, 0.2 * Math.min(w, h)));
    }

    /** Position of corner {@code c} in the frame's plane. */
    public static Vec3d cornerPoint(Frame f, Corner c) {
        double sx = c == Corner.TOP_RIGHT || c == Corner.BOTTOM_RIGHT ? 1 : -1;
        double sy = c == Corner.TOP_LEFT || c == Corner.TOP_RIGHT ? 1 : -1;
        return f.center().add(f.right().scale(sx * f.w() / 2)).add(f.up().scale(sy * f.h() / 2));
    }

    /** The nearest corner to the in-plane point (x, y), or null if it is farther than the grab radius. */
    private static Corner cornerAt(double x, double y, Frame f) {
        double hw = f.w() / 2;
        double hh = f.h() / 2;
        boolean top = y >= 0;
        boolean left = x < 0;
        double dx = Math.abs(x) - hw;
        double dy = Math.abs(y) - hh;
        if (Math.sqrt(dx * dx + dy * dy) > grabRadius(f.w(), f.h())) {
            return null;
        }
        if (top) {
            return left ? Corner.TOP_LEFT : Corner.TOP_RIGHT;
        }
        return left ? Corner.BOTTOM_LEFT : Corner.BOTTOM_RIGHT;
    }

    /** Texture pixel under the hit, clamped to [0, texW-1] x [0, texH-1]. */
    public static int[] toPixel(Hit hit, int texW, int texH) {
        int x = (int) Math.floor(hit.u() * texW);
        int y = (int) Math.floor(hit.v() * texH);
        return new int[] {Math.max(0, Math.min(texW - 1, x)), Math.max(0, Math.min(texH - 1, y))};
    }

    /**
     * New width when corner {@code c} is dragged to {@code planePoint} (a point on the frame's plane)
     * with the opposite corner fixed. Two candidates: the width implied by the horizontal distance from the
     * fixed edge, and the width implied by the vertical distance times {@code aspect} (width / height).
     * The axis the user moved more decides: whichever candidate differs more from the current width wins
     * (ties go to horizontal), so pure horizontal, pure vertical and diagonal drags all grow <em>and</em>
     * shrink. Clamped to [0.5, 12.0].
     */
    public static double widthFromDrag(Frame f, Corner c, Vec3d planePoint, double aspect) {
        boolean rightSide = c == Corner.TOP_RIGHT || c == Corner.BOTTOM_RIGHT;
        boolean topSide = c == Corner.TOP_LEFT || c == Corner.TOP_RIGHT;
        Vec3d rel = planePoint.sub(f.center());
        double px = rel.dot(f.right());
        double py = rel.dot(f.up());
        double wH = rightSide ? px + f.w() / 2 : f.w() / 2 - px;
        double hV = topSide ? py + f.h() / 2 : f.h() / 2 - py;
        double wV = hV * aspect;
        double chosen = Math.abs(wV - f.w()) > Math.abs(wH - f.w()) ? wV : wH;
        return HologramSpec.clampWidth(chosen);
    }

    /**
     * Center of the resized frame (newW x newH, same orientation) such that the corner opposite
     * {@code dragged} stays where it was.
     */
    public static Vec3d centerForFixedCorner(Frame f, Corner dragged, double newW, double newH) {
        boolean draggedRight = dragged == Corner.TOP_RIGHT || dragged == Corner.BOTTOM_RIGHT;
        boolean draggedTop = dragged == Corner.TOP_LEFT || dragged == Corner.TOP_RIGHT;
        double sx = draggedRight ? -1 : 1; // side of the fixed corner along right
        double sy = draggedTop ? -1 : 1; // side of the fixed corner along up
        return f.center()
                .add(f.right().scale(sx * (f.w() - newW) / 2))
                .add(f.up().scale(sy * (f.h() - newH) / 2));
    }
}
