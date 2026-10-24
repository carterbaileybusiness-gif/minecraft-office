package local.carter.office.magic.terrain;

/** Terrain power of a spell: radius cap per edit batch and block budget per cast. */
public enum EditTier {
	MEDIUM(6, 256),
	ULTIMATE(12, 1500);

	private final double radius;
	private final int budget;

	EditTier(double radius, int budget) {
		this.radius = radius;
		this.budget = budget;
	}

	/** Max distance of an edit from its batch origin. */
	public double radius() { return radius; }

	/** Max block changes per cast, summed over all of its batches. */
	public int budget() { return budget; }
}
