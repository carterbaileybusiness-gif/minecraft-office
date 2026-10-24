package local.carter.office.magic;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.WeakHashMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;

import org.jspecify.annotations.Nullable;

import local.carter.office.hologram.HologramBlockEntity;
import local.carter.office.hologram.OfficeBlocks;
import local.carter.office.magic.cast.CastService;
import local.carter.office.magic.cast.CastService.Result;
import local.carter.office.magic.core.Element;
import local.carter.office.magic.core.HandSigns;
import local.carter.office.magic.core.ManaMath;
import local.carter.office.magic.data.MagicAttachments;
import local.carter.office.magic.data.MagicData;
import local.carter.office.magic.item.GrimoireLook;
import local.carter.office.magic.item.MagicItems;
import local.carter.office.magic.net.FxEvent;
import local.carter.office.magic.net.MagicPayloads.OpenGrimoire;
import local.carter.office.magic.net.MagicPayloads.SignChallenge;
import local.carter.office.magic.net.MagicPayloads.TravelRoster;
import local.carter.office.magic.net.MagicSender;
import local.carter.office.magic.net.MagicServer;
import local.carter.office.magic.spell.CastContext;
import local.carter.office.magic.spell.CastOutcome;
import local.carter.office.magic.spell.Spell;
import local.carter.office.magic.spell.SpellCost;
import local.carter.office.magic.spell.SpellIds;
import local.carter.office.magic.spell.SpellRegistry;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.gametest.framework.GameTestInfo;
import net.minecraft.gametest.framework.GameTestListener;
import net.minecraft.gametest.framework.GameTestRunner;
import net.minecraft.network.DisconnectionDetails;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.game.ServerboundPlayerLoadedPacket;
import net.minecraft.network.chat.contents.TranslatableContents;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.Mth;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.ai.goal.GoalSelector;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.animal.wolf.Wolf;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.monster.zombie.Zombie;
import net.minecraft.world.entity.npc.villager.Villager;
import net.minecraft.world.entity.projectile.arrow.AbstractArrow;
import net.minecraft.world.entity.projectile.arrow.Arrow;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.Fluids;
import net.minecraft.world.phys.Vec3;

/**
 * Everything the magic GameTests share. It is complete for the element lanes, which never edit it; only M1 tasks (1–11) may
 * extend it. Players are real: in the player list and the level, with a connection, so death, respawn, teleport,
 * disconnect, player ticks and level entity queries behave as on a server. Positions are relative to the test
 * unless named {@code abs}. Use {@code @GameTest(structure = MagicTestKit.ARENA)}: a 32 × 24 × 32 arena, stone at y 0–3,
 * air above; the caster stands at {@link #STAND} facing +Z.
 *
 * <p>GameTests in one batch run at the same time on one server, so the kit keeps per-test state: the {@link Recorder} only
 * sees events of its own test's players (or, failing that, events inside its test's bounds), and {@link #override} probes are
 * seen only by its own test's kit players (lookups without a player, such as {@code SpellRegistry.get(id)}, never see them)
 * and are removed when the test ends. GameTest servers have no spawn protection and the kit clears
 * respawn configs, so the fallback claim rule only reacts to what a test sets up.
 */
public final class MagicTestKit {
	public static final String ARENA = "office-test:magic_arena";
	public static final int SIZE_X = 32;
	public static final int SIZE_Y = 24;
	public static final int SIZE_Z = 32;
	/** Top stone layer; the floor surface is y = 4. */
	public static final int FLOOR_TOP = 3;
	public static final BlockPos STAND = new BlockPos(16, 4, 4);
	/** XP levels every {@link #caster} starts with. */
	public static final int XP_LEVELS = 30;
	/**
	 * GameTest environment for tests that expect terrain edits to land: {@code @GameTest(environment = MagicTestKit.TERRAIN_ENV)}.
	 * Tests in one environment run as one batch, and the fallback claim rule sees every hologram emitter and every online
	 * player's home and respawn point, including other tests' (the default batch has hologram tests placing emitters). Tests
	 * here must never place an emitter or set a home or respawn point; a test that does needs an environment of its own.
	 */
	public static final String TERRAIN_ENV = "office-test:magic_terrain";

