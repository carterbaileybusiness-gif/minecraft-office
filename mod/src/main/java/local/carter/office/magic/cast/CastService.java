package local.carter.office.magic.cast;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Predicate;

import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import local.carter.office.magic.core.ChargeMath;
import local.carter.office.magic.core.Dir;
import local.carter.office.magic.core.Element;
import local.carter.office.magic.core.LevelCurve;
import local.carter.office.magic.core.ManaMath;
import local.carter.office.magic.core.Slots;
import local.carter.office.magic.data.MagicAttachments;
import local.carter.office.magic.data.MagicData;
import local.carter.office.magic.net.MagicServer;
import local.carter.office.magic.spell.ActiveEffects;
import local.carter.office.magic.spell.CastContext;
import local.carter.office.magic.spell.CastOutcome;
import local.carter.office.magic.spell.Motion;
import local.carter.office.magic.spell.Projectiles;
import local.carter.office.magic.spell.Spell;
import local.carter.office.magic.spell.SpellCost;
import local.carter.office.magic.spell.SpellRegistry;

import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;

/**
 * The server cast pipeline. {@link #cast} runs every check in order (element bound, spell element, unlocked, channeling,
 * cooldown, mana, XP levels), then the spell; only a {@link CastOutcome.Cast} spends anything. {@link #answer} lands in Task 6.
 */
public final class CastService {
	public enum Result { CAST, CHALLENGED, FIZZLED, NO_ELEMENT, WRONG_ELEMENT, LOCKED, COOLDOWN, NO_MANA, NO_LEVELS, BUSY }

	/** {@code CastFx} range in blocks; ultimates reach twice as far. */
	public static final double FX_RANGE = 64;
	public static final double FX_RANGE_ULTIMATE = 128;
	/** The fizzle message that makes the result {@link Result#BUSY} instead of {@link Result#FIZZLED}. */
	public static final String BUSY_KEY = "magic.office.busy";

	private static final Logger LOGGER = LoggerFactory.getLogger("office");
	private static final AtomicLong CAST_IDS = new AtomicLong();
	/** Player → game time of their last successful cast (mana regen pauses after it). */
	private static final Map<UUID, Long> LAST_CAST = new HashMap<>();
	/** True while the player is channeling (Recall, Task 7 installs {@code RecallSpell::channeling}): every cast is BUSY. */
	private static Predicate<ServerPlayer> channeling = p -> false;

	private CastService() {}

	/** What {@link #check} decided: a failure, or the charge and mana the cast will use. */
	record Checked(@Nullable Result failure, float charge, int mana) {
		static Checked fail(Result r) {
			return new Checked(r, 0, 0);
		}
	}

	/** Task 7: the channel check ({@code RecallSpell::channeling}). */
	public static void setChannelCheck(Predicate<ServerPlayer> check) {
		channeling = check;
	}

	/**
	 * Casts {@code spell} at {@code charge} (0..1; ultimates always 1) after every check. Failures show their message on the
	 * action bar and spend nothing.
	 */
	public static Result cast(ServerPlayer p, Spell spell, float charge) {
		Checked c = check(p, spell, charge);
		if (c.failure() != null) return c.failure();
		// Ultimates: Task 6 sends them to SignChallenges.issue here (CHALLENGED) and runs them from answer() after a re-check.
		// Until it lands they cast directly, at charge 1 for base mana and their XP levels.
		return run(p, spell, c.charge(), c.mana());
	}

	/** The grimoire was released after {@code ticksHeld}: cast the selected spell (Recall ignores charge, ultimates use 1). */
	public static void release(ServerPlayer p, int ticksHeld) {
		Optional<Identifier> selected = MagicAttachments.get(p).selected();
		if (selected.isEmpty()) return;
		Optional<Spell> spell = SpellRegistry.get(selected.get());
		if (spell.isEmpty()) return;
		Spell s = spell.get();
		float charge = s.slot() == Slots.RECALL ? 0f : isUltimate(s) ? 1f : ChargeMath.charge(ticksHeld);
		cast(p, s, charge);
	}

	/** The player's hand-sign answer for challenge {@code id}. */
	public static Result answer(ServerPlayer p, int id, Dir[] dirs) {
		throw new UnsupportedOperationException("CastService.answer lands in Task 6");
	}

	/** Once per server tick: mana regen, lingering effects, projectiles and dash no-fall windows. */
	public static void tick(MinecraftServer server) {
		for (ServerPlayer p : server.getPlayerList().getPlayers()) regen(p);
		ActiveEffects.tick();
		Projectiles.tick();
		Motion.tick(server);
	}

	/** A player left: forget their regen pause. */
	public static void forget(ServerPlayer p) {
		LAST_CAST.remove(p.getUUID());
	}

	/** Server stopping: end lingering effects (their {@code end} runs), drop projectiles and per-player state. */
	public static void stop() {
		ActiveEffects.endAll();
		Projectiles.clear();
		Motion.clear();
		LAST_CAST.clear();
	}

	static boolean isUltimate(Spell s) {
		return s.slot() == Slots.ULTIMATE;
	}

