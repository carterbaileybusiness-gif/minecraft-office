// Adapted from Wildercord (MIT) https://github.com/defnotean/wildercord @9f5a21fe7964097492cd92ac64e4c4e05ef5236a
package local.carter.office.client.magic.fx;

import java.util.HashMap;
import java.util.Map;

import local.carter.office.client.magic.fx.MagicCircleRenderer.Frame;
import local.carter.office.magic.core.ChargeMath;
import local.carter.office.magic.core.Element;
import local.carter.office.magic.data.MagicAttachments;
import local.carter.office.magic.item.GrimoireItem;
import local.carter.office.magic.item.GrimoireLook;
import local.carter.office.magic.spell.SpellIds;

import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.Vec3;

import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;

/**
 * The circle a caster holds out while charging (Wildercord's ChargeCircles), drawn on every client for every player using a
 * grimoire, local and remote (their use state is synced): a vertical circle {@link #AHEAD} blocks in front of the eye and
 * {@link #BELOW} below it, facing them along their look. It has {@code ChargeMath.rings(useTicks)} rings, each fading in over 3
 * ticks as the charge reaches it, turning opposite ways, runes on rings 2 and 4; radius {@code 0.55 + 0.15 × rings}, alpha
 * {@link #ALPHA}, in the grimoire's element colour. At full charge it pulses, and a local {@code BEACON_POWER_SELECT} (pitch 1.6)
 * plays once. The local player charging Recall gets a gold circle at their feet instead (other players' selections are not synced).
 * Letting go fades it out in a few ticks.
 */
public final class ChargeCircles {
	private ChargeCircles() {}

	public static final double AHEAD = 1.5;
	public static final double BELOW = 0.25;
	public static final float ALPHA = 0.55F;

	/** The charging circle shown for each player, by entity id. */
	private static final Map<Integer, Circle> SHOWN = new HashMap<>();
	private static ClientLevel seen;

	public static void register() {
		ClientTickEvents.END_CLIENT_TICK.register(ChargeCircles::tick);
	}

	/** Whether {@code p} is charging a grimoire. */
	static boolean charging(Player p) {
		return p.isUsingItem() && GrimoireItem.isGrimoire(p.getUseItem());
	}

	/** Tick at which ring {@code k} (0-based) appears: when {@code ChargeMath.rings} first reaches {@code k + 1}. */
	public static int ringAppears(int k) {
		if (k <= 0) return 0;
		return ChargeMath.TAP_TICKS + Mth.ceil(k * (ChargeMath.FULL_TICKS - ChargeMath.TAP_TICKS) / 4F);
	}

	/** Circles showing (the game tests read it). */
	public static int showing() {
		SHOWN.values().removeIf(c -> !c.isAlive());
		return SHOWN.size();
	}

	private static void tick(Minecraft mc) {
		ClientLevel level = mc.level;
		if (level != seen) {
			seen = level;
			SHOWN.clear();
		}
		if (level == null) return;
		SHOWN.values().removeIf(c -> !c.isAlive());
		for (Player p : level.players()) {
			if (!charging(p)) continue;
			Circle c = SHOWN.get(p.getId());
			if (c != null && !c.released) continue;
			boolean feet = p == mc.player && MagicAttachments.get(p).selected().filter(SpellIds.RECALL::equals).isPresent();
			c = new Circle(level, p, feet);
			SHOWN.put(p.getId(), c);
			mc.particleEngine.add(c);
		}
	}

	/** One player's charging circle. */
	static final class Circle extends CircleParticle {
		private final Player player;
		private final boolean feet;
		private boolean released;
		private boolean fullPlayed;
		private float heldTicks;
		private int fullAt = -1;

		Circle(ClientLevel level, Player player, boolean feet) {
			super(level, player.getEyePosition(), player.getId());
			this.player = player;
			this.feet = feet;
			Element e = GrimoireLook.element(player.getUseItem()).orElse(null);
			frame.rgb = feet ? RecallFx.GOLD : DefaultFx.colour(e);
			frame.glyph = feet || e == null ? MagicCircleRenderer.Glyph.STAR : MagicCircleRenderer.Glyph.of(e);
		}

		@Override
		public void tick() {
			super.tick();
			if (released) return;
			if (player.isRemoved() || !charging(player)) {
				released = true;
				fadeOut();
				return;
			}
			// The culling centre: where the circle stands (its reach covers it from there).
			anchor(feet ? player.position() : player.getEyePosition().add(player.getViewVector(1F).scale(AHEAD)));
			int ticks = player.getTicksUsingItem();
			if (!fullPlayed && ticks >= ChargeMath.FULL_TICKS) {
				fullPlayed = true;
				fullAt = age;
				level.playLocalSound(player.getX(), player.getEyeY(), player.getZ(), SoundEvents.BEACON_POWER_SELECT, SoundSource.PLAYERS, 0.7F, 1.6F, false);
			}
		}

		@Override
		protected boolean pose(float partial, Frame f) {
			if (!released) {
				heldTicks = player.getTicksUsingItem(partial);
			}
			float t = heldTicks;
			int rings = ChargeMath.rings((int) t);
			f.rings = rings;
			for (int k = 0; k < rings; k++) {
				f.ringAge[k] = t - ringAppears(k);
			}
			Vec3 at;
			if (feet) {
				at = player.getPosition(partial).add(0, 0.06, 0);
				MagicCircleRenderer.orientation(MagicCircleRenderer.basis(0, 1, 0), f.plane);
			} else {
				Vec3 look = player.getViewVector(partial);
				at = player.getEyePosition(partial).add(look.scale(AHEAD)).add(0, -BELOW, 0);
				// Facing the caster, so they read it the right way round.
				MagicCircleRenderer.orientation(MagicCircleRenderer.basis(-look.x, -look.y, -look.z), f.plane);
			}
			f.x = at.x;
			f.y = at.y;
			f.z = at.z;
			float now = age + partial;
			boolean full = fullAt >= 0;
			// At full charge: a swell as it lands, then a steady pulse.
			float swell = full ? Math.max(0F, 1F - (now - fullAt) / 6F) : 0F;
			f.flare = full ? Math.max(swell, 0.5F + 0.5F * Mth.sin(now * 0.45F)) : 0F;
			f.scale = 1F + 0.08F * swell;
			f.alpha = ALPHA * leaving(partial);
			f.time = now;
			return f.alpha > 0.01F;
		}
	}
}
