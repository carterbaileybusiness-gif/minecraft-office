package local.carter.office.magic;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.jspecify.annotations.Nullable;

import local.carter.office.magic.cast.CastService.Result;
import local.carter.office.magic.cast.TeleportChannel;
import local.carter.office.magic.cast.Travel;
import local.carter.office.magic.core.Element;
import local.carter.office.magic.data.MagicAttachments;
import local.carter.office.magic.data.MagicData;
import local.carter.office.magic.item.MagicItems;
import local.carter.office.magic.net.FxEvent;
import local.carter.office.magic.net.MagicPayloads.TravelRoster;
import local.carter.office.magic.spell.SpellIds;

import net.minecraft.core.BlockPos;
import net.minecraft.core.GlobalPos;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.Mth;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.Vec3;

import net.fabricmc.fabric.api.gametest.v1.GameTest;

/**
 * Server GameTests for Travel to a friend (Task 7b): request → accept → 3 s channel → beside the friend; deny, expiry, travel
 * off, the cooldown shared with Recall both ways, interruptions, any dimension, unsafe landings, one request at a time, a friend
 * who leaves, the checks on asking, the Friends roster and the commands (non-op players may use {@code /magic travel}).
 *
 * <p>Travel shares Recall's channel, so these run in an environment of their own ({@link #ENV}), like Recall's.
 */
public class TravelGameTests {
	static final String ENV = "office-test:magic_travel";
	/** Where the friend stands: open floor, 24 blocks from {@link MagicTestKit#STAND}. */
	private static final BlockPos FRIEND = new BlockPos(16, 4, 28);

	private static ServerPlayer caster(GameTestHelper h) {
		return MagicTestKit.caster(h, Element.WIND, 1);
	}

	private static ServerPlayer friend(GameTestHelper h) {
		return MagicTestKit.otherPlayer(h, FRIEND);
	}

	private static long cooldownLeft(GameTestHelper h, ServerPlayer p) {
		return MagicAttachments.get(p).cooldownLeft(SpellIds.RECALL, p.level().getGameTime());
	}

	private static Result recall(ServerPlayer p) {
		return MagicTestKit.cast(p, SpellIds.RECALL, 0f);
	}

	/** Runs {@code command} as {@code p} (its own permissions: kit players are not operators). */
	private static void run(ServerPlayer p, String command) {
		p.level().getServer().getCommands().performPrefixedCommand(p.createCommandSourceStack(), command);
	}

	/** The command behind the first clickable part of {@code line} whose command starts with {@code prefix}, or null. */
	private static @Nullable String click(Component line, String prefix) {
		if (line.getStyle().getClickEvent() instanceof ClickEvent.RunCommand rc && rc.command().startsWith(prefix)) return rc.command();
		for (Component c : line.getSiblings()) {
			String found = click(c, prefix);
			if (found != null) return found;
		}
		return null;
	}

	/** Asks, checks the friend's request line and returns the request id its [Accept] button carries. */
	private static int ask(GameTestHelper h, MagicTestKit.Recorder rec, ServerPlayer caster, ServerPlayer friend) {
		h.assertValueEqual(Travel.request(caster, friend.getUUID()), Travel.Result.SENT, "request");
		List<Component> lines = rec.chats(friend);
		h.assertTrue(!lines.isEmpty(), "the friend got a chat line");
		Component line = lines.get(lines.size() - 1);
		h.assertValueEqual(rec.chatKeys(friend).get(lines.size() - 1), "magic.office.travel_ask", "request line");
		String accept = click(line, "/magic travel accept ");
		String deny = click(line, "/magic travel deny ");
		h.assertTrue(accept != null && deny != null, "[Accept] and [Deny] are clickable: " + line);
		int id = Integer.parseInt(accept.substring("/magic travel accept ".length()));
		h.assertValueEqual(deny, "/magic travel deny " + id, "both buttons carry the same id");
		h.assertValueEqual(Travel.pendingFrom(caster).map(Travel.Request::id), Optional.of(id), "open request");
		return id;
	}

	private static void assertStayed(GameTestHelper h, ServerPlayer p, String what) {
		double d = MagicTestKit.dist(p, Vec3.atBottomCenterOf(MagicTestKit.STAND));
		h.assertTrue(d < 0.01, what + ": " + d + " blocks from the start, at " + p.position());
	}

	/** On the floor right next to {@code friend} (not inside them), in their level, facing them. */
	private static void assertBeside(GameTestHelper h, ServerPlayer p, ServerPlayer friend, String what) {
		h.assertTrue(p.level() == friend.level(), what + ": same level as the friend");
		Vec3 d = friend.position().subtract(p.position());
		double flat = Math.sqrt(d.x * d.x + d.z * d.z);
		h.assertTrue(flat > 0.5 && flat < 1.5, what + ": " + flat + " blocks from the friend, at " + p.position());
		h.assertTrue(Math.abs(d.y) < 0.01, what + ": same floor height, dy " + d.y);
		float expected = (float) (Mth.atan2(d.z, d.x) * Mth.RAD_TO_DEG) - 90f;
		h.assertTrue(Math.abs(Mth.wrapDegrees(p.getYRot() - expected)) < 1f, what + ": facing the friend, yaw " + p.getYRot() + " vs " + expected);
	}

