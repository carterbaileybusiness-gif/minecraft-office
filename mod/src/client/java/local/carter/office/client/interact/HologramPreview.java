package local.carter.office.client.interact;

import java.util.HashMap;
import java.util.Map;

import org.jspecify.annotations.Nullable;

import local.carter.office.core.HologramSpec;
import local.carter.office.hologram.HologramBlockEntity;

import net.minecraft.core.BlockPos;
import net.minecraft.util.Util;

/**
 * Specs the renderer should show instead of the synced one: the live position while moving, and the predicted result
 * of a just-released drag until the server's update arrives (so the hologram doesn't snap back for a round trip).
 */
public final class HologramPreview {
	private static final long PENDING_MS = 1500;
	private static final double EPS = 1e-4;

	private record Pending(HologramSpec spec, long until) {}

	private static final Map<BlockPos, Pending> PENDING = new HashMap<>();

	private HologramPreview() {}

	/** Keep showing {@code predicted} until the block entity's spec matches it (or 1.5 s pass). */
	public static void expect(final BlockPos pos, final HologramSpec predicted) {
		PENDING.put(pos.immutable(), new Pending(predicted, Util.getMillis() + PENDING_MS));
	}

	/** The spec to draw for {@code be}: an active move, a pending prediction, or the synced spec. */
	public static @Nullable HologramSpec effective(final HologramBlockEntity be) {
		HologramSpec synced = be.spec();
		HologramSpec moving = HologramInteractions.movingPreview(be);
		if (moving != null) {
			return moving;
		}
		Pending p = PENDING.get(be.getBlockPos());
		if (p == null) {
			return synced;
		}
		if (synced == null || Util.getMillis() > p.until() || same(synced, p.spec())) {
			PENDING.remove(be.getBlockPos());
			return synced;
		}
		return p.spec();
	}

	static boolean same(final HologramSpec a, final HologramSpec b) {
		return Math.abs(a.width() - b.width()) < EPS && Math.abs(a.offX() - b.offX()) < EPS && Math.abs(a.offY() - b.offY()) < EPS
				&& Math.abs(a.offZ() - b.offZ()) < EPS && Math.abs(a.yaw() - b.yaw()) < 1e-3;
	}

	public static void clear() {
		PENDING.clear();
	}
}
