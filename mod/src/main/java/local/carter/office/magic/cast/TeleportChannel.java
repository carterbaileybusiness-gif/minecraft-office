package local.carter.office.magic.cast;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Predicate;

import org.jspecify.annotations.Nullable;

import local.carter.office.magic.core.Slots;
import local.carter.office.magic.data.MagicAttachments;
import local.carter.office.magic.data.MagicData;
import local.carter.office.magic.net.FxEvent;
import local.carter.office.magic.net.MagicServer;
import local.carter.office.magic.spell.ActiveEffects;
import local.carter.office.magic.spell.LingeringEffect;
import local.carter.office.magic.spell.SpellCost;
import local.carter.office.magic.spell.SpellIds;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.tags.BlockTags;
import net.minecraft.tags.FluidTags;
import net.minecraft.world.entity.Pose;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.portal.TeleportTransition;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.VoxelShape;

/**
 * The 3 s teleport channel Recall and Travel share, and the safe-landing search both use.
 *
 * <p><b>The channel</b> ({@link #start}) is a {@link LingeringEffect} of {@link #CHANNEL_TICKS} ticks. While it runs every cast
 * is BUSY ({@link #channeling}). Taking damage, dying, disconnecting or changing dimension interrupts it ({@link #interrupt}):
 * no teleport, no cooldown. Moving does not (the spec cancels on damage only), and nothing roots the player: the client owns
 * player movement, so the circle simply follows the caster. At the end the {@link Trip} is asked where to land <i>now</i>;
 * nowhere safe → the caster stays, is told, and pays nothing. Otherwise the caster is dismounted (the mount stays, like an ender
 * pearl), riders are ejected, the caster is teleported, and the shared Recall cooldown ({@code SpellIds.RECALL}, 60 s, never
 * lowered) starts. Leashed animals are left to vanilla: the leash snaps and the lead drops beyond 12 blocks or across
 * dimensions, so nothing is lost.
 *
 * <p><b>Safe landing</b> ({@link #safeSpot}): a box the size of the player must fit with no block collision inside the world
 * border and the build height, with no lava, fire, campfire, cactus, berry bush, wither rose, magma or powder snow in the
 * player's two cells, and solid or watery ground (not lava, magma, campfire...) within {@link #MAX_DROP} blocks below, so a
 * void, a cliff or a lava floor are never a landing. A low floor in the cell (carpet, path, farmland, snow, a slab) lifts the
 * landing to the top of its collision shape. The cells within {@link #SEARCH_XZ} blocks sideways and {@link #SEARCH_Y} up or down
 * are tried nearest first. Loading the chunk is done on demand (as vanilla does for a respawn point).
 */
public final class TeleportChannel {
	/** Length of the channel, in ticks (3 s). */
	public static final int CHANNEL_TICKS = 60;
	/** How far from the wanted cell a safe cell is looked for. */
	public static final int SEARCH_XZ = 2;
	public static final int SEARCH_Y = 2;
	/** Ground may be at most this many blocks below the feet (a drop of 3 does no fall damage). */
	public static final int MAX_DROP = 3;
	/** {@code FxEvent.data[1]} of a channel circle: 0 = Recall, 1 = Travel (data[0] stays 0 = a lasting channel). */
	public static final float FX_RECALL = 0f;
	public static final float FX_TRAVEL = 1f;

	/** Channeling player → their channel. Server thread only. */
	private static final Map<UUID, Channel> CHANNELS = new HashMap<>();
	/** Candidate offsets around the wanted cell, nearest first (the cell itself first). */
	private static final List<BlockPos> OFFSETS = offsets();
	/** The same offsets with the wanted cell's own column last ("beside": never inside another player if avoidable). */
	private static final List<BlockPos> BESIDE = beside(OFFSETS);

	private TeleportChannel() {}

	/** Where a channel lands: the level, the feet position and the facing. */
	public record Landing(ServerLevel level, Vec3 pos, float yaw, float pitch) {}

	/** What a channel carries the caster to, and what it tells them. Server thread only. */
	public interface Trip {
		/** Where the caster lands now (asked again when the channel ends), or null when nowhere is safe or the destination is gone. */
		@Nullable Landing resolve(ServerPlayer caster);

		/** The channel ended but {@link #resolve} found nowhere safe: no teleport, no cooldown. Tell the caster. */
		void blocked(ServerPlayer caster);

		/** The channel was interrupted with {@code notify} (damage, death, dimension change): no teleport, no cooldown. */
		void interrupted(ServerPlayer caster);

		/** The caster arrived; the shared cooldown is already set. */
		default void arrived(ServerPlayer caster, Landing at) {}

		/** The channel is over for any reason (arrived, blocked, interrupted, cancelled). */
		default void ended(ServerPlayer caster) {}
	}