	private static ServerLevel nether(GameTestHelper h) {
		return h.getLevel().getServer().getLevel(Level.NETHER);
	}

	// ---- The happy path ----

	@GameTest(structure = MagicTestKit.ARENA, environment = ENV, maxTicks = 100)
	public void requestAcceptChannelArriveBesideTheFriend(GameTestHelper h) {
		ServerPlayer p = caster(h);
		ServerPlayer f = friend(h);
		MagicTestKit.Recorder rec = MagicTestKit.recorder(h);
		MagicData before = MagicAttachments.get(p);
		int id = ask(h, rec, p, f);
		h.assertValueEqual(rec.messageKeys(p), List.of("magic.office.travel_sent"), "caster told");
		run(f, "magic travel accept " + id);
		h.assertTrue(TeleportChannel.channeling(p), "channeling");
		h.assertTrue(Travel.pendingFrom(p).isEmpty(), "the request is answered");
		List<FxEvent> fx = rec.fx(p);
		h.assertValueEqual(fx.size(), 1, "one fx");
		h.assertValueEqual(fx.get(0).spell(), SpellIds.RECALL, "Recall's circle");
		h.assertValueEqual(fx.get(0).duration(), TeleportChannel.CHANNEL_TICKS, "a 3 s channel");
		h.assertValueEqual(fx.get(0).data()[1], TeleportChannel.FX_TRAVEL, "marked as travel");
		h.assertTrue(rec.messageKeys(p).contains("magic.office.travel_accepted"), "caster told " + rec.messageKeys(p));
		h.assertTrue(rec.messageKeys(f).contains("magic.office.travel_coming"), "friend told " + rec.messageKeys(f));
		h.assertValueEqual(recall(p), Result.BUSY, "no cast during the channel");
		h.runAfterDelay(59, () -> {
			assertStayed(h, p, "still in place at tick 59");
			h.assertValueEqual(cooldownLeft(h, p), 0L, "no cooldown while channeling");
		});
		h.runAfterDelay(61, () -> {
			assertBeside(h, p, f, "arrived");
			h.assertFalse(TeleportChannel.channeling(p), "channel over");
			long left = cooldownLeft(h, p);
			h.assertTrue(left >= 1190 && left <= 1200, "Recall's 60 s cooldown " + left);
			MagicData after = MagicAttachments.get(p);
			h.assertValueEqual(MagicAttachments.mana(p), 100, "no mana");
			h.assertValueEqual(after.level(), before.level(), "no grimoire level");
			h.assertValueEqual(after.xp(), before.xp(), "no grimoire xp");
			h.assertValueEqual(rec.cancels().size(), 0, "nothing cancelled");
			h.assertTrue(rec.messageKeys(f).contains("magic.office.travel_arrived"), "friend told " + rec.messageKeys(f));
			// Shared cooldown, travel → Recall: Recall is now cooling down too.
			MagicAttachments.set(p, MagicAttachments.get(p).withHome(Optional.of(GlobalPos.of(h.getLevel().dimension(), h.absolutePos(MagicTestKit.STAND)))));
			h.assertValueEqual(recall(p), Result.COOLDOWN, "Recall shares the cooldown");
			h.succeed();
		});
	}

	@GameTest(structure = MagicTestKit.ARENA, environment = ENV, maxTicks = 100)
	public void deniedRequestCostsNothing(GameTestHelper h) {
		ServerPlayer p = caster(h);
		ServerPlayer f = friend(h);
		MagicTestKit.Recorder rec = MagicTestKit.recorder(h);
		int id = ask(h, rec, p, f);
		run(f, "magic travel deny " + id);
		h.assertTrue(Travel.pendingFrom(p).isEmpty(), "request closed");
		h.assertValueEqual(rec.chatKeys(p), List.of("magic.office.travel_denied"), "caster told in chat");
		h.assertTrue(rec.messageKeys(f).contains("magic.office.travel_you_denied"), "friend told " + rec.messageKeys(f));
		h.assertFalse(TeleportChannel.channeling(p), "no channel");
		h.assertValueEqual(cooldownLeft(h, p), 0L, "no cooldown");
		h.assertValueEqual(rec.fx(p).size(), 0, "no fx");
		// The old buttons do nothing now.
		h.assertValueEqual(Travel.accept(f, id), Travel.Answer.GONE, "accept after deny");
		h.assertTrue(rec.messageKeys(f).contains("magic.office.travel_gone"), "friend told it is gone");
		h.assertFalse(TeleportChannel.channeling(p), "still no channel");
		// No asking the same friend again right away (they just said no).
		rec.clear();
		h.assertValueEqual(Travel.request(p, f.getUUID()), Travel.Result.TOO_SOON, "ask again at once");
		h.assertValueEqual(rec.messageKeys(p), List.of("magic.office.travel_too_soon"), "told kindly");
		h.assertValueEqual(rec.chats(f).size(), 0, "the friend is not asked again");
		h.succeed();
	}

