package local.carter.office.magic;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import local.carter.office.magic.cast.CastService.Result;
import local.carter.office.magic.core.Element;
import local.carter.office.magic.core.Slots;
import local.carter.office.magic.data.MagicAttachments;
import local.carter.office.magic.net.FxEvent;
import local.carter.office.magic.net.MagicServer;
import local.carter.office.magic.spell.Spell;
import local.carter.office.magic.spell.SpellIds;
import local.carter.office.magic.spell.SpellRegistry;
import local.carter.office.magic.spell.TargetFilter;
import local.carter.office.magic.spell.Targets;
import local.carter.office.magic.spell.earth.EarthSpikes;
import local.carter.office.magic.spell.earth.Quake;
import local.carter.office.magic.spell.water.WaterPrison;
import local.carter.office.magic.terrain.EditLog.LoggedCast;
import local.carter.office.magic.terrain.EditLog.LoggedEdit;
import local.carter.office.magic.terrain.EditTier;
import local.carter.office.magic.terrain.WorldTerrainEdits;
import local.carter.office.magic.tree.SpellRank;

import net.minecraft.core.BlockPos;
import net.minecraft.core.GlobalPos;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.boss.wither.WitherBoss;
import net.minecraft.world.entity.monster.zombie.Zombie;
import net.minecraft.world.phys.Vec3;

import net.fabricmc.fabric.api.entity.event.v1.ServerLivingEntityEvents;
import net.fabricmc.fabric.api.gametest.v1.GameTest;

/**
 * The balance pass (Task 24): every spell casts once with one fx, small spells never change a block, no cast exceeds its
 * budgets, the five small bolts kill a zombie in a sane number of casts, and bosses are never pulled, lifted or chained onto.
 *
 * <p><b>Per-spell checks.</b> {@code everySpellCastsOnceWithOneFx}, {@code smallSpellsNeverEditBlocks} and
 * {@code noSpellExceedsBudgets} are one GameTest per spell ({@code balance<Spell>}, 31 of them), each in a fresh arena: the
 * spell is cast once at level 10 and full charge (ultimates through their hand signs), then left to run out. Each checks that
 * the result is CAST and exactly one fx was sent for the whole cast; for slots 0 and 2 that the block hash is unchanged and
 * nothing was logged; and for every spell that the whole cast hurt at most {@link Targets#MAX_ENTITIES} different entities
 * (area spells cast into a crowd of {@link #CROWD} zombies, so the cap is really tested), that its logged edits stay within
 * its tier's budget ({@link EditTier}, plus the landings of its launched blocks) and that it launched at most
 * {@link WorldTerrainEdits#MAX_LAUNCH} blocks (mediums none). {@link #everySpellHasABalanceTest} keeps the 31 in step with the
 * registry.
 *
 * <p>The entity cap is per cast, summed over a lingering effect's whole life and every blast of a volley
 * ({@code HitBudget}), not just per query.
 *
 * <p>Small-spell tests run in {@link #ENV}, terrain spells in {@link #TERRAIN}, each in a batch of its own: ultimates throw
 * debris that may land in a neighbouring arena, which must never be a small spell's.
 *
 * <p><b>Ranked.</b> {@code ranked<Spell>} (30 of them, {@link #everySpellHasARankedTest}) run the same checks for every pair of
 * rank V forks at once: four casters, one per pair (III A or B × V A or B), all at rank V (three bumps), level 10 and full
 * charge, cast together into a crowd of tough zombies ({@link #TOUGH} health, so every caster has something to hit), each held
 * to its own fx, budgets and tier caps, and watched for {@link #SETTLE_RANKED} ticks (the longest ranked effects).
 */
public class BalanceGameTests {
	/** No terrain edits in this batch: small spells (block hashes), time-to-kill and the boss rules. */
	static final String ENV = "office-test:magic_balance";
	/** Mediums and ultimates (their own batch: they queue thousands of block changes and fling debris). */
	static final String TERRAIN = "office-test:magic_balance_terrain";
	static final int LEVEL = 10;
	/** Zombies in a crowd: more than {@link Targets#MAX_ENTITIES}. */
	static final int CROWD = 66;
	/** Ticks from the cast to the checks: every spell (Bubble Shield, 240 at full charge, is the longest) has run out by then. */
	static final int SETTLE = 260;
	/** The ranked checks wait longer: Fortress's bubble lasts 360 ticks, Long Hold's prison 320. */
	static final int SETTLE_RANKED = 400;
	/** Health of the ranked tests' zombies. */
	static final float TOUGH = 1000;
	/** Half-width of the ranked Fireball Barrage's zombie grid: the widest ranked volley's landing disc. */
	static final int SPREAD_GRID = 8;


	private static final Logger LOGGER = LoggerFactory.getLogger("office");

	// ---- Hurt bookkeeping: caster → game tick → entities hurt that tick ----

	private static final Map<UUID, Map<Long, Set<Integer>>> HURT = new HashMap<>();

	static {
		ServerLivingEntityEvents.ALLOW_DAMAGE.register((entity, source, amount) -> {
			Entity by = source.getEntity();
			if (by != null) {
				Map<Long, Set<Integer>> byTick = HURT.get(by.getUUID());
				if (byTick != null) byTick.computeIfAbsent(entity.level().getGameTime(), t -> new HashSet<>()).add(entity.getId());
			}
			return true;
		});
	}

	private static void watch(ServerPlayer p) {
		HURT.put(p.getUUID(), new TreeMap<>());
	}

	/** {max hurt in one tick, different entities hurt in all}; stops watching. */
	private static int[] hurt(ServerPlayer p) {
		Map<Long, Set<Integer>> byTick = HURT.remove(p.getUUID());
		int max = 0;
		Set<Integer> all = new HashSet<>();
		if (byTick != null) {
			for (Set<Integer> s : byTick.values()) {
				max = Math.max(max, s.size());
				all.addAll(s);
			}
		}
		return new int[] {max, all.size()};
	}

	// ---- The cases ----

	/**
	 * How a spell is tried: aimed at {@code aim}, with a crowd of {@link #CROWD} zombies at {@code at} (or one zombie there when
	 * {@code crowd} is false and {@code at} is not null), and whether it should hurt something there ({@code hurts}).
	 */
	record Case(Identifier id, BlockPos aim, BlockPos at, boolean crowd, boolean hurts) {}

	private static final Map<Identifier, Case> CASES = new LinkedHashMap<>();

	private static void add(Identifier id, BlockPos aim, BlockPos at, boolean crowd, boolean hurts) {
		CASES.put(id, new Case(id, aim, at, crowd, hurts));
	}

