package local.carter.office.magic.tree;

import static local.carter.office.magic.tree.Trees.hit;
import static local.carter.office.magic.tree.Trees.size;
import static local.carter.office.magic.tree.Trees.stat;
import static local.carter.office.magic.tree.Trees.tree;

import java.util.List;

import local.carter.office.magic.core.Slots;
import local.carter.office.magic.spell.SpellIds;
import local.carter.office.magic.spell.water.BubbleShield;
import local.carter.office.magic.spell.water.BubbleShieldGeometry;
import local.carter.office.magic.spell.water.DragonGeometry;
import local.carter.office.magic.spell.water.TidalPush;
import local.carter.office.magic.spell.water.WaterBullet;
import local.carter.office.magic.spell.water.WaterDragon;
import local.carter.office.magic.spell.water.WaterPrison;
import local.carter.office.magic.spell.water.WaterPrisonGeometry;
import local.carter.office.magic.tree.SpellTree.Stat.Unit;

import net.minecraft.world.effect.MobEffects;

/** Water's five trees (the plan's Water table). */
final class WaterTrees {
	private WaterTrees() {}

	static List<SpellTree> all() {
		return List.of(
				tree(SpellIds.WATER_BULLET, Slots.SMALL_A, "size",
						Fork.of(WaterBullet.RIPTIDE).mul(Knob.KNOCK, WaterBullet.RIPTIDE_KNOCK),
						Fork.of(WaterBullet.FROST).effect(MobEffects.SLOWNESS, 80, 1),
						Fork.of(WaterBullet.LANCE), Fork.of(WaterBullet.SPLASH),
						hit(WaterBullet.DAMAGE, Slots.SMALL_A),
						size("size", Unit.BLOCKS, WaterBullet.RADIUS, 9),
						stat("knockback", Unit.PERCENT, (r, lv) -> 100 * r.mul(Knob.KNOCK)),
						stat("range", Unit.BLOCKS, (r, lv) -> WaterBullet.SPEED * (r.is(WaterBullet.LANCE) ? WaterBullet.LANCE_TICKS : WaterBullet.MAX_TICKS))),
				tree(SpellIds.TIDAL_PUSH, Slots.MEDIUM_A, "range",
						Fork.of(TidalPush.BREAKER), Fork.of(TidalPush.SURGE).mul(Knob.KNOCK, TidalPush.SURGE_KNOCK),
						Fork.of(TidalPush.TSUNAMI), Fork.of(TidalPush.FRIGID),
						stat("range", Unit.BLOCKS, (r, lv) -> TidalPush.range(0, r)),
						stat("knockback", Unit.PERCENT, (r, lv) -> 100 * r.mul(Knob.DAMAGE) * r.mul(Knob.KNOCK)),
						stat("damage", Unit.DAMAGE, (r, lv) -> r.is(TidalPush.BREAKER) ? Trees.damage(TidalPush.BREAKER_DAMAGE, Slots.MEDIUM_A, r, lv) : 0)),
				tree(SpellIds.BUBBLE_SHIELD, Slots.SMALL_B, "radius",
						Fork.of(BubbleShield.MIRROR), Fork.of(BubbleShield.MENDING), Fork.of(BubbleShield.FORTRESS), Fork.of(BubbleShield.POP),
						size("radius", Unit.BLOCKS, BubbleShieldGeometry.RADIUS, 99),
						stat("life", Unit.SECONDS, (r, lv) -> BubbleShieldGeometry.duration(0) / 20.0 * (r.is(BubbleShield.FORTRESS) ? BubbleShield.FORTRESS_LIFE : 1)),
						stat("reflect", Unit.DAMAGE, (r, lv) -> r.is(BubbleShield.MIRROR) ? Trees.damage(BubbleShield.MIRROR_DAMAGE, Slots.SMALL_B, r, lv) : 0),
						stat("heal_hp", Unit.DAMAGE, (r, lv) -> r.is(BubbleShield.MENDING) ? BubbleShield.MENDING_HEAL : 0),
						stat("damage", Unit.DAMAGE, (r, lv) -> r.is(BubbleShield.POP) ? Trees.damage(BubbleShield.POP_DAMAGE, Slots.SMALL_B, r, lv) : 0)),
				tree(SpellIds.WATER_PRISON, Slots.MEDIUM_B, "radius",
						Fork.of(WaterPrison.CRUSHING), Fork.of(WaterPrison.LONG_HOLD), Fork.of(WaterPrison.TWIN), Fork.of(WaterPrison.HURL),
						size("radius", Unit.BLOCKS, WaterPrisonGeometry.RADIUS, 99),
						stat("life", Unit.SECONDS, (r, lv) -> WaterPrisonGeometry.duration(0) / 20.0 * (r.is(WaterPrison.LONG_HOLD) ? WaterPrison.LONG_HOLD_LIFE : 1)),
						stat("prisons", Unit.COUNT, (r, lv) -> r.is(WaterPrison.TWIN) ? 2 : 1),
						stat("damage", Unit.DAMAGE, (r, lv) -> r.is(WaterPrison.CRUSHING) ? Trees.damage(WaterPrison.CRUSH_DAMAGE, Slots.MEDIUM_B, r, lv) : 0),
						stat("throw", Unit.PERCENT, (r, lv) -> r.is(WaterPrison.HURL) ? 100 * WaterPrison.HURL_KNOCK : 0)),

				tree(SpellIds.WATER_DRAGON, Slots.ULTIMATE, "strike",
						Fork.of(WaterDragon.DROWNING), Fork.of(WaterDragon.TORRENT), Fork.of(WaterDragon.LEVIATHAN).mul(Knob.DAMAGE, 1.25F),
						Fork.of(WaterDragon.MAELSTROM),
						hit(DragonGeometry.DAMAGE, Slots.ULTIMATE),
						stat("strike", Unit.BLOCKS, (r, lv) -> WaterDragon.strikeRadius(r)),
						stat("flood", Unit.BLOCKS, (r, lv) -> WaterDragon.floodRadius(Math.max(lv, Slots.unlockLevel(Slots.ULTIMATE)), r)),
						stat("knockback", Unit.PERCENT, (r, lv) -> r.is(WaterDragon.TORRENT) ? 100 * WaterDragon.TORRENT_KNOCK : 100)));
	}
}