	@GameTest(structure = MagicTestKit.ARENA, environment = ENV, maxTicks = 700)
	public void requestExpiresAfterThirtySeconds(GameTestHelper h) {
		ServerPlayer p = caster(h);
		ServerPlayer f = friend(h);
		MagicTestKit.Recorder rec = MagicTestKit.recorder(h);
		int id = ask(h, rec, p, f);
		h.runAfterDelay(Travel.EXPIRE_TICKS - 10, () -> {
			h.assertTrue(Travel.pendingFrom(p).isPresent(), "still open just before 30 s");
			h.assertValueEqual(rec.chatKeys(p), List.of(), "nothing told yet");
		});
		h.runAfterDelay(Travel.EXPIRE_TICKS + 5, () -> {
			h.assertTrue(Travel.pendingFrom(p).isEmpty(), "expired");
			h.assertValueEqual(rec.chatKeys(p), List.of("magic.office.travel_expired"), "caster told");
			h.assertValueEqual(Travel.accept(f, id), Travel.Answer.GONE, "accept after expiry");
			h.assertFalse(TeleportChannel.channeling(p), "no channel");
			h.assertValueEqual(cooldownLeft(h, p), 0L, "no cooldown");
			h.assertValueEqual(Travel.request(p, f.getUUID()), Travel.Result.TOO_SOON, "an expired request also waits 30 s");
			h.succeed();
		});
	}

	@GameTest(structure = MagicTestKit.ARENA, environment = ENV)
	public void travelOffRefusesRequests(GameTestHelper h) {
		ServerPlayer p = caster(h);
		ServerPlayer f = friend(h);
		MagicTestKit.Recorder rec = MagicTestKit.recorder(h);
		h.assertTrue(MagicAttachments.get(f).travel(), "requests are on by default");
		run(f, "magic travel off");
		h.assertFalse(MagicAttachments.get(f).travel(), "off, stored in MagicData");
		h.assertValueEqual(rec.chatKeys(f), List.of("magic.office.travel_now_off"), "told");
		h.assertValueEqual(Travel.request(p, f.getUUID()), Travel.Result.TRAVEL_OFF, "refused");
		h.assertValueEqual(rec.messageKeys(p), List.of("magic.office.travel_refuses"), "caster told why");
		h.assertTrue(Travel.pendingFrom(p).isEmpty(), "no request");
		h.assertValueEqual(rec.chatKeys(f).size(), 1, "the friend got no request line");
		run(f, "magic travel on");
		h.assertTrue(MagicAttachments.get(f).travel(), "back on");
		h.assertValueEqual(Travel.request(p, f.getUUID()), Travel.Result.SENT, "accepted again");
		h.succeed();
	}

	@GameTest(structure = MagicTestKit.ARENA, environment = ENV, maxTicks = 100)
	public void recallCooldownBlocksTravel(GameTestHelper h) {
		ServerPlayer p = caster(h);
		ServerPlayer f = friend(h);
		MagicTestKit.Recorder rec = MagicTestKit.recorder(h);
		MagicAttachments.set(p, MagicAttachments.get(p).withHome(Optional.of(GlobalPos.of(h.getLevel().dimension(), h.absolutePos(new BlockPos(8, 4, 8))))));
		h.assertValueEqual(recall(p), Result.CAST, "recall");
		h.assertValueEqual(Travel.request(p, f.getUUID()), Travel.Result.BUSY, "asking during the Recall channel");
		h.runAfterDelay(62, () -> {
			h.assertTrue(cooldownLeft(h, p) > 1000, "Recall is cooling down");
			rec.clear();
			h.assertValueEqual(Travel.request(p, f.getUUID()), Travel.Result.COOLDOWN, "Recall → travel: shared cooldown");
			h.assertValueEqual(rec.messageKeys(p), List.of("magic.office.travel_cooldown"), "caster told");
			h.assertTrue(Travel.pendingFrom(p).isEmpty(), "no request");
			h.assertValueEqual(rec.chats(f).size(), 0, "the friend was not asked");
			h.succeed();
		});
	}

	// ---- Interruptions ----

	@GameTest(structure = MagicTestKit.ARENA, environment = ENV, maxTicks = 100)
	public void damageCancelsTheChannel(GameTestHelper h) {
		ServerPlayer p = caster(h);
		ServerPlayer f = friend(h);
		MagicTestKit.Recorder rec = MagicTestKit.recorder(h);
		run(f, "magic travel accept " + ask(h, rec, p, f));
		h.assertTrue(TeleportChannel.channeling(p), "channeling");
		h.runAfterDelay(30, () -> {
			h.assertTrue(p.hurtServer(h.getLevel(), h.getLevel().damageSources().generic(), 1f), "the hit landed");
			h.assertFalse(TeleportChannel.channeling(p), "interrupted");
			h.assertValueEqual(rec.cancels(), List.of(p.getId()), "circle cancelled");
			h.assertTrue(rec.messageKeys(p).contains("magic.office.travel_interrupted"), "told " + rec.messageKeys(p));
		});
		h.runAfterDelay(70, () -> {
			assertStayed(h, p, "stayed");
			h.assertValueEqual(cooldownLeft(h, p), 0L, "no cooldown");
			h.assertValueEqual(Travel.request(p, f.getUUID()), Travel.Result.SENT, "may ask again at once");
			h.succeed();
		});
	}