	private static BlockPos p(int x, int y, int z) {
		return new BlockPos(x, y, z);
	}

	static {
		// Fire
		add(SpellIds.FIRE_BOLT, p(16, 5, 10), p(16, 4, 10), false, true);
		add(SpellIds.FIRE_WALL, p(16, 3, 10), p(16, 4, 9), true, true);
		add(SpellIds.FLAME_DASH, p(16, 5, 12), null, false, false);
		add(SpellIds.FIREBALL_BARRAGE, p(16, 3, 14), p(16, 4, 14), true, true);
		add(SpellIds.METEOR, p(16, 3, 14), p(16, 4, 14), true, true);
		// Water
		add(SpellIds.WATER_BULLET, p(16, 5, 10), p(16, 4, 10), false, true);
		add(SpellIds.TIDAL_PUSH, p(16, 4, 9), p(16, 4, 9), true, false);
		add(SpellIds.BUBBLE_SHIELD, p(16, 5, 12), null, false, false);
		add(SpellIds.WATER_PRISON, p(16, 5, 10), p(16, 4, 10), true, false);
		add(SpellIds.WATER_DRAGON, p(16, 3, 14), p(16, 4, 14), true, true);
		// Wind
		add(SpellIds.GUST, p(16, 5, 8), p(16, 4, 8), true, true);
		add(SpellIds.BLADE_WIND, p(16, 5, 10), p(16, 4, 10), true, true);
		add(SpellIds.AIR_STEP, p(16, 5, 12), null, false, false);
		add(SpellIds.TORNADO, p(16, 3, 10), p(16, 4, 11), true, true);
		add(SpellIds.WIND_SPHERE, p(16, 5, 12), p(16, 4, 12), true, true);
		// Lightning
		add(SpellIds.SPARK, p(16, 5, 10), p(16, 4, 10), false, true);
		add(SpellIds.CHAIN_LIGHTNING, p(16, 5, 10), p(16, 4, 10), true, true);
		add(SpellIds.BLINK, p(16, 5, 20), null, false, false);
		add(SpellIds.THUNDER_SPEAR, p(16, 5, 12), p(16, 4, 12), true, true);
		add(SpellIds.CHIDORI_STRIKE, p(16, 5, 12), p(16, 4, 12), true, true);
		// Earth
		add(SpellIds.STONE_BULLET, p(16, 5, 10), p(16, 4, 10), false, true);
		add(SpellIds.EARTH_WALL, p(16, 5, 10), null, false, false);
		add(SpellIds.EARTH_SPIKES, p(16, 5, 10), p(16, 4, 9), true, true);
		add(SpellIds.QUAKE, p(16, 5, 10), p(16, 4, 9), true, true);
		add(SpellIds.MOUNTAIN_FIST, p(16, 3, 16), p(16, 4, 16), true, true);
		// Shadow
		add(SpellIds.SHADOW_BOLT, p(16, 5, 10), p(16, 4, 10), false, true);
		add(SpellIds.GRAVITY_WELL, p(16, 3, 12), p(16, 4, 12), true, true);
		add(SpellIds.SHADOW_STEP, p(16, 5, 10), p(16, 4, 10), false, false);
		add(SpellIds.SHADOW_BIND, p(16, 5, 10), p(16, 4, 10), true, true);
		add(SpellIds.BLACK_HOLE, p(16, 3, 16), p(16, 4, 16), true, true);
	}

	/**
	 * {@link #CROWD} zombies in the 3 × 3 cells round {@code at}: NoAI ones, or (for Quake, which strikes only the grounded: a NoAI
	 * mob never lands) ones physics moves.
	 */
	private static void crowd(GameTestHelper h, BlockPos at, boolean movable) {
		for (int i = 0; i < CROWD; i++) {
			BlockPos cell = at.offset(i % 3 - 1, 0, i / 3 % 3 - 1);
			if (movable) MagicTestKit.movableDummy(h, cell);
			else MagicTestKit.dummy(h, cell);
		}
	}

	/** The one check every per-spell test runs (see the class comment). */
	private static void check(GameTestHelper h, Identifier id) {
		Case c = CASES.get(id);
		Spell spell = SpellRegistry.get(id).orElseThrow(() -> new IllegalStateException("not registered: " + id));
		int slot = spell.slot();
		boolean small = slot == Slots.SMALL_A || slot == Slots.SMALL_B;
		boolean ultimate = slot == Slots.ULTIMATE;
		ServerPlayer p = MagicTestKit.caster(h, spell.element(), LEVEL);
		MagicTestKit.Recorder rec = MagicTestKit.recorder(h);
		if (c.at() != null) {
			if (c.crowd()) crowd(h, c.at(), id.equals(SpellIds.QUAKE));
			else MagicTestKit.dummy(h, c.at());
		}
		MagicTestKit.aim(p, c.aim());
		long hash = MagicTestKit.blockHash(h);
		watch(p);
		Result r = ultimate ? MagicTestKit.castUltimate(p, id) : MagicTestKit.cast(p, id, 1f);
		h.assertValueEqual(r, Result.CAST, id + ": result");
		h.assertValueEqual(rec.fx(p).size(), 1, id + ": fx at the cast");
		h.runAtTickTime(SETTLE, () -> {
			List<FxEvent> fx = rec.fx(p);
			h.assertValueEqual(fx.size(), 1, id + ": one fx for the whole cast");
			h.assertTrue(fx.getFirst().spell().equals(id), id + ": the fx is the spell's own, got " + fx.getFirst().spell());
			int[] hurt = hurt(p);
			h.assertTrue(hurt[0] <= Targets.MAX_ENTITIES, id + ": hurt " + hurt[0] + " entities in one tick (cap " + Targets.MAX_ENTITIES + ")");
			h.assertTrue(hurt[1] <= Targets.MAX_ENTITIES, id + ": hurt " + hurt[1] + " different entities in the cast (cap " + Targets.MAX_ENTITIES + ")");
			if (c.hurts()) h.assertTrue(hurt[1] > 0, id + ": positive control, it hurt nothing");
			List<LoggedCast> logged = log().casts(p.getUUID());
			int edits = 0, launched = 0;
			for (LoggedCast lc : logged) {
				h.assertTrue(lc.spell().equals(id), id + ": logged as " + lc.spell());
				edits += lc.edits().size();
				for (LoggedEdit e : lc.edits()) if (e.launched()) launched++;
			}
			h.assertTrue(logged.size() <= 1, id + ": one logged cast at most, got " + logged.size());
			if (small) {
				h.assertValueEqual(MagicTestKit.blockHash(h), hash, id + ": a small spell changed a block");
				h.assertValueEqual(edits, 0, id + ": logged edits of a small spell");
			} else if (ultimate) {
				h.assertTrue(launched <= WorldTerrainEdits.MAX_LAUNCH, id + ": launched " + launched);
				// Each launched block may add its landing to the log (landings are outside the budget).
				h.assertTrue(edits <= EditTier.ULTIMATE.budget() + launched, id + ": " + edits + " edits");
			} else {
				h.assertValueEqual(launched, 0, id + ": a medium launched blocks");
				h.assertTrue(edits <= EditTier.MEDIUM.budget(), id + ": " + edits + " edits");
			}
			LOGGER.info("[balance] {}: hurt {} max a tick, {} in all; {} edits, {} launched", id.getPath(), hurt[0], hurt[1], edits, launched);
			h.succeed();
		});
	}

