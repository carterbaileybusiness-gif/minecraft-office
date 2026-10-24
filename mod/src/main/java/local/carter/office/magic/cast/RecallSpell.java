package local.carter.office.magic.cast;

import java.util.Optional;

import org.jspecify.annotations.Nullable;

import local.carter.office.OfficeMod;
import local.carter.office.magic.core.Element;
import local.carter.office.magic.core.Slots;
import local.carter.office.magic.data.MagicAttachments;
import local.carter.office.magic.data.MagicData;
import local.carter.office.magic.net.FxEvent;
import local.carter.office.magic.net.MagicServer;
import local.carter.office.magic.spell.CastContext;
import local.carter.office.magic.spell.CastOutcome;
import local.carter.office.magic.spell.Spell;
import local.carter.office.magic.spell.SpellIds;
import local.carter.office.magic.terrain.ClaimCheck;
import local.carter.office.magic.terrain.OpacClaims;
import local.carter.office.magic.terrain.WorldTerrainEdits;

import net.minecraft.core.BlockPos;
import net.minecraft.core.GlobalPos;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.level.TicketType;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.phys.Vec3;

/**
 * Recall, the one spell every element shares (slot 5, level 1, no mana).
 *
 * <ul>
 * <li><b>Shift + cast</b> marks home: the feet block, in any dimension, replacing the old home (one home per player). It sets
 * its own cooldown ({@link #MARK_KEY}, {@link #MARK_COOLDOWN_TICKS}) so a double press cannot mark twice; Recall's 60 s cooldown
 * never blocks it ({@link #cooldownKey}). Never inside another player's Open Parties and Claims claim ({@link #homeClaims}):
 * {@link #HOME_CLAIMED_KEY}, nothing changes, so no one keeps a way back into someone else's base.
 * <li><b>Cast</b> with a home starts the shared 3 s {@link TeleportChannel} toward it (interrupted by damage, death, disconnect
 * or a dimension change; no cost then). When it completes the caster is home and the 60 s cooldown, which Travel shares, starts.
 * </ul>
 *
 * <p><b>Teleport safety</b> ({@link #destination}) is {@link TeleportChannel#safeSpot} around the home cell, decided when the
 * channel starts (so a doomed recall fizzles free instead of making the player wait 3 s) and again at the end. The home
 * dimension must still exist. If nothing is safe the player stays, is told {@code magic.office.home_blocked} and pays only a
 * short {@link #BLOCKED_COOLDOWN_TICKS} Recall cooldown (each try loads the home chunk, so it must not be spammed); the home
 * is kept (the obstruction may be cleared later). The home chunk is loaded on demand and held by a PORTAL
 * ticket for the channel.
 */
public final class RecallSpell implements Spell {
	/** Length of the channel, in ticks (3 s). */
	public static final int CHANNEL_TICKS = TeleportChannel.CHANNEL_TICKS;
	/** Cooldown set by Mark Home, in ticks. */
	public static final int MARK_COOLDOWN_TICKS = 20;
	/**
	 * Mark Home's own {@code readyAt} key: Recall's 60 s cooldown never blocks marking a new home (and marking never blocks
	 * Recall).
	 */
	public static final Identifier MARK_KEY = OfficeMod.id("recall_mark");
	/** Action-bar message when the home cannot be reached safely. */
	public static final String HOME_BLOCKED_KEY = "magic.office.home_blocked";
	/** Recall's cooldown after a blocked start, in ticks (3 s): the try loaded the home chunk. Never lowers a longer one. */
	public static final int BLOCKED_COOLDOWN_TICKS = 60;
	/** Action-bar message when Mark Home is refused inside another player's claim. */
	public static final String HOME_CLAIMED_KEY = "magic.office.home_claimed";
	/** How far from the home cell a safe cell is looked for. */
	public static final int SEARCH_XZ = TeleportChannel.SEARCH_XZ;
	public static final int SEARCH_Y = TeleportChannel.SEARCH_Y;
	/** Ground may be at most this many blocks below the feet (a drop of 3 does no fall damage). */
	public static final int MAX_DROP = TeleportChannel.MAX_DROP;

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

	@Override
	public Identifier cooldownKey(Player p) {
		return p.isShiftKeyDown() ? MARK_KEY : SpellIds.RECALL;
	}

	/** True while {@code p} has a Recall or Travel channel running ({@code CastService} answers BUSY to every cast meanwhile). */
	public static boolean channeling(ServerPlayer p) {
		return TeleportChannel.channeling(p);
	}

	@Override
	public CastOutcome cast(CastContext ctx) {
		ServerPlayer p = ctx.caster();
		return p.isShiftKeyDown() ? markHome(ctx) : startChannel(ctx);
	}

