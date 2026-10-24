package local.carter.office.magic;

import java.lang.reflect.Method;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import local.carter.office.magic.cast.CastService.Result;
import local.carter.office.magic.core.ChargeMath;
import local.carter.office.magic.core.Element;
import local.carter.office.magic.core.LevelCurve;
import local.carter.office.magic.core.ManaMath;
import local.carter.office.magic.core.Slots;
import local.carter.office.magic.data.MagicAttachments;
import local.carter.office.magic.net.FxEvent;
import local.carter.office.magic.spell.Spell;
import local.carter.office.magic.spell.SpellIds;
import local.carter.office.magic.spell.SpellRegistry;
import local.carter.office.magic.spell.TargetFilter;
import local.carter.office.magic.spell.earth.EarthSpikesGeometry;
import local.carter.office.magic.spell.earth.EarthWallGeometry;
import local.carter.office.magic.spell.earth.QuakeGeometry;
import local.carter.office.magic.spell.earth.StoneBullet;
import local.carter.office.magic.spell.fire.BarrageGeometry;
import local.carter.office.magic.spell.fire.FireBolt;
import local.carter.office.magic.spell.fire.FireWallGeometry;
import local.carter.office.magic.spell.fire.FlameDash;
import local.carter.office.magic.spell.lightning.Blink;
import local.carter.office.magic.spell.lightning.ChainHops;
import local.carter.office.magic.spell.lightning.Spark;
import local.carter.office.magic.spell.lightning.ThunderSpear;
import local.carter.office.magic.spell.shadow.BindGeometry;
import local.carter.office.magic.spell.shadow.ShadowBolt;
import local.carter.office.magic.spell.shadow.ShadowStep;
import local.carter.office.magic.spell.shadow.WellGeometry;
import local.carter.office.magic.spell.water.BubbleShieldGeometry;
import local.carter.office.magic.spell.water.TidalWaveGeometry;
import local.carter.office.magic.spell.water.WaterBullet;
import local.carter.office.magic.spell.water.WaterPrisonGeometry;
import local.carter.office.magic.spell.wind.AirStep;
import local.carter.office.magic.spell.wind.BladeWindGeometry;
import local.carter.office.magic.spell.wind.Gust;
import local.carter.office.magic.spell.wind.TornadoGeometry;

import net.minecraft.core.BlockPos;
import net.minecraft.core.GlobalPos;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.animal.cow.Cow;
import net.minecraft.world.entity.animal.equine.Horse;
import net.minecraft.world.entity.animal.happyghast.HappyGhast;
import net.minecraft.world.entity.animal.pig.Pig;
import net.minecraft.world.entity.animal.wolf.Wolf;
import net.minecraft.world.entity.npc.villager.Villager;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.phys.Vec3;

import net.fabricmc.fabric.api.gametest.v1.GameTest;

/**
 * Carter's play-test rulings (2026-10-24, Task 25) as GameTests.
 *
 * <p><b>Animals are fair game.</b> {@link #targetRuleCoversEveryKind} pins the one target rule ({@link TargetFilter}) for every
 * kind of entity the ruling names; {@link #gravityWellPullsACowButNeverTheProtected} watches it in the world: Gravity Well drags
 * a cow in, never a named cow, a tamed wolf, a villager or another player.
 *
 * <p><b>Charge ≈ 2×.</b> {@link #chargeScalesAboutTwice}: every non-ultimate spell's size knob (radius, length, width, reach or
 * count) at full charge is 1.7–2.5 times its tap's, a full charge's damage factor and mana are twice a tap's, and for the spells
 * whose fx event carries the knob a real cast at charge 0 and at charge 1 shows the same ratio.
 *
 * <p><b>No duds.</b> {@code dud<Spell>}, one per spell ({@link #noSpellIsADudWithOnlyAnimals} keeps them in step with the
 * registry): the spell, cast at the top grimoire level and full charge (ultimates through their hand signs) into a field with
 * only three cows, hurts or moves a cow within {@link #WINDOW} ticks. The spells that never touch a creature ({@link #SELF}: they
 * move, shield or wall in the caster) must instead have cast with their fx.
 *
 * <p>Its own batch ({@link #ENV}): ultimates fling debris, which must never land in another lane's arena.
 */
