package local.carter.office.magic.tree;

import static local.carter.office.magic.tree.Trees.hit;
import static local.carter.office.magic.tree.Trees.size;
import static local.carter.office.magic.tree.Trees.stat;
import static local.carter.office.magic.tree.Trees.tree;

import java.util.List;

import local.carter.office.magic.core.Slots;
import local.carter.office.magic.spell.SpellIds;
import local.carter.office.magic.spell.wind.AirStep;
import local.carter.office.magic.spell.wind.BladeWind;
import local.carter.office.magic.spell.wind.BladeWindGeometry;
import local.carter.office.magic.spell.wind.DrillGeometry;
import local.carter.office.magic.spell.wind.Gust;
import local.carter.office.magic.spell.wind.Tornado;
import local.carter.office.magic.spell.wind.TornadoGeometry;
import local.carter.office.magic.spell.wind.WindSphere;
import local.carter.office.magic.tree.SpellTree.Stat.Unit;

import net.minecraft.world.phys.Vec3;

/** Wind's five trees (the plan's Wind table). */
final class WindTrees {
	private WindTrees() {}

	static List<SpellTree> all() {
		return List.of(
				tree(SpellIds.GUST, Slots.SMALL_A, "range",
						Fork.of(Gust.VACUUM), Fork.of(Gust.UPDRAFT), Fork.of(Gust.CYCLONE), Fork.of(Gust.WIND_WALL),
						stat("damage", Unit.DAMAGE, (r, lv) -> Gust.damage(0, r)),
						size("range", Unit.BLOCKS, Gust.RANGE, 99),
						stat("cone", Unit.DEGREES, (r, lv) -> 2 * Gust.halfAngle(r)),
						stat("knockback", Unit.PERCENT, (r, lv) -> r.is(Gust.VACUUM) ? 0 : 100 * r.mul(Knob.DAMAGE) * r.mul(Knob.KNOCK)),
						stat("lift", Unit.BLOCKS, (r, lv) -> r.is(Gust.VACUUM) ? 0 : AirStep.apex(r.is(Gust.UPDRAFT) ? Gust.UPDRAFT_UP : Gust.UP)),
						stat("wall", Unit.SECONDS, (r, lv) -> r.is(Gust.WIND_WALL) ? Gust.WALL_TICKS / 20.0 : 0)),
				tree(SpellIds.BLADE_WIND, Slots.MEDIUM_A, "blades",
						Fork.of(BladeWind.WIDE), Fork.of(BladeWind.BOOMERANG), Fork.of(BladeWind.CLEAVE).mul(Knob.DAMAGE, 1.8F).add(Knob.COUNT, -2),
						Fork.of(BladeWind.TEMPEST),
						hit(BladeWind.DAMAGE, Slots.MEDIUM_A),
						stat("blades", Unit.COUNT, (r, lv) -> BladeWindGeometry.blades(0, r)),
						stat("fan", Unit.DEGREES, (r, lv) -> 2 * BladeWindGeometry.spread(0, r)),
						stat("range", Unit.BLOCKS, (r, lv) -> BladeWindGeometry.SPEED * BladeWindGeometry.maxTicks(r)),
						stat("strikes", Unit.COUNT, (r, lv) -> BladeWindGeometry.returns(r) ? 2 : 1)),
				tree(SpellIds.AIR_STEP, Slots.SMALL_B, "height",
						Fork.of(AirStep.AIR_DASH), Fork.of(AirStep.LONG_JUMP), Fork.of(AirStep.SECOND_WIND).mul(Knob.COOLDOWN, 0.4F),
						Fork.of(AirStep.SHOCKWAVE),
						stat("height", Unit.BLOCKS, (r, lv) -> AirStep.apex(AirStep.velocity(new Vec3(0, 0, 1), 0, 0, r).y)),
						stat("forward", Unit.PERCENT, (r, lv) -> r.is(AirStep.LONG_JUMP) ? 100 * AirStep.LONG_JUMP_FORWARD : 100),
						stat("damage", Unit.DAMAGE, (r, lv) -> r.is(AirStep.SHOCKWAVE) ? Trees.damage(AirStep.SHOCKWAVE_DAMAGE, Slots.SMALL_B, r, lv) : 0)),
				tree(SpellIds.TORNADO, Slots.MEDIUM_B, "radius",
						Fork.of(Tornado.LONG_STORM), Fork.of(Tornado.DEBRIS), Fork.of(Tornado.TWIN), Fork.of(Tornado.EYE),
						stat("damage", Unit.DAMAGE, (r, lv) -> TornadoGeometry.HURT * r.mul(Knob.DAMAGE)),
						stat("debris", Unit.DAMAGE, (r, lv) -> r.is(Tornado.DEBRIS) ? Trees.damage(Tornado.DEBRIS_DAMAGE, Slots.MEDIUM_B, r, lv) : 0),

						stat("radius", Unit.BLOCKS, (r, lv) -> TornadoGeometry.radius(0, r)),
						stat("life", Unit.SECONDS, (r, lv) -> Tornado.life(0, r) / 20.0),
						stat("tornadoes", Unit.COUNT, (r, lv) -> r.is(Tornado.TWIN) ? 2 : 1)),
				tree(SpellIds.WIND_SPHERE, Slots.ULTIMATE, "blast",
						Fork.of(WindSphere.LONG_DRILL), Fork.of(WindSphere.VORTEX), Fork.of(WindSphere.SHURIKEN), Fork.of(WindSphere.SHOCKWAVE),
						hit(DrillGeometry.DAMAGE, Slots.ULTIMATE),
						stat("blast", Unit.BLOCKS, (r, lv) -> WindSphere.blast(r)),
						stat("drill", Unit.BLOCKS, (r, lv) -> WindSphere.length(Math.max(lv, Slots.unlockLevel(Slots.ULTIMATE)), r)),
						stat("ring", Unit.BLOCKS, (r, lv) -> r.is(WindSphere.SHOCKWAVE) ? WindSphere.SHOCKWAVE_RADIUS : 0)));
	}
}