	@GameTest(structure = MagicTestKit.ARENA, environment = ENV, maxTicks = 100)
	public void casterDeathCancelsTheChannel(GameTestHelper h) {
		ServerPlayer p = caster(h);
		ServerPlayer f = friend(h);
		MagicTestKit.Recorder rec = MagicTestKit.recorder(h);
		run(f, "magic travel accept " + ask(h, rec, p, f));
		h.runAfterDelay(30, () -> {
			MagicTestKit.kill(p);
			h.assertFalse(TeleportChannel.channeling(p), "interrupted by death");
		});
		h.runAfterDelay(70, () -> {
			assertStayed(h, p, "the dead caster was not moved");
			h.assertValueEqual(cooldownLeft(h, p), 0L, "no cooldown");
			h.succeed();
		});
	}

	@GameTest(structure = MagicTestKit.ARENA, environment = ENV, maxTicks = 100)
	public void casterDeathDisconnectOrDimensionChangeWithdrawTheRequest(GameTestHelper h) {
		ServerPlayer f = friend(h);
		MagicTestKit.Recorder rec = MagicTestKit.recorder(h);
		ServerPlayer dies = caster(h);
		int a = ask(h, rec, dies, f);
		MagicTestKit.kill(dies);
		h.assertTrue(Travel.pendingFrom(dies).isEmpty(), "death withdraws");
		h.assertValueEqual(Travel.accept(f, a), Travel.Answer.GONE, "accept after death");

		ServerPlayer leaves = caster(h);
		int b = ask(h, rec, leaves, f);
		MagicTestKit.disconnect(leaves);
		h.assertTrue(Travel.pendingTo(f).stream().noneMatch(r -> r.id() == b), "disconnect withdraws");
		h.assertValueEqual(Travel.accept(f, b), Travel.Answer.GONE, "accept after disconnect");

		ServerPlayer moves = caster(h);
		int c = ask(h, rec, moves, f);
		MagicTestKit.teleportTo(moves, nether(h), new Vec3(330.5, 80, 330.5));
		h.assertTrue(Travel.pendingFrom(moves).isEmpty(), "dimension change withdraws");
		h.assertValueEqual(Travel.accept(f, c), Travel.Answer.GONE, "accept after the dimension change");
		h.assertFalse(TeleportChannel.channeling(moves), "no channel");
		h.succeed();
	}

	@GameTest(structure = MagicTestKit.ARENA, environment = ENV, maxTicks = 100)
	public void friendLeavingCancelsTheRequest(GameTestHelper h) {
		ServerPlayer p = caster(h);
		ServerPlayer f = friend(h);
		MagicTestKit.Recorder rec = MagicTestKit.recorder(h);
		ask(h, rec, p, f);
		MagicTestKit.disconnect(f);
		h.assertTrue(Travel.pendingFrom(p).isEmpty(), "request closed");
		h.assertValueEqual(rec.chatKeys(p), List.of("magic.office.travel_left"), "caster told");
		h.assertValueEqual(Travel.request(p, f.getUUID()), Travel.Result.OFFLINE, "the friend is gone");
		h.succeed();
	}

	@GameTest(structure = MagicTestKit.ARENA, environment = ENV, maxTicks = 100)
	public void friendLeavingStopsTheChannel(GameTestHelper h) {
		ServerPlayer p = caster(h);
		ServerPlayer f = friend(h);
		MagicTestKit.Recorder rec = MagicTestKit.recorder(h);
		run(f, "magic travel accept " + ask(h, rec, p, f));
		h.runAfterDelay(30, () -> {
			MagicTestKit.disconnect(f);
			h.assertFalse(TeleportChannel.channeling(p), "channel stopped");
			h.assertValueEqual(rec.cancels(), List.of(p.getId()), "circle cancelled");
			h.assertTrue(rec.chatKeys(p).contains("magic.office.travel_left"), "caster told " + rec.chatKeys(p));
		});
		h.runAfterDelay(70, () -> {
			assertStayed(h, p, "stayed");
			h.assertValueEqual(cooldownLeft(h, p), 0L, "no cooldown");
			h.succeed();
		});
	}

	// ---- Any dimension, safety ----

	@GameTest(structure = MagicTestKit.ARENA, environment = ENV, maxTicks = 100)
	public void travelToAFriendInAnotherDimension(GameTestHelper h) {
		ServerPlayer p = caster(h);
		ServerPlayer f = friend(h);
		MagicTestKit.Recorder rec = MagicTestKit.recorder(h);
		ServerLevel nether = nether(h);
		BlockPos feet = new BlockPos(260, 80, 260);
		for (BlockPos b : BlockPos.betweenClosed(feet.offset(-2, -1, -2), feet.offset(2, -1, 2))) nether.setBlockAndUpdate(b, Blocks.STONE.defaultBlockState());
		for (BlockPos b : BlockPos.betweenClosed(feet.offset(-2, 0, -2), feet.offset(2, 2, 2))) nether.setBlockAndUpdate(b, Blocks.AIR.defaultBlockState());
		MagicTestKit.teleportTo(f, nether, Vec3.atBottomCenterOf(feet));
		run(f, "magic travel accept " + ask(h, rec, p, f));
		h.assertTrue(TeleportChannel.channeling(p), "channeling");
		h.runAfterDelay(59, () -> h.assertTrue(p.level() == h.getLevel(), "still in the overworld at tick 59"));
		h.runAfterDelay(61, () -> {
			h.assertTrue(p.level() == nether, "in the Nether");
			assertBeside(h, p, f, "arrived in the Nether");
			h.assertValueEqual(rec.cancels().size(), 0, "arriving is not an interruption");
			h.assertTrue(cooldownLeft(h, p) > 1000, "cooldown paid");
			h.succeed();
		});
	}

