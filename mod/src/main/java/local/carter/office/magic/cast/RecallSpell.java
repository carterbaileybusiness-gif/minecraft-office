package local.carter.office.magic.cast;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import org.jspecify.annotations.Nullable;

import local.carter.office.magic.core.Element;
import local.carter.office.magic.core.Slots;
import local.carter.office.magic.data.MagicAttachments;
import local.carter.office.magic.data.MagicData;
import local.carter.office.magic.net.FxEvent;
import local.carter.office.magic.net.MagicServer;
import local.carter.office.magic.spell.ActiveEffects;
import local.carter.office.magic.spell.CastContext;
import local.carter.office.magic.spell.CastOutcome;
import local.carter.office.magic.spell.LingeringEffect;
import local.carter.office.magic.spell.Spell;
import local.carter.office.magic.spell.SpellCost;
import local.carter.office.magic.spell.SpellIds;

import net.minecraft.core.BlockPos;
import net.minecraft.core.GlobalPos;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.server.MinecraftServer;
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

/**
 * Recall, the one spell every element shares (slot 5, level 1, no mana).
 *
 * <ul>
 * <li><b>Shift + cast</b> marks home: the feet block, in any dimension, replacing the old home (one home per player). It sets
 * Recall's cooldown to {@link #MARK_COOLDOWN_TICKS} so a double press cannot mark twice.
 * <li><b>Cast</b> with a home starts a {@link #CHANNEL_TICKS}-tick channel (a {@link LingeringEffect}); while it runs every
 * other cast is BUSY ({@link #channeling}). Taking damage, dying, disconnecting or changing dimension interrupts it
 * ({@link #interrupt}): no teleport, no cooldown. Moving does not (the spec cancels on damage only), and nothing roots the
 * player: the client owns player movement, so the circle simply follows the caster. When it completes the caster is teleported
 * home and Recall's 60 s cooldown starts.
 * </ul>
 *
 * <p><b>Teleport safety</b> ({@link #destination}), decided when the channel starts (so a doomed recall fizzles free instead of
 * making the player wait 3 s) and again at the end:
 * <ul>
 * <li>The home dimension must still exist; its chunk is loaded on demand (the same way vanilla loads a respawn point's chunk).
 * <li>A box the size of the player must fit with no block collision inside the world border, with no lava, fire, campfire,
 * cactus, berry bush, wither rose or powder snow in the player's two cells, and solid or watery ground (not lava, magma,
 * campfire...) within {@link #MAX_DROP} blocks below, so a void, a cliff or a lava floor are never a destination.
 * <li>If the exact home cell fails, the nearest cell within {@link #SEARCH_XZ} blocks sideways and {@link #SEARCH_Y} up or down
 * that passes is used (a block placed in the home, or a slab floor, still works).
 * <li>If nothing passes the player stays where they are, is told {@code magic.office.home_blocked}, and pays no cooldown. The home
 * is kept: the obstruction may be cleared later.
 * <li>The caster is dismounted and anything riding the caster is ejected first (the mount or rider stays behind, like an ender
 * pearl). Leashed animals are left to vanilla: the leash snaps and the lead drops when the player is more than 12 blocks away or
 * in another dimension, so nothing is lost.
 * </ul>
 */
public final class RecallSpell implements Spell {
	/** Length of the channel, in ticks (3 s). */
	public static final int CHANNEL_TICKS = 60;
	/** Cooldown set by Mark Home, in ticks. */
	public static final int MARK_COOLDOWN_TICKS = 20;
	/** Action-bar message when the home cannot be reached safely. */
	public static final String HOME_BLOCKED_KEY = "magic.office.home_blocked";
	/** How far from the home cell a safe cell is looked for. */
	public static final int SEARCH_XZ = 2;
	public static final int SEARCH_Y = 2;
	/** Ground may be at most this many blocks below the feet (a drop of 3 does no fall damage). */
	public static final int MAX_DROP = 3;

	/** Channeling player → their channel. Server thread only. */
	private static final Map<UUID, Channel> CHANNELS = new HashMap<>();
	/** Candidate offsets around the home cell, nearest first (the home cell itself first). */
	private static final List<BlockPos> OFFSETS = offsets();

