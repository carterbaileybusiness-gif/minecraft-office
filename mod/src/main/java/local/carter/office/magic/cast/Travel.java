package local.carter.office.magic.cast;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import org.jspecify.annotations.Nullable;

import local.carter.office.magic.data.MagicAttachments;
import local.carter.office.magic.data.MagicData;
import local.carter.office.magic.item.GrimoireItem;
import local.carter.office.magic.net.MagicPayloads.TravelRoster;
import local.carter.office.magic.net.MagicServer;
import local.carter.office.magic.spell.SpellIds;

import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.HoverEvent;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.Mth;
import net.minecraft.world.phys.Vec3;

/**
 * Travel to a friend (spec §6): from the grimoire's Friends page a player asks to travel to another online player; the friend
 * gets a chat line with [Accept] [Deny] ({@code /magic travel accept|deny <id>}); a request expires after
 * {@link #EXPIRE_TICKS}. On accept the caster channels 3 s exactly like Recall ({@link TeleportChannel}: same circle, cancelled by
 * damage, death, disconnect or a dimension change) and lands at a safe spot beside the friend, resolved again when the channel
 * ends (any dimension; nowhere safe → nothing happens and nothing is spent). Success costs Recall's shared 60 s cooldown and
 * nothing else (no mana, no grimoire xp). One open request per caster. {@code /magic travel off} refuses requests
 * ({@code MagicData.travel}). A friend who leaves cancels the request (and a channel heading to them).
 *
 * <p>Messages: the caster's refusals and channel progress go to the action bar (like casting); the request line and the
 * request's outcome (declined, expired, the friend left) go to chat, which outlasts a glance. Server thread only.
 */
public final class Travel {
	/** A request stays open this long (30 s). */
	public static final int EXPIRE_TICKS = 600;
	/** At most one roster reply per player this often (a client polls once a second; a flood gets the cached answer only). */
	private static final int ROSTER_MIN_TICKS = 5;

	/** What {@link #request} decided. */
	public enum Result { SENT, NOT_BOUND, NO_GRIMOIRE, BUSY, COOLDOWN, SELF, OFFLINE, TRAVEL_OFF, PENDING }

	/** What answering a request did. */
	public enum Answer { STARTED, DENIED, GONE, CANNOT }

	/** An open request: {@code caster} asked to travel to {@code target}; the line's buttons carry {@code id}. */
	public record Request(int id, UUID caster, UUID target, String casterName, String targetName, long expiresAt) {}

	private static final Map<Integer, Request> BY_ID = new LinkedHashMap<>();
	private static final Map<UUID, Request> BY_CASTER = new HashMap<>();
	private static final Map<UUID, Long> LAST_ROSTER = new HashMap<>();
	private static int nextId = 1;

	private Travel() {}

	// ---------------------------------------------------------------- asking

	/**
	 * {@code caster} asks to travel to {@code targetId}. Checked in order: bound; a grimoire in either hand; not channeling,
	 * answering hand signs or charging; Recall's cooldown ready; not themselves; the target online and accepting requests; no
	 * other open request. A refusal goes to the caster's action bar and changes nothing.
	 */
	public static Result request(ServerPlayer caster, UUID targetId) {
		Result r = check(caster, targetId);
		ServerPlayer target = caster.level().getServer().getPlayerList().getPlayer(targetId);
		if (r != Result.SENT) {
			MagicServer.sender().actionBar(caster, refusal(caster, r, target));
			return r;
		}
		long now = caster.level().getServer().overworld().getGameTime();
		Request req = new Request(nextId++, caster.getUUID(), targetId, name(caster), name(target), now + EXPIRE_TICKS);
		BY_ID.put(req.id(), req);
		BY_CASTER.put(caster.getUUID(), req);
		MagicServer.sender().chat(target, askLine(req));
		target.level().playSound(null, target.blockPosition(), SoundEvents.AMETHYST_BLOCK_CHIME, SoundSource.PLAYERS, 1f, 1.2f);
		MagicServer.sender().actionBar(caster, Component.translatable("magic.office.travel_sent", req.targetName()));
		return Result.SENT;
	}