public class FeelGameTests {
	static final String ENV = "office-test:magic_feel";
	static final int LEVEL = LevelCurve.MAX_LEVEL;
	/** Ticks a dud test waits for a cow to be hurt or moved (Water Prison drowns its cow after about 107). */
	static final int WINDOW = 160;
	/** How far a cow must have moved to count as moved. */
	static final double MOVED = 0.5;
	/** The knob ratio a full charge must reach over a tap. */
	static final double LOW = 1.7;
	static final double HIGH = 2.5;
	/** Spells whose effect is on the caster or the world, never a creature: their fx is what must show. */
	static final Set<Identifier> SELF = Set.of(SpellIds.AIR_STEP, SpellIds.BLINK, SpellIds.BUBBLE_SHIELD, SpellIds.SHADOW_STEP,
			SpellIds.EARTH_WALL, SpellIds.RECALL);

	private static final Logger LOGGER = LoggerFactory.getLogger("office");

	// ---- Target rule ----

	private static <T extends Entity> T make(ServerLevel level, EntityType<T> type) {
		T e = type.create(level, EntitySpawnReason.COMMAND);
		if (e == null) throw new IllegalStateException("could not make " + type);
		return e;
	}

	@GameTest(structure = MagicTestKit.ARENA, environment = ENV)
	public void targetRuleCoversEveryKind(GameTestHelper h) {
		ServerPlayer p = MagicTestKit.caster(h, Element.SHADOW, LEVEL);
		ServerPlayer friend = MagicTestKit.otherPlayer(h, new BlockPos(10, 4, 4));
		ServerLevel level = h.getLevel();
		// Fair game: hostile mobs and animals, hurt and controlled alike.
		for (EntityType<?> type : List.of(EntityTypes.ZOMBIE, EntityTypes.CREEPER, EntityTypes.SHULKER, EntityTypes.COW, EntityTypes.PIG,
				EntityTypes.SHEEP, EntityTypes.CHICKEN, EntityTypes.BAT, EntityTypes.SQUID, EntityTypes.COD, EntityTypes.DOLPHIN,
				EntityTypes.WOLF, EntityTypes.HORSE)) {
			Entity e = make(level, type);
			h.assertTrue(TargetFilter.affects(p, e), type + " is fair game: spells hurt it");
			h.assertTrue(TargetFilter.controllable(p, e), type + " is fair game: control effects take it");
		}
		// Never: players, owned pets and horses, villagers and traders, golems, allays, named mobs, armor stands.
		Cow named = make(level, EntityTypes.COW);
		named.setCustomName(Component.literal("Daisy"));
		Wolf pet = make(level, EntityTypes.WOLF);
		pet.setTame(true, false);
		pet.setOwner(p);
		Horse horse = make(level, EntityTypes.HORSE);
		horse.setTamed(true);
		horse.setOwner(friend);
		// Saddled or harnessed animals are someone's mount (a vehicle), ridden or not.
		Pig saddled = make(level, EntityTypes.PIG);
		saddled.setItemSlot(EquipmentSlot.SADDLE, new ItemStack(Items.SADDLE));
		HappyGhast harnessed = make(level, EntityTypes.HAPPY_GHAST);
		harnessed.setItemSlot(EquipmentSlot.BODY, new ItemStack(Items.HARNESS.white()));
		List<Entity> never = List.of(friend, named, pet, horse, saddled, harnessed, make(level, EntityTypes.VILLAGER), make(level, EntityTypes.WANDERING_TRADER),
				make(level, EntityTypes.IRON_GOLEM), make(level, EntityTypes.SNOW_GOLEM), make(level, EntityTypes.COPPER_GOLEM),
				make(level, EntityTypes.ALLAY), make(level, EntityTypes.ARMOR_STAND));
		for (Entity e : never) {
			h.assertFalse(TargetFilter.affects(p, e), e.getType() + " (" + e.getName().getString() + "): never hurt");
			h.assertFalse(TargetFilter.pushable(p, e), e.getType() + " (" + e.getName().getString() + "): never pushed");
			h.assertFalse(TargetFilter.controllable(p, e), e.getType() + " (" + e.getName().getString() + "): never controlled");
		}
		// Bosses: hurt, never controlled.
		Entity wither = make(level, EntityTypes.WITHER);
		h.assertTrue(TargetFilter.affects(p, wither) && !TargetFilter.controllable(p, wither), "a boss is hurt but never controlled");
		h.succeed();
	}