	@Override
	public Identifier id() {
		return SpellIds.RECALL;
	}

	@Override
	public @Nullable Element element() {
		return null;
	}

	@Override
	public int slot() {
		return Slots.RECALL;
	}

	@Override
	public boolean managesOwnCooldown() {
		return true;
	}

	/** True while {@code p} has a Recall channel running ({@code CastService} answers BUSY to every cast meanwhile). */
	public static boolean channeling(ServerPlayer p) {
		return CHANNELS.containsKey(p.getUUID());
	}

	@Override
	public CastOutcome cast(CastContext ctx) {
		ServerPlayer p = ctx.caster();
		return p.isShiftKeyDown() ? markHome(ctx) : startChannel(ctx);
	}

	private static CastOutcome markHome(CastContext ctx) {
		ServerPlayer p = ctx.caster();
		long now = ctx.level().getGameTime();
		MagicData d = MagicAttachments.get(p);
		Long existing = d.readyAt().get(SpellIds.RECALL);
		MagicAttachments.set(p, d.withHome(Optional.of(GlobalPos.of(ctx.level().dimension(), p.blockPosition())))
				.withReady(SpellIds.RECALL, Math.max(existing == null ? Long.MIN_VALUE : existing, now + MARK_COOLDOWN_TICKS)));
		MagicServer.sender().actionBar(p, Component.translatable("magic.office.home_marked"));
		return CastOutcome.cast(fx(ctx, 0));
	}

	private static CastOutcome startChannel(CastContext ctx) {
		ServerPlayer p = ctx.caster();
		Optional<GlobalPos> home = MagicAttachments.get(p).home();
		if (home.isEmpty()) return CastOutcome.fizzle("magic.office.no_home");
		if (destination(p, home.get()) == null) return CastOutcome.fizzle(HOME_BLOCKED_KEY);
		Channel channel = new Channel(p, home.get());
		if (!ActiveEffects.add(ctx.level(), channel)) return CastOutcome.fizzle(CastService.BUSY_KEY);
		CHANNELS.put(p.getUUID(), channel);
		MagicServer.sender().actionBar(p, Component.translatable("magic.office.recalling"));
		return CastOutcome.cast(fx(ctx, CHANNEL_TICKS));
	}

	/**
	 * The one fx: origin = the caster's feet, direction up (the circle's normal is chosen by the client). Duration 0 =
	 * Mark Home (a short flash, {@code data[0] = 1}); {@link #CHANNEL_TICKS} = the lasting channel circle (until
	 * {@code FxCancel}).
	 */
	private static FxEvent fx(CastContext ctx, int duration) {
		Vec3 feet = ctx.caster().position();
		return new FxEvent(SpellIds.RECALL, ctx.caster().getId(), feet, new Vec3(0, 1, 0), feet, ctx.charge(), ctx.power(), ctx.seed(),
				duration, new int[0], new float[] {duration == 0 ? 1f : 0f});
	}

	/**
	 * Interrupts {@code p}'s channel, if any: no teleport and no cooldown. Everyone drawing the circle is told to stop, and
	 * the player gets {@code magic.office.recall_interrupted} unless {@code notify} is false (a disconnect).
	 *
	 * @return true if a channel was running
	 */
	public static boolean interrupt(ServerPlayer p, boolean notify) {
		Channel c = CHANNELS.remove(p.getUUID());
		if (c == null) return false;
		abandon(p, notify);
		ActiveEffects.remove(c);
		return true;
	}

	private static void abandon(ServerPlayer p, boolean notify) {
		MagicServer.sender().cancelFx(p);
		if (notify) MagicServer.sender().actionBar(p, Component.translatable("magic.office.recall_interrupted"));
	}