	private static Result check(ServerPlayer caster, UUID targetId) {
		MagicData d = MagicAttachments.get(caster);
		if (d.element().isEmpty()) return Result.NOT_BOUND;
		if (!GrimoireItem.isGrimoire(caster.getMainHandItem()) && !GrimoireItem.isGrimoire(caster.getOffhandItem())) return Result.NO_GRIMOIRE;
		if (busy(caster)) return Result.BUSY;
		if (cooldownLeft(caster) > 0) return Result.COOLDOWN;
		if (caster.getUUID().equals(targetId)) return Result.SELF;
		ServerPlayer target = caster.level().getServer().getPlayerList().getPlayer(targetId);
		if (target == null) return Result.OFFLINE;
		if (!MagicAttachments.get(target).travel()) return Result.TRAVEL_OFF;
		if (BY_CASTER.containsKey(caster.getUUID())) return Result.PENDING;
		return Result.SENT;
	}

	private static Component refusal(ServerPlayer caster, Result r, @Nullable ServerPlayer target) {
		return switch (r) {
			case NOT_BOUND -> Component.translatable("magic.office.choose");
			case NO_GRIMOIRE -> Component.translatable("magic.office.travel_need_grimoire");
			case BUSY -> Component.translatable("magic.office.travel_busy");
			case COOLDOWN -> Component.translatable("magic.office.travel_cooldown", seconds(cooldownLeft(caster)));
			case SELF, OFFLINE -> Component.translatable("magic.office.travel_offline");
			case TRAVEL_OFF -> Component.translatable("magic.office.travel_refuses", name(target));
			case PENDING -> Component.translatable("magic.office.travel_pending", BY_CASTER.get(caster.getUUID()).targetName());
			case SENT -> Component.empty();
		};
	}

	/** "<name> wants to travel to you  [Accept] [Deny]" with clickable, hoverable buttons. */
	private static Component askLine(Request req) {
		MutableComponent accept = Component.translatable("magic.office.travel_accept").withStyle(s -> s.withColor(ChatFormatting.GREEN)
				.withBold(true).withClickEvent(new ClickEvent.RunCommand("/magic travel accept " + req.id()))
				.withHoverEvent(new HoverEvent.ShowText(Component.translatable("magic.office.travel_accept_hover", req.casterName()))));
		MutableComponent deny = Component.translatable("magic.office.travel_deny").withStyle(s -> s.withColor(ChatFormatting.RED)
				.withBold(true).withClickEvent(new ClickEvent.RunCommand("/magic travel deny " + req.id()))
				.withHoverEvent(new HoverEvent.ShowText(Component.translatable("magic.office.travel_deny_hover"))));
		return Component.translatable("magic.office.travel_ask", Component.literal(req.casterName()).withStyle(ChatFormatting.YELLOW))
				.append("  ").append(accept).append(" ").append(deny);
	}

	// ---------------------------------------------------------------- answering

	/**
	 * {@code target} accepts request {@code id}. The caster is checked again (online, alive, not busy, Recall's cooldown ready)
	 * and so is the landing beside {@code target}; then the caster's channel starts (one fx, nothing spent yet).
	 */
	public static Answer accept(ServerPlayer target, int id) {
		Request req = take(target, id);
		if (req == null) {
			MagicServer.sender().actionBar(target, Component.translatable("magic.office.travel_gone"));
			return Answer.GONE;
		}
		ServerPlayer caster = target.level().getServer().getPlayerList().getPlayer(req.caster());
		if (caster == null || !caster.isAlive()) {
			MagicServer.sender().actionBar(target, Component.translatable("magic.office.travel_gone"));
			return Answer.GONE;
		}
		Component why = null;
		if (busy(caster)) why = Component.translatable("magic.office.travel_busy");
		else if (cooldownLeft(caster) > 0) why = Component.translatable("magic.office.travel_cooldown", seconds(cooldownLeft(caster)));
		Friend trip = new Friend(target.getUUID(), req.targetName());
		if (why == null && trip.resolve(caster) == null) why = Component.translatable("magic.office.travel_blocked", req.targetName());
		if (why == null && !TeleportChannel.start(caster, trip)) why = Component.translatable(CastService.BUSY_KEY);
		if (why != null) {
			MagicServer.sender().actionBar(caster, why);
			MagicServer.sender().actionBar(target, Component.translatable("magic.office.travel_cannot", req.casterName()));
			return Answer.CANNOT;
		}
		ServerLevel level = caster.level();
		MagicServer.sender().fx(level, TeleportChannel.fx(caster, 0f, 1f, level.getRandom().nextLong(), TeleportChannel.FX_TRAVEL),
				CastService.FX_RANGE);
		MagicServer.sender().actionBar(caster, Component.translatable("magic.office.travel_accepted", req.targetName()));
		MagicServer.sender().actionBar(target, Component.translatable("magic.office.travel_coming", req.casterName()));
		return Answer.STARTED;
	}