	/** A cow that physics moves (AI on, every goal removed: it stands still unless something moves it), persistent. */
	private static Cow cow(GameTestHelper h, BlockPos rel) {
		Cow c = h.spawn(EntityTypes.COW, rel);
		c.removeAllGoals(g -> true);
		c.setPersistenceRequired();
		return c;
	}

	/** A NoAI mob: it never moves itself, but keeps any velocity it is given (so a pull shows as its velocity). */
	private static <T extends Mob> T still(T m) {
		m.setNoAi(true);
		m.setPersistenceRequired();
		return m;
	}

	@GameTest(structure = MagicTestKit.ARENA, environment = ENV, maxTicks = 80)
	public void gravityWellPullsACowButNeverTheProtected(GameTestHelper h) {
		ServerPlayer p = MagicTestKit.caster(h, Element.SHADOW, 2);
		MagicTestKit.Recorder rec = MagicTestKit.recorder(h);
		MagicTestKit.aim(p, new BlockPos(16, 3, 14));
		h.assertValueEqual(MagicTestKit.cast(p, SpellIds.GRAVITY_WELL, 0f), Result.CAST, "result");
		Vec3 c = h.relativeVec(rec.fx(p).getFirst().target());
		Cow cow = cow(h, BlockPos.containing(c.x - 4, 4, c.z));
		// The named cow stands inside the hurt core (1.15 from the centre, within 2.5): never pulled, and never hurt either.
		Cow named = still(h.spawn(EntityTypes.COW, new Vec3(c.x, 4, c.z + 1.6)));
		named.setCustomName(Component.literal("Daisy"));
		Wolf pet = MagicTestKit.tamedWolf(h, BlockPos.containing(c.x, 4, c.z + 4), p);
		Villager villager = MagicTestKit.villager(h, BlockPos.containing(c.x, 4, c.z - 4));
		ServerPlayer friend = MagicTestKit.otherPlayer(h, BlockPos.containing(c.x + 3, 4, c.z + 3));
		List<LivingEntity> spared = List.of(named, pet, villager, friend);
		Map<LivingEntity, Vec3> at = new LinkedHashMap<>();
		for (LivingEntity e : spared) at.put(e, e.position());
		double[] fastest = new double[spared.size()];
		h.onEachTick(() -> {
			for (int i = 0; i < spared.size(); i++) {
				LivingEntity e = spared.get(i);
				fastest[i] = Math.max(fastest[i], e.getDeltaMovement().horizontalDistance());
				if (e != friend) e.setDeltaMovement(Vec3.ZERO);
			}
		});
		double cow0 = MagicTestKit.dist(h, cow, c);
		float cowHp = cow.getHealth();
		h.runAfterDelay(40, () -> {
			// Within 2 s at a tap the cow is dragged into the core and hurt there.
			double now = MagicTestKit.dist(h, cow, c);
			h.assertTrue(now < cow0 - 2, "the cow is dragged in: " + cow0 + " -> " + now);
			h.assertTrue(cow.getHealth() < cowHp, "the core hurts the cow: " + cow.getHealth());
			for (int i = 0; i < spared.size(); i++) {
				LivingEntity e = spared.get(i);
				String what = e == named ? "the named cow" : e == pet ? "the tamed wolf" : e == villager ? "the villager" : "the other player";
				h.assertTrue(fastest[i] < 0.01, what + " is never pulled: top speed " + fastest[i]);
				h.assertTrue(e.position().distanceTo(at.get(e)) < 0.05, what + " stays put: " + at.get(e) + " -> " + e.position());
				h.assertValueEqual(e.getHealth(), e.getMaxHealth(), what + " is never hurt");
			}
			h.succeed();
		});
	}

	// ---- Charge ≈ 2× ----

	/** Every non-ultimate spell's size knob at {@code charge} (what the player sees grow: radius, length, width, reach, count). */
	private static final Map<Identifier, Knob> KNOBS = new LinkedHashMap<>();

