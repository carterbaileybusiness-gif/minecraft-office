package local.carter.office;

import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Predicate;

import local.carter.office.core.HologramSpec;
import local.carter.office.core.Privacy;
import local.carter.office.core.StreamHeader;
import local.carter.office.hologram.HologramBlockEntity;
import local.carter.office.hologram.OfficeBlocks;
import local.carter.office.net.HologramActions;
import local.carter.office.net.OfficePayloads;
import local.carter.office.stream.OfficeConfig;
import local.carter.office.stream.StreamRelayServer;
import local.carter.office.stream.StreamRouter;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.Vec3;

import net.fabricmc.fabric.api.gametest.v1.GameTest;

/**
 * Server GameTests for stream routing. Players are connected in-level mocks (real player-list entries, so join/leave
 * events and {@code ownerOnline} are the real ones). Each test drives its own {@link StreamRouter} whose candidate
 * players are only this test's players (other tests run in parallel in the same level) and whose signals go to a
 * recorder instead of the network; privacy changes and disconnects reach it through the production hooks.
 */
public class StreamRoutingGameTests {
	private static final BlockPos EMITTER = new BlockPos(1, 1, 1);

	record Event(String kind, UUID player, int streamId, UUID hologramId, boolean wanted, long tick) {}

	/** Records router signals with the test tick they happened on. */
	static final class Recorder implements StreamRouter.Sink {
		final GameTestHelper h;
		final List<Event> events = new ArrayList<>();

		Recorder(GameTestHelper h) {
			this.h = h;
		}

		@Override
		public void demand(ServerPlayer owner, UUID hologramId, int streamId, boolean wanted) {
			events.add(new Event("demand", owner.getUUID(), streamId, hologramId, wanted, h.getTick()));
		}

		@Override
		public void start(ServerPlayer viewer, int streamId, UUID hologramId) {
			events.add(new Event("start", viewer.getUUID(), streamId, hologramId, true, h.getTick()));
		}

		@Override
		public void ended(ServerPlayer viewer, int streamId) {
			events.add(new Event("ended", viewer.getUUID(), streamId, null, false, h.getTick()));
		}

		List<Event> find(String kind, ServerPlayer p, Predicate<Event> also) {
			return events.stream().filter(e -> e.kind.equals(kind) && e.player.equals(p.getUUID()) && also.test(e)).toList();
		}

		List<Event> find(String kind, ServerPlayer p) {
			return find(kind, p, e -> true);
		}
	}

	@SuppressWarnings("removal")
	private static ServerPlayer player(GameTestHelper h, Vec3 absolute) {
		ServerPlayer p = h.makeMockServerPlayerInLevel();
		p.setPos(absolute.x, absolute.y, absolute.z);
		return p;
	}

	private static HologramBlockEntity place(GameTestHelper h, ServerPlayer owner) {
		owner.setItemInHand(InteractionHand.MAIN_HAND, new ItemStack(OfficeBlocks.HOLOGRAM_EMITTER_ITEM, 1));
		h.placeAt(owner, owner.getMainHandItem(), EMITTER.below(), Direction.UP);
		h.assertBlockPresent(OfficeBlocks.HOLOGRAM_EMITTER, EMITTER);
		HologramBlockEntity be = h.getBlockEntity(EMITTER, HologramBlockEntity.class);
		// a player's first hologram is a Mirror that starts Private (spec 5.10 round 2); these tests are about a shared one
		be.setSpec(be.spec().withPrivacy(local.carter.office.core.Privacy.PUBLIC));
		return be;
	}

	/** World-space centre of the hologram (emitter centre + offsets), the point view range is measured from. */
	private static Vec3 center(HologramBlockEntity be) {
		HologramSpec s = be.spec();
		return Vec3.atCenterOf(be.getBlockPos()).add(s.offX(), s.offY(), s.offZ());
	}

	/** This test's players that are still in the server's player list. */
	private static Collection<ServerPlayer> online(GameTestHelper h, List<ServerPlayer> mine) {
		var list = h.getLevel().getServer().getPlayerList();
		return mine.stream().filter(p -> list.getPlayer(p.getUUID()) == p).toList();
	}

	private static StreamRouter router(GameTestHelper h, Recorder rec, List<ServerPlayer> players, HologramBlockEntity be) {
		StreamRouter r = new StreamRouter(rec, OfficeConfig.DEFAULTS.viewRange(), () -> online(h, players), () -> List.of(be));
		h.onEachTick(r::tick);
		return r;
	}

	private static void removeAll(GameTestHelper h, ServerPlayer... players) {
		var list = h.getLevel().getServer().getPlayerList();
		for (ServerPlayer p : players) {
			if (list.getPlayer(p.getUUID()) == p) {
				list.remove(p);
			}
		}
	}