	private static CastOutcome markHome(CastContext ctx) {
		ServerPlayer p = ctx.caster();
		BlockPos cell = p.blockPosition();
		if (homeClaims().forBatch(p, ctx.level(), cell, 0).test(cell)) return CastOutcome.fizzle(HOME_CLAIMED_KEY);
		long now = ctx.level().getGameTime();
		MagicData d = MagicAttachments.get(p);
		Long existing = d.readyAt().get(MARK_KEY);
		MagicAttachments.set(p, d.withHome(Optional.of(GlobalPos.of(ctx.level().dimension(), cell)))
				.withReady(MARK_KEY, Math.max(existing == null ? Long.MIN_VALUE : existing, now + MARK_COOLDOWN_TICKS)));
		MagicServer.sender().actionBar(p, Component.translatable("magic.office.home_marked"));
		// Mark Home's fx: a short flash (duration 0, data[0] = 1).
		Vec3 feet = p.position();
		return CastOutcome.cast(new FxEvent(SpellIds.RECALL, p.getId(), feet, new Vec3(0, 1, 0), feet, ctx.charge(), ctx.power(),
				ctx.seed(), 0, new int[0], new float[] {1f}));
	}

	private static CastOutcome startChannel(CastContext ctx) {
		ServerPlayer p = ctx.caster();
		Optional<GlobalPos> home = MagicAttachments.get(p).home();
		if (home.isEmpty()) return CastOutcome.fizzle("magic.office.no_home");
		Home trip = new Home(home.get());
		TeleportChannel.Landing at = trip.resolve(p);
		if (at == null) {
			// The search loaded the home chunk (maybe in another dimension): a short cooldown, so taps can't keep loading it.
			MagicData d = MagicAttachments.get(p);
			Long existing = d.readyAt().get(SpellIds.RECALL);
			long until = ctx.level().getGameTime() + BLOCKED_COOLDOWN_TICKS;
			MagicAttachments.set(p, d.withReady(SpellIds.RECALL, Math.max(existing == null ? Long.MIN_VALUE : existing, until)));
			return CastOutcome.fizzle(HOME_BLOCKED_KEY);
		}
		if (!TeleportChannel.start(p, trip)) return CastOutcome.fizzle(CastService.BUSY_KEY);
		// The check above loaded the home chunk; hold it (and its neighbours) so the teleport does not load it a second time.
		at.level().getChunkSource().addTicketWithRadius(TicketType.PORTAL, ChunkPos.containing(BlockPos.containing(at.pos())), 1);
		MagicServer.sender().actionBar(p, Component.translatable("magic.office.recalling"));
		return CastOutcome.cast(TeleportChannel.fx(p, ctx.charge(), ctx.power(), ctx.seed(), TeleportChannel.FX_RECALL));
	}

	/**
	 * Interrupts {@code p}'s channel (Recall or Travel), if any: no teleport and no cooldown. Everyone drawing the circle is told
	 * to stop, and the player is told unless {@code notify} is false (a disconnect).
	 *
	 * @return true if a channel was running
	 */
	public static boolean interrupt(ServerPlayer p, boolean notify) {
		return TeleportChannel.interrupt(p, notify);
	}

	/**
	 * Where {@code home} can be reached safely (see the class comment), or null. Loads the home chunk if it is not loaded.
	 * {@code p} only supplies the body size.
	 */
	public static @Nullable Vec3 destination(ServerPlayer p, GlobalPos home) {
		ServerLevel level = p.level().getServer().getLevel(home.dimension());
		return level == null ? null : TeleportChannel.safeSpot(p, level, home.pos(), false);
	}

	private static @Nullable ClaimCheck testHomeClaims;

	/**
	 * The claims Mark Home respects: the terrain kit's Open Parties and Claims rule, asked as the caster. Without OPAC there are
	 * no claims to respect: the fallback rule's circles round homes, beds and emitters are a terrain heuristic, and refusing a
	 * home near a friend's would only get in the way.
	 */
	static ClaimCheck homeClaims() {
		ClaimCheck test = testHomeClaims;
		if (test != null) return test;
		ClaimCheck c = MagicServer.kit().terrain() instanceof WorldTerrainEdits edits ? edits.claims() : ClaimCheck.NONE;
		return c instanceof OpacClaims ? c : ClaimCheck.NONE;
	}

	/** GameTests only: the claims Mark Home asks instead of {@link #homeClaims}'s (null restores them). */
	public static void setHomeClaimsForTesting(@Nullable ClaimCheck c) {
		testHomeClaims = c;
	}

	/** Recall's trip: the home, resolved again when the channel ends; the caster keeps their facing. */
	private record Home(GlobalPos home) implements TeleportChannel.Trip {
		@Override
		public TeleportChannel.@Nullable Landing resolve(ServerPlayer p) {
			ServerLevel level = p.level().getServer().getLevel(home.dimension());
			Vec3 at = level == null ? null : TeleportChannel.safeSpot(p, level, home.pos(), false);
			return at == null ? null : new TeleportChannel.Landing(level, at, p.getYRot(), p.getXRot());
		}

		@Override
		public void blocked(ServerPlayer p) {
			MagicServer.sender().actionBar(p, Component.translatable(HOME_BLOCKED_KEY));
		}

		@Override
		public void interrupted(ServerPlayer p) {
			MagicServer.sender().actionBar(p, Component.translatable("magic.office.recall_interrupted"));
		}
	}
}
