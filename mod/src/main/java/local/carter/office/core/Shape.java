package local.carter.office.core;

/** Hologram monitor shapes and their native pixel sizes. */
public enum Shape {
    WIDE_16_9(1920, 1080),
    ULTRA_21_9(2560, 1080),
    TALL_9_16(1080, 1920);

    private final int pxW;
    private final int pxH;

    Shape(int pxW, int pxH) {
        this.pxW = pxW;
        this.pxH = pxH;
    }

    public int pxW() {
        return pxW;
    }

    public int pxH() {
        return pxH;
    }

    /** Width divided by height. */
    public double aspect() {
        return (double) pxW / pxH;
    }
}
