package local.carter.office.client.magic.fx;

import java.util.Optional;

import local.carter.office.magic.core.Element;
import local.carter.office.magic.item.GrimoireItem;
import local.carter.office.magic.item.GrimoireLook;

import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.component.DataComponents;
import net.minecraft.server.level.ParticleStatus;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.HumanoidArm;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.CustomModelData;
import net.minecraft.world.phys.Vec3;

import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;

/**
 * A tier-3 grimoire's aura: held in any player's hand within {@link #RANGE} blocks, it gives off one mote of its element every
 * {@link #EVERY} ticks (embers rise, water drips, wind wanders, sparks flicker, grit falls, shadow sinks). Off on Minimal particles.
 */
public final class GrimoireParticles {
	private GrimoireParticles() {}

	public static final double RANGE = 32;
	public static final int EVERY = 4;
	private static int clock;

	public static void register() {
		ClientTickEvents.END_CLIENT_TICK.register(GrimoireParticles::tick);
	}

	/** Whether {@code stack} is a tier-3 grimoire (its look's string 0 is {@code t3}). */
	static boolean tierThree(ItemStack stack) {
		if (!GrimoireItem.isGrimoire(stack)) return false;
		CustomModelData look = stack.get(DataComponents.CUSTOM_MODEL_DATA);
		return look != null && "t3".equals(look.getString(0));
	}

	private static void tick(Minecraft mc) {
		ClientLevel level = mc.level;
		if (level == null || mc.isPaused() || ++clock % EVERY != 0) return;
		if (mc.options.particles().get() == ParticleStatus.MINIMAL) return;
		Vec3 cam = mc.gameRenderer.mainCamera().position();
		for (Player p : level.players()) {
			if (p.isInvisible() || p.distanceToSqr(cam) > RANGE * RANGE) continue;
			emit(mc, level, p, p.getMainHandItem(), p.getMainArm());
			emit(mc, level, p, p.getOffhandItem(), p.getMainArm().getOpposite());
		}
	}

	private static void emit(Minecraft mc, ClientLevel level, Player p, ItemStack stack, HumanoidArm arm) {
		if (!tierThree(stack)) return;
		Optional<Element> element = GrimoireLook.element(stack);
		if (element.isEmpty()) return;
		float yaw = p.yBodyRot * Mth.DEG_TO_RAD;
		Vec3 forward = new Vec3(-Mth.sin(yaw), 0, Mth.cos(yaw));
		Vec3 right = new Vec3(-Mth.cos(yaw), 0, -Mth.sin(yaw));
		double side = arm == HumanoidArm.RIGHT ? 1 : -1;
		Vec3 hand = p.position().add(right.scale(0.38 * side)).add(forward.scale(0.22)).add(0, p.isCrouching() ? 0.55 : 0.78, 0);
		var r = level.getRandom();
		Vec3 at = hand.add((r.nextDouble() - 0.5) * 0.2, (r.nextDouble() - 0.5) * 0.2, (r.nextDouble() - 0.5) * 0.2);
		GlowMote.Drift drift = DefaultFx.drift(element.get());
		Vec3 v = switch (drift) {
			case RISE -> new Vec3(0, 0.02, 0);
			case SWIRL -> new Vec3((r.nextDouble() - 0.5) * 0.04, 0.01, (r.nextDouble() - 0.5) * 0.04);
			default -> Vec3.ZERO;
		};
		int rgb = MagicCircleRenderer.lighter(element.get().argb() & 0xFFFFFF, 0.25F);
		mc.particleEngine.add(new GlowMote(level, at, v, rgb, 0.07F, 22, drift, r.nextInt(3) == 0));
	}
}