	private static void setPrivacy(GameTestHelper h, ServerPlayer owner, HologramBlockEntity be, Privacy privacy) {
		HologramSpec s = be.spec();
		h.assertTrue(HologramActions.apply(owner, new OfficePayloads.UpdateHologram(be.getBlockPos(), s.shape(), s.mode(),
				privacy, s.displaySlot(), s.windowKey())), "owner privacy change applied");
	}

	@GameTest(maxTicks = 40)
	public void viewerAt40GetsDemandAt60Not(GameTestHelper h) {
		ServerPlayer a = player(h, h.absoluteVec(new Vec3(1.5, 1, 3.5)));
		HologramBlockEntity be = place(h, a);
		Vec3 c = center(be);
		ServerPlayer b = player(h, c.add(40, 0, 0));
		ServerPlayer far = player(h, c.add(0, 0, 60));
		Recorder near = new Recorder(h);
		Recorder away = new Recorder(h);
		StreamRouter routerNear = router(h, near, List.of(a, b), be);
		StreamRouter routerFar = router(h, away, List.of(a, far), be);
		StreamRouter all = new StreamRouter(new Recorder(h), OfficeConfig.DEFAULTS.viewRange(),
				() -> online(h, List.of(a, b, far)), () -> List.of(be));

		h.runAtTickTime(12, () -> {
			try {
				h.assertValueEqual(all.viewers(be), Set.of(b), "only the player at 40 blocks views");
				h.assertValueEqual(routerFar.viewers(be), Set.of(), "60 blocks is out of range");
				List<Event> demand = near.find("demand", a, e -> e.wanted);
				h.assertValueEqual(demand.size(), 1, "owner asked to stream once");
				h.assertValueEqual(demand.get(0).hologramId, be.hologramId(), "demand names the hologram");
				h.assertValueEqual(near.find("start", b).size(), 1, "viewer at 40 told the stream started");
				h.assertValueEqual(near.find("start", b).get(0).streamId, demand.get(0).streamId, "same streamId");
				h.assertValueEqual(away.events, List.of(), "nothing for an owner whose only neighbour is 60 blocks away");
				h.assertValueEqual(routerNear.routes().size(), 1, "one live route");
				h.assertValueEqual(routerFar.routes().size(), 0, "no route");
				h.succeed();
			} finally {
				removeAll(h, a, b, far);
			}
		});
	}

	@GameTest(maxTicks = 60)
	public void privateWhileViewingEndsWithin20Ticks(GameTestHelper h) {
		ServerPlayer a = player(h, h.absoluteVec(new Vec3(1.5, 1, 3.5)));
		HologramBlockEntity be = place(h, a);
		ServerPlayer b = player(h, center(be).add(5, 0, 0));
		Recorder rec = new Recorder(h);
		StreamRouter router = router(h, rec, List.of(a, b), be);
		long[] changedAt = new long[1];

		h.runAtTickTime(5, () -> {
			h.assertValueEqual(rec.find("start", b).size(), 1, "viewing before the change");
			setPrivacy(h, a, be, Privacy.PRIVATE);
			changedAt[0] = h.getTick();
		});
		h.runAtTickTime(26, () -> {
			try {
				int sid = rec.find("start", b).get(0).streamId;
				List<Event> stop = rec.find("demand", a, e -> !e.wanted && e.streamId == sid);
				List<Event> ended = rec.find("ended", b, e -> e.streamId == sid);
				h.assertValueEqual(stop.size(), 1, "owner told to stop");
				h.assertValueEqual(ended.size(), 1, "viewer told the stream ended");
				// Required: within 20 ticks. Ruling: immediately, so the edit handler reroutes before it returns.
				h.assertValueEqual(stop.get(0).tick, changedAt[0], "owner told to stop on the same tick as the edit");
				h.assertValueEqual(ended.get(0).tick, changedAt[0], "viewer told on the same tick as the edit");
				h.assertValueEqual(router.routes().size(), 0, "relay no longer routes it");
				h.assertValueEqual(router.viewers(be), Set.of(), "nobody views a private hologram");
				h.succeed();
			} finally {
				removeAll(h, a, b);
			}
		});
	}

