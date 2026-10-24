package local.carter.office.client.magic.fx;

import java.util.Optional;

import org.jspecify.annotations.Nullable;

import local.carter.office.magic.core.Element;
import local.carter.office.magic.core.Slots;
import local.carter.office.magic.item.GrimoireItem;
import local.carter.office.magic.item.GrimoireLook;
import local.carter.office.magic.net.FxEvent;
import local.carter.office.magic.spell.SpellIds;

import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.particles.BlockParticleOption;
import net.minecraft.core.particles.ParticleOptions;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.resources.Identifier;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.Vec3;

/**
 * What a spell without its own visuals draws: a summoning circle flashing open in front of the caster (facing them, rings by
 * charge), and a burst of its element's particles and motes out along the cast. Also the element look shared by the fx core:
 * {@link #element}, {@link #particle}, {@link #drift}.
 */
public final class DefaultFx implements SpellFx {
	@Override
	public void play(FxEvent e, ClientLevel level, FxKit kit) {
		Element element = element(e.spell(), level.getEntity(e.casterId()));
		int rgb = colour(element);
		Vec3 dir = direction(e);
		float c = Mth.clamp(e.charge(), 0F, 1F);
		// Far enough out that the caster sees the whole circle open, not a wall of light in their face.
		Vec3 at = e.origin().add(dir.scale(1.7)).add(0, -0.15, 0);
		int rings = 2 + Math.round(c * 3);
		kit.circle(at, dir.reverse(), 0xFF000000 | rgb, 0.55F * (1F + c), rings, 16 + Math.round(8 * c));
		Vec3 front = at.add(dir.scale(0.3));
		kit.burst(front, particle(element), 6 + Math.round(10 * c), 0.07 + 0.08 * c);
		kit.motes(front, MagicCircleRenderer.lighter(rgb, 0.3F), 12 + Math.round(14 * c), 0.25, dir.scale(0.16 + 0.14 * c), 0.09,
				GlowMote.Drift.COAST, 0.1F, 14);
		level.playLocalSound(at.x, at.y, at.z, SoundEvents.ILLUSIONER_CAST_SPELL, SoundSource.PLAYERS, 0.5F + 0.3F * c,
				1.25F + 0.2F * (1 - c) + level.getRandom().nextFloat() * 0.1F, false);
	}

	/** The event's direction, normalised (straight ahead if it has none). */
	static Vec3 direction(FxEvent e) {
		Vec3 d = e.dir();
		double l = d.length();
		return l < 1.0E-6 || !Double.isFinite(l) ? new Vec3(0, 0, 1) : d.scale(1 / l);
	}

	/**
	 * The element a spell belongs to: from its id, else (a spell no element owns, like a test probe) from the caster's grimoire;
	 * null when neither says (Recall, or a caster out of sight).
	 */
	static @Nullable Element element(Identifier spell, @Nullable Entity caster) {
		for (Element e : Element.values()) {
			for (int slot = 0; slot <= Slots.ULTIMATE; slot++) {
				if (SpellIds.at(e, slot).equals(spell)) return e;
			}
		}
		if (SpellIds.RECALL.equals(spell) || !(caster instanceof LivingEntity living)) return null;
		for (var stack : new net.minecraft.world.item.ItemStack[] {living.getOffhandItem(), living.getMainHandItem()}) {
			if (GrimoireItem.isGrimoire(stack)) {
				Optional<Element> el = GrimoireLook.element(stack);
				if (el.isPresent()) return el.get();
			}
		}
		return null;
	}

	/** An element's colour (RGB), Recall gold for none. */
	static int colour(@Nullable Element e) {
		return (e == null ? Element.RECALL_ARGB : e.argb()) & 0xFFFFFF;
	}

	/** The vanilla particle that reads as the element (Recall: end rod sparkles). */
	static ParticleOptions particle(@Nullable Element e) {
		if (e == null) return ParticleTypes.END_ROD;
		return switch (e) {
			case FIRE -> ParticleTypes.FLAME;
			case WATER -> ParticleTypes.SPLASH;
			case WIND -> ParticleTypes.CLOUD;
			case LIGHTNING -> ParticleTypes.ELECTRIC_SPARK;
			case EARTH -> new BlockParticleOption(ParticleTypes.BLOCK, Blocks.COARSE_DIRT.defaultBlockState());
			case SHADOW -> ParticleTypes.REVERSE_PORTAL;
		};
	}

	/** How the element's motes move. */
	static GlowMote.Drift drift(@Nullable Element e) {
		if (e == null) return GlowMote.Drift.RISE;
		return switch (e) {
			case FIRE -> GlowMote.Drift.RISE;
			case WATER, EARTH -> GlowMote.Drift.FALL;
			case WIND -> GlowMote.Drift.SWIRL;
			case LIGHTNING -> GlowMote.Drift.FLICKER;
			case SHADOW -> GlowMote.Drift.SINK;
		};
	}
}