	/** True while {@code p} has a channel running (Recall or Travel); {@code CastService} answers BUSY to every cast meanwhile. */
	public static boolean channeling(ServerPlayer p) {
		return CHANNELS.containsKey(p.getUUID());
	}

	/** The trip {@code p} is channeling, or null. */
	public static @Nullable Trip trip(ServerPlayer p) {
		Channel c = CHANNELS.get(p.getUUID());
		return c == null ? null : c.trip;
	}

	/** The players whose running trip matches {@code which}. */
	public static List<ServerPlayer> casters(Predicate<Trip> which) {
		List<ServerPlayer> out = new ArrayList<>();
		for (Channel c : CHANNELS.values()) if (which.test(c.trip)) out.add(c.player);
		return out;
	}

	/**
	 * Starts {@code p}'s channel toward {@code trip} (the caller has already checked that {@code trip} resolves and that
	 * {@code p} is not channeling). False when the server's lingering effects are full (nothing started).
	 */
	public static boolean start(ServerPlayer p, Trip trip) {
		if (channeling(p)) return false;
		Channel channel = new Channel(p, trip);
		if (!ActiveEffects.add(p.level(), channel)) return false;
		CHANNELS.put(p.getUUID(), channel);
		return true;
	}

	/**
	 * Interrupts {@code p}'s channel, if any: no teleport and no cooldown. Everyone drawing the circle is told to stop, and the
	 * trip tells the player unless {@code notify} is false (a disconnect, or a caller that sends its own message).
	 *
	 * @return true if a channel was running
	 */
	public static boolean interrupt(ServerPlayer p, boolean notify) {
		Channel c = CHANNELS.remove(p.getUUID());
		if (c == null) return false;
		MagicServer.sender().cancelFx(p);
		if (notify) c.trip.interrupted(p);
		c.trip.ended(p);
		ActiveEffects.remove(c);
		return true;
	}

	/**
	 * The one channel fx: origin = the caster's feet, direction up (the circle's normal is chosen by the client), duration
	 * {@link #CHANNEL_TICKS} (the lasting circle, until {@code FxCancel}), {@code data = {0, kind}}.
	 */
	public static FxEvent fx(ServerPlayer p, float charge, float power, long seed, float kind) {
		Vec3 feet = p.position();
		return new FxEvent(SpellIds.RECALL, p.getId(), feet, new Vec3(0, 1, 0), feet, charge, power, seed, CHANNEL_TICKS, new int[0],
				new float[] {0f, kind});
	}

	// ---------------------------------------------------------------- safe landing

	/**
	 * Where a player the size of {@code p} can stand safely at or near {@code cell} in {@code level} (see the class comment), or
	 * null. With {@code beside} the cell's own column is tried last (landing next to someone rather than inside them). Loads
	 * the chunk if it is not loaded.
	 */
	public static @Nullable Vec3 safeSpot(ServerPlayer p, ServerLevel level, BlockPos cell, boolean beside) {
		for (BlockPos offset : beside ? BESIDE : OFFSETS) {
			Vec3 at = arrival(p, level, cell.offset(offset));
			if (at != null) return at;
		}
		return null;
	}

	/**
	 * Where the player would stand in the cell {@code feet}, or null if that is not a safe place. A low floor in the cell (carpet,
	 * path, farmland, snow layers, a slab) lifts the arrival to the top of its collision shape; a full block is not a floor to
	 * stand in, it is an obstruction (the box test fails).
	 */
	private static @Nullable Vec3 arrival(ServerPlayer p, ServerLevel level, BlockPos feet) {
		if (level.isOutsideBuildHeight(feet) || level.isOutsideBuildHeight(feet.getY() + 1)) return null;
		BlockState inside = level.getBlockState(feet);
		if (dangerous(inside) || dangerous(level.getBlockState(feet.above()))) return null;
		double lift = 0;
		VoxelShape shape = inside.getCollisionShape(level, feet);
		if (!shape.isEmpty()) {
			double top = shape.max(Direction.Axis.Y);
			if (top > 0 && top < 1) lift = top;
		}
		Vec3 at = new Vec3(feet.getX() + 0.5, feet.getY() + lift, feet.getZ() + 0.5);
		AABB box = p.getDimensions(Pose.STANDING).makeBoundingBox(at);
		if (!level.getWorldBorder().isWithinBounds(box)) return null;
		if (!level.noCollision(box)) return null;
		for (int drop = 1; drop <= MAX_DROP + 1; drop++) {
			BlockPos below = feet.below(drop);
			if (level.isOutsideBuildHeight(below)) return null;
			BlockState s = level.getBlockState(below);
			if (dangerous(s)) return null;
			if (!s.getCollisionShape(level, below).isEmpty() || !s.getFluidState().isEmpty()) return at;
		}
		return null;
	}