	/** The rank V fork pairs: III A or B, V A or B. */
	private static final int[][] PAIRS = {{1, 1}, {1, 2}, {2, 1}, {2, 2}};

	/** A zombie of {@link #TOUGH} health (a ranked volley never kills the crowd before every caster has had its go). */
	private static void tough(Zombie z) {
		z.getAttribute(Attributes.MAX_HEALTH).setBaseValue(TOUGH);
		z.setHealth(TOUGH);
	}

	/** {@link #check} for every rank V fork pair at once (see the class comment). */
	private static void checkRanked(GameTestHelper h, Identifier id) {
		Case c = CASES.get(id);
		Spell spell = SpellRegistry.get(id).orElseThrow(() -> new IllegalStateException("not registered: " + id));
		int slot = spell.slot();
		boolean small = slot == Slots.SMALL_A || slot == Slots.SMALL_B;
		boolean ultimate = slot == Slots.ULTIMATE;
		MagicTestKit.Recorder rec = MagicTestKit.recorder(h);
		if (c.at() != null && id.equals(SpellIds.FIREBALL_BARRAGE)) {
			// A ranked volley lands up to spread(n) × Carpet's 1.6 from the aim (8 for 10 fireballs, 8.8 for 11) at seeded spots: a
			// 3 × 3 crowd can be missed by every fireball of one cast (Windows CI run 40116275385, Carpet + Rapid Volley: each of its
			// 9 fireballs misses the crowd's blast reach with p ≈ 0.7, all of them about 3 % of runs). So the crowd covers the whole
			// landing disc, one zombie every 2 blocks over ±8 (81 of them, past the cap): every spot in the disc is within 1.5 of
			// one, inside a fireball's 2.5 blast.
			for (int dx = -SPREAD_GRID; dx <= SPREAD_GRID; dx += 2) {
				for (int dz = -SPREAD_GRID; dz <= SPREAD_GRID; dz += 2) tough(MagicTestKit.dummy(h, c.at().offset(dx, 0, dz)));
			}
		} else if (c.at() != null) {
			boolean movable = id.equals(SpellIds.QUAKE);
			int n = c.crowd() ? CROWD : 1;
			for (int i = 0; i < n; i++) {
				BlockPos cell = c.crowd() ? c.at().offset(i % 3 - 1, 0, i / 3 % 3 - 1) : c.at();
				tough(movable ? MagicTestKit.movableDummy(h, cell) : MagicTestKit.dummy(h, cell));
			}
		}
		long hash = MagicTestKit.blockHash(h);
		List<ServerPlayer> casters = new ArrayList<>();
		for (int[] pair : PAIRS) {
			ServerPlayer p = MagicTestKit.caster(h, spell.element(), LEVEL);
			MagicAttachments.set(p, MagicAttachments.get(p).withRank(new SpellRank(id, SpellRank.MAX, pair[0], pair[1])));
			MagicTestKit.aim(p, c.aim());
			casters.add(p);
		}
		for (ServerPlayer p : casters) {
			watch(p);
			Result r = ultimate ? MagicTestKit.castUltimate(p, id) : MagicTestKit.cast(p, id, 1f);
			String who = id + " " + MagicAttachments.get(p).rank(id);
			h.assertValueEqual(r, Result.CAST, who + ": result");
			h.assertValueEqual(rec.fx(p).size(), 1, who + ": fx at the cast");
		}
		h.runAtTickTime(SETTLE_RANKED, () -> {
			for (ServerPlayer p : casters) {
				String who = id + " " + MagicAttachments.get(p).rank(id);
				List<FxEvent> fx = rec.fx(p);
				h.assertValueEqual(fx.size(), 1, who + ": one fx for the whole cast");
				h.assertTrue(fx.getFirst().spellRank().rank() == SpellRank.MAX, who + ": the fx carries the rank");
				int[] hurt = hurt(p);
				h.assertTrue(hurt[1] <= Targets.MAX_ENTITIES, who + ": hurt " + hurt[1] + " different entities (cap " + Targets.MAX_ENTITIES + ")");
				if (c.hurts()) h.assertTrue(hurt[1] > 0, who + ": positive control, it hurt nothing");
				List<LoggedCast> logged = log().casts(p.getUUID());
				int edits = 0, launched = 0;
				for (LoggedCast lc : logged) {
					edits += lc.edits().size();
					for (LoggedEdit e : lc.edits()) if (e.launched()) launched++;
				}
				h.assertTrue(logged.size() <= 1, who + ": one logged cast at most, got " + logged.size());
				if (small) {
					h.assertValueEqual(edits, 0, who + ": logged edits of a small spell");
				} else if (ultimate) {
					h.assertTrue(launched <= WorldTerrainEdits.MAX_LAUNCH, who + ": launched " + launched);
					h.assertTrue(edits <= EditTier.ULTIMATE.budget() + launched, who + ": " + edits + " edits");
				} else {
					h.assertValueEqual(launched, 0, who + ": a medium launched blocks");
					h.assertTrue(edits <= EditTier.MEDIUM.budget(), who + ": " + edits + " edits");
				}
				LOGGER.info("[balance] {}: {} different hurt; {} edits, {} launched", who, hurt[1], edits, launched);
			}
			if (small) h.assertValueEqual(MagicTestKit.blockHash(h), hash, id + ": a ranked small spell changed a block");
			h.succeed();
		});
	}

	@GameTest(structure = MagicTestKit.ARENA, environment = ENV)
	public void everySpellHasARankedTest(GameTestHelper h) {
		Set<String> methods = new HashSet<>();
		for (Method m : BalanceGameTests.class.getDeclaredMethods()) if (m.isAnnotationPresent(GameTest.class)) methods.add(m.getName());
		for (Identifier id : CASES.keySet()) {
			String name = "ranked" + camel(id.getPath());
			h.assertTrue(methods.contains(name), "no ranked test " + name + " for " + id);
		}
		h.succeed();
	}