	@FunctionalInterface
	interface Knob {
		double at(float charge);
	}

	static {
		KNOBS.put(SpellIds.FIRE_BOLT, FireBolt::radius);
		KNOBS.put(SpellIds.FIRE_WALL, FireWallGeometry::width);
		KNOBS.put(SpellIds.FLAME_DASH, FlameDash::speed);
		KNOBS.put(SpellIds.FIREBALL_BARRAGE, BarrageGeometry::count);
		KNOBS.put(SpellIds.WATER_BULLET, WaterBullet::radius);
		KNOBS.put(SpellIds.TIDAL_PUSH, TidalWaveGeometry::range);
		KNOBS.put(SpellIds.BUBBLE_SHIELD, BubbleShieldGeometry::radius);
		KNOBS.put(SpellIds.WATER_PRISON, WaterPrisonGeometry::radius);
		KNOBS.put(SpellIds.GUST, Gust::range);
		KNOBS.put(SpellIds.BLADE_WIND, BladeWindGeometry::blades); // and its fan twice as wide (spread)
		KNOBS.put(SpellIds.AIR_STEP, c -> AirStep.apex(AirStep.velocity(new Vec3(0, 0, 1), 0F, c).y));
		KNOBS.put(SpellIds.TORNADO, TornadoGeometry::radius);
		KNOBS.put(SpellIds.SPARK, Spark::range);
		KNOBS.put(SpellIds.CHAIN_LIGHTNING, ChainHops::hops);
		KNOBS.put(SpellIds.BLINK, Blink::range);
		KNOBS.put(SpellIds.THUNDER_SPEAR, ThunderSpear::blast);
		KNOBS.put(SpellIds.STONE_BULLET, StoneBullet::radius);
		KNOBS.put(SpellIds.EARTH_WALL, EarthWallGeometry::width);
		KNOBS.put(SpellIds.EARTH_SPIKES, EarthSpikesGeometry::length);
		KNOBS.put(SpellIds.QUAKE, QuakeGeometry::radius);
		KNOBS.put(SpellIds.SHADOW_BOLT, ShadowBolt::radius);
		KNOBS.put(SpellIds.GRAVITY_WELL, WellGeometry::radius);
		KNOBS.put(SpellIds.SHADOW_STEP, ShadowStep::range);
		KNOBS.put(SpellIds.SHADOW_BIND, BindGeometry::foes);
	}

	/** Spells whose fx event carries their knob: the index into {@code data}. */
	private static final Map<Identifier, Integer> FX_KNOB = new LinkedHashMap<>();

	static {
		FX_KNOB.put(SpellIds.FIRE_WALL, 0);
		FX_KNOB.put(SpellIds.FLAME_DASH, 0);
		FX_KNOB.put(SpellIds.FIREBALL_BARRAGE, 0);
		FX_KNOB.put(SpellIds.WATER_BULLET, 1);
		FX_KNOB.put(SpellIds.TIDAL_PUSH, 0);
		FX_KNOB.put(SpellIds.BUBBLE_SHIELD, 0);
		FX_KNOB.put(SpellIds.WATER_PRISON, 0);
		FX_KNOB.put(SpellIds.GUST, 0);
		FX_KNOB.put(SpellIds.BLADE_WIND, 0);
		FX_KNOB.put(SpellIds.STONE_BULLET, 2);
		FX_KNOB.put(SpellIds.EARTH_WALL, 2);
		FX_KNOB.put(SpellIds.EARTH_SPIKES, 0);
		FX_KNOB.put(SpellIds.QUAKE, 0);
		FX_KNOB.put(SpellIds.SHADOW_BOLT, 1);
		FX_KNOB.put(SpellIds.GRAVITY_WELL, 0);
	}

