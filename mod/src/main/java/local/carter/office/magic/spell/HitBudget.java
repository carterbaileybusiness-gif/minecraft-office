package local.carter.office.magic.spell;

import java.util.HashSet;
import java.util.Set;

import net.minecraft.world.entity.Entity;

/**
 * The per-cast entity budget of a spell that hurts again and again (a lingering effect, a volley): over the whole cast it hurts
 * at most {@link Targets#MAX_ENTITIES} different creatures. Each query is capped already ({@code Targets}); this caps their sum,
 * which a moving or repeating effect would otherwise let grow. One per cast, server thread only.
 */
public final class HitBudget {
	private final Set<Integer> hit = new HashSet<>();

	/** True when this cast may hurt {@code e}: it already did, or fewer than the budget so far (then {@code e} is counted). */
	public boolean admit(Entity e) {
		if (hit.contains(e.getId())) return true;
		if (hit.size() >= Targets.MAX_ENTITIES) return false;
		hit.add(e.getId());
		return true;
	}

	/** Different creatures counted so far. */
	public int size() {
		return hit.size();
	}
}