	private static final Map<Entity, GameTestHelper> OWNERS = new WeakHashMap<>();
	private static final Map<GameTestHelper, TestState> TESTS = new IdentityHashMap<>();
	private static final Router ROUTER = new Router();

	private MagicTestKit() {}

	// ---- Players ----

	/** A bound survival mock at {@link #STAND} facing +Z: full mana, grimoire in the off hand, empty main hand, 30 XP levels, slot 0 selected. */
	public static ServerPlayer caster(GameTestHelper h, Element e, int level) {
		ServerPlayer p = mock(h, STAND);
		ItemStack grimoire = new ItemStack(MagicItems.GRIMOIRE);
		GrimoireLook.stamp(grimoire, Optional.of(e), level);
		p.setItemInHand(InteractionHand.OFF_HAND, grimoire);
		MagicAttachments.set(p, MagicData.EMPTY.withElement(Optional.of(e)).withLevel(level).withSelected(Optional.of(SpellIds.at(e, 0))));
		MagicAttachments.setMana(p, ManaMath.max(level));
		p.setExperienceLevels(XP_LEVELS);
		return p;
	}

	/** Another survival mock at {@code rel} facing +Z: unbound, empty hands, no XP. */
	public static ServerPlayer otherPlayer(GameTestHelper h, BlockPos rel) {
		return mock(h, rel);
	}

	/**
	 * Real respawn of a dead kit player ({@code PlayerList.respawn}, which fires {@code AFTER_RESPAWN}, where Fabric copies
	 * {@code copyOnDeath} attachments). The new player stands at the world spawn (respawn configs are cleared); it is a kit
	 * player too and is disconnected when the test ends. If {@code dead} is still alive it is killed first.
	 */
	public static ServerPlayer respawn(GameTestHelper h, ServerPlayer dead) {
		if (dead.isAlive()) kill(dead);
		ServerPlayer p = dead.level().getServer().getPlayerList().respawn(dead, false, Entity.RemovalReason.KILLED);
		p.connection.handleAcceptPlayerLoad(new ServerboundPlayerLoadedPacket());
		track(h, p);
		return p;
	}

	/** Kills {@code p} the vanilla way ({@code ServerPlayer.die} runs: drops, death message, Fabric death events). */
	public static void kill(ServerPlayer p) {
		p.kill(p.level());
	}

	/** Disconnects {@code p} the way a lost connection does (Fabric {@code DISCONNECT} fires, the player leaves the list and level). */
	public static void disconnect(ServerPlayer p) {
		if (p.level().getServer().getPlayerList().getPlayer(p.getUUID()) != p) return;
		DisconnectionDetails details = new DisconnectionDetails(Component.literal("MagicTestKit disconnect"));
		// Through Connection.handleDisconnection, as a real lost connection goes: Fabric fires DISCONNECT only there.
		p.connection.disconnect(details);
		if (p.level().getServer().getPlayerList().getPlayer(p.getUUID()) == p) p.connection.onDisconnect(details);
	}

	/** Teleports {@code p} to the bottom centre of {@code rel} in its test (keeps the rotation). */
	public static void teleportTo(ServerPlayer p, BlockPos rel) {
		GameTestHelper h = helperOf(p);
		teleportTo(p, h.getLevel(), h.absoluteVec(Vec3.atBottomCenterOf(rel)));
	}

	/** Teleports {@code p} to absolute {@code abs} in {@code level} (any dimension; keeps the rotation). */
	public static void teleportTo(ServerPlayer p, ServerLevel level, Vec3 abs) {
		p.teleportTo(level, abs.x, abs.y, abs.z, Set.of(), p.getYRot(), p.getXRot(), true);
	}

