package local.carter.office.magic.spell;

import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.tags.DamageTypeTags;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

/** Moving the caster: dashes (velocity, no fall damage for a while) and safe short teleports. Server thread only. */
public final class Motion {
	/** How far {@link #teleportSafe} steps back per try. */
	public static final double STEP_BACK = 0.25;

	/** Player → game time until which fall damage is ignored. */
	private static final Map<UUID, Long> NO_FALL_UNTIL = new HashMap<>();

	private Motion() {}

	/**
	 * Sets the player's velocity to {@code velocity} (synced to their client, which does the moving) and ignores fall damage
	 * for the next {@code noFallTicks} ticks (fall distance is also reset each tick during that window).
	 */
	public static void dash(ServerPlayer p, Vec3 velocity, int noFallTicks) {
		p.setDeltaMovement(velocity);
		p.syncVelocity = true;
		p.needsSync = true;
		p.resetFallDistance();
		if (noFallTicks > 0) {
			long until = p.level().getGameTime() + noFallTicks;
			NO_FALL_UNTIL.merge(p.getUUID(), until, Math::max);
		}
	}

	/** True while a dash's no-fall window is open for {@code p}. */
	public static boolean noFall(ServerPlayer p) {
		Long until = NO_FALL_UNTIL.get(p.getUUID());
		return until != null && p.level().getGameTime() <= until;
	}

	/**
	 * Teleports the player to {@code target} in its level, or, if the player's box does not fit there (or it is outside the
	 * world border), to the nearest point stepping back along the path toward where they stand that fits. If nothing fits
	 * the player stays put.
	 *
	 * @return where the player ended up
	 */
	public static Vec3 teleportSafe(ServerPlayer p, Vec3 target) {
		ServerLevel level = p.level();
		Vec3 from = p.position();
		Vec3 path = target.subtract(from);
		double len = path.length();
		Vec3 back = len < 1e-6 ? Vec3.ZERO : path.scale(-1 / len);
		for (double d = 0; d <= len; d += STEP_BACK) {
			Vec3 at = target.add(back.scale(d));
			AABB box = p.getDimensions(p.getPose()).makeBoundingBox(at);
			if (level.getWorldBorder().isWithinBounds(at) && level.noCollision(p, box)) {
				if (p.isPassenger()) p.stopRiding();
				p.teleportTo(level, at.x, at.y, at.z, Set.of(), p.getYRot(), p.getXRot(), true);
				p.resetFallDistance();
				return at;
			}
		}
		return from;
	}

	/** {@code ServerLivingEntityEvents.ALLOW_DAMAGE}: false for fall damage inside a dash's no-fall window. */
	public static boolean allowDamage(LivingEntity e, DamageSource source, float amount) {
		return !(e instanceof ServerPlayer p && source.is(DamageTypeTags.IS_FALL) && noFall(p));
	}

	/** Once per server tick: keeps fall distance at 0 inside open windows and forgets closed ones (and players who left). */
	public static void tick(MinecraftServer server) {
		if (NO_FALL_UNTIL.isEmpty()) return;
		Iterator<Map.Entry<UUID, Long>> it = NO_FALL_UNTIL.entrySet().iterator();
		while (it.hasNext()) {
			Map.Entry<UUID, Long> e = it.next();
			ServerPlayer p = server.getPlayerList().getPlayer(e.getKey());
			if (p == null || p.level().getGameTime() > e.getValue()) {
				it.remove();
			} else {
				p.resetFallDistance();
			}
		}
	}

	/** Forgets everything (server stopping). */
	public static void clear() {
		NO_FALL_UNTIL.clear();
	}
}