	/** {@code target} declines request {@code id}: the caster is told; nothing is spent. */
	public static Answer deny(ServerPlayer target, int id) {
		Request req = take(target, id);
		if (req == null) {
			MagicServer.sender().actionBar(target, Component.translatable("magic.office.travel_gone"));
			return Answer.GONE;
		}
		ServerPlayer caster = target.level().getServer().getPlayerList().getPlayer(req.caster());
		if (caster != null) MagicServer.sender().chat(caster, Component.translatable("magic.office.travel_denied", req.targetName()));
		MagicServer.sender().actionBar(target, Component.translatable("magic.office.travel_you_denied", req.casterName()));
		return Answer.DENIED;
	}

	/** Removes and returns request {@code id} if it is open and addressed to {@code target}; otherwise null and nothing changes. */
	private static @Nullable Request take(ServerPlayer target, int id) {
		Request req = BY_ID.get(id);
		if (req == null || !req.target().equals(target.getUUID())) return null;
		remove(req);
		return req;
	}

	private static void remove(Request req) {
		BY_ID.remove(req.id());
		BY_CASTER.remove(req.caster(), req);
	}

	// ---------------------------------------------------------------- lifecycle

	/** Once per server tick: requests past {@link #EXPIRE_TICKS} close and their casters are told. */
	public static void tick(MinecraftServer server) {
		if (BY_ID.isEmpty()) return;
		long now = server.overworld().getGameTime();
		for (Iterator<Request> it = BY_ID.values().iterator(); it.hasNext();) {
			Request req = it.next();
			if (now < req.expiresAt()) continue;
			it.remove();
			BY_CASTER.remove(req.caster(), req);
			ServerPlayer caster = server.getPlayerList().getPlayer(req.caster());
			if (caster != null) MagicServer.sender().chat(caster, Component.translatable("magic.office.travel_expired", req.targetName()));
		}
	}

	/** The caster died or changed dimension: their open request is withdrawn (the channel is interrupted by its own hooks). */
	public static void withdraw(ServerPlayer caster) {
		Request req = BY_CASTER.remove(caster.getUUID());
		if (req != null) BY_ID.remove(req.id());
	}

	/**
	 * {@code p} disconnected: their own request is withdrawn; requests to them close and channels heading to them stop, and
	 * those casters are told.
	 */
	public static void left(ServerPlayer p) {
		withdraw(p);
		LAST_ROSTER.remove(p.getUUID());
		MinecraftServer server = p.level().getServer();
		String who = name(p);
		for (Iterator<Request> it = BY_ID.values().iterator(); it.hasNext();) {
			Request req = it.next();
			if (!req.target().equals(p.getUUID())) continue;
			it.remove();
			BY_CASTER.remove(req.caster(), req);
			ServerPlayer caster = server.getPlayerList().getPlayer(req.caster());
			if (caster != null) MagicServer.sender().chat(caster, Component.translatable("magic.office.travel_left", who));
		}
		for (ServerPlayer caster : TeleportChannel.casters(t -> t instanceof Friend f && f.target().equals(p.getUUID()))) {
			TeleportChannel.interrupt(caster, false);
			MagicServer.sender().chat(caster, Component.translatable("magic.office.travel_left", who));
		}
	}

	/** {@code /magic travel on|off}. */
	public static void setAccepting(ServerPlayer p, boolean on) {
		MagicAttachments.set(p, MagicAttachments.get(p).withTravel(on));
		MagicServer.sender().chat(p, Component.translatable(on ? "magic.office.travel_now_on" : "magic.office.travel_now_off"));
	}

	/** Server stopping: forget everything. */
	public static void clear() {
		BY_ID.clear();
		BY_CASTER.clear();
		LAST_ROSTER.clear();
	}

	/** {@code caster}'s open request, if any. */
	public static Optional<Request> pendingFrom(ServerPlayer caster) {
		return Optional.ofNullable(BY_CASTER.get(caster.getUUID()));
	}

	/** Open requests addressed to {@code target}, oldest first. */
	public static List<Request> pendingTo(ServerPlayer target) {
		List<Request> out = new ArrayList<>();
		for (Request req : BY_ID.values()) if (req.target().equals(target.getUUID())) out.add(req);
		return out;
	}

	// ---------------------------------------------------------------- the Friends page

	/** The roster query receiver: answered at most once every {@link #ROSTER_MIN_TICKS} ticks per player. */
	public static void query(ServerPlayer viewer) {
		long now = viewer.level().getServer().overworld().getGameTime();
		Long last = LAST_ROSTER.get(viewer.getUUID());
		if (last != null && now >= last && now - last < ROSTER_MIN_TICKS) return;
		sendRoster(viewer);
	}

