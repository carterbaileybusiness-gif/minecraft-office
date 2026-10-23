package local.carter.office.stream;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.WeakHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;
import java.util.function.Supplier;

import local.carter.office.core.HologramSpec;
import local.carter.office.hologram.HologramBlockEntity;

import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;

/**
 * Decides, on the server thread, who streams to whom. A hologram streams while its owner is online and at least one
 * other online player in the same dimension, within {@code viewRange} blocks of the hologram's centre, may view it
 * ({@code privacy.canView}). Recomputed every {@link #UPDATE_INTERVAL_TICKS} ticks and on the next tick after
 * {@link #signalChange()} (disconnect, UDP status). Hologram edits call {@link #rerouteNow()}, which recomputes every live
 * router synchronously, so a privacy change or removal takes effect before the edit handler returns.
 *
 * <p>Each stream gets a fresh server-wide {@code streamId} when it (re)starts. Signals: owner gets
 * {@code demand(wanted=true)} when a stream starts and {@code demand(wanted=false)} when it stops; each viewer gets
 * {@code start} when added and {@code ended} when removed or when the stream stops. The relay reads {@link #routes()}.
 */
public final class StreamRouter {
	public static final int UPDATE_INTERVAL_TICKS = 10;

	/** Server-lifetime (JVM-lifetime) unique stream ids. */
	private static final AtomicInteger NEXT_STREAM_ID = new AtomicInteger(1);
	private static final AtomicLong CHANGES = new AtomicLong();
	/** Routers that {@link #rerouteNow()} updates (weak: a dropped router simply disappears). Server thread only. */
	private static final Set<StreamRouter> LIVE = Collections.synchronizedSet(Collections.newSetFromMap(new WeakHashMap<>()));

	/** Where routing signals go (network in production, a recorder in tests). Called on the server thread. */
	public interface Sink {
		void demand(ServerPlayer owner, UUID hologramId, int streamId, boolean wanted);

		void start(ServerPlayer viewer, int streamId, UUID hologramId);

		void ended(ServerPlayer viewer, int streamId);
	}

	/** A live stream as published to the relay. */
	public record Route(int streamId, UUID hologramId, ServerPlayer owner, List<ServerPlayer> viewers) {
		public Route {
			viewers = List.copyOf(viewers);
		}
	}

	private static final class Active {
		final int streamId;
		final UUID hologramId;
		final ServerPlayer owner;
		final Map<UUID, ServerPlayer> viewers = new LinkedHashMap<>();

		Active(final int streamId, final UUID hologramId, final ServerPlayer owner) {
			this.streamId = streamId;
			this.hologramId = hologramId;
			this.owner = owner;
		}
	}

	private final Sink sink;
	private final double viewRange;
	private final Supplier<? extends Collection<ServerPlayer>> players;
	private final Supplier<? extends Collection<HologramBlockEntity>> holograms;
	private final Map<UUID, Active> active = new LinkedHashMap<>();
	private Consumer<List<Route>> listener = r -> { };
	private List<Route> routes = List.of();
	private long ticks;
	private long seenChanges = -1;
	private boolean closed;

	/**
	 * @param players   online players to consider (production: the server's player list)
	 * @param holograms loaded holograms to consider (production: those of online owners, from the index)
	 */
	public StreamRouter(final Sink sink, final double viewRange, final Supplier<? extends Collection<ServerPlayer>> players,
			final Supplier<? extends Collection<HologramBlockEntity>> holograms) {
		this.sink = sink;
		this.viewRange = viewRange;
		this.players = players;
		this.holograms = holograms;
		LIVE.add(this);
	}

	/** Stops this router: no more updates, signals or routes. */
	public void close() {
		closed = true;
		LIVE.remove(this);
		routes = List.of();
		listener.accept(routes);
	}

	/**
	 * A hologram was edited or removed: recompute every live router now (server thread), publishing new routes to the
	 * relay before returning. Privacy changes must not wait for a tick.
	 */
	public static void rerouteNow() {
		signalChange();
		List<StreamRouter> routers;
		synchronized (LIVE) {
			routers = List.copyOf(LIVE);
		}
		for (StreamRouter r : routers) {
			r.update();
		}
	}

	/** Something that affects routing changed (privacy, disconnect, UDP status): every router updates on its next tick. */
	public static void signalChange() {
		CHANGES.incrementAndGet();
	}

	/** Called after every update with the new routes. */
	public void onRoutes(final Consumer<List<Route>> l) {
		this.listener = l;
	}

	/** Call once per server tick. */
	public void tick() {
		if (closed) {
			return;
		}
		ticks++;
		long changes = CHANGES.get();
		if (changes != seenChanges || ticks % UPDATE_INTERVAL_TICKS == 0) {
			seenChanges = changes;
			update();
		}
	}