	@GameTest(structure = MagicTestKit.ARENA, environment = ENV, maxTicks = 40)
	public void chargeScalesAboutTwice(GameTestHelper h) {
		StringBuilder report = new StringBuilder();
		// The knob of every non-ultimate spell.
		for (Spell s : SpellRegistry.all()) {
			if (s.slot() == Slots.ULTIMATE || s.slot() == Slots.RECALL) continue;
			Knob k = KNOBS.get(s.id());
			h.assertTrue(k != null, "no size knob listed for " + s.id());
			double ratio = k.at(1F) / k.at(0F);
			report.append(String.format(Locale.ROOT, " %s=%.2f", s.id().getPath(), ratio));
			h.assertTrue(ratio >= LOW && ratio <= HIGH, s.id() + ": full charge is " + ratio + "× a tap, want " + LOW + "–" + HIGH);
			// Mana: a full charge costs twice the tap.
			int base = s.cost().mana();
			h.assertValueEqual(ChargeMath.cost(base, 1F, false), 2 * base, s.id() + ": a full charge's mana");
		}
		for (int level = 1; level <= LEVEL; level++) {
			h.assertTrue(Math.abs(ChargeMath.power(1F, level) / ChargeMath.power(0F, level) - 2) < 1.0E-5, "a full charge hits twice as hard at level " + level);
		}
		LOGGER.info("[feel] full charge ÷ tap, by size knob:{}", report);
		// The same ratio in the world, read from the fx event a real cast sends at charge 0 and at charge 1 (one caster per spell).
		MagicTestKit.Recorder rec = MagicTestKit.recorder(h);
		for (Map.Entry<Identifier, Integer> en : FX_KNOB.entrySet()) {
			Spell s = SpellRegistry.get(en.getKey()).orElseThrow();
			ServerPlayer p = MagicTestKit.caster(h, s.element(), LEVEL);
			MagicTestKit.teleportTo(p, new BlockPos(16, 4, 10));
			MagicTestKit.aim(p, new BlockPos(16, 4, 20));
			double[] knob = new double[2];
			for (int i = 0; i < 2; i++) {
				MagicAttachments.set(p, MagicAttachments.get(p).withReadyAt(Map.of()));
				MagicAttachments.setMana(p, ManaMath.max(LEVEL));
				int before = rec.fx(p).size();
				h.assertValueEqual(MagicTestKit.cast(p, s.id(), i), Result.CAST, s.id() + " at charge " + i);
				List<FxEvent> fx = rec.fx(p);
				h.assertValueEqual(fx.size(), before + 1, s.id() + ": one fx at charge " + i);
				knob[i] = fx.getLast().data()[en.getValue()];
			}
			double ratio = knob[1] / knob[0];
			double want = KNOBS.get(s.id()).at(1F) / KNOBS.get(s.id()).at(0F);
			h.assertTrue(Math.abs(ratio - want) < 0.02, s.id() + ": its fx says " + knob[0] + " -> " + knob[1] + ", the knob says " + want + "×");
		}
		h.succeed();
	}

	// ---- No duds ----

	/** Where a dud test aims, and where its three cows stand (that cell and one each side of it). */
	record Field(BlockPos aim, BlockPos cows) {}

	private static final Map<Identifier, Field> FIELDS = new LinkedHashMap<>();

	private static void field(Identifier id, int aimY, int aimZ, int cowsZ) {
		FIELDS.put(id, new Field(new BlockPos(16, aimY, aimZ), new BlockPos(16, 4, cowsZ)));
	}

