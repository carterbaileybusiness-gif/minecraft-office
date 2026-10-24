// Adapted from Wildercord (MIT) https://github.com/defnotean/wildercord @9f5a21fe7964097492cd92ac64e4c4e05ef5236a
package local.carter.office.client.magic.fx;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

import net.minecraft.client.renderer.entity.state.ArmedEntityRenderState;
import net.minecraft.client.renderer.entity.state.EntityRenderState;
import net.minecraft.client.renderer.entity.state.LivingEntityRenderState;
import net.minecraft.world.entity.Entity;

/**
 * Hit-stop, on the client only (Wildercord's HitStop): as a big hit lands, the entities it struck (and the caster) hold still on
 * this screen for a few frames (real time, so it reads the same at any frame rate), then carry on. Nothing in the game waits: the
 * world ticks on and only how they are drawn holds. Where each stood and how it was posed (place, turn, walk, swing) is caught the
 * first time it is drawn in the hold and laid over its render state every frame after ({@code HitStopMixin}); a held mob catches up
 * to where its knockback took it as the moment ends. Stops close together count as one.
 */
public final class HitStop {
	private HitStop() {}

	/** Longest hold accepted, and the gap below which a second stop is ignored. */
	public static final int MAX_MILLIS = 250;
	private static final long GAP_MILLIS = 120;

	private record Pose(double x, double y, double z, float age, float bodyRot, float yRot, float xRot, float walk, float walkSpeed, float swing) {
		static Pose of(EntityRenderState state) {
			float bodyRot = 0;
			float yRot = 0;
			float xRot = 0;
			float walk = 0;
			float walkSpeed = 0;
			float swing = 0;
			if (state instanceof LivingEntityRenderState living) {
				bodyRot = living.bodyRot;
				yRot = living.yRot;
				xRot = living.xRot;
				walk = living.walkAnimationPos;
				walkSpeed = living.walkAnimationSpeed;
			}
			if (state instanceof ArmedEntityRenderState armed) {
				swing = armed.swingAnimation;
			}
			return new Pose(state.x, state.y, state.z, state.ageInTicks, bodyRot, yRot, xRot, walk, walkSpeed, swing);
		}

		void onto(EntityRenderState state) {
			state.x = x;
			state.y = y;
			state.z = z;
			state.ageInTicks = age;
			if (state instanceof LivingEntityRenderState living) {
				living.bodyRot = bodyRot;
				living.yRot = yRot;
				living.xRot = xRot;
				living.walkAnimationPos = walk;
				living.walkAnimationSpeed = walkSpeed;
			}
			if (state instanceof ArmedEntityRenderState armed) {
				armed.swingAnimation = swing;
			}
		}
	}

	private static final class Held {
		final long until;
		Pose pose;
		UUID identity;

		Held(long until) {
			this.until = until;
		}
	}

	private static final Map<Integer, Held> HELD = new HashMap<>();
	private static long lastStart = Long.MIN_VALUE / 4;
	private static int stops;

	/** Holds these entities (network ids; -1 skipped) for {@code millis} of real time, unless a stop began just now. */
	public static void hold(int millis, int... entities) {
		millis = Math.min(millis, MAX_MILLIS);
		if (millis <= 0 || entities.length == 0) {
			return;
		}
		long now = System.nanoTime();
		HELD.values().removeIf(h -> now >= h.until);
		if ((now - lastStart) / 1_000_000L < GAP_MILLIS) {
			return;
		}
		lastStart = now;
		stops++;
		long until = now + millis * 1_000_000L;
		for (int id : entities) {
			if (id >= 0) {
				HELD.put(id, new Held(until));
			}
		}
	}

	/** An entity's render state was just made for this frame: while it is held, it stands and is posed as when the hold began. */
	public static void extracted(Entity entity, EntityRenderState state) {
		if (HELD.isEmpty() || state == null) {
			return;
		}
		Held held = HELD.get(entity.getId());
		if (held == null) {
			return;
		}
		if (System.nanoTime() >= held.until || entity.isRemoved() || held.identity != null && !held.identity.equals(entity.getUUID())) {
			HELD.remove(entity.getId());
			return;
		}
		if (held.pose == null) {
			held.identity = entity.getUUID();
			held.pose = Pose.of(state);
		} else {
			held.pose.onto(state);
		}
	}

	/** Whether anything is held now. */
	public static boolean holding() {
		long now = System.nanoTime();
		HELD.values().removeIf(h -> now >= h.until);
		return !HELD.isEmpty();
	}

	/** Lets go of everything (leaving a world). */
	public static void clear() {
		HELD.clear();
		lastStart = Long.MIN_VALUE / 4;
	}

	/** How many stops began since the game started (the game tests read it). */
	public static int stops() {
		return stops;
	}
}