	/** Every check, in order, with its action-bar message. Task 6 re-runs it when a hand-sign answer arrives. */
	static Checked check(ServerPlayer p, Spell spell, float charge) {
		MagicData d = MagicAttachments.get(p);
		if (d.element().isEmpty()) return refuse(p, Result.NO_ELEMENT, Component.translatable("magic.office.choose"));
		Element bound = d.element().get();
		if (spell.element() != null && spell.element() != bound) {
			return refuse(p, Result.WRONG_ELEMENT, Component.translatable("magic.office.bound", Component.translatable("element.office." + bound.key())));
		}
		int unlock = Slots.unlockLevel(spell.slot());
		if (d.level() < unlock) return refuse(p, Result.LOCKED, Component.translatable("magic.office.locked", name(spell), unlock));
		if (channeling.test(p)) return refuse(p, Result.BUSY, Component.translatable(BUSY_KEY));
		long left = d.cooldownLeft(spell.id(), p.level().getGameTime());
		if (left > 0) return refuse(p, Result.COOLDOWN, Component.translatable("magic.office.cooldown", name(spell), left / 20.0));

		SpellCost cost = spell.cost();
		boolean ultimate = isUltimate(spell);
		int mana = MagicAttachments.mana(p);
		float c = ultimate ? 1f : ChargeMath.affordableCharge(mana, cost.mana(), Math.clamp(charge, 0f, 1f));
		int need = ChargeMath.cost(cost.mana(), ultimate ? 1f : Math.max(c, 0f), ultimate);
		if (c < 0 || need > mana) {
			return refuse(p, Result.NO_MANA, Component.translatable("magic.office.no_mana", mana, ChargeMath.cost(cost.mana(), 0f, ultimate)));
		}
		if (ultimate && p.experienceLevel < cost.xpLevels()) {
			return refuse(p, Result.NO_LEVELS, Component.translatable("magic.office.no_levels", name(spell), cost.xpLevels()));
		}
		return new Checked(null, c, need);
	}

	/**
	 * Runs {@code spell.cast}. A fizzle spends nothing ({@code magic.office.busy} → BUSY, else FIZZLED). A cast spends
	 * {@code mana} (and XP levels for ultimates), sets the cooldown unless the spell manages its own (never lowering one),
	 * adds grimoire xp (title on level-up), sends the one fx and starts the regen pause.
	 */
	static Result run(ServerPlayer p, Spell spell, float charge, int mana) {
		ServerLevel level = p.level();
		MagicData before = MagicAttachments.get(p);
		CastContext ctx = new CastContext(p, level, spell, charge, ChargeMath.power(charge, before.level()), before.level(),
				level.getRandom().nextLong(), CAST_IDS.incrementAndGet(), MagicServer.kit());
		CastOutcome outcome;
		try {
			outcome = spell.cast(ctx);
		} catch (RuntimeException ex) {
			LOGGER.error("[office] Spell {} failed while casting; treating it as a fizzle", spell.id(), ex);
			outcome = CastOutcome.fizzle("magic.office.fizzle");
		}
		if (outcome instanceof CastOutcome.Fizzle f) {
			MagicServer.sender().actionBar(p, Component.translatable(f.messageKey()));
			return BUSY_KEY.equals(f.messageKey()) ? Result.BUSY : Result.FIZZLED;
		}
		CastOutcome.Cast done = (CastOutcome.Cast) outcome;
		SpellCost cost = spell.cost();
		long now = level.getGameTime();

		MagicAttachments.setMana(p, MagicAttachments.mana(p) - mana);
		if (isUltimate(spell) && cost.xpLevels() > 0) p.giveExperienceLevels(-cost.xpLevels());

		MagicData d = MagicAttachments.get(p); // re-read: the spell may have written its own data
		if (cost.cooldownTicks() > 0 && !spell.managesOwnCooldown()) {
			Long existing = d.readyAt().get(spell.id());
			long readyAt = Math.max(existing == null ? Long.MIN_VALUE : existing, now + cost.cooldownTicks());
			d = d.withReady(spell.id(), readyAt);
		}
		LevelCurve.Progress progress = LevelCurve.add(d.level(), d.xp(), cost.xp());
		int oldLevel = d.level();
		d = d.withLevel(progress.level()).withXp(progress.xp());
		MagicAttachments.set(p, d);
		if (progress.level() > oldLevel) levelUp(p, d, oldLevel);

		MagicServer.sender().fx(level, done.fx(), isUltimate(spell) ? FX_RANGE_ULTIMATE : FX_RANGE);
		LAST_CAST.put(p.getUUID(), now);
		return Result.CAST;
	}

	private static void levelUp(ServerPlayer p, MagicData d, int oldLevel) {
		p.level().playSound(null, p.blockPosition(), SoundEvents.PLAYER_LEVELUP, SoundSource.PLAYERS, 1f, 1f);
		Spell unlocked = null;
		for (int slot = 0; slot < Slots.WHEEL_SLICES; slot++) {
			int at = Slots.unlockLevel(slot);
			if (at > oldLevel && at <= d.level()) {
				Optional<Spell> s = SpellRegistry.at(d.element().orElseThrow(), slot);
				if (s.isPresent()) unlocked = s.get();
			}
		}
		Component subtitle = unlocked != null ? Component.translatable("magic.office.unlocked", name(unlocked))
				: Component.translatable("magic.office.stronger");
		MagicServer.sender().title(p, Component.translatable("magic.office.level_up", d.level()), subtitle);
	}

	private static void regen(ServerPlayer p) {
		if (!p.isAlive()) return;
		int level = MagicAttachments.get(p).level();
		int mana = MagicAttachments.mana(p);
		if (mana >= ManaMath.max(level)) return;
		long now = p.level().getGameTime();
		Long last = LAST_CAST.get(p.getUUID());
		if (last != null && now - last < ManaMath.REGEN_PAUSE_TICKS) return;
		if (now % ManaMath.regenInterval(level) == 0) MagicAttachments.setMana(p, mana + 1);
	}

	private static Checked refuse(ServerPlayer p, Result r, Component message) {
		MagicServer.sender().actionBar(p, message);
		return Checked.fail(r);
	}

	/** The spell's display name ({@code spell.<namespace>.<path>}). */
	public static Component name(Spell s) {
		return Component.translatable("spell." + s.id().getNamespace() + "." + s.id().getPath());
	}
}