	static {
		// Fire
		field(SpellIds.FIRE_BOLT, 4, 10, 10);
		field(SpellIds.FIRE_WALL, 3, 10, 8); // the wall stands 4 ahead: its foot is the cows' row
		field(SpellIds.FLAME_DASH, 4, 12, 6); // a mock caster never moves (its client would): the cows stand in its reach
		field(SpellIds.FIREBALL_BARRAGE, 3, 12, 12);
		field(SpellIds.METEOR, 3, 14, 14);
		// Water
		field(SpellIds.WATER_BULLET, 4, 10, 10);
		field(SpellIds.TIDAL_PUSH, 4, 9, 9);
		field(SpellIds.BUBBLE_SHIELD, 4, 12, 8);
		field(SpellIds.WATER_PRISON, 4, 10, 10);
		field(SpellIds.WATER_DRAGON, 3, 14, 14);
		// Wind
		field(SpellIds.GUST, 4, 8, 8);
		field(SpellIds.BLADE_WIND, 4, 10, 10);
		field(SpellIds.AIR_STEP, 4, 12, 8);
		field(SpellIds.TORNADO, 3, 10, 11);
		field(SpellIds.WIND_SPHERE, 4, 12, 12);
		// Lightning
		field(SpellIds.SPARK, 4, 10, 10);
		field(SpellIds.CHAIN_LIGHTNING, 4, 10, 10);
		field(SpellIds.BLINK, 5, 20, 12);
		field(SpellIds.THUNDER_SPEAR, 4, 12, 12);
		field(SpellIds.CHIDORI_STRIKE, 4, 12, 12);
		// Earth
		field(SpellIds.STONE_BULLET, 4, 10, 10);
		field(SpellIds.EARTH_WALL, 4, 10, 10);
		field(SpellIds.EARTH_SPIKES, 4, 10, 9);
		field(SpellIds.QUAKE, 4, 10, 9);
		field(SpellIds.MOUNTAIN_FIST, 3, 16, 16);
		// Shadow
		field(SpellIds.SHADOW_BOLT, 4, 10, 10);
		field(SpellIds.GRAVITY_WELL, 3, 12, 12);
		field(SpellIds.SHADOW_STEP, 4, 10, 10);
		field(SpellIds.SHADOW_BIND, 4, 10, 10);
		field(SpellIds.BLACK_HOLE, 3, 16, 16);
	}

	/** Three cows in the field, a caster at the top level casting at full charge; then a cow must be hurt or moved (or, for {@link #SELF}, the fx sent). */
	private static void dud(GameTestHelper h, Identifier id) {
		Spell spell = SpellRegistry.get(id).orElseThrow(() -> new IllegalStateException("not registered: " + id));
		Field f = FIELDS.get(id);
		ServerPlayer p = MagicTestKit.caster(h, spell.element(), LEVEL);
		MagicTestKit.Recorder rec = MagicTestKit.recorder(h);
		List<Cow> cows = List.of(cow(h, f.cows().west()), cow(h, f.cows()), cow(h, f.cows().east()));
		// Let them settle on the floor first. (A delayed action that does not end the test may run again: once is enough.)
		boolean[] cast = {false};
		h.runAfterDelay(5, () -> {
			if (cast[0]) return;
			cast[0] = true;
			Vec3[] from = new Vec3[cows.size()];
			for (int i = 0; i < from.length; i++) from[i] = cows.get(i).position();
			MagicTestKit.aim(p, f.aim());
			Result r = spell.slot() == Slots.ULTIMATE ? MagicTestKit.castUltimate(p, id) : MagicTestKit.cast(p, id, 1f);
			h.assertValueEqual(r, Result.CAST, id + ": result");
			h.assertTrue(!rec.fx(p).isEmpty(), id + ": no fx");
			if (SELF.contains(id)) {
				h.succeed();
				return;
			}
			long start = h.getTick();
			boolean[] done = {false};
			h.onEachTick(() -> {
				if (done[0]) return;
				for (int i = 0; i < cows.size(); i++) {
					Cow c = cows.get(i);
					boolean hurt = c.isRemoved() || c.isDeadOrDying() || c.getHealth() < c.getMaxHealth();
					boolean moved = !c.isRemoved() && c.position().distanceTo(from[i]) > MOVED;
					if (hurt || moved) {
						LOGGER.info("[feel] {}: cow {} {} after {} ticks", id.getPath(), i, hurt ? "hurt" : "moved", h.getTick() - start);
						done[0] = true;
						h.succeed();
						return;
					}
				}
				if (h.getTick() - start > WINDOW) {
					done[0] = true;
					h.fail(id + ": a dud with only cows around: none hurt or moved in " + WINDOW + " ticks");
				}
			});
		});
	}

	@GameTest(structure = MagicTestKit.ARENA, environment = ENV)
	public void noSpellIsADudWithOnlyAnimals(GameTestHelper h) {
		Set<String> methods = new HashSet<>();
		for (Method m : FeelGameTests.class.getDeclaredMethods()) if (m.isAnnotationPresent(GameTest.class)) methods.add(m.getName());
		for (Spell s : SpellRegistry.all()) {
			String name = "dud" + camel(s.id().getPath());
			h.assertTrue(methods.contains(name), "no dud test " + name + " for " + s.id());
			h.assertTrue(s.id().equals(SpellIds.RECALL) || FIELDS.containsKey(s.id()), "no dud field for " + s.id());
		}
		h.assertValueEqual(FIELDS.size(), 30, "element spell fields");
		h.succeed();
	}