	/** Sends {@code viewer} their roster now. */
	public static void sendRoster(ServerPlayer viewer) {
		LAST_ROSTER.put(viewer.getUUID(), viewer.level().getServer().overworld().getGameTime());
		MagicServer.sender().roster(viewer, roster(viewer));
	}

	/** Every other online player (by name) with whether they accept requests, and {@code viewer}'s own travel state. */
	public static TravelRoster roster(ServerPlayer viewer) {
		MinecraftServer server = viewer.level().getServer();
		List<TravelRoster.Friend> friends = new ArrayList<>();
		for (ServerPlayer p : server.getPlayerList().getPlayers()) {
			if (p == viewer || p.getUUID().equals(viewer.getUUID())) continue;
			friends.add(new TravelRoster.Friend(p.getUUID(), name(p), MagicAttachments.get(p).travel()));
		}
		friends.sort(Comparator.comparing((TravelRoster.Friend f) -> f.name().toLowerCase(Locale.ROOT)).thenComparing(f -> f.id()));
		Request req = BY_CASTER.get(viewer.getUUID());
		TeleportChannel.Trip trip = TeleportChannel.trip(viewer);
		if (trip instanceof Friend f) return new TravelRoster(friends, TravelRoster.State.TRAVELLING, Optional.of(f.target()), 0);
		if (trip != null || SignChallenges.pending(viewer) || CastService.charging(viewer)) {
			return new TravelRoster(friends, TravelRoster.State.BUSY, Optional.empty(), 0);
		}
		if (req != null) {
			long left = req.expiresAt() - server.overworld().getGameTime();
			return new TravelRoster(friends, TravelRoster.State.WAITING, Optional.of(req.target()), (int) Math.max(0, left));
		}
		return new TravelRoster(friends, TravelRoster.State.READY, Optional.empty(), 0);
	}

	// ---------------------------------------------------------------- helpers

	/** Channeling (Recall or Travel), answering hand signs or charging a cast. */
	static boolean busy(ServerPlayer p) {
		return TeleportChannel.channeling(p) || SignChallenges.pending(p) || CastService.charging(p);
	}

	private static long cooldownLeft(ServerPlayer p) {
		return MagicAttachments.get(p).cooldownLeft(SpellIds.RECALL, p.level().getGameTime());
	}

	/** Whole seconds, rounded up (lang strings take only {@code %s}). */
	private static String seconds(long ticks) {
		return Long.toString((ticks + 19) / 20);
	}

	private static String name(@Nullable ServerPlayer p) {
		return p == null ? "?" : p.getName().getString();
	}

	/**
	 * The travel trip: beside the friend wherever they are when it resolves (looked up by UUID, so a respawned friend still
	 * counts), facing them. Their own column is tried last, so the caster lands next to them rather than inside them.
	 */
	private record Friend(UUID target, String targetName) implements TeleportChannel.Trip {
		@Override
		public TeleportChannel.@Nullable Landing resolve(ServerPlayer caster) {
			ServerPlayer friend = caster.level().getServer().getPlayerList().getPlayer(target);
			if (friend == null || !friend.isAlive() || friend.isRemoved()) return null;
			ServerLevel level = friend.level();
			Vec3 at = TeleportChannel.safeSpot(caster, level, friend.blockPosition(), true);
			if (at == null) return null;
			Vec3 to = friend.position().subtract(at);
			float yaw = to.horizontalDistanceSqr() < 1e-4 ? caster.getYRot() : (float) (Mth.atan2(to.z, to.x) * Mth.RAD_TO_DEG) - 90f;
			return new TeleportChannel.Landing(level, at, Mth.wrapDegrees(yaw), 0f);
		}

		@Override
		public void blocked(ServerPlayer caster) {
			boolean online = caster.level().getServer().getPlayerList().getPlayer(target) != null;
			MagicServer.sender().actionBar(caster, Component.translatable(online ? "magic.office.travel_blocked" : "magic.office.travel_left",
					targetName));
		}

		@Override
		public void interrupted(ServerPlayer caster) {
			MagicServer.sender().actionBar(caster, Component.translatable("magic.office.travel_interrupted"));
		}

		@Override
		public void arrived(ServerPlayer caster, TeleportChannel.Landing at) {
			ServerPlayer friend = caster.level().getServer().getPlayerList().getPlayer(target);
			if (friend != null) MagicServer.sender().actionBar(friend, Component.translatable("magic.office.travel_arrived", name(caster)));
		}
	}
}