	/**
	 * A real player for {@code h}: in the player list and the level, with an embedded-channel connection, marked as loaded
	 * (so it can be hurt), survival, inventory cleared (the join starter kit), no respawn point, at {@code rel} facing +Z.
	 * Kit players are disconnected when the test ends. The server never simulates a player's movement, so dashes and
	 * knockback on a player show in {@code getDeltaMovement()}, not in its position.
	 */
	@SuppressWarnings("removal") // the only GameTest helper that makes an in-level player with a connection on 26.3
	private static ServerPlayer mock(GameTestHelper h, BlockPos rel) {
		ServerPlayer p = h.makeMockServerPlayerInLevel();
		p.connection.handleAcceptPlayerLoad(new ServerboundPlayerLoadedPacket());
		p.setGameMode(GameType.SURVIVAL);
		p.getInventory().clearContent();
		p.setRespawnPosition(null, false);
		Vec3 at = h.absoluteVec(Vec3.atBottomCenterOf(rel));
		float yaw = h.getTestRotation().rotate(Direction.SOUTH).toYRot();
		p.teleportTo(h.getLevel(), at.x, at.y, at.z, Set.of(), yaw, 0, true);
		face(p, yaw, 0);
		track(h, p);
		return p;
	}

	private static void track(GameTestHelper h, ServerPlayer p) {
		own(h, p);
		TestState s = state(h);
		s.players.add(p.getId());
		s.uuids.add(p.getUUID());
		s.playerObjects.add(p);
	}

	private static void face(Entity e, float yaw, float pitch) {
		e.setYRot(yaw);
		e.setXRot(pitch);
		e.yRotO = yaw;
		e.xRotO = pitch;
		e.setYHeadRot(yaw);
		if (e instanceof net.minecraft.world.entity.LivingEntity l) l.setYBodyRot(yaw);
	}

	/** Turns {@code p}'s eyes to the centre of block {@code rel} (no packet, safe for mocks). */
	public static void aim(ServerPlayer p, BlockPos rel) {
		Vec3 target = helperOf(p).absoluteVec(Vec3.atCenterOf(rel));
		Vec3 d = target.subtract(p.getEyePosition());
		double horizontal = Math.sqrt(d.x * d.x + d.z * d.z);
		float yaw = (float) (Mth.atan2(d.z, d.x) * Mth.RAD_TO_DEG) - 90f;
		float pitch = (float) -(Mth.atan2(d.y, horizontal) * Mth.RAD_TO_DEG);
		face(p, Mth.wrapDegrees(yaw), pitch);
	}

	// ---- Casting ----

	/** {@code CastService.cast} for the registered spell {@code spell}, or {@code p}'s override of it. */
	public static Result cast(ServerPlayer p, Identifier spell, float charge) {
		Spell s = SpellRegistry.get(p.getUUID(), spell).orElseThrow(() -> new IllegalArgumentException("no spell " + spell));
		return CastService.cast(p, s, charge);
	}

	/** Casts an ultimate, reads the challenge it issued and answers it correctly; returns the answer's result (or the cast's). */
	public static Result castUltimate(ServerPlayer p, Identifier spell) {
		Recorder rec = recorder(helperOf(p));
		int before = rec.challenges.size();
		Result r = cast(p, spell, 1f);
		if (r != Result.CHALLENGED) return r;
		SignChallenge c = rec.lastChallenge(p);
		if (c == null || rec.challenges.size() == before) throw new IllegalStateException("CHALLENGED but no challenge was sent");
		return CastService.answer(p, c.id(), HandSigns.sequence(c.seed(), c.length()));
	}

	/**
	 * Lays {@code s} over the registry until this test ends, for this test's kit players only: every player the kit made for
	 * {@code h}, before or after this call (respawns included). Other tests, and lookups without a player, still see the
	 * registered spells, so a probe may reuse a real spell's id or (element, slot).
	 */
	public static void override(GameTestHelper h, Spell s) {
		TestState st = state(h);
		st.cleanups.add(SpellRegistry.overrideForTesting(s, st.uuids::contains));
	}

	/** As {@link #override}, but for the kit player {@code p} alone (and its respawns); removed when its test ends. */
	public static void overrideFor(ServerPlayer p, Spell s) {
		UUID owner = p.getUUID();
		state(helperOf(p)).cleanups.add(SpellRegistry.overrideForTesting(s, owner::equals));
	}

	/** A test spell at {@code office-test:<path>} that casts {@code ctx.fx()}. Tweak it with {@code with…}, install with {@link #override}. */
	public static Probe probe(String path, @Nullable Element element, int slot) {
		return new Probe(Identifier.fromNamespaceAndPath("office-test", path), element, slot, SpellCost.forSlot(slot), false,
				ctx -> CastOutcome.cast(ctx.fx()));
	}