	@GameTest(structure = MagicTestKit.ARENA, environment = ENV, maxTicks = 100)
	public void unsafeLandingAtAcceptStartsNothing(GameTestHelper h) {
		ServerPlayer p = caster(h);
		ServerPlayer f = friend(h);
		MagicTestKit.Recorder rec = MagicTestKit.recorder(h);
		int id = ask(h, rec, p, f);
		MagicTestKit.teleportTo(f, FRIEND.above(10)); // in mid-air: no ground within three blocks anywhere near
		h.assertValueEqual(Travel.accept(f, id), Travel.Answer.CANNOT, "accept");
		h.assertFalse(TeleportChannel.channeling(p), "no channel");
		h.assertTrue(rec.messageKeys(p).contains("magic.office.travel_blocked"), "caster told " + rec.messageKeys(p));
		h.assertTrue(rec.messageKeys(f).contains("magic.office.travel_cannot"), "friend told " + rec.messageKeys(f));
		h.assertValueEqual(rec.fx(p).size(), 0, "no fx");
		h.assertValueEqual(cooldownLeft(h, p), 0L, "no cooldown");
		assertStayed(h, p, "stayed");
		h.succeed();
	}

	@GameTest(structure = MagicTestKit.ARENA, environment = ENV, maxTicks = 100)
	public void landingResolvedAgainAtTheEnd(GameTestHelper h) {
		ServerPlayer p = caster(h);
		ServerPlayer f = friend(h);
		MagicTestKit.Recorder rec = MagicTestKit.recorder(h);
		run(f, "magic travel accept " + ask(h, rec, p, f));
		// The friend walks off a cliff edge into the air mid-channel: nowhere safe beside them any more.
		h.runAfterDelay(20, () -> MagicTestKit.teleportTo(f, FRIEND.above(10)));
		h.runAfterDelay(62, () -> {
			assertStayed(h, p, "stayed");
			h.assertFalse(TeleportChannel.channeling(p), "channel over");
			h.assertValueEqual(rec.cancels(), List.of(p.getId()), "circle cancelled");
			h.assertTrue(rec.messageKeys(p).contains("magic.office.travel_blocked"), "told " + rec.messageKeys(p));
			h.assertValueEqual(cooldownLeft(h, p), 0L, "no cooldown");
			h.succeed();
		});
	}

	@GameTest(structure = MagicTestKit.ARENA, environment = ENV, maxTicks = 100)
	public void friendWhoMovedIsFollowed(GameTestHelper h) {
		ServerPlayer p = caster(h);
		ServerPlayer f = friend(h);
		MagicTestKit.Recorder rec = MagicTestKit.recorder(h);
		run(f, "magic travel accept " + ask(h, rec, p, f));
		h.runAfterDelay(20, () -> MagicTestKit.teleportTo(f, new BlockPos(26, 4, 20)));
		h.runAfterDelay(62, () -> {
			assertBeside(h, p, f, "beside the friend's new spot");
			h.succeed();
		});
	}

	// ---- Asking ----

	@GameTest(structure = MagicTestKit.ARENA, environment = ENV)
	public void oneOpenRequestAtATime(GameTestHelper h) {
		ServerPlayer p = caster(h);
		ServerPlayer a = friend(h);
		ServerPlayer b = MagicTestKit.otherPlayer(h, new BlockPos(24, 4, 24));
		MagicTestKit.Recorder rec = MagicTestKit.recorder(h);
		int id = ask(h, rec, p, a);
		h.assertValueEqual(Travel.request(p, b.getUUID()), Travel.Result.PENDING, "a second friend");
		h.assertValueEqual(Travel.request(p, a.getUUID()), Travel.Result.PENDING, "the same friend again");
		h.assertValueEqual(rec.messageKeys(p).get(rec.messageKeys(p).size() - 1), "magic.office.travel_pending", "told");
		h.assertTrue(Travel.pendingTo(b).isEmpty(), "b was never asked");
		h.assertValueEqual(rec.chats(b).size(), 0, "no line for b");
		h.assertValueEqual(rec.chats(a).size(), 1, "one line for a");
		h.assertValueEqual(Travel.pendingFrom(p).map(Travel.Request::id), Optional.of(id), "the first request stays");
		h.succeed();
	}

