package local.carter.office.core;

import java.util.Optional;

/**
 * Ray / quad math for hologram monitors. The frame is a rectangle centred at {@code center},
 * spanned by the unit vectors {@code right} and {@code up}. (u,v) are texture coordinates:
 * u grows along {@code right}, v grows downward (v=0 is the top edge).
 */
public final class HologramGeometry {
    /** A grab handle counts when the hit is within this many blocks of a corner. */
    public static final double CORNER_RADIUS = 0.25;

    private static final double EPS = 1e-9;

    private HologramGeometry() {}

    public enum Corner { TOP_LEFT, TOP_RIGHT, BOTTOM_LEFT, BOTTOM_RIGHT }

    public record Frame(Vec3d center, Vec3d right, Vec3d up, double w, double h) {}

    /**
     * {@code corner} is null unless the hit is within {@link #CORNER_RADIUS} of a corner.
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
        if (u < 0 || u > 1 || v < 0 || v > 1) {
            return Optional.empty();
        }
        boolean front = dir.dot(f.right().cross(f.up())) < 0;
        return Optional.of(new Hit(u, v, t * dir.length(), cornerAt(x, y, f), front));
    }

    private static Corner cornerAt(double x, double y, Frame f) {
        double hw = f.w() / 2;
        double hh = f.h() / 2;
        double dx = hw - Math.abs(x);
        double dy = hh - Math.abs(y);
        if (Math.sqrt(dx * dx + dy * dy) >= CORNER_RADIUS) {
            return null;
        }
        boolean top = y >= 0;
        boolean left = x < 0;
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
     * with the opposite corner fixed. Takes the larger of the width implied by the horizontal distance
     * from the fixed edge and the width implied by the vertical distance times {@code aspect}
     * (width / height), so horizontal, vertical and diagonal drags all resize. Clamped to [0.5, 12.0].
     */
    public static double widthFromDrag(Frame f, Corner c, Vec3d planePoint, double aspect) {
        boolean rightSide = c == Corner.TOP_RIGHT || c == Corner.BOTTOM_RIGHT;
        boolean topSide = c == Corner.TOP_LEFT || c == Corner.TOP_RIGHT;
        Vec3d rel = planePoint.sub(f.center());
        double px = rel.dot(f.right());
        double py = rel.dot(f.up());
        double wH = rightSide ? px + f.w() / 2 : f.w() / 2 - px;
        double hV = topSide ? py + f.h() / 2 : f.h() / 2 - py;
        return HologramSpec.clampWidth(Math.max(wH, hV * aspect));
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