	public List<Route> routes() {
		return routes;
	}

	/** Who may watch {@code be} right now (online, same dimension, within range, allowed, not the owner). */
	public Set<ServerPlayer> viewers(final HologramBlockEntity be) {
		return viewers(be, online());
	}

	private Collection<ServerPlayer> online() {
		List<ServerPlayer> out = new ArrayList<>();
		for (ServerPlayer p : players.get()) {
			if (!p.hasDisconnected() && !p.isRemoved()) {
				out.add(p);
			}
		}
		return out;
	}

	private Set<ServerPlayer> viewers(final HologramBlockEntity be, final Collection<ServerPlayer> online) {
		HologramSpec spec = be.spec();
		Level level = be.getLevel();
		if (spec == null || level == null || be.isRemoved()) {
			return Set.of();
		}
		Vec3 c = center(be, spec);
		double r2 = viewRange * viewRange;
		Set<ServerPlayer> out = new LinkedHashSet<>();
		for (ServerPlayer p : online) {
			if (p.getUUID().equals(spec.owner()) || !p.level().dimension().equals(level.dimension())
					|| !spec.privacy().canView(spec.owner(), p.getUUID()) || p.position().distanceToSqr(c) > r2) {
				continue;
			}
			out.add(p);
		}
		return out;
	}

	/** Hologram centre in world space: emitter block centre + spec offsets. */
	static Vec3 center(final HologramBlockEntity be, final HologramSpec spec) {
		return Vec3.atCenterOf(be.getBlockPos()).add(spec.offX(), spec.offY(), spec.offZ());
	}

	/** Recomputes every stream now. */
	public void update() {
		if (closed) {
			return;
		}
		Collection<ServerPlayer> online = online();
		Map<UUID, ServerPlayer> byId = new HashMap<>();
		for (ServerPlayer p : online) {
			byId.put(p.getUUID(), p);
		}
		Set<UUID> seen = new HashSet<>();
		for (HologramBlockEntity be : holograms.get()) {
			HologramSpec spec = be.spec();
			if (spec == null || be.isRemoved() || !seen.add(be.hologramId())) {
				continue;
			}
			UUID hid = be.hologramId();
			ServerPlayer owner = byId.get(spec.owner());
			Active a = active.get(hid);
			if (a != null && a.owner != owner) {
				// Owner left (or is a new session): this stream is over; a new session gets a new streamId.
				end(a, byId);
				a = null;
			}
			if (owner == null) {
				continue;
			}
			Set<ServerPlayer> now = viewers(be, online);
			if (now.isEmpty()) {
				if (a != null) {
					end(a, byId);
				}
				continue;
			}
			if (a == null) {
				a = new Active(NEXT_STREAM_ID.getAndIncrement(), hid, owner);
				active.put(hid, a);
				sink.demand(owner, hid, a.streamId, true);
			}
			Map<UUID, ServerPlayer> nowById = new LinkedHashMap<>();
			for (ServerPlayer p : now) {
				nowById.put(p.getUUID(), p);
			}
			for (Iterator<Map.Entry<UUID, ServerPlayer>> it = a.viewers.entrySet().iterator(); it.hasNext();) {
				Map.Entry<UUID, ServerPlayer> e = it.next();
				if (nowById.get(e.getKey()) != e.getValue()) {
					it.remove();
					if (byId.get(e.getKey()) == e.getValue()) {
						sink.ended(e.getValue(), a.streamId);
					}
				}
			}
			for (ServerPlayer p : now) {
				if (a.viewers.putIfAbsent(p.getUUID(), p) == null) {
					sink.start(p, a.streamId, hid);
				}
			}
		}
		// Streams whose hologram is gone (broken, unloaded, owner offline and so not listed).
		for (Active a : List.copyOf(active.values())) {
			if (!seen.contains(a.hologramId)) {
				end(a, byId);
			}
		}
		List<Route> next = new ArrayList<>(active.size());
		for (Active a : active.values()) {
			next.add(new Route(a.streamId, a.hologramId, a.owner, List.copyOf(a.viewers.values())));
		}
		routes = List.copyOf(next);
		listener.accept(routes);
	}

	/** Stops {@code a}: ended to its still-online viewers, demand(false) to its owner if that session is still online. */
	private void end(final Active a, final Map<UUID, ServerPlayer> online) {
		active.remove(a.hologramId);
		for (Map.Entry<UUID, ServerPlayer> e : a.viewers.entrySet()) {
			if (online.get(e.getKey()) == e.getValue()) {
				sink.ended(e.getValue(), a.streamId);
			}
		}
		a.viewers.clear();
		if (online.get(a.owner.getUUID()) == a.owner) {
			sink.demand(a.owner, a.hologramId, a.streamId, false);
		}
	}
}