	@GameTest(structure = MagicTestKit.ARENA, environment = ENV)
	public void askingIsChecked(GameTestHelper h) {
		ServerPlayer f = friend(h);
		MagicTestKit.Recorder rec = MagicTestKit.recorder(h);
		ServerPlayer unbound = MagicTestKit.otherPlayer(h, new BlockPos(8, 4, 8));
		unbound.setItemInHand(InteractionHand.OFF_HAND, new ItemStack(MagicItems.GRIMOIRE));
		h.assertValueEqual(Travel.request(unbound, f.getUUID()), Travel.Result.NOT_BOUND, "unbound");

		ServerPlayer p = caster(h);
		p.setItemInHand(InteractionHand.OFF_HAND, ItemStack.EMPTY);
		h.assertValueEqual(Travel.request(p, f.getUUID()), Travel.Result.NO_GRIMOIRE, "no grimoire");
		p.setItemInHand(InteractionHand.MAIN_HAND, new ItemStack(MagicItems.GRIMOIRE));
		h.assertValueEqual(Travel.request(p, p.getUUID()), Travel.Result.SELF, "yourself");
		h.assertValueEqual(Travel.request(p, UUID.randomUUID()), Travel.Result.OFFLINE, "nobody");
		h.assertValueEqual(rec.messageKeys(p), List.of("magic.office.travel_need_grimoire", "magic.office.travel_offline",
				"magic.office.travel_offline"), "messages");
		h.assertTrue(Travel.pendingFrom(p).isEmpty(), "no request");

		// Creative players ask the same way (travel costs no mana anyway).
		p.setGameMode(GameType.CREATIVE);
		h.assertValueEqual(Travel.request(p, f.getUUID()), Travel.Result.SENT, "creative, grimoire in the main hand");
		h.succeed();
	}

	@GameTest(structure = MagicTestKit.ARENA, environment = ENV)
	public void busyWhileAnsweringSignsOrCharging(GameTestHelper h) {
		ServerPlayer f = friend(h);
		MagicTestKit.recorder(h);
		ServerPlayer signs = MagicTestKit.caster(h, Element.WIND, 7);
		Identifier ultimate = Identifier.fromNamespaceAndPath("office-test", "probe_travel_signs");
		MagicTestKit.override(h, MagicTestKit.probe("probe_travel_signs", Element.WIND, 4));
		h.assertValueEqual(MagicTestKit.cast(signs, ultimate, 1f), Result.CHALLENGED, "hand signs open");
		h.assertValueEqual(Travel.request(signs, f.getUUID()), Travel.Result.BUSY, "during hand signs");

		ServerPlayer charging = caster(h);
		MagicTestKit.Probe probe = MagicTestKit.probe("probe_travel_charge", Element.WIND, 0);
		MagicTestKit.override(h, probe);
		MagicAttachments.set(charging, MagicAttachments.get(charging).withSelected(Optional.of(probe.id())));
		MagicItems.GRIMOIRE.use(h.getLevel(), charging, InteractionHand.OFF_HAND);
		h.assertTrue(charging.isUsingItem(), "charging");
		h.assertValueEqual(Travel.request(charging, f.getUUID()), Travel.Result.BUSY, "while charging");
		charging.stopUsingItem();
		h.succeed();
	}

	@GameTest(structure = MagicTestKit.ARENA, environment = ENV, maxTicks = 100)
	public void acceptChecksTheCasterAgain(GameTestHelper h) {
		ServerPlayer p = caster(h);
		ServerPlayer f = friend(h);
		MagicTestKit.Recorder rec = MagicTestKit.recorder(h);
		int id = ask(h, rec, p, f);
		// Meanwhile the caster recalls home: by the time the friend accepts, Recall's cooldown is running.
		MagicAttachments.set(p, MagicAttachments.get(p).withHome(Optional.of(GlobalPos.of(h.getLevel().dimension(), h.absolutePos(new BlockPos(8, 4, 8))))));
		h.assertValueEqual(recall(p), Result.CAST, "recall");
		h.assertValueEqual(Travel.accept(f, id), Travel.Answer.CANNOT, "the caster is channeling Recall");
		h.assertTrue(rec.messageKeys(p).contains("magic.office.travel_busy"), "caster told " + rec.messageKeys(p));
		h.assertTrue(rec.messageKeys(f).contains("magic.office.travel_cannot"), "friend told " + rec.messageKeys(f));
		h.assertTrue(Travel.pendingFrom(p).isEmpty(), "the answered request is closed");
		h.runAfterDelay(62, () -> {
			// Home now, with Recall's cooldown running: a new accept is refused for the cooldown.
			h.assertTrue(cooldownLeft(h, p) > 1000, "Recall cooled down");
			rec.clear();
			MagicAttachments.set(p, MagicAttachments.get(p).withReadyAt(java.util.Map.of()));
			int again = ask(h, rec, p, f);
			MagicAttachments.set(p, MagicAttachments.get(p).withReady(SpellIds.RECALL, p.level().getGameTime() + 400));
			h.assertValueEqual(Travel.accept(f, again), Travel.Answer.CANNOT, "cooldown started after asking");
			h.assertTrue(rec.messageKeys(p).contains("magic.office.travel_cooldown"), "caster told " + rec.messageKeys(p));
			h.assertFalse(TeleportChannel.channeling(p), "no channel");
			h.succeed();
		});
	}

	@GameTest(structure = MagicTestKit.ARENA, environment = ENV)
	public void onlyTheAskedFriendCanAnswer(GameTestHelper h) {
		ServerPlayer p = caster(h);
		ServerPlayer f = friend(h);
		ServerPlayer other = MagicTestKit.otherPlayer(h, new BlockPos(24, 4, 24));
		MagicTestKit.Recorder rec = MagicTestKit.recorder(h);
		int id = ask(h, rec, p, f);
		run(other, "magic travel accept " + id);
		run(other, "magic travel deny " + id);
		h.assertTrue(Travel.pendingFrom(p).isPresent(), "still open");
		h.assertFalse(TeleportChannel.channeling(p), "no channel");
		h.assertValueEqual(rec.messageKeys(other), List.of("magic.office.travel_gone", "magic.office.travel_gone"), "other told");
		h.succeed();
	}

