package local.carter.office.magic.tree;

import static local.carter.office.magic.tree.Trees.hit;
import static local.carter.office.magic.tree.Trees.size;
import static local.carter.office.magic.tree.Trees.stat;
import static local.carter.office.magic.tree.Trees.tree;

import java.util.List;

import local.carter.office.magic.core.Slots;
import local.carter.office.magic.spell.SpellIds;
import local.carter.office.magic.spell.earth.EarthSpikes;
import local.carter.office.magic.spell.earth.EarthWall;
import local.carter.office.magic.spell.earth.MountainFist;
import local.carter.office.magic.spell.earth.MountainFistGeometry;
import local.carter.office.magic.spell.earth.Quake;
import local.carter.office.magic.spell.earth.StoneBullet;
import local.carter.office.magic.tree.SpellTree.Stat.Unit;

import net.minecraft.world.effect.MobEffects;

/** Earth's five trees (the plan's Earth table). */
final class EarthTrees {
	private EarthTrees() {}

	static List<SpellTree> all() {
		return List.of(
				tree(SpellIds.STONE_BULLET, Slots.SMALL_A, "size",
						Fork.of(StoneBullet.BOULDER).mul(Knob.SIZE, 1.5F).mul(Knob.KNOCK, 1.5F), Fork.of(StoneBullet.SHRAPNEL),
						Fork.of(StoneBullet.RICOCHET),
						Fork.of(StoneBullet.CONCUSSION).effect(MobEffects.BLINDNESS, 40, 0).effect(MobEffects.SLOWNESS, 40, 2),
						hit(StoneBullet.DAMAGE, Slots.SMALL_A),
						size("size", Unit.BLOCKS, StoneBullet.RADIUS, 99),
						stat("knockback", Unit.PERCENT, (r, lv) -> 100 * r.mul(Knob.KNOCK))),
				tree(SpellIds.EARTH_WALL, Slots.MEDIUM_A, "life",
						Fork.of(EarthWall.THORNS), Fork.of(EarthWall.UPHEAVAL), Fork.of(EarthWall.BASTION), Fork.of(EarthWall.CRUMBLE),
						stat("life", Unit.SECONDS, (r, lv) -> EarthWall.life(r) / 20.0),
						stat("damage", Unit.DAMAGE, (r, lv) -> r.is(EarthWall.THORNS) ? Trees.damage(EarthWall.THORN_DAMAGE, Slots.MEDIUM_A, r, lv)
								: r.is(EarthWall.CRUMBLE) ? Trees.damage(EarthWall.CRUMBLE_DAMAGE, Slots.MEDIUM_A, r, lv) : 0)),
				tree(SpellIds.EARTH_SPIKES, Slots.SMALL_B, "length",
						Fork.of(EarthSpikes.IMPALE).mul(Knob.DAMAGE, 2).mul(Knob.SIZE, 0.75F), Fork.of(EarthSpikes.UPLIFT),
						Fork.of(EarthSpikes.FIELD),
						Fork.of(EarthSpikes.PETRIFY).effect(MobEffects.SLOWNESS, 40, 5).effect(MobEffects.WEAKNESS, 80, 0),
						hit(EarthSpikes.DAMAGE, Slots.SMALL_B),
						stat("length", Unit.BLOCKS, (r, lv) -> EarthSpikes.length(0, r)),
						stat("lines", Unit.COUNT, (r, lv) -> r.is(EarthSpikes.FIELD) ? 3 : 1),
						stat("lift", Unit.PERCENT, (r, lv) -> r.is(EarthSpikes.UPLIFT) ? 200 : 100)),
				tree(SpellIds.QUAKE, Slots.MEDIUM_B, "radius",
						Fork.of(Quake.AFTERSHOCK), Fork.of(Quake.FISSURE).effect(MobEffects.SLOWNESS, 60, 1), Fork.of(Quake.TECTONIC),
						Fork.of(Quake.PULL),
						hit(Quake.DAMAGE, Slots.MEDIUM_B),
						stat("radius", Unit.BLOCKS, (r, lv) -> Quake.radius(0, r)),
						stat("rings", Unit.COUNT, (r, lv) -> r.is(Quake.AFTERSHOCK) ? 2 : 1),
						stat("throw", Unit.PERCENT, (r, lv) -> r.is(Quake.TECTONIC) ? 200 : 100)),
				tree(SpellIds.MOUNTAIN_FIST, Slots.ULTIMATE, "blast",
						Fork.of(MountainFist.STONE_SKIN), Fork.of(MountainFist.SKYWARD), Fork.of(MountainFist.COLOSSUS).mul(Knob.DAMAGE, 1.25F),
						Fork.of(MountainFist.AVALANCHE),
						hit(MountainFistGeometry.DAMAGE, Slots.ULTIMATE),
						stat("blast", Unit.BLOCKS, (r, lv) -> MountainFist.blast(r)),
						stat("spire", Unit.BLOCKS, (r, lv) -> MountainFist.height(Math.max(lv, Slots.unlockLevel(Slots.ULTIMATE)), r)),
						stat("launch", Unit.PERCENT, (r, lv) -> r.is(MountainFist.SKYWARD) ? 100 * MountainFist.SKYWARD_UP : 100),
						stat("boulders", Unit.COUNT, (r, lv) -> r.is(MountainFist.AVALANCHE) ? MountainFist.BOULDERS : 0)));
	}
}