	/**
	 * Where {@code home} can be reached safely (see the class comment), or null. Loads the home chunk if it is not loaded.
	 * {@code p} only supplies the body size.
	 */
	public static @Nullable Vec3 destination(ServerPlayer p, GlobalPos home) {
		MinecraftServer server = p.level().getServer();
		ServerLevel level = server.getLevel(home.dimension());
		if (level == null) return null;
		for (BlockPos offset : OFFSETS) {
			BlockPos at = home.pos().offset(offset);
			if (safe(p, level, at)) return Vec3.atBottomCenterOf(at);
		}
		return null;
	}

	/** Whether the player's body fits at the bottom centre of {@code feet} and the ground below it is safe. */
	private static boolean safe(ServerPlayer p, ServerLevel level, BlockPos feet) {
		if (level.isOutsideBuildHeight(feet) || level.isOutsideBuildHeight(feet.getY() + 1)) return false;
		Vec3 at = Vec3.atBottomCenterOf(feet);
		if (!level.getWorldBorder().isWithinBounds(at)) return false;
		AABB box = p.getDimensions(Pose.STANDING).makeBoundingBox(at);
		if (!level.noCollision(box)) return false;
		if (dangerous(level.getBlockState(feet)) || dangerous(level.getBlockState(feet.above()))) return false;
		for (int drop = 1; drop <= MAX_DROP + 1; drop++) {
			BlockPos below = feet.below(drop);
			if (level.isOutsideBuildHeight(below)) return false;
			BlockState s = level.getBlockState(below);
			if (dangerous(s)) return false;
			if (!s.getCollisionShape(level, below).isEmpty() || !s.getFluidState().isEmpty()) return true;
		}
		return false;
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

	/** The running channel: waits {@link #CHANNEL_TICKS}, then carries the caster home. */
	private static final class Channel implements LingeringEffect {
		private final ServerPlayer player;
		private final GlobalPos home;

		Channel(ServerPlayer player, GlobalPos home) {
			this.player = player;
			this.home = home;
		}

		@Override
		public boolean tick(ServerLevel level, int age) {
			ServerPlayer p = player;
			// The hooks interrupt first; this is the backstop for any way a player can vanish without one.
			if (CHANNELS.get(p.getUUID()) != this) return false;
			if (p.isRemoved() || p.hasDisconnected() || !p.isAlive() || p.level() != level) {
				CHANNELS.remove(p.getUUID(), this);
				abandon(p, false);
				return false;
			}
			if (age < CHANNEL_TICKS) return true;
			CHANNELS.remove(p.getUUID(), this); // before the teleport: the dimension-change hook must not read it as an interruption
			complete(p);
			return false;
		}

		@Override
		public void end(ServerLevel level) {
			CHANNELS.remove(player.getUUID(), this);
		}

		private void complete(ServerPlayer p) {
			Vec3 dest = destination(p, home);
			ServerLevel target = dest == null ? null : p.level().getServer().getLevel(home.dimension());
			if (dest == null || target == null) {
				abandon(p, false);
				MagicServer.sender().actionBar(p, Component.translatable(HOME_BLOCKED_KEY));
				return;
			}
			p.stopRiding();
			p.ejectPassengers();
			ServerPlayer moved = p.teleport(new TeleportTransition(target, dest, Vec3.ZERO, p.getYRot(), p.getXRot(),
					TeleportTransition.DO_NOTHING));
			if (moved == null) { // a mod vetoed the teleport
				abandon(p, false);
				MagicServer.sender().actionBar(p, Component.translatable(HOME_BLOCKED_KEY));
				return;
			}
			p.resetFallDistance();
			target.playSound(null, dest.x, dest.y, dest.z, SoundEvents.ENDERMAN_TELEPORT, SoundSource.PLAYERS, 1f, 1f);
			target.sendParticles(ParticleTypes.PORTAL, dest.x, dest.y + 1, dest.z, 32, 0.3, 0.6, 0.3, 0.1);
			MagicData d = MagicAttachments.get(p);
			long ready = target.getGameTime() + SpellCost.forSlot(Slots.RECALL).cooldownTicks();
			Long existing = d.readyAt().get(SpellIds.RECALL);
			MagicAttachments.set(p, d.withReady(SpellIds.RECALL, Math.max(existing == null ? Long.MIN_VALUE : existing, ready)));
		}
	}
}