	@GameTest(structure = MagicTestKit.ARENA, environment = ENV)
	public void travelCommandsNeedNoOpButTheRestDo(GameTestHelper h) {
		ServerPlayer f = friend(h);
		MagicAttachments.set(f, MagicAttachments.get(f).withLevel(2));
		MagicAttachments.setMana(f, 40);
		run(f, "magic level @s 9");
		run(f, "magic reset @s");
		run(f, "magic mana @s 5");
		h.assertValueEqual(MagicAttachments.get(f).level(), 2, "level and reset refused for a non-op");
		h.assertValueEqual(MagicAttachments.mana(f), 40, "mana refused for a non-op");
		// undo touches no player data, so check the command tree itself: a non-op cannot use it, an op can.
		var magic = f.level().getServer().getCommands().getDispatcher().getRoot().getChild("magic");
		var mine = f.createCommandSourceStack();
		var op = f.level().getServer().createCommandSourceStack();
		for (String sub : List.of("level", "mana", "reset", "undo")) {
			h.assertFalse(magic.getChild(sub).canUse(mine), "/magic " + sub + " is refused for a non-op");
			h.assertTrue(magic.getChild(sub).canUse(op), "/magic " + sub + " works for an op");
		}
		h.assertTrue(magic.canUse(mine) && magic.getChild("travel").canUse(mine), "/magic travel is for anyone");
		run(f, "magic travel off");
		h.assertFalse(MagicAttachments.get(f).travel(), "travel off works for anyone");
		h.succeed();
	}

	@GameTest(structure = MagicTestKit.ARENA, environment = ENV, maxTicks = 800)
	public void declinedFriendCanBeAskedAgainAfterThirtySeconds(GameTestHelper h) {
		ServerPlayer p = caster(h);
		ServerPlayer f = friend(h);
		ServerPlayer other = MagicTestKit.otherPlayer(h, new BlockPos(24, 4, 24));
		MagicTestKit.Recorder rec = MagicTestKit.recorder(h);
		run(f, "magic travel deny " + ask(h, rec, p, f));
		h.assertValueEqual(Travel.request(p, f.getUUID()), Travel.Result.TOO_SOON, "the same friend at once");
		h.assertValueEqual(Travel.request(p, other.getUUID()), Travel.Result.SENT, "another friend is fine");
		run(other, "magic travel accept " + Travel.pendingFrom(p).orElseThrow().id());
		TeleportChannel.interrupt(p, false); // stay put; only the request mattered
		MagicAttachments.set(p, MagicAttachments.get(p).withReadyAt(java.util.Map.of()));
		h.runAfterDelay(Travel.RETRY_TICKS - 20, () ->
				h.assertValueEqual(Travel.request(p, f.getUUID()), Travel.Result.TOO_SOON, "still too soon just before 30 s"));
		h.runAfterDelay(Travel.RETRY_TICKS + 5, () -> {
			h.assertValueEqual(Travel.request(p, f.getUUID()), Travel.Result.SENT, "may ask again after 30 s");
			h.succeed();
		});
	}

	@GameTest(structure = MagicTestKit.ARENA, environment = ENV)
	public void aFriendHasAtMostThreeRequestsWaiting(GameTestHelper h) {
		ServerPlayer f = friend(h);
		MagicTestKit.Recorder rec = MagicTestKit.recorder(h);
		for (int i = 0; i < Travel.MAX_OPEN_PER_FRIEND; i++) {
			ServerPlayer c = caster(h);
			h.assertValueEqual(Travel.request(c, f.getUUID()), Travel.Result.SENT, "request " + (i + 1));
		}
		ServerPlayer fourth = caster(h);
		h.assertValueEqual(Travel.request(fourth, f.getUUID()), Travel.Result.FRIEND_FULL, "a fourth waits");
		h.assertValueEqual(rec.messageKeys(fourth), List.of("magic.office.travel_friend_full"), "told");
		h.assertValueEqual(Travel.pendingTo(f).size(), Travel.MAX_OPEN_PER_FRIEND, "three waiting");
		h.assertValueEqual(rec.chats(f).size(), Travel.MAX_OPEN_PER_FRIEND, "three lines, not four");
		run(f, "magic travel deny " + Travel.pendingTo(f).get(0).id());
		h.assertValueEqual(Travel.request(fourth, f.getUUID()), Travel.Result.SENT, "room again after an answer");
		h.succeed();
	}

	@GameTest(structure = MagicTestKit.ARENA, environment = ENV, maxTicks = 100)
	public void acceptingTwiceStartsOneChannel(GameTestHelper h) {
		ServerPlayer p = caster(h);
		ServerPlayer f = friend(h);
		MagicTestKit.Recorder rec = MagicTestKit.recorder(h);
		int id = ask(h, rec, p, f);
		h.assertValueEqual(Travel.accept(f, id), Travel.Answer.STARTED, "first accept");
		h.assertValueEqual(Travel.accept(f, id), Travel.Answer.GONE, "second accept");
		run(f, "magic travel accept " + id);
		h.assertValueEqual(rec.fx(p).size(), 1, "one fx");
		h.runAfterDelay(62, () -> {
			assertBeside(h, p, f, "arrived once");
			h.assertValueEqual(rec.fx(p).size(), 1, "still one fx");
			h.succeed();
		});
	}