	private static local.carter.office.magic.terrain.EditLog log() {
		if (!(MagicServer.kit().terrain() instanceof WorldTerrainEdits w)) throw new IllegalStateException("world terrain kit not installed");
		return w.log();
	}

	// ---- Registry ----

	@GameTest(structure = MagicTestKit.ARENA, environment = ENV)
	public void everyElementHasFiveSpellsAndRecall(GameTestHelper h) {
		for (Element e : Element.values()) {
			Spell[] wheel = SpellRegistry.wheel(e);
			h.assertValueEqual(wheel.length, Slots.WHEEL_SLICES, e + ": wheel size");
			for (int slot = 0; slot < wheel.length; slot++) {
				Spell s = wheel[slot];
				h.assertTrue(s != null, e + ": slot " + slot + " is empty");
				h.assertTrue(s.id().equals(SpellIds.at(e, slot)), e + ": slot " + slot + " holds " + s.id());
				h.assertValueEqual(s.slot(), slot, s.id() + ": slot");
				if (slot == Slots.RECALL) h.assertTrue(s.element() == null, "Recall belongs to every element");
				else h.assertTrue(s.element() == e, s.id() + ": element " + s.element());
			}
		}
		h.assertValueEqual(SpellRegistry.all().size(), 31, "registered spells");
		h.succeed();
	}

	@GameTest(structure = MagicTestKit.ARENA, environment = ENV)
	public void everySpellHasABalanceTest(GameTestHelper h) {
		Set<String> methods = new HashSet<>();
		for (Method m : BalanceGameTests.class.getDeclaredMethods()) if (m.isAnnotationPresent(GameTest.class)) methods.add(m.getName());
		for (Spell s : SpellRegistry.all()) {
			String name = "balance" + camel(s.id().getPath());
			h.assertTrue(methods.contains(name), "no balance test " + name + " for " + s.id());
			h.assertTrue(s.id().equals(SpellIds.RECALL) || CASES.containsKey(s.id()), "no balance case for " + s.id());
		}
		h.assertValueEqual(CASES.size(), 30, "element spell cases");
		h.succeed();
	}

	private static String camel(String path) {
		StringBuilder b = new StringBuilder();
		for (String part : path.split("_")) b.append(Character.toUpperCase(part.charAt(0))).append(part.substring(1));
		return b.toString();
	}

	// ---- Per spell: small spells (no terrain in their batch) ----

	@GameTest(structure = MagicTestKit.ARENA, environment = ENV, maxTicks = SETTLE + 40)
	public void balanceFireBolt(GameTestHelper h) { check(h, SpellIds.FIRE_BOLT); }

	@GameTest(structure = MagicTestKit.ARENA, environment = ENV, maxTicks = SETTLE + 40)
	public void balanceFlameDash(GameTestHelper h) { check(h, SpellIds.FLAME_DASH); }

	@GameTest(structure = MagicTestKit.ARENA, environment = ENV, maxTicks = SETTLE + 40)
	public void balanceWaterBullet(GameTestHelper h) { check(h, SpellIds.WATER_BULLET); }

	@GameTest(structure = MagicTestKit.ARENA, environment = ENV, maxTicks = SETTLE + 40)
	public void balanceBubbleShield(GameTestHelper h) { check(h, SpellIds.BUBBLE_SHIELD); }

	@GameTest(structure = MagicTestKit.ARENA, environment = ENV, maxTicks = SETTLE + 40)
	public void balanceGust(GameTestHelper h) { check(h, SpellIds.GUST); }

	@GameTest(structure = MagicTestKit.ARENA, environment = ENV, maxTicks = SETTLE + 40)
	public void balanceAirStep(GameTestHelper h) { check(h, SpellIds.AIR_STEP); }

	@GameTest(structure = MagicTestKit.ARENA, environment = ENV, maxTicks = SETTLE + 40)
	public void balanceSpark(GameTestHelper h) { check(h, SpellIds.SPARK); }

	@GameTest(structure = MagicTestKit.ARENA, environment = ENV, maxTicks = SETTLE + 40)
	public void balanceBlink(GameTestHelper h) { check(h, SpellIds.BLINK); }

	@GameTest(structure = MagicTestKit.ARENA, environment = ENV, maxTicks = SETTLE + 40)
	public void balanceStoneBullet(GameTestHelper h) { check(h, SpellIds.STONE_BULLET); }

	@GameTest(structure = MagicTestKit.ARENA, environment = ENV, maxTicks = SETTLE + 40)
	public void balanceEarthSpikes(GameTestHelper h) { check(h, SpellIds.EARTH_SPIKES); }

	@GameTest(structure = MagicTestKit.ARENA, environment = ENV, maxTicks = SETTLE + 40)
	public void balanceShadowBolt(GameTestHelper h) { check(h, SpellIds.SHADOW_BOLT); }

	@GameTest(structure = MagicTestKit.ARENA, environment = ENV, maxTicks = SETTLE + 40)
	public void balanceShadowStep(GameTestHelper h) { check(h, SpellIds.SHADOW_STEP); }

	// ---- Per spell: mediums and ultimates ----

	@GameTest(structure = MagicTestKit.ARENA, environment = TERRAIN, maxTicks = SETTLE + 40)
	public void balanceFireWall(GameTestHelper h) { check(h, SpellIds.FIRE_WALL); }

	@GameTest(structure = MagicTestKit.ARENA, environment = TERRAIN, maxTicks = SETTLE + 40)
	public void balanceFireballBarrage(GameTestHelper h) { check(h, SpellIds.FIREBALL_BARRAGE); }

	@GameTest(structure = MagicTestKit.ARENA, environment = TERRAIN, maxTicks = SETTLE + 40)
	public void balanceMeteor(GameTestHelper h) { check(h, SpellIds.METEOR); }

	@GameTest(structure = MagicTestKit.ARENA, environment = TERRAIN, maxTicks = SETTLE + 40)
	public void balanceTidalPush(GameTestHelper h) { check(h, SpellIds.TIDAL_PUSH); }

	@GameTest(structure = MagicTestKit.ARENA, environment = TERRAIN, maxTicks = SETTLE + 40)
	public void balanceWaterPrison(GameTestHelper h) { check(h, SpellIds.WATER_PRISON); }

	@GameTest(structure = MagicTestKit.ARENA, environment = TERRAIN, maxTicks = SETTLE + 40)
	public void balanceWaterDragon(GameTestHelper h) { check(h, SpellIds.WATER_DRAGON); }