	public record Probe(Identifier id, @Nullable Element element, int slot, SpellCost cost, boolean managesOwnCooldown,
			Function<CastContext, CastOutcome> body) implements Spell {
		public Probe withCost(SpellCost c) { return new Probe(id, element, slot, c, managesOwnCooldown, body); }

		public Probe withOwnCooldown(boolean own) { return new Probe(id, element, slot, cost, own, body); }

		public Probe withBody(Function<CastContext, CastOutcome> b) { return new Probe(id, element, slot, cost, managesOwnCooldown, b); }

		@Override
		public CastOutcome cast(CastContext ctx) { return body.apply(ctx); }
	}

	// ---- Entities ----

	/** A zombie target: no AI, 20 hp, persistent, a carved pumpkin on its head so daylight never burns it (no armour). */
	public static Zombie dummy(GameTestHelper h, BlockPos rel) {
		Zombie z = h.spawn(EntityTypes.ZOMBIE, rel);
		z.setNoAi(true);
		z.getAttribute(Attributes.MAX_HEALTH).setBaseValue(20);
		z.setHealth(20);
		z.setPersistenceRequired();
		z.setItemSlot(EquipmentSlot.HEAD, new ItemStack(Items.CARVED_PUMPKIN));
		own(h, z);
		return z;
	}

	/**
	 * A zombie target that physics moves: like {@link #dummy} (20 hp, persistent, pumpkin head) but with AI on, so gravity,
	 * knockback, pushes and pulls move it ({@link #dummy} is NoAI, and a NoAI mob never moves, so its knockback reads 0).
	 * Every goal and target goal is removed, loot pickup and reinforcements are off: it stands still, attacks no one and
	 * only moves when something moves it. Use it wherever a test measures movement of a mob.
	 */
	public static Zombie movableDummy(GameTestHelper h, BlockPos rel) {
		Zombie z = h.spawn(EntityTypes.ZOMBIE, rel);
		z.removeAllGoals(g -> true);
		targetSelector(z).removeAllGoals(g -> true);
		z.setTarget(null);
		z.setCanPickUpLoot(false);
		z.getAttribute(Attributes.SPAWN_REINFORCEMENTS_CHANCE).setBaseValue(0);
		z.getAttribute(Attributes.MAX_HEALTH).setBaseValue(20);
		z.setHealth(20);
		z.setPersistenceRequired();
		z.setItemSlot(EquipmentSlot.HEAD, new ItemStack(Items.CARVED_PUMPKIN));
		own(h, z);
		return z;
	}

	private static GoalSelector targetSelector(Mob m) {
		try {
			Field f = Mob.class.getDeclaredField("targetSelector");
			f.setAccessible(true);
			return (GoalSelector) f.get(m);
		} catch (ReflectiveOperationException e) {
			throw new IllegalStateException("Mob.targetSelector not found", e);
		}
	}

	/** A villager (no AI, persistent): spells must spare it. */
	public static Villager villager(GameTestHelper h, BlockPos rel) {
		Villager v = h.spawn(EntityTypes.VILLAGER, rel);
		v.setNoAi(true);
		v.setPersistenceRequired();
		own(h, v);
		return v;
	}

	/** A wolf tamed by {@code owner} (no AI, persistent): spells must spare pets. */
	public static Wolf tamedWolf(GameTestHelper h, BlockPos rel, ServerPlayer owner) {
		Wolf w = h.spawn(EntityTypes.WOLF, rel);
		w.setTame(true, true);
		w.setOwner(owner);
		w.setNoAi(true);
		w.setPersistenceRequired();
		own(h, w);
		return w;
	}

	/** A cobblestone item entity (push spells move items). */
	public static ItemEntity item(GameTestHelper h, BlockPos rel) {
		ItemEntity i = h.spawnItem(Items.COBBLESTONE, rel);
		own(h, i);
		return i;
	}

