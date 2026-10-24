package local.carter.office.magic.spell;

import java.util.ArrayList;
import java.util.List;

import local.carter.office.OfficeMod;
import local.carter.office.magic.core.Element;
import local.carter.office.magic.core.Slots;

import net.minecraft.resources.Identifier;

/**
 * Every spell id, one constant per spell. tools/make_magic_textures.py parses the IDS block: a {@code // <element key>}
 * comment starts each element's five spells (in slot order 0..4) and {@code // recall} marks Recall.
 */
public final class SpellIds {
	// IDS
	// fire
	public static final Identifier FIRE_BOLT = id("fire_bolt");
	public static final Identifier FIRE_WALL = id("fire_wall");
	public static final Identifier FLAME_DASH = id("flame_dash");
	public static final Identifier FIREBALL_BARRAGE = id("fireball_barrage");
	public static final Identifier METEOR = id("meteor");
	// water
	public static final Identifier WATER_BULLET = id("water_bullet");
	public static final Identifier TIDAL_PUSH = id("tidal_push");
	public static final Identifier BUBBLE_SHIELD = id("bubble_shield");
	public static final Identifier WATER_PRISON = id("water_prison");
	public static final Identifier WATER_DRAGON = id("water_dragon");
	// wind
	public static final Identifier GUST = id("gust");
	public static final Identifier BLADE_WIND = id("blade_wind");
	public static final Identifier AIR_STEP = id("air_step");
	public static final Identifier TORNADO = id("tornado");
	public static final Identifier WIND_SPHERE = id("wind_sphere");
	// lightning
	public static final Identifier SPARK = id("spark");
	public static final Identifier CHAIN_LIGHTNING = id("chain_lightning");
	public static final Identifier BLINK = id("blink");
	public static final Identifier THUNDER_SPEAR = id("thunder_spear");
	public static final Identifier CHIDORI_STRIKE = id("chidori_strike");
	// earth
	public static final Identifier STONE_BULLET = id("stone_bullet");
	public static final Identifier EARTH_WALL = id("earth_wall");
	public static final Identifier EARTH_SPIKES = id("earth_spikes");
	public static final Identifier QUAKE = id("quake");
	public static final Identifier MOUNTAIN_FIST = id("mountain_fist");
	// shadow
	public static final Identifier SHADOW_BOLT = id("shadow_bolt");
	public static final Identifier GRAVITY_WELL = id("gravity_well");
	public static final Identifier SHADOW_STEP = id("shadow_step");
	public static final Identifier SHADOW_BIND = id("shadow_bind");
	public static final Identifier BLACK_HOLE = id("black_hole");
	// recall
	public static final Identifier RECALL = id("recall");
	// END IDS

	/** Slots 0..4 per element, rows in {@link Element} order. */
	private static final Identifier[][] BY_ELEMENT = {
		{FIRE_BOLT, FIRE_WALL, FLAME_DASH, FIREBALL_BARRAGE, METEOR},
		{WATER_BULLET, TIDAL_PUSH, BUBBLE_SHIELD, WATER_PRISON, WATER_DRAGON},
		{GUST, BLADE_WIND, AIR_STEP, TORNADO, WIND_SPHERE},
		{SPARK, CHAIN_LIGHTNING, BLINK, THUNDER_SPEAR, CHIDORI_STRIKE},
		{STONE_BULLET, EARTH_WALL, EARTH_SPIKES, QUAKE, MOUNTAIN_FIST},
		{SHADOW_BOLT, GRAVITY_WELL, SHADOW_STEP, SHADOW_BIND, BLACK_HOLE},
	};

	/** All 31 ids: each element's five in slot order, then Recall. */
	public static final List<Identifier> ALL = all();

	private SpellIds() {}

	/**
	 * The spell in {@code slot} of {@code e}'s wheel; slot 5 ({@link Slots#RECALL}) is Recall for everyone.
	 *
	 * @throws IllegalArgumentException if {@code slot} is outside 0..5
	 */
	public static Identifier at(Element e, int slot) {
		if (slot == Slots.RECALL) return RECALL;
		if (slot < 0 || slot > Slots.ULTIMATE) throw new IllegalArgumentException("slot " + slot);
		return BY_ELEMENT[e.ordinal()][slot];
	}

	private static List<Identifier> all() {
		List<Identifier> out = new ArrayList<>();
		for (Identifier[] row : BY_ELEMENT) out.addAll(List.of(row));
		out.add(RECALL);
		return List.copyOf(out);
	}

	private static Identifier id(String path) {
		return OfficeMod.id(path);
	}
}