	@GameTest(structure = MagicTestKit.ARENA, environment = ENV)
	public void deadOrSpectatingCastersAreBusyAndSpectatorsAreNotListed(GameTestHelper h) {
		ServerPlayer f = friend(h);
		MagicTestKit.recorder(h);
		ServerPlayer watcher = caster(h);
		watcher.setGameMode(GameType.SPECTATOR);
		h.assertValueEqual(Travel.request(watcher, f.getUUID()), Travel.Result.BUSY, "a spectator");
		h.assertValueEqual(Travel.roster(watcher).state(), TravelRoster.State.BUSY, "a spectator's roster");
		ServerPlayer viewer = caster(h);
		h.assertTrue(Travel.roster(viewer).friends().stream().noneMatch(x -> x.id().equals(watcher.getUUID())), "spectators are not listed");
		ServerPlayer dead = caster(h);
		MagicTestKit.kill(dead);
		h.assertValueEqual(Travel.request(dead, f.getUUID()), Travel.Result.BUSY, "a dead caster");
		h.succeed();
	}

	@GameTest(structure = MagicTestKit.ARENA, environment = ENV)
	public void landingPrefersTheFriendsSideOfAClaimBorder(GameTestHelper h) {
		ServerPlayer p = caster(h);
		ServerPlayer f = friend(h);
		int fx = f.blockPosition().getX();
		// Everything west of the friend's column is someone else's claim; the friend stands in unclaimed land.
		local.carter.office.magic.terrain.ClaimCheck westClaimed = (caster, level, origin, radius) -> pos -> pos.getX() < fx;
		Vec3 at = Travel.besideFriend(p, f, westClaimed);
		h.assertTrue(at != null && at.x >= fx, "landed on the friend's side: " + at + " (friend x " + fx + ")");
		// With no claims the nearest cell wins, which is the western neighbour.
		Vec3 plain = Travel.besideFriend(p, f, local.carter.office.magic.terrain.ClaimCheck.NONE);
		h.assertTrue(plain != null && plain.x < fx, "without claims the west cell is nearest first: " + plain);
		// The friend inside the other claim: cells in that same claim come first.
		local.carter.office.magic.terrain.ClaimCheck eastClaimed = (caster, level, origin, radius) -> pos -> pos.getX() >= fx;
		Vec3 inside = Travel.besideFriend(p, f, eastClaimed);
		h.assertTrue(inside != null && inside.x >= fx, "stays inside the claim the friend stands in: " + inside);
		h.succeed();
	}

	// ---- The Friends page roster ----

	@GameTest(structure = MagicTestKit.ARENA, environment = ENV, maxTicks = 100)
	public void rosterListsOthersWithTheirStateAndMine(GameTestHelper h) {
		ServerPlayer p = caster(h);
		ServerPlayer a = friend(h);
		ServerPlayer b = MagicTestKit.otherPlayer(h, new BlockPos(24, 4, 24));
		MagicTestKit.Recorder rec = MagicTestKit.recorder(h);
		MagicAttachments.set(b, MagicAttachments.get(b).withTravel(false));
		TravelRoster r = Travel.roster(p);
		h.assertTrue(r.friends().stream().noneMatch(x -> x.id().equals(p.getUUID())), "not yourself");
		h.assertTrue(r.friends().stream().anyMatch(x -> x.id().equals(a.getUUID()) && x.accepts()), "a accepts");
		h.assertTrue(r.friends().stream().anyMatch(x -> x.id().equals(b.getUUID()) && !x.accepts()), "b has travel off");
		h.assertValueEqual(r.state(), TravelRoster.State.READY, "ready");
		ask(h, rec, p, a);
		r = Travel.roster(p);
		h.assertValueEqual(r.state(), TravelRoster.State.WAITING, "waiting");
		h.assertValueEqual(r.waitingFor(), Optional.of(a.getUUID()), "for a");
		h.assertTrue(r.ticksLeft() > Travel.EXPIRE_TICKS - 5 && r.ticksLeft() <= Travel.EXPIRE_TICKS, "ticks left " + r.ticksLeft());
		Travel.sendRoster(p);
		h.assertValueEqual(rec.rosters(p).size(), 1, "sent");
		Travel.query(p);
		Travel.query(p);
		Travel.query(p);
		h.assertValueEqual(rec.rosters(p).size(), 1, "queries inside the throttle window are not answered at once");
		run(a, "magic travel accept " + Travel.pendingFrom(p).orElseThrow().id());
		h.assertValueEqual(Travel.roster(p).state(), TravelRoster.State.TRAVELLING, "travelling");
		h.runAfterDelay(Travel.ROSTER_MIN_TICKS + 5, () -> {
			h.assertValueEqual(rec.rosters(p).size(), 2, "answered once when the window ended, however many queries arrived");
			h.assertValueEqual(rec.rosters(p).get(1).state(), TravelRoster.State.TRAVELLING, "with the fresh state");
			h.succeed();
		});
	}
}
