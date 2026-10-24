package local.carter.office.magic.core;

public final class WheelMath {
	/** Inside this radius (px) no slice is hovered. */
	public static final int DEAD_ZONE = 14;
	/** Hover starts only after the accumulated vector has moved more than this since the wheel opened. */
	public static final int SELECT_MIN = 4;
	public static final int MAX_RADIUS = 60;

	private WheelMath() {}

	/** Slice under the offset (screen coordinates, y down). Slice 0 is centred straight up, clockwise. -1 in the dead zone. */
	public static int sliceAt(double dx, double dy, int slices) {
		if (Math.sqrt(dx * dx + dy * dy) < DEAD_ZONE) return -1;
		double angle = Math.toDegrees(Math.atan2(dx, -dy)); // 0 = up, clockwise positive
		if (angle < 0) angle += 360;
		double width = 360.0 / slices;
		return (int) Math.floor((angle + width / 2) / width) % slices;
	}

	/** Limits the accumulated vector to MAX_RADIUS. */
	public static double[] clamp(double dx, double dy) {
		double len = Math.sqrt(dx * dx + dy * dy);
		if (len <= MAX_RADIUS) return new double[] {dx, dy};
		double k = MAX_RADIUS / len;
		return new double[] {dx * k, dy * k};
	}
}
