package local.carter.office.client.magic.fx;

/**
 * One fx event's budget (pure): at most {@link #PARTICLES} particles ({@link #ULTIMATE_PARTICLES} for an ultimate) and
 * {@link #GHOST_BLOCKS} ghost blocks over the whole life of the event, lingering ticks included. Each event gets a fresh one.
 * Every {@link FxKit} call asks it first and draws only what it grants.
 */
public final class FxBudget {
	public static final int PARTICLES = 400;
	public static final int ULTIMATE_PARTICLES = 1500;
	public static final int GHOST_BLOCKS = 128;

	private final int particleCap;
	private int particles;
	private int ghosts;

	public FxBudget(boolean ultimate) {
		this.particleCap = ultimate ? ULTIMATE_PARTICLES : PARTICLES;
	}

	/** Grants up to {@code requested} particles (0 once the budget is spent) and counts them. */
	public int particles(int requested) {
		int granted = Math.max(0, Math.min(requested, particleCap - particles));
		particles += granted;
		return granted;
	}

	/** One ghost block: true (counted) while fewer than {@link #GHOST_BLOCKS} were granted, else false. */
	public boolean ghostBlock() {
		if (ghosts >= GHOST_BLOCKS) {
			return false;
		}
		ghosts++;
		return true;
	}

	public int particlesLeft() {
		return particleCap - particles;
	}

	public int ghostBlocksLeft() {
		return GHOST_BLOCKS - ghosts;
	}
}