	private static String camel(String path) {
		StringBuilder b = new StringBuilder();
		for (String part : path.split("_")) b.append(Character.toUpperCase(part.charAt(0))).append(part.substring(1));
		return b.toString();
	}

	private static final int DUD_TICKS = WINDOW + 60;

	@GameTest(structure = MagicTestKit.ARENA, environment = ENV, maxTicks = DUD_TICKS)
	public void dudFireBolt(GameTestHelper h) { dud(h, SpellIds.FIRE_BOLT); }

	@GameTest(structure = MagicTestKit.ARENA, environment = ENV, maxTicks = DUD_TICKS)
	public void dudFireWall(GameTestHelper h) { dud(h, SpellIds.FIRE_WALL); }

	@GameTest(structure = MagicTestKit.ARENA, environment = ENV, maxTicks = DUD_TICKS)
	public void dudFlameDash(GameTestHelper h) { dud(h, SpellIds.FLAME_DASH); }

	@GameTest(structure = MagicTestKit.ARENA, environment = ENV, maxTicks = DUD_TICKS)
	public void dudFireballBarrage(GameTestHelper h) { dud(h, SpellIds.FIREBALL_BARRAGE); }

	@GameTest(structure = MagicTestKit.ARENA, environment = ENV, maxTicks = DUD_TICKS)
	public void dudMeteor(GameTestHelper h) { dud(h, SpellIds.METEOR); }

	@GameTest(structure = MagicTestKit.ARENA, environment = ENV, maxTicks = DUD_TICKS)
	public void dudWaterBullet(GameTestHelper h) { dud(h, SpellIds.WATER_BULLET); }

	@GameTest(structure = MagicTestKit.ARENA, environment = ENV, maxTicks = DUD_TICKS)
	public void dudTidalPush(GameTestHelper h) { dud(h, SpellIds.TIDAL_PUSH); }

	@GameTest(structure = MagicTestKit.ARENA, environment = ENV, maxTicks = DUD_TICKS)
	public void dudBubbleShield(GameTestHelper h) { dud(h, SpellIds.BUBBLE_SHIELD); }

	@GameTest(structure = MagicTestKit.ARENA, environment = ENV, maxTicks = DUD_TICKS)
	public void dudWaterPrison(GameTestHelper h) { dud(h, SpellIds.WATER_PRISON); }

	@GameTest(structure = MagicTestKit.ARENA, environment = ENV, maxTicks = DUD_TICKS)
	public void dudWaterDragon(GameTestHelper h) { dud(h, SpellIds.WATER_DRAGON); }

	@GameTest(structure = MagicTestKit.ARENA, environment = ENV, maxTicks = DUD_TICKS)
	public void dudGust(GameTestHelper h) { dud(h, SpellIds.GUST); }

	@GameTest(structure = MagicTestKit.ARENA, environment = ENV, maxTicks = DUD_TICKS)
	public void dudBladeWind(GameTestHelper h) { dud(h, SpellIds.BLADE_WIND); }

	@GameTest(structure = MagicTestKit.ARENA, environment = ENV, maxTicks = DUD_TICKS)
	public void dudAirStep(GameTestHelper h) { dud(h, SpellIds.AIR_STEP); }

	@GameTest(structure = MagicTestKit.ARENA, environment = ENV, maxTicks = DUD_TICKS)
	public void dudTornado(GameTestHelper h) { dud(h, SpellIds.TORNADO); }

	@GameTest(structure = MagicTestKit.ARENA, environment = ENV, maxTicks = DUD_TICKS)
	public void dudWindSphere(GameTestHelper h) { dud(h, SpellIds.WIND_SPHERE); }

	@GameTest(structure = MagicTestKit.ARENA, environment = ENV, maxTicks = DUD_TICKS)
	public void dudSpark(GameTestHelper h) { dud(h, SpellIds.SPARK); }