	/** Blocks that hurt to stand in or on. */
	static boolean dangerous(BlockState s) {
		return s.getFluidState().is(FluidTags.LAVA) || s.is(BlockTags.FIRE) || s.is(BlockTags.CAMPFIRES) || s.is(Blocks.CACTUS)
				|| s.is(Blocks.MAGMA_BLOCK) || s.is(Blocks.SWEET_BERRY_BUSH) || s.is(Blocks.WITHER_ROSE) || s.is(Blocks.POWDER_SNOW);
	}

	private static List<BlockPos> offsets() {
		List<BlockPos> out = new ArrayList<>();
		for (int dx = -SEARCH_XZ; dx <= SEARCH_XZ; dx++) {
			for (int dy = -SEARCH_Y; dy <= SEARCH_Y; dy++) {
				for (int dz = -SEARCH_XZ; dz <= SEARCH_XZ; dz++) out.add(new BlockPos(dx, dy, dz));
			}
		}
		// Nearest first; at equal distance prefer the same height, then a higher cell over a lower one.
		out.sort(Comparator.comparingInt((BlockPos o) -> o.getX() * o.getX() + o.getY() * o.getY() + o.getZ() * o.getZ())
				.thenComparingInt(o -> Math.abs(o.getY())).thenComparingInt(o -> -o.getY())
				.thenComparingInt(BlockPos::getX).thenComparingInt(BlockPos::getZ));
		return List.copyOf(out);
	}

	private static List<BlockPos> beside(List<BlockPos> offsets) {
		List<BlockPos> out = new ArrayList<>();
		for (BlockPos o : offsets) if (o.getX() != 0 || o.getZ() != 0) out.add(o);
		for (BlockPos o : offsets) if (o.getX() == 0 && o.getZ() == 0) out.add(o);
		return List.copyOf(out);
	}

	// ---------------------------------------------------------------- the channel

	/** The running channel: waits {@link #CHANNEL_TICKS}, then carries the caster to wherever the trip resolves now. */
	private static final class Channel implements LingeringEffect {
		private final ServerPlayer player;
		private final Trip trip;

		Channel(ServerPlayer player, Trip trip) {
			this.player = player;
			this.trip = trip;
		}

		@Override
		public boolean tick(ServerLevel level, int age) {
			ServerPlayer p = player;
			// The hooks interrupt first; this is the backstop for any way a player can vanish without one.
			if (CHANNELS.get(p.getUUID()) != this) return false;
			if (p.isRemoved() || p.hasDisconnected() || !p.isAlive() || p.level() != level) {
				CHANNELS.remove(p.getUUID(), this);
				MagicServer.sender().cancelFx(p);
				trip.ended(p);
				return false;
			}
			if (age < CHANNEL_TICKS) return true;
			CHANNELS.remove(p.getUUID(), this); // before the teleport: the dimension-change hook must not read it as an interruption
			try {
				complete(p);
			} finally {
				trip.ended(p);
			}
			return false;
		}

		@Override
		public void end(ServerLevel level) {
			if (CHANNELS.remove(player.getUUID(), this)) trip.ended(player);
		}

		private void complete(ServerPlayer p) {
			Landing at = trip.resolve(p);
			if (at == null) {
				MagicServer.sender().cancelFx(p);
				trip.blocked(p);
				return;
			}
			p.stopRiding();
			p.ejectPassengers();
			ServerPlayer moved = p.teleport(new TeleportTransition(at.level(), at.pos(), Vec3.ZERO, at.yaw(), at.pitch(),
					TeleportTransition.DO_NOTHING));
			if (moved == null) { // a mod vetoed the teleport
				MagicServer.sender().cancelFx(p);
				trip.blocked(p);
				return;
			}
			p.resetFallDistance();
			Vec3 dest = at.pos();
			ServerLevel target = at.level();
			target.playSound(null, dest.x, dest.y, dest.z, SoundEvents.ENDERMAN_TELEPORT, SoundSource.PLAYERS, 1f, 1f);
			target.sendParticles(ParticleTypes.PORTAL, dest.x, dest.y + 1, dest.z, 32, 0.3, 0.6, 0.3, 0.1);
			MagicData d = MagicAttachments.get(p);
			long ready = target.getGameTime() + SpellCost.forSlot(Slots.RECALL).cooldownTicks();
			Long existing = d.readyAt().get(SpellIds.RECALL);
			MagicAttachments.set(p, d.withReady(SpellIds.RECALL, Math.max(existing == null ? Long.MIN_VALUE : existing, ready)));
			trip.arrived(p, at);
		}
	}
}