	@GameTest(structure = MagicTestKit.ARENA, environment = TERRAIN, maxTicks = SETTLE + 40)
	public void balanceBladeWind(GameTestHelper h) { check(h, SpellIds.BLADE_WIND); }

	@GameTest(structure = MagicTestKit.ARENA, environment = TERRAIN, maxTicks = SETTLE + 40)
	public void balanceTornado(GameTestHelper h) { check(h, SpellIds.TORNADO); }

	@GameTest(structure = MagicTestKit.ARENA, environment = TERRAIN, maxTicks = SETTLE + 40)
	public void balanceWindSphere(GameTestHelper h) { check(h, SpellIds.WIND_SPHERE); }

	@GameTest(structure = MagicTestKit.ARENA, environment = TERRAIN, maxTicks = SETTLE + 40)
	public void balanceChainLightning(GameTestHelper h) { check(h, SpellIds.CHAIN_LIGHTNING); }

	@GameTest(structure = MagicTestKit.ARENA, environment = TERRAIN, maxTicks = SETTLE + 40)
	public void balanceThunderSpear(GameTestHelper h) { check(h, SpellIds.THUNDER_SPEAR); }

	@GameTest(structure = MagicTestKit.ARENA, environment = TERRAIN, maxTicks = SETTLE + 40)
	public void balanceChidoriStrike(GameTestHelper h) { check(h, SpellIds.CHIDORI_STRIKE); }

	@GameTest(structure = MagicTestKit.ARENA, environment = TERRAIN, maxTicks = SETTLE + 40)
	public void balanceEarthWall(GameTestHelper h) { check(h, SpellIds.EARTH_WALL); }

	@GameTest(structure = MagicTestKit.ARENA, environment = TERRAIN, maxTicks = SETTLE + 40)
	public void balanceQuake(GameTestHelper h) { check(h, SpellIds.QUAKE); }

	@GameTest(structure = MagicTestKit.ARENA, environment = TERRAIN, maxTicks = SETTLE + 40)
	public void balanceMountainFist(GameTestHelper h) { check(h, SpellIds.MOUNTAIN_FIST); }

	@GameTest(structure = MagicTestKit.ARENA, environment = TERRAIN, maxTicks = SETTLE + 40)
	public void balanceGravityWell(GameTestHelper h) { check(h, SpellIds.GRAVITY_WELL); }

	@GameTest(structure = MagicTestKit.ARENA, environment = TERRAIN, maxTicks = SETTLE + 40)
	public void balanceShadowBind(GameTestHelper h) { check(h, SpellIds.SHADOW_BIND); }

	@GameTest(structure = MagicTestKit.ARENA, environment = TERRAIN, maxTicks = SETTLE + 40)
	public void balanceBlackHole(GameTestHelper h) { check(h, SpellIds.BLACK_HOLE); }

	// ---- Per spell, ranked: every rank V fork pair ----

	@GameTest(structure = MagicTestKit.ARENA, environment = ENV, maxTicks = SETTLE_RANKED + 40)
	public void rankedFireBolt(GameTestHelper h) { checkRanked(h, SpellIds.FIRE_BOLT); }

	@GameTest(structure = MagicTestKit.ARENA, environment = ENV, maxTicks = SETTLE_RANKED + 40)
	public void rankedFlameDash(GameTestHelper h) { checkRanked(h, SpellIds.FLAME_DASH); }

	@GameTest(structure = MagicTestKit.ARENA, environment = ENV, maxTicks = SETTLE_RANKED + 40)
	public void rankedWaterBullet(GameTestHelper h) { checkRanked(h, SpellIds.WATER_BULLET); }

	@GameTest(structure = MagicTestKit.ARENA, environment = ENV, maxTicks = SETTLE_RANKED + 40)
	public void rankedBubbleShield(GameTestHelper h) { checkRanked(h, SpellIds.BUBBLE_SHIELD); }

	@GameTest(structure = MagicTestKit.ARENA, environment = ENV, maxTicks = SETTLE_RANKED + 40)
	public void rankedGust(GameTestHelper h) { checkRanked(h, SpellIds.GUST); }

	@GameTest(structure = MagicTestKit.ARENA, environment = ENV, maxTicks = SETTLE_RANKED + 40)
	public void rankedAirStep(GameTestHelper h) { checkRanked(h, SpellIds.AIR_STEP); }

	@GameTest(structure = MagicTestKit.ARENA, environment = ENV, maxTicks = SETTLE_RANKED + 40)
	public void rankedSpark(GameTestHelper h) { checkRanked(h, SpellIds.SPARK); }

	@GameTest(structure = MagicTestKit.ARENA, environment = ENV, maxTicks = SETTLE_RANKED + 40)
	public void rankedBlink(GameTestHelper h) { checkRanked(h, SpellIds.BLINK); }

	@GameTest(structure = MagicTestKit.ARENA, environment = ENV, maxTicks = SETTLE_RANKED + 40)
	public void rankedStoneBullet(GameTestHelper h) { checkRanked(h, SpellIds.STONE_BULLET); }

	@GameTest(structure = MagicTestKit.ARENA, environment = ENV, maxTicks = SETTLE_RANKED + 40)
	public void rankedEarthSpikes(GameTestHelper h) { checkRanked(h, SpellIds.EARTH_SPIKES); }

	@GameTest(structure = MagicTestKit.ARENA, environment = ENV, maxTicks = SETTLE_RANKED + 40)
	public void rankedShadowBolt(GameTestHelper h) { checkRanked(h, SpellIds.SHADOW_BOLT); }

	@GameTest(structure = MagicTestKit.ARENA, environment = ENV, maxTicks = SETTLE_RANKED + 40)
	public void rankedShadowStep(GameTestHelper h) { checkRanked(h, SpellIds.SHADOW_STEP); }

	@GameTest(structure = MagicTestKit.ARENA, environment = TERRAIN, maxTicks = SETTLE_RANKED + 40)
	public void rankedFireWall(GameTestHelper h) { checkRanked(h, SpellIds.FIRE_WALL); }

	@GameTest(structure = MagicTestKit.ARENA, environment = TERRAIN, maxTicks = SETTLE_RANKED + 40)
	public void rankedFireballBarrage(GameTestHelper h) { checkRanked(h, SpellIds.FIREBALL_BARRAGE); }

	@GameTest(structure = MagicTestKit.ARENA, environment = TERRAIN, maxTicks = SETTLE_RANKED + 40)
	public void rankedMeteor(GameTestHelper h) { checkRanked(h, SpellIds.METEOR); }

	@GameTest(structure = MagicTestKit.ARENA, environment = TERRAIN, maxTicks = SETTLE_RANKED + 40)
	public void rankedTidalPush(GameTestHelper h) { checkRanked(h, SpellIds.TIDAL_PUSH); }