	@GameTest(maxTicks = 40)
	public void privacyBlocksTheVeryNextOwnerDatagram(GameTestHelper h) {
		ServerPlayer a = player(h, h.absoluteVec(new Vec3(1.5, 1, 3.5)));
		HologramBlockEntity be = place(h, a);
		ServerPlayer b = player(h, center(be).add(5, 0, 0));
		Recorder rec = new Recorder(h);
		StreamRouter router = router(h, rec, List.of(a, b), be);
		// The router's routes feed a real relay (payload-only here); B is a fallback viewer so deliveries are visible.
		StreamRelayServer relay = new StreamRelayServer(OfficeConfig.DEFAULTS.maxKbpsPerStream());
		List<byte[]> delivered = new ArrayList<>();
		router.onRoutes(routes -> {
			Map<Integer, StreamRelayServer.Route> snapshot = new HashMap<>();
			for (StreamRouter.Route r : routes) {
				snapshot.put(r.streamId(), new StreamRelayServer.Route(r.owner().getUUID(),
						r.viewers().stream().map(v -> new StreamRelayServer.Viewer(v.getUUID(), delivered::add)).toList()));
			}
			relay.setRoutes(snapshot);
		});

		h.runAtTickTime(5, () -> {
			try {
				int sid = rec.find("start", b).get(0).streamId;
				h.assertTrue(relay.fromFallback(a.getUUID(), tile(sid, 1)), "owner datagram accepted while public");
				h.assertValueEqual(delivered.size(), 1, "viewer got it");

				setPrivacy(h, a, be, Privacy.PRIVATE); // no router tick in between
				h.assertFalse(relay.fromFallback(a.getUUID(), tile(sid, 2)), "very next owner datagram refused");
				h.assertValueEqual(delivered.size(), 1, "viewer got nothing more");
				h.assertValueEqual(rec.find("ended", b, e -> e.streamId == sid).size(), 1, "viewer told at once");
				h.succeed();
			} finally {
				relay.close();
				router.close();
				removeAll(h, a, b);
			}
		});
	}

	/** One single-part TILE datagram (header + small body), as an owner would send it in a fallback payload. */
	private static byte[] tile(int streamId, int seq) {
		return ByteBuffer.allocate(StreamHeader.HEADER_BYTES + 64)
				.put(new StreamHeader(StreamHeader.TYPE_TILE, streamId, seq, 0, 0, 0, 1).encode()).array();
	}

	@GameTest(maxTicks = 40)
	public void showToReachesOnlyTheListedPlayer(GameTestHelper h) {
		ServerPlayer a = player(h, h.absoluteVec(new Vec3(1.5, 1, 3.5)));
		HologramBlockEntity be = place(h, a);
		ServerPlayer b = player(h, center(be).add(4, 0, 0));
		ServerPlayer c = player(h, center(be).add(-4, 0, 0));
		setPrivacy(h, a, be, new Privacy(Privacy.Level.SHOW_TO, Set.of(b.getUUID())));
		Recorder rec = new Recorder(h);
		StreamRouter router = router(h, rec, List.of(a, b, c), be);

		h.runAtTickTime(12, () -> {
			try {
				h.assertValueEqual(router.viewers(be), Set.of(b), "only B may view");
				h.assertValueEqual(rec.find("start", b).size(), 1, "B receives");
				h.assertValueEqual(rec.find("start", c).size(), 0, "C does not");
				h.assertValueEqual(router.routes().size(), 1, "one route");
				StreamRouter.Route route = router.routes().get(0);
				h.assertValueEqual(route.owner(), a, "route owner");
				h.assertValueEqual(route.viewers(), List.of(b), "relay forwards to B only");
				h.succeed();
			} finally {
				removeAll(h, a, b, c);
			}
		});
	}

	@GameTest(maxTicks = 100)
	public void ownerDisconnectEndsStreamWithin60Ticks(GameTestHelper h) {
		ServerPlayer a = player(h, h.absoluteVec(new Vec3(1.5, 1, 3.5)));
		HologramBlockEntity be = place(h, a);
		ServerPlayer b = player(h, center(be).add(5, 0, 0));
		Recorder rec = new Recorder(h);
		StreamRouter router = router(h, rec, List.of(a, b), be);
		long[] leftAt = new long[1];

		h.runAtTickTime(5, () -> {
			h.assertTrue(be.ownerOnline(), "owner online while connected");
			h.assertValueEqual(rec.find("start", b).size(), 1, "streaming before the disconnect");
			// The real kick path: closes the connection and runs Connection.handleDisconnection, which is where Fabric
			// fires DISCONNECT (calling the listener's onDisconnect directly would skip the event).
			a.connection.disconnect(Component.literal("test disconnect"));
			leftAt[0] = h.getTick();
		});
		h.runAtTickTime(66, () -> {
			try {
				int sid = rec.find("start", b).get(0).streamId;
				List<Event> ended = rec.find("ended", b, e -> e.streamId == sid);
				h.assertValueEqual(ended.size(), 1, "viewer told the stream ended");
				h.assertTrue(ended.get(0).tick - leftAt[0] <= 60, "ended within 60 ticks");
				h.assertFalse(be.ownerOnline(), "emitter shows the owner offline");
				h.assertValueEqual(router.routes().size(), 0, "no route for an offline owner");
				h.succeed();
			} finally {
				removeAll(h, a, b);
			}
		});
	}
}