	/** An arrow at the centre of {@code rel} flying with {@code velocity} (blocks/tick; no owner, not pick-up-able). */
	public static Arrow arrow(GameTestHelper h, BlockPos rel, Vec3 velocity) {
		Vec3 at = h.absoluteVec(Vec3.atCenterOf(rel));
		Arrow a = new Arrow(h.getLevel(), at.x, at.y, at.z, new ItemStack(Items.ARROW), null);
		a.pickup = AbstractArrow.Pickup.DISALLOWED;
		a.setDeltaMovement(velocity);
		h.getLevel().addFreshEntity(a);
		own(h, a);
		return a;
	}

	// ---- Blocks ----

	/** Fills the box {@code fromRel}..{@code toRel} (inclusive) with {@code b}. */
	public static void fill(GameTestHelper h, BlockPos fromRel, BlockPos toRel, Block b) {
		for (BlockPos p : BlockPos.betweenClosed(fromRel, toRel)) h.setBlock(p, b);
	}

	/** A chest (a block entity: terrain edits must refuse it). */
	public static void chest(GameTestHelper h, BlockPos rel) {
		h.setBlock(rel, Blocks.CHEST);
	}

	/** A hologram emitter owned by {@code owner} at {@code rel}, placed the normal way (the block below must be solid). */
	public static HologramBlockEntity emitter(GameTestHelper h, BlockPos rel, ServerPlayer owner) {
		ItemStack held = owner.getMainHandItem();
		owner.setItemInHand(InteractionHand.MAIN_HAND, new ItemStack(OfficeBlocks.HOLOGRAM_EMITTER_ITEM));
		h.placeAt(owner, owner.getMainHandItem(), rel.below(), Direction.UP);
		owner.setItemInHand(InteractionHand.MAIN_HAND, held);
		h.assertBlockPresent(OfficeBlocks.HOLOGRAM_EMITTER, rel);
		return h.getBlockEntity(rel, HologramBlockEntity.class);
	}

	/** A hash of every block state in the arena (equal hashes = no block changed). */
	public static long blockHash(GameTestHelper h) {
		long hash = 0xcbf29ce484222325L;
		for (int y = 0; y < SIZE_Y; y++) {
			for (int z = 0; z < SIZE_Z; z++) {
				for (int x = 0; x < SIZE_X; x++) {
					hash = (hash ^ Block.getId(h.getBlockState(new BlockPos(x, y, z)))) * 0x100000001b3L;
				}
			}
		}
		return hash;
	}

	/** Blocks of {@code b} in the arena. */
	public static int count(GameTestHelper h, Block b) {
		int n = 0;
		for (int y = 0; y < SIZE_Y; y++) {
			for (int z = 0; z < SIZE_Z; z++) {
				for (int x = 0; x < SIZE_X; x++) if (h.getBlockState(new BlockPos(x, y, z)).is(b)) n++;
			}
		}
		return n;
	}

	/** Water in the arena: sources, flows and waterlogged blocks. */
	public static int waterCount(GameTestHelper h) {
		int n = 0;
		for (int y = 0; y < SIZE_Y; y++) {
			for (int z = 0; z < SIZE_Z; z++) {
				for (int x = 0; x < SIZE_X; x++) {
					BlockState s = h.getBlockState(new BlockPos(x, y, z));
					if (s.getFluidState().getType().isSame(Fluids.WATER)) n++;
				}
			}
		}
		return n;
	}

	/** Distance from {@code a} (feet) to the test-relative point {@code relPoint}. */
	public static double dist(GameTestHelper h, Entity a, Vec3 relPoint) {
		return a.position().distanceTo(h.absoluteVec(relPoint));
	}

	/** As {@link #dist(GameTestHelper, Entity, Vec3)} for an entity the kit made. */
	public static double dist(Entity a, Vec3 relPoint) {
		return dist(helperOf(a), a, relPoint);
	}

	// ---- Recorder ----

	/** This test's recorder (the same one on every call): installs the recording sender until the test ends. */
	public static Recorder recorder(GameTestHelper h) {
		TestState s = state(h);
		if (s.recorder == null) {
			s.recorder = new Recorder();
			MagicServer.setSenderForTesting(ROUTER);
		}
		return s.recorder;
	}

	/** What the server would have sent during this test. */
	public static final class Recorder {
		public record Sent<T>(ServerPlayer player, T value) {}