	@GameTest(structure = MagicTestKit.ARENA, environment = TERRAIN, maxTicks = SETTLE_RANKED + 40)
	public void rankedWaterPrison(GameTestHelper h) { checkRanked(h, SpellIds.WATER_PRISON); }

	@GameTest(structure = MagicTestKit.ARENA, environment = TERRAIN, maxTicks = SETTLE_RANKED + 40)
	public void rankedWaterDragon(GameTestHelper h) { checkRanked(h, SpellIds.WATER_DRAGON); }

	@GameTest(structure = MagicTestKit.ARENA, environment = TERRAIN, maxTicks = SETTLE_RANKED + 40)
	public void rankedBladeWind(GameTestHelper h) { checkRanked(h, SpellIds.BLADE_WIND); }

	@GameTest(structure = MagicTestKit.ARENA, environment = TERRAIN, maxTicks = SETTLE_RANKED + 40)
	public void rankedTornado(GameTestHelper h) { checkRanked(h, SpellIds.TORNADO); }

	@GameTest(structure = MagicTestKit.ARENA, environment = TERRAIN, maxTicks = SETTLE_RANKED + 40)
	public void rankedWindSphere(GameTestHelper h) { checkRanked(h, SpellIds.WIND_SPHERE); }

	@GameTest(structure = MagicTestKit.ARENA, environment = TERRAIN, maxTicks = SETTLE_RANKED + 40)
	public void rankedChainLightning(GameTestHelper h) { checkRanked(h, SpellIds.CHAIN_LIGHTNING); }

	@GameTest(structure = MagicTestKit.ARENA, environment = TERRAIN, maxTicks = SETTLE_RANKED + 40)
	public void rankedThunderSpear(GameTestHelper h) { checkRanked(h, SpellIds.THUNDER_SPEAR); }

	@GameTest(structure = MagicTestKit.ARENA, environment = TERRAIN, maxTicks = SETTLE_RANKED + 40)
	public void rankedChidoriStrike(GameTestHelper h) { checkRanked(h, SpellIds.CHIDORI_STRIKE); }

	@GameTest(structure = MagicTestKit.ARENA, environment = TERRAIN, maxTicks = SETTLE_RANKED + 40)
	public void rankedEarthWall(GameTestHelper h) { checkRanked(h, SpellIds.EARTH_WALL); }

	@GameTest(structure = MagicTestKit.ARENA, environment = TERRAIN, maxTicks = SETTLE_RANKED + 40)
	public void rankedQuake(GameTestHelper h) { checkRanked(h, SpellIds.QUAKE); }

	@GameTest(structure = MagicTestKit.ARENA, environment = TERRAIN, maxTicks = SETTLE_RANKED + 40)
	public void rankedMountainFist(GameTestHelper h) { checkRanked(h, SpellIds.MOUNTAIN_FIST); }

	@GameTest(structure = MagicTestKit.ARENA, environment = TERRAIN, maxTicks = SETTLE_RANKED + 40)
	public void rankedGravityWell(GameTestHelper h) { checkRanked(h, SpellIds.GRAVITY_WELL); }

	@GameTest(structure = MagicTestKit.ARENA, environment = TERRAIN, maxTicks = SETTLE_RANKED + 40)
	public void rankedShadowBind(GameTestHelper h) { checkRanked(h, SpellIds.SHADOW_BIND); }

	@GameTest(structure = MagicTestKit.ARENA, environment = TERRAIN, maxTicks = SETTLE_RANKED + 40)
	public void rankedBlackHole(GameTestHelper h) { checkRanked(h, SpellIds.BLACK_HOLE); }

	/** Recall: in Recall's environment (it reads a home), cast with a home 24 blocks away; one fx, home reached, no block changed. */
	@GameTest(structure = MagicTestKit.ARENA, environment = RecallGameTests.ENV, maxTicks = 120)
	public void balanceRecall(GameTestHelper h) {
		ServerPlayer p = MagicTestKit.caster(h, Element.FIRE, LEVEL);
		MagicTestKit.Recorder rec = MagicTestKit.recorder(h);
		BlockPos home = h.absolutePos(new BlockPos(16, 4, 28));
		MagicAttachments.set(p, MagicAttachments.get(p).withHome(Optional.of(GlobalPos.of(h.getLevel().dimension(), home))));
		long hash = MagicTestKit.blockHash(h);
		h.assertValueEqual(MagicTestKit.cast(p, SpellIds.RECALL, 0f), Result.CAST, "result");
		h.assertValueEqual(rec.fx(p).size(), 1, "fx at the cast");
		h.runAtTickTime(80, () -> {
			h.assertValueEqual(rec.fx(p).size(), 1, "one fx for the whole channel");
			h.assertTrue(p.position().distanceTo(Vec3.atBottomCenterOf(home)) < 2, "home reached: " + h.relativeVec(p.position()));
			h.assertValueEqual(MagicTestKit.blockHash(h), hash, "Recall changes no block");
			h.succeed();
		});
	}

	// ---- Time to kill ----

	/**
	 * Level 1, charge 0: each small bolt kills a 20-hp zombie in 3–8 casts. The 3-damage spells need 7; 8 allows one miss and
	 * still catches a halving or doubling of damage. Five lanes 6 apart (Spark's arc reaches 4), one cast every 12 ticks
	 * (every slot-0 cooldown is 10).
	 */
	@GameTest(structure = MagicTestKit.ARENA, environment = ENV, maxTicks = 160)
	public void zombieTimeToKillBands(GameTestHelper h) {
		Identifier[] spells = {SpellIds.FIRE_BOLT, SpellIds.WATER_BULLET, SpellIds.SPARK, SpellIds.STONE_BULLET, SpellIds.SHADOW_BOLT};
		ServerPlayer[] casters = new ServerPlayer[spells.length];
		Zombie[] zombies = new Zombie[spells.length];
		int[] casts = new int[spells.length];
		int[] killedAfter = new int[spells.length];
		for (int i = 0; i < spells.length; i++) {
			int x = 4 + 6 * i;
			Spell s = SpellRegistry.get(spells[i]).orElseThrow();
			casters[i] = MagicTestKit.caster(h, s.element(), 1);
			MagicTestKit.teleportTo(casters[i], new BlockPos(x, 4, 4));
			zombies[i] = MagicTestKit.dummy(h, new BlockPos(x, 4, 9));
			MagicTestKit.aim(casters[i], new BlockPos(x, 5, 9));
		}
		h.onEachTick(() -> {
			long t = h.getTick();
			for (int i = 0; i < spells.length; i++) {
				if (killedAfter[i] == 0 && zombies[i].isDeadOrDying()) killedAfter[i] = casts[i];
				if (t % 12 != 0 || killedAfter[i] > 0 || casts[i] >= 9) continue;
				h.assertValueEqual(MagicTestKit.cast(casters[i], spells[i], 0f), Result.CAST, spells[i] + " cast " + (casts[i] + 1));
				casts[i]++;
			}
		});
		h.runAtTickTime(12 * 9 + 20, () -> {
			StringBuilder report = new StringBuilder();
			for (int i = 0; i < spells.length; i++) {
				report.append(String.format(Locale.ROOT, " %s=%d", spells[i].getPath(), killedAfter[i]));
				h.assertTrue(killedAfter[i] >= 3 && killedAfter[i] <= 8,
						spells[i] + ": killed after " + killedAfter[i] + " casts (0 = alive after 9), want 3–8");
			}
			LOGGER.info("[balance] casts to kill a 20-hp zombie at level 1:{}", report);
			h.succeed();
		});
	}