	@GameTest(structure = MagicTestKit.ARENA, environment = ENV, maxTicks = DUD_TICKS)
	public void dudChainLightning(GameTestHelper h) { dud(h, SpellIds.CHAIN_LIGHTNING); }

	@GameTest(structure = MagicTestKit.ARENA, environment = ENV, maxTicks = DUD_TICKS)
	public void dudBlink(GameTestHelper h) { dud(h, SpellIds.BLINK); }

	@GameTest(structure = MagicTestKit.ARENA, environment = ENV, maxTicks = DUD_TICKS)
	public void dudThunderSpear(GameTestHelper h) { dud(h, SpellIds.THUNDER_SPEAR); }

	@GameTest(structure = MagicTestKit.ARENA, environment = ENV, maxTicks = DUD_TICKS)
	public void dudChidoriStrike(GameTestHelper h) { dud(h, SpellIds.CHIDORI_STRIKE); }

	@GameTest(structure = MagicTestKit.ARENA, environment = ENV, maxTicks = DUD_TICKS)
	public void dudStoneBullet(GameTestHelper h) { dud(h, SpellIds.STONE_BULLET); }

	@GameTest(structure = MagicTestKit.ARENA, environment = ENV, maxTicks = DUD_TICKS)
	public void dudEarthWall(GameTestHelper h) { dud(h, SpellIds.EARTH_WALL); }

	@GameTest(structure = MagicTestKit.ARENA, environment = ENV, maxTicks = DUD_TICKS)
	public void dudEarthSpikes(GameTestHelper h) { dud(h, SpellIds.EARTH_SPIKES); }

	@GameTest(structure = MagicTestKit.ARENA, environment = ENV, maxTicks = DUD_TICKS)
	public void dudQuake(GameTestHelper h) { dud(h, SpellIds.QUAKE); }

	@GameTest(structure = MagicTestKit.ARENA, environment = ENV, maxTicks = DUD_TICKS)
	public void dudMountainFist(GameTestHelper h) { dud(h, SpellIds.MOUNTAIN_FIST); }

	@GameTest(structure = MagicTestKit.ARENA, environment = ENV, maxTicks = DUD_TICKS)
	public void dudShadowBolt(GameTestHelper h) { dud(h, SpellIds.SHADOW_BOLT); }

	@GameTest(structure = MagicTestKit.ARENA, environment = ENV, maxTicks = DUD_TICKS)
	public void dudGravityWell(GameTestHelper h) { dud(h, SpellIds.GRAVITY_WELL); }

	@GameTest(structure = MagicTestKit.ARENA, environment = ENV, maxTicks = DUD_TICKS)
	public void dudShadowStep(GameTestHelper h) { dud(h, SpellIds.SHADOW_STEP); }

	@GameTest(structure = MagicTestKit.ARENA, environment = ENV, maxTicks = DUD_TICKS)
	public void dudShadowBind(GameTestHelper h) { dud(h, SpellIds.SHADOW_BIND); }

	@GameTest(structure = MagicTestKit.ARENA, environment = ENV, maxTicks = DUD_TICKS)
	public void dudBlackHole(GameTestHelper h) { dud(h, SpellIds.BLACK_HOLE); }

	/** Recall (in Recall's environment: it reads a home): with a home set, it casts with its channel circle (its fx). */
	@GameTest(structure = MagicTestKit.ARENA, environment = RecallGameTests.ENV, maxTicks = 60)
	public void dudRecall(GameTestHelper h) {
		ServerPlayer p = MagicTestKit.caster(h, Element.FIRE, LEVEL);
		MagicTestKit.Recorder rec = MagicTestKit.recorder(h);
		cow(h, new BlockPos(16, 4, 8));
		BlockPos home = h.absolutePos(new BlockPos(16, 4, 28));
		MagicAttachments.set(p, MagicAttachments.get(p).withHome(Optional.of(GlobalPos.of(h.getLevel().dimension(), home))));
		h.assertValueEqual(MagicTestKit.cast(p, SpellIds.RECALL, 1f), Result.CAST, "result");
		h.assertTrue(!rec.fx(p).isEmpty(), "Recall shows its channel circle");
		h.succeed();
	}
}