		public record Title(ServerPlayer player, Component title, Component subtitle) {}

		private final List<FxEvent> fx = new ArrayList<>();
		private final List<Sent<SignChallenge>> challenges = new ArrayList<>();
		private final List<Sent<Component>> messages = new ArrayList<>();
		private final List<Title> titles = new ArrayList<>();
		private final List<Integer> cancels = new ArrayList<>();
		private final List<Sent<OpenGrimoire.Mode>> opens = new ArrayList<>();
		private final List<Sent<Component>> chats = new ArrayList<>();
		private final List<Sent<TravelRoster>> rosters = new ArrayList<>();

		private Recorder() {}

		public List<FxEvent> fx() { return List.copyOf(fx); }

		public List<FxEvent> fx(Entity caster) {
			return fx.stream().filter(e -> e.casterId() == caster.getId()).toList();
		}

		public List<SignChallenge> challenges() { return challenges.stream().map(Sent::value).toList(); }

		public @Nullable SignChallenge lastChallenge(ServerPlayer p) {
			for (int i = challenges.size() - 1; i >= 0; i--) if (challenges.get(i).player() == p) return challenges.get(i).value();
			return null;
		}

		/** Action-bar messages. */
		public List<Component> messages() { return messages.stream().map(Sent::value).toList(); }

		/** Translation keys of the action-bar messages (literal ones as their text). */
		public List<String> messageKeys() { return messages.stream().map(m -> key(m.value())).toList(); }

		public List<Title> titles() { return List.copyOf(titles); }

		public List<String> titleKeys() { return titles.stream().map(t -> key(t.title())).toList(); }

		/** Entity ids of casters whose fx were cancelled. */
		public List<Integer> cancels() { return List.copyOf(cancels); }

		public List<OpenGrimoire.Mode> opens() { return opens.stream().map(Sent::value).toList(); }

		/** Chat lines sent to {@code p} (travel requests and their outcomes). */
		public List<Component> chats(ServerPlayer p) { return chats.stream().filter(c -> c.player() == p).map(Sent::value).toList(); }

		/** Translation keys of the chat lines sent to {@code p}. */
		public List<String> chatKeys(ServerPlayer p) { return chats(p).stream().map(Recorder::key).toList(); }

		/** Translation keys of the action-bar messages sent to {@code p}. */
		public List<String> messageKeys(ServerPlayer p) {
			return messages.stream().filter(m -> m.player() == p).map(m -> key(m.value())).toList();
		}

		/** Rosters sent to {@code p}. */
		public List<TravelRoster> rosters(ServerPlayer p) { return rosters.stream().filter(r -> r.player() == p).map(Sent::value).toList(); }

		public void clear() {
			fx.clear();
			challenges.clear();
			messages.clear();
			titles.clear();
			cancels.clear();
			opens.clear();
			chats.clear();
			rosters.clear();
		}

		private static String key(Component c) {
			return c.getContents() instanceof TranslatableContents t ? t.getKey() : c.getString();
		}
	}

	/** The one installed sender: hands each event to the recorder of the test it belongs to, else to the network. */
	private static final class Router implements MagicSender {
		private static @Nullable Recorder route(int entityId, Vec3 abs) {
			for (TestState s : TESTS.values()) if (s.recorder != null && s.players.contains(entityId)) return s.recorder;
			for (TestState s : TESTS.values()) if (s.recorder != null && s.helper.getBounds().contains(abs)) return s.recorder;
			return null;
		}

		private static @Nullable Recorder route(ServerPlayer p) {
			return route(p.getId(), p.position());
		}

		@Override
		public void fx(ServerLevel level, FxEvent e, double range) {
			Recorder r = route(e.casterId(), e.origin());
			if (r != null) r.fx.add(e);
			else MagicServer.network().fx(level, e, range);
		}

		@Override
		public void cancelFx(ServerPlayer caster) {
			Recorder r = route(caster);
			if (r != null) r.cancels.add(caster.getId());
			else MagicServer.network().cancelFx(caster);
		}

		@Override
		public void challenge(ServerPlayer p, SignChallenge c) {
			Recorder r = route(p);
			if (r != null) r.challenges.add(new Recorder.Sent<>(p, c));
			else MagicServer.network().challenge(p, c);
		}