	// ---- Bosses: hurt, never pulled, lifted or chained onto ----

	/** A NoAI wither (a boss by class and by {@code #c:bosses}), persistent. */
	private static WitherBoss wither(GameTestHelper h, BlockPos rel) {
		WitherBoss w = h.spawn(EntityTypes.WITHER, rel);
		w.setNoAi(true);
		w.setPersistenceRequired();
		return w;
	}

	/**
	 * What moved {@code e} each tick from now on, its velocity zeroed after every read (a NoAI mob keeps any velocity it is given):
	 * {top speed on ticks it was not hurt, top level speed on ticks it was, top upward speed on ticks it was, top vertical speed on
	 * ticks it was not}. A spell hit carries vanilla's damage knockback (0.4 level, 0.4 up at most), so a hurt tick may show that
	 * much; anything more, or motion on an unhurt tick, is a pull or a lift.
	 */
	private static double[] motion(GameTestHelper h, LivingEntity e) {
		double[] top = {0, 0, 0, 0};
		float[] hp = {e.getHealth()};
		h.onEachTick(() -> {
			Vec3 v = e.getDeltaMovement();
			boolean hurt = e.getHealth() < hp[0];
			hp[0] = e.getHealth();
			if (hurt) {
				top[1] = Math.max(top[1], v.horizontalDistance());
				top[2] = Math.max(top[2], v.y);
			} else {
				top[0] = Math.max(top[0], v.length());
				top[3] = Math.max(top[3], Math.abs(v.y));
			}
			e.setDeltaMovement(Vec3.ZERO);
		});
		return top;
	}

	/**
	 * {@code e} was never pulled, lifted or thrown (see {@link #motion}): no vertical motion on an unhurt tick, under
	 * {@code jostle} of any motion then (a mob caught beside it may bump it, sideways only), and no more than a hit's knockback
	 * (plus that jostle) on a hurt tick.
	 */
	private static void assertUnmoved(GameTestHelper h, double[] m, double jostle, String what) {
		h.assertTrue(m[3] < 0.01, what + ": lifted or dropped on a tick it was not hurt, top vertical speed " + m[3]);
		h.assertTrue(m[0] < jostle, what + ": moved on a tick it was not hurt, top speed " + m[0]);
		h.assertTrue(m[1] <= 0.4 + jostle + 1.0E-3, what + ": thrown sideways past a hit's knockback, " + m[1]);
		h.assertTrue(m[2] <= 0.4 + 1.0E-3, what + ": lifted past a hit's knockback, " + m[2]);
	}

	@GameTest(structure = MagicTestKit.ARENA, environment = ENV)
	public void bossesAreNeverControlled(GameTestHelper h) {
		ServerPlayer p = MagicTestKit.caster(h, Element.EARTH, LEVEL);
		var level = h.getLevel();
		List<LivingEntity> bosses = List.of(EntityTypes.WITHER.create(level, EntitySpawnReason.COMMAND),
				EntityTypes.ENDER_DRAGON.create(level, EntitySpawnReason.COMMAND), EntityTypes.WARDEN.create(level, EntitySpawnReason.COMMAND));
		for (LivingEntity b : bosses) {
			h.assertTrue(TargetFilter.isBoss(b), b.getType() + " is a boss");
			h.assertTrue(TargetFilter.affects(p, b), b.getType() + ": spells still hurt it");
			h.assertFalse(TargetFilter.controllable(p, b), b.getType() + ": never pulled, lifted or chained");
			h.assertFalse(Quake.pushes(p, b), b.getType() + ": Quake never throws it");
			h.assertFalse(EarthSpikes.liftable(p, b), b.getType() + ": Earth Spikes never lift it");
			h.assertFalse(WaterPrison.takes(p, b), b.getType() + ": Water Prison never holds or pulls it");
		}
		Zombie z = EntityTypes.ZOMBIE.create(level, EntitySpawnReason.COMMAND);
		h.assertFalse(TargetFilter.isBoss(z), "positive control: a zombie is no boss");
		h.assertTrue(TargetFilter.controllable(p, z) && Quake.pushes(p, z) && EarthSpikes.liftable(p, z), "positive control: a zombie is controlled");
		h.assertTrue(WaterPrison.takes(p, z), "positive control: Water Prison takes a zombie");
		h.succeed();
	}

	@GameTest(structure = MagicTestKit.ARENA, environment = ENV, maxTicks = 60)
	public void gravityWellHurtsButNeverPullsABoss(GameTestHelper h) {
		ServerPlayer p = MagicTestKit.caster(h, Element.SHADOW, 2);
		MagicTestKit.Recorder rec = MagicTestKit.recorder(h);
		MagicTestKit.aim(p, new BlockPos(16, 3, 12));
		h.assertValueEqual(MagicTestKit.cast(p, SpellIds.GRAVITY_WELL, 0f), Result.CAST, "result");
		Vec3 c = h.relativeVec(rec.fx(p).getFirst().target());
		WitherBoss w = wither(h, BlockPos.containing(c.x + 1, 4, c.z));
		Zombie z = MagicTestKit.movableDummy(h, BlockPos.containing(c.x - 5, 4, c.z));
		Vec3 w0 = w.position();
		double z0 = MagicTestKit.dist(z, c);
		double[] speed = motion(h, w);
		float hp = w.getHealth();
		h.runAfterDelay(45, () -> {
			h.assertTrue(MagicTestKit.dist(z, c) < z0 - 2, "positive control: the zombie is dragged in " + MagicTestKit.dist(z, c));
			assertUnmoved(h, speed, 0.01, "the wither is never pulled");
			h.assertTrue(w.position().distanceTo(w0) < 0.01, "the wither stays put");
			h.assertTrue(w.getHealth() < hp, "the core still hurts it: " + w.getHealth());
			h.succeed();
		});
	}

