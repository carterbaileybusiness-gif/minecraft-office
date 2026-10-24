package local.carter.office.magic.tree;

import static local.carter.office.magic.tree.Trees.hit;
import static local.carter.office.magic.tree.Trees.size;
import static local.carter.office.magic.tree.Trees.stat;
import static local.carter.office.magic.tree.Trees.tree;

import java.util.List;

import local.carter.office.magic.core.Slots;
import local.carter.office.magic.spell.SpellIds;
import local.carter.office.magic.spell.fire.BarrageGeometry;
import local.carter.office.magic.spell.fire.FireBolt;
import local.carter.office.magic.spell.fire.FireWall;
import local.carter.office.magic.spell.fire.FireWallGeometry;
import local.carter.office.magic.spell.fire.FireballBarrage;
import local.carter.office.magic.spell.fire.FlameDash;
import local.carter.office.magic.spell.fire.Meteor;
import local.carter.office.magic.spell.fire.MeteorGeometry;
import local.carter.office.magic.tree.SpellTree.Stat.Unit;

/** Fire's five trees (the plan's Fire table). */
final class FireTrees {
	private FireTrees() {}

	static List<SpellTree> all() {
		return List.of(
				tree(SpellIds.FIRE_BOLT, Slots.SMALL_A, "size",
						Fork.of(FireBolt.SCATTER), Fork.of(FireBolt.SEARING), Fork.of(FireBolt.LANCE), Fork.of(FireBolt.FLARE),
						hit(FireBolt.DAMAGE, Slots.SMALL_A, r -> r.is(FireBolt.SCATTER) ? FireBolt.SCATTER_SHARE : 1),
						stat("bolts", Unit.COUNT, (r, lv) -> r.is(FireBolt.SCATTER) ? 3 : 1),
						size("size", Unit.BLOCKS, FireBolt.RADIUS, 9),
						stat("burn", Unit.SECONDS, (r, lv) -> FireBolt.FIRE_TICKS / 20.0 * (r.is(FireBolt.SEARING) ? 2 : 1)),
						stat("range", Unit.BLOCKS, (r, lv) -> FireBolt.SPEED * (r.is(FireBolt.LANCE) ? FireBolt.LANCE_TICKS : FireBolt.MAX_TICKS))),
				tree(SpellIds.FIRE_WALL, Slots.MEDIUM_A, "width",
						Fork.of(FireWall.INFERNO), Fork.of(FireWall.FIREBREAK), Fork.of(FireWall.MARCHING), Fork.of(FireWall.BACKDRAFT),
						stat("damage", Unit.DAMAGE, (r, lv) -> FireWall.DAMAGE * r.mul(Knob.DAMAGE)),
						stat("pulse", Unit.SECONDS, (r, lv) -> (r.is(FireWall.INFERNO) ? FireWall.INFERNO_PULSE_TICKS : FireWall.PULSE_TICKS) / 20.0),
						size("width", Unit.BLOCKS, FireWallGeometry.WIDTH, FireWallGeometry.MAX_WIDTH),
						stat("life", Unit.SECONDS, (r, lv) -> FireWallGeometry.BASE_TICKS / 20.0)),
				tree(SpellIds.FLAME_DASH, Slots.SMALL_B, "reach",
						Fork.of(FlameDash.OVERDRIVE), Fork.of(FlameDash.PHOENIX), Fork.of(FlameDash.COMET), Fork.of(FlameDash.WAKE),
						hit(FlameDash.DAMAGE, Slots.SMALL_B),
						size("reach", Unit.BLOCKS, FlameDash.REACH, 9),
						stat("dash", Unit.PERCENT, (r, lv) -> r.is(FlameDash.OVERDRIVE) ? 100 * FlameDash.OVERDRIVE_SPEED : 100),
						stat("burn", Unit.SECONDS, (r, lv) -> FlameDash.FIRE_TICKS / 20.0 * (r.is(FlameDash.WAKE) ? 3 : 1))),
				tree(SpellIds.FIREBALL_BARRAGE, Slots.MEDIUM_B, "fireballs",
						Fork.of(FireballBarrage.CARPET).add(Knob.COUNT, 2), Fork.of(FireballBarrage.NAPALM),
						Fork.of(FireballBarrage.RAPID).add(Knob.COUNT, 2), Fork.of(FireballBarrage.HEAVY).add(Knob.COUNT, -1),
						hit(FireballBarrage.DAMAGE, Slots.MEDIUM_B),
						stat("fireballs", Unit.COUNT, (r, lv) -> BarrageGeometry.count(0, r)),
						stat("blast", Unit.BLOCKS, (r, lv) -> BarrageGeometry.blast(r)),
						stat("interval", Unit.SECONDS, (r, lv) -> BarrageGeometry.interval(r) / 20.0),
						stat("burn", Unit.SECONDS, (r, lv) -> FireballBarrage.FIRE_TICKS / 20.0 * (r.is(FireballBarrage.NAPALM) ? 3 : 1))),
				tree(SpellIds.METEOR, Slots.ULTIMATE, "blast",
						Fork.of(Meteor.SCORCHED), Fork.of(Meteor.SHOCKWAVE), Fork.of(Meteor.EXTINCTION).mul(Knob.DAMAGE, 1.25F),
						Fork.of(Meteor.CLUSTER),
						hit(Meteor.DAMAGE, Slots.ULTIMATE),
						stat("blast", Unit.BLOCKS, (r, lv) -> Meteor.blast(r)),
						stat("crater", Unit.BLOCKS, (r, lv) -> Math.min(MeteorGeometry.MAX_RADIUS,
								MeteorGeometry.radius(Math.max(lv, Slots.unlockLevel(Slots.ULTIMATE))) + (r.is(Meteor.EXTINCTION) ? Meteor.EXTINCTION_RADIUS : 0))),
						stat("knockback", Unit.PERCENT, (r, lv) -> r.is(Meteor.SHOCKWAVE) ? 100 * Meteor.SHOCKWAVE_KNOCK : 100),
						stat("fragments", Unit.COUNT, (r, lv) -> r.is(Meteor.CLUSTER) ? MeteorGeometry.FRAGMENTS : 0)));
	}
}