		@Override
		public void open(ServerPlayer p, OpenGrimoire.Mode m) {
			Recorder r = route(p);
			if (r != null) r.opens.add(new Recorder.Sent<>(p, m));
			else MagicServer.network().open(p, m);
		}

		@Override
		public void actionBar(ServerPlayer p, Component c) {
			Recorder r = route(p);
			if (r != null) r.messages.add(new Recorder.Sent<>(p, c));
			else MagicServer.network().actionBar(p, c);
		}

		@Override
		public void title(ServerPlayer p, Component title, Component subtitle) {
			Recorder r = route(p);
			if (r != null) r.titles.add(new Recorder.Title(p, title, subtitle));
			else MagicServer.network().title(p, title, subtitle);
		}

		@Override
		public void chat(ServerPlayer p, Component c) {
			Recorder r = route(p);
			if (r != null) r.chats.add(new Recorder.Sent<>(p, c));
			else MagicServer.network().chat(p, c);
		}

		@Override
		public void roster(ServerPlayer p, TravelRoster roster) {
			Recorder r = route(p);
			if (r != null) r.rosters.add(new Recorder.Sent<>(p, roster));
			else MagicServer.network().roster(p, roster);
		}
	}

	// ---- Per-test state ----

	private static final class TestState {
		final GameTestHelper helper;
		final Set<Integer> players = new HashSet<>();
		/** Kit players' UUIDs: the owners of this test's overrides. */
		final Set<UUID> uuids = ConcurrentHashMap.newKeySet();
		final List<ServerPlayer> playerObjects = new ArrayList<>();
		final List<AutoCloseable> cleanups = new ArrayList<>();
		@Nullable Recorder recorder;

		TestState(GameTestHelper helper) {
			this.helper = helper;
		}

		/** Runs every cleanup and disconnect even if one throws, always drops this test's state, then rethrows the first failure. */
		void finish() {
			@Nullable IllegalStateException failure = null;
			try {
				for (AutoCloseable c : cleanups) {
					try {
						c.close();
					} catch (Exception e) {
						failure = collect(failure, e);
					}
				}
				cleanups.clear();
				for (ServerPlayer p : playerObjects) {
					try {
						disconnect(p);
					} catch (RuntimeException e) {
						failure = collect(failure, e);
					}
				}
				playerObjects.clear();
			} finally {
				TESTS.remove(helper);
				boolean anyRecorder = TESTS.values().stream().anyMatch(s -> s.recorder != null);
				if (!anyRecorder) MagicServer.setSenderForTesting(null);
			}
			if (failure != null) throw failure;
		}

		private static IllegalStateException collect(@Nullable IllegalStateException first, Exception e) {
			if (first == null) return new IllegalStateException("MagicTestKit cleanup failed", e);
			first.addSuppressed(e);
			return first;
		}
	}

	private static TestState state(GameTestHelper h) {
		TestState s = TESTS.get(h);
		if (s == null) {
			s = new TestState(h);
			TESTS.put(h, s);
			TestState created = s;
			info(h).addListener(new GameTestListener() {
				@Override public void testStructureLoaded(GameTestInfo info) {}

				@Override public void testPassed(GameTestInfo info, GameTestRunner runner) { created.finish(); }

				@Override public void testFailed(GameTestInfo info, GameTestRunner runner) { created.finish(); }

				@Override public void testAddedForRerun(GameTestInfo original, GameTestInfo rerun, GameTestRunner runner) {}
			});
		}
		return s;
	}

	private static GameTestInfo info(GameTestHelper h) {
		try {
			Field f = GameTestHelper.class.getDeclaredField("testInfo");
			f.setAccessible(true);
			return (GameTestInfo) f.get(h);
		} catch (ReflectiveOperationException e) {
			throw new IllegalStateException("GameTestHelper.testInfo not found", e);
		}
	}

	private static void own(GameTestHelper h, Entity e) {
		OWNERS.put(e, h);
	}

	private static GameTestHelper helperOf(Entity e) {
		GameTestHelper h = OWNERS.get(e);
		if (h == null) throw new IllegalArgumentException(e + " was not made by MagicTestKit");
		return h;
	}
}