	@GameTest(structure = MagicTestKit.ARENA, environment = ENV, maxTicks = 60)
	public void tornadoHurtsButNeverLiftsABoss(GameTestHelper h) {
		ServerPlayer p = MagicTestKit.caster(h, Element.WIND, 5);
		MagicTestKit.aim(p, new BlockPos(16, 3, 12));
		WitherBoss w = wither(h, new BlockPos(17, 4, 12));
		Zombie z = MagicTestKit.movableDummy(h, new BlockPos(15, 4, 12));
		double zy = z.getY();
		double[] zTop = {zy};
		h.onEachTick(() -> zTop[0] = Math.max(zTop[0], z.getY()));
		Vec3 w0 = w.position();
		double[] speed = motion(h, w);
		float hp = w.getHealth();
		h.assertValueEqual(MagicTestKit.cast(p, SpellIds.TORNADO, 0f), Result.CAST, "result");
		h.runAfterDelay(40, () -> {
			h.assertTrue(zTop[0] - zy >= 1, "positive control: the zombie is lifted " + (zTop[0] - zy));
			// The lifted zombie whirls round it and may bump it (sideways, under 0.1); the tornado's own push is 0.35 round and 0.25 up.
			assertUnmoved(h, speed, 0.15, "the wither is never lifted");
			h.assertTrue(w.position().distanceTo(w0) < 0.01, "the wither stays put");
			h.assertTrue(w.getHealth() < hp, "the wind still hurts it: " + w.getHealth());
			h.succeed();
		});
	}

	@GameTest(structure = MagicTestKit.ARENA, environment = ENV, maxTicks = 40)
	public void earthSpikesHurtButNeverLiftABoss(GameTestHelper h) {
		ServerPlayer p = MagicTestKit.caster(h, Element.EARTH, 3);
		WitherBoss w = wither(h, new BlockPos(16, 4, 9));
		Zombie z = MagicTestKit.movableDummy(h, new BlockPos(16, 4, 7));
		double zy = z.getY();
		double[] zTop = {zy};
		h.onEachTick(() -> zTop[0] = Math.max(zTop[0], z.getY()));
		double[] speed = motion(h, w);
		float hp = w.getHealth();
		h.assertValueEqual(MagicTestKit.cast(p, SpellIds.EARTH_SPIKES, 0f), Result.CAST, "result");
		h.runAfterDelay(20, () -> {
			h.assertTrue(zTop[0] - zy >= 0.5, "positive control: the zombie is thrown up " + (zTop[0] - zy));
			assertUnmoved(h, speed, 0.01, "the wither is never lifted");
			h.assertTrue(w.getHealth() < hp, "the spike still hurts it: " + w.getHealth());
			h.succeed();
		});
	}

	@GameTest(structure = MagicTestKit.ARENA, environment = ENV, maxTicks = 40)
	public void quakeHurtsButNeverThrowsABoss(GameTestHelper h) {
		ServerPlayer p = MagicTestKit.caster(h, Element.EARTH, 5);
		MagicTestKit.teleportTo(p, new BlockPos(16, 4, 16));
		WitherBoss w = wither(h, new BlockPos(20, 4, 16));
		Zombie z = MagicTestKit.movableDummy(h, new BlockPos(12, 4, 16));
		// A NoAI wither never moves, so it never finds the ground itself: it stands on it here (Quake strikes the grounded).
		h.onEachTick(() -> w.setOnGround(true));
		double zy = z.getY();
		double[] zTop = {zy};
		h.onEachTick(() -> zTop[0] = Math.max(zTop[0], z.getY()));
		double[] speed = motion(h, w);
		float hp = w.getHealth();
		h.assertValueEqual(MagicTestKit.cast(p, SpellIds.QUAKE, 0f), Result.CAST, "result");
		h.runAfterDelay(20, () -> {
			h.assertTrue(zTop[0] - zy >= 0.3, "positive control: the zombie is thrown " + (zTop[0] - zy));
			assertUnmoved(h, speed, 0.01, "the wither is never thrown");
			h.assertTrue(w.getHealth() < hp, "the ring still hurts it: " + w.getHealth());
			h.succeed();
		});
	}

	@GameTest(structure = MagicTestKit.ARENA, environment = ENV, maxTicks = 40)
	public void chainLightningNeverHopsToABoss(GameTestHelper h) {
		// Lane A: zombie, then a wither nearer than the next zombie: the hop skips the wither.
		ServerPlayer a = MagicTestKit.caster(h, Element.LIGHTNING, 2);
		MagicTestKit.teleportTo(a, new BlockPos(6, 4, 4));
		Zombie first = MagicTestKit.dummy(h, new BlockPos(6, 4, 8));
		WitherBoss skipped = wither(h, new BlockPos(6, 4, 10));
		Zombie next = MagicTestKit.dummy(h, new BlockPos(9, 4, 12));
		MagicTestKit.aim(a, new BlockPos(6, 5, 8));
		// Lane B: the look strikes a wither first: it is hurt (damage still applies), and the chain goes on to a zombie.
		ServerPlayer b = MagicTestKit.caster(h, Element.LIGHTNING, 2);
		MagicTestKit.teleportTo(b, new BlockPos(24, 4, 4));
		WitherBoss struck = wither(h, new BlockPos(24, 4, 9));
		Zombie after = MagicTestKit.dummy(h, new BlockPos(27, 4, 10));
		MagicTestKit.aim(b, new BlockPos(24, 6, 9));
		float skippedHp = skipped.getHealth(), struckHp = struck.getHealth();
		h.assertValueEqual(MagicTestKit.cast(a, SpellIds.CHAIN_LIGHTNING, 0f), Result.CAST, "lane A");
		h.assertValueEqual(MagicTestKit.cast(b, SpellIds.CHAIN_LIGHTNING, 0f), Result.CAST, "lane B");
		h.assertTrue(first.getHealth() < 20 && next.getHealth() < 20, "lane A: both zombies struck " + first.getHealth() + ", " + next.getHealth());
		h.assertValueEqual(skipped.getHealth(), skippedHp, "lane A: the chain never hops to the wither");
		h.assertTrue(struck.getHealth() < struckHp, "lane B: a wither struck first is hurt " + struck.getHealth());
		h.assertTrue(after.getHealth() < 20, "lane B: the chain goes on to the zombie " + after.getHealth());
		h.succeed();
	}
}
