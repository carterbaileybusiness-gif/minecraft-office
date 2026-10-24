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
import local.carter.office.magic.core.HandSigns;
import local.carter.office.magic.core.LevelCurve;
import local.carter.office.magic.core.ManaMath;
import local.carter.office.magic.core.Slots;
import local.carter.office.magic.data.MagicAttachments;
import local.carter.office.magic.data.MagicData;
import local.carter.office.magic.item.GrimoireItem;
import local.carter.office.magic.net.MagicServer;
import local.carter.office.magic.spell.ActiveEffects;
import local.carter.office.magic.spell.CastContext;
import local.carter.office.magic.spell.CastOutcome;
import local.carter.office.magic.spell.Motion;
import local.carter.office.magic.spell.Projectiles;
import local.carter.office.magic.spell.Spell;
import local.carter.office.magic.spell.SpellCost;
import local.carter.office.magic.spell.SpellRegistry;
import local.carter.office.magic.tree.Knob;
import local.carter.office.magic.tree.SpellRank;

import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.entity.player.Player;

/**
 * The server cast pipeline. {@link #cast} runs every check in order (element bound, spell element, unlocked, channeling,
 * cooldown, mana, XP levels), then the spell; only a {@link CastOutcome.Cast} spends anything. An ultimate is not cast
 * directly: {@link #cast} issues a hand-sign challenge ({@link SignChallenges}) and {@link #answer} casts it after a re-check,
 * or fizzles it.
 */
public final class CastService {
	/** {@link #IGNORED}: an answer for a challenge that is not open (unknown id, discarded or already expired); nothing happened. */
	public enum Result { CAST, CHALLENGED, FIZZLED, NO_ELEMENT, WRONG_ELEMENT, LOCKED, COOLDOWN, NO_MANA, NO_LEVELS, BUSY, IGNORED }

	/** {@code CastFx} range in blocks; ultimates reach twice as far. */
	public static final double FX_RANGE = 64;
	public static final double FX_RANGE_ULTIMATE = 128;
	/** The fizzle message that makes the result {@link Result#BUSY} instead of {@link Result#FIZZLED}. */
	public static final String BUSY_KEY = "magic.office.busy";
	/** The fizzle message of a spell that threw while casting. */
	public static final String SPELL_FAILED_KEY = "magic.office.spell_failed";
	/** Action-bar message of a hand-sign fizzle (wrong, late or no longer affordable). */
	public static final String SIGN_FIZZLE_KEY = "magic.office.fizzle";
	/** Fizzle message when the signs were right but the cast no longer could be (its argument is the reason). */
	public static final String SIGN_FIZZLE_REASON_KEY = "magic.office.fizzle_reason";
	/** Cooldown after a hand-sign fizzle, in ticks. */
	public static final int FIZZLE_COOLDOWN_TICKS = 100;
	/**
	 * Mana regen is added (and so synced to the client) only on ticks that are a multiple of this: at most 2 updates a second per
	 * player instead of one every 2–6 ticks. The rate is unchanged: each step adds every point of its 10 ticks at once.
	 */
	public static final int REGEN_STEP_TICKS = 10;

	private static final Logger LOGGER = LoggerFactory.getLogger("office");
	private static final AtomicLong CAST_IDS = new AtomicLong();
	/** Player → game time of their last successful cast (mana regen pauses after it). */
	private static final Map<UUID, Long> LAST_CAST = new HashMap<>();
	/** True while the player is channeling (Recall, Task 7 installs {@code RecallSpell::channeling}): every cast is BUSY. */
	private static Predicate<ServerPlayer> channeling = p -> false;
	/**
	 * Player → the spell selected when their current grimoire charge started (release casts that one). Only meaningful while the
	 * player really is using a grimoire: {@link #charging} drops an entry vanilla left behind.
	 */
	private static final Map<UUID, Identifier> CHARGING = new HashMap<>();

	private CastService() {}

	/** What {@link #check} decided: a failure with its message, or the charge and mana the cast will use. */
	record Checked(@Nullable Result failure, float charge, int mana, @Nullable Component message) {
		Checked(@Nullable Result failure, float charge, int mana) {
			this(failure, charge, mana, null);
		}

		static Checked fail(Result r, Component message) {
			return new Checked(r, 0, 0, message);
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
		if (isUltimate(spell)) {
			// Nothing is spent yet: answer() re-checks and casts (or fizzles) when the signs come back.
			SignChallenges.issue(p, spell, c.charge());
			return Result.CHALLENGED;
		}
		return run(p, spell, c.charge(), c.mana());
	}

	/**
	 * The grimoire was released after {@code ticksHeld}: cast the spell captured when the charge started ({@link #beginCharge}),
	 * else the selected one. Recall ignores charge; ultimates use 1.
	 */
	public static void release(ServerPlayer p, int ticksHeld) {
		Identifier captured = CHARGING.remove(p.getUUID());
		Optional<Identifier> selected = captured != null ? Optional.of(captured) : MagicAttachments.get(p).selected();
		if (selected.isEmpty()) return;
		Optional<Spell> spell = SpellRegistry.get(p.getUUID(), selected.get());
		if (spell.isEmpty()) return;
		Spell s = spell.get();
		float charge = s.slot() == Slots.RECALL ? 0f : isUltimate(s) ? 1f : ChargeMath.charge(ticksHeld);
		cast(p, s, charge);
	}

	/** A grimoire charge started with {@code spell} selected; changing the selection mid-charge does not change what is cast. */
	public static void beginCharge(ServerPlayer p, Identifier spell) {
		CHARGING.put(p.getUUID(), spell);
	}

	/** The charge ended without a cast (cancelled, death, disconnect, dimension change). */
	public static void endCharge(ServerPlayer p) {
		CHARGING.remove(p.getUUID());
	}

	/**
	 * True while {@code p} is charging a grimoire cast (Travel refuses to start meanwhile): the server's own item use, with a
	 * grimoire. Vanilla ends a use without a release (no {@code releaseUsing}) when the hands are swapped (F), the book is
	 * dropped or the used stack changes, so a {@link #CHARGING} entry can outlive its charge; it is dropped here.
	 */
	public static boolean charging(ServerPlayer p) {
		if (p.isUsingItem() && GrimoireItem.isGrimoire(p.getUseItem())) return true;
		CHARGING.remove(p.getUUID());
		return false;
	}

	/**
	 * The client dropped its charge without a release (it started typing into a monitor, {@code CancelCharge}): the server stops
	 * the use too, so other players stop seeing the circle and Travel is not BUSY. Nothing is cast or spent.
	 */
	public static void cancelCharge(ServerPlayer p) {
		if (p.isUsingItem() && GrimoireItem.isGrimoire(p.getUseItem())) p.stopUsingItem();
		CHARGING.remove(p.getUUID());
	}

	/**
	 * Why a grimoire charge for {@code spell} should not even start (both sides; the server re-checks everything on release):
	 * on cooldown, or not enough mana for the base cost (creative players never lack mana). Null = may charge.
	 */
	public static @Nullable Component chargeBlocker(Player p, MagicData d, Spell spell) {
		if (p instanceof ServerPlayer sp && channeling.test(sp)) return Component.translatable(BUSY_KEY);
		long left = d.cooldownLeft(spell.cooldownKey(p), p.level().getGameTime());
		if (left > 0) return cooldownMessage(spell, left);
		int mana = MagicAttachments.mana(p);
		int base = cost(d, spell).mana();
		if (!p.hasInfiniteMaterials() && mana < base) return noManaMessage(mana, base);
		return null;
	}

	/**
	 * {@code spell}'s skill-tree rank for the player whose data is {@code d}: their stored rank if the spell is their bound
	 * element's (Recall and other elements' spells cast at rank 0).
	 */
	public static SpellRank rank(MagicData d, Spell spell) {
		if (spell.element() == null || d.element().isEmpty() || spell.element() != d.element().get()) return SpellRank.none(spell.id());
		return d.rank(spell.id());
	}

	/** {@code spell}'s cost at the player's rank: mana × the rank's {@code MANA} knob, cooldown × its {@code COOLDOWN} knob (rounded). */
	public static SpellCost cost(MagicData d, Spell spell) {
		return cost(spell.cost(), rank(d, spell));
	}

	/** {@code base} at rank {@code r} (the Skill tree previews ranks not bought yet with it). */
	public static SpellCost cost(SpellCost base, SpellRank r) {
		if (r.rank() == 0) return base;
		return new SpellCost(Math.max(1, Math.round(base.mana() * r.mul(Knob.MANA))), Math.round(base.cooldownTicks() * r.mul(Knob.COOLDOWN)),
				base.xp(), base.xpLevels());
	}

	/**
	 * The player's hand-sign answer for challenge {@code id} (a partial or null answer is a miss). The id must be the player's
	 * open challenge, else the answer is {@link Result#IGNORED}. Right, not too fast ({@link HandSigns#MIN_MS_PER_SIGN} per
	 * arrow: no human types faster, a bot reading the seed does), within {@code limit + GRACE_TICKS}, still in casting stance
	 * (grimoire in the off hand, main hand empty), with element, unlock, cooldown, mana and XP levels re-checked now → the full
	 * ultimate cast ({@link #run}). Wrong, too fast, late, out of stance or no longer affordable → {@link Result#FIZZLED}
	 * ({@link #fizzle}).
	 */
	public static Result answer(ServerPlayer p, int id, Dir @Nullable [] dirs) {
		SignChallenges.Open c = SignChallenges.take(p, id);
		if (c == null) return Result.IGNORED;
		long now = p.level().getGameTime();
		if (c.late(now) || c.tooFast(now, System.nanoTime()) || !HandSigns.matches(c.expected(), dirs)) return fizzle(p, c.spell(), null);
		if (!GrimoireItem.isGrimoire(p.getOffhandItem()) || !p.getMainHandItem().isEmpty()) return fizzle(p, c.spell(), null);
		Checked k = evaluate(p, c.spell(), 1f);
		if (k.failure() != null) return fizzle(p, c.spell(), k.message());
		return run(p, c.spell(), k.charge(), k.mana());
	}

	/**
	 * A hand-sign miss: {@code HandSigns.fizzleMana(base)} mana spent (all that is left if less; creative players spend none),
	 * no XP levels, a {@link #FIZZLE_COOLDOWN_TICKS} cooldown (never lowering one), the extinguish sound and one action-bar
	 * message: {@link #SIGN_FIZZLE_KEY}, or {@link #SIGN_FIZZLE_REASON_KEY} with {@code reason} when the signs were right but
	 * the answer-time re-check failed.
	 */
	static Result fizzle(ServerPlayer p, Spell spell, @Nullable Component reason) {
		ServerLevel level = p.level();
		long now = level.getGameTime();
		if (!p.hasInfiniteMaterials()) {
			int mana = MagicAttachments.mana(p);
			int spent = Math.min(mana, HandSigns.fizzleMana(cost(MagicAttachments.get(p), spell).mana()));
			if (spent > 0) {
				MagicAttachments.setMana(p, mana - spent);
				LAST_CAST.put(p.getUUID(), now);
			}
		}
		MagicData d = MagicAttachments.get(p);
		Long existing = d.readyAt().get(spell.id());
		MagicAttachments.set(p, d.withReady(spell.id(), Math.max(existing == null ? Long.MIN_VALUE : existing, now + FIZZLE_COOLDOWN_TICKS)));
		level.playSound(null, p.blockPosition(), SoundEvents.FIRE_EXTINGUISH, SoundSource.PLAYERS, 0.8f, 1.1f);
		MagicServer.sender().actionBar(p, reason == null ? Component.translatable(SIGN_FIZZLE_KEY)
				: Component.translatable(SIGN_FIZZLE_REASON_KEY, reason));
		return Result.FIZZLED;
	}

	/**
	 * Once per server tick: unanswered hand signs fizzle, mana regen, lingering effects, projectiles and dash no-fall windows.
	 * Each part (and each player's fizzle and regen) is guarded: one that throws is logged and the rest still run.
	 */
	public static void tick(MinecraftServer server) {
		for (var expired : SignChallenges.expire(server)) guarded("hand-sign expiry", () -> fizzle(expired.getKey(), expired.getValue().spell(), null));
		for (ServerPlayer p : server.getPlayerList().getPlayers()) guarded("mana regen", () -> regen(p));
		guarded("lingering effects", ActiveEffects::tick);
		guarded("projectiles", Projectiles::tick);
		guarded("motion", () -> Motion.tick(server));
	}

	private static void guarded(String what, Runnable r) {
		try {
			r.run();
		} catch (RuntimeException e) {
			LOGGER.error("[office] Magic {} failed; skipped this tick", what, e);
		}
	}

	/** A player left: forget their regen pause, charge and hand-sign challenge. */
	public static void forget(ServerPlayer p) {
		LAST_CAST.remove(p.getUUID());
		CHARGING.remove(p.getUUID());
		SignChallenges.discard(p);
	}

	/** Server stopping: end lingering effects (their {@code end} runs), drop projectiles and per-player state. */
	public static void stop() {
		ActiveEffects.endAll();
		Projectiles.clear();
		Motion.clear();
		LAST_CAST.clear();
		CHARGING.clear();
		SignChallenges.clear();
	}

	static boolean isUltimate(Spell s) {
		return s.slot() == Slots.ULTIMATE;
	}

	/** Every check, in order; a failure shows its message on the action bar. */
	static Checked check(ServerPlayer p, Spell spell, float charge) {
		Checked c = evaluate(p, spell, charge);
		if (c.failure() != null && c.message() != null) MagicServer.sender().actionBar(p, c.message());
		return c;
	}

	/**
	 * {@link #check} without sending anything: {@link #answer} re-runs it after taking the challenge (so an open challenge,
	 * which makes every other cast BUSY, never blocks its own answer) and folds a failure's reason into the one fizzle message.
	 */
	static Checked evaluate(ServerPlayer p, Spell spell, float charge) {
		MagicData d = MagicAttachments.get(p);
		if (d.element().isEmpty()) return Checked.fail(Result.NO_ELEMENT, Component.translatable("magic.office.choose"));
		Element bound = d.element().get();
		if (spell.element() != null && spell.element() != bound) {
			return Checked.fail(Result.WRONG_ELEMENT,
					Component.translatable("magic.office.wrong_element", name(spell), Component.translatable("element.office." + bound.key())));
		}
		int unlock = Slots.unlockLevel(spell.slot());
		if (d.level() < unlock) return Checked.fail(Result.LOCKED, Component.translatable("magic.office.locked", name(spell), unlock));
		if (channeling.test(p)) return Checked.fail(Result.BUSY, Component.translatable(BUSY_KEY));
		if (SignChallenges.pending(p)) return Checked.fail(Result.BUSY, Component.translatable("magic.office.signs"));
		long left = d.cooldownLeft(spell.cooldownKey(p), p.level().getGameTime());
		if (left > 0) return Checked.fail(Result.COOLDOWN, cooldownMessage(spell, left));

		SpellCost cost = cost(d, spell);
		boolean ultimate = isUltimate(spell);
		float requested = ultimate ? 1f : Math.clamp(charge, 0f, 1f);
		if (p.hasInfiniteMaterials()) return new Checked(null, requested, 0); // creative: no mana, no XP levels
		int mana = MagicAttachments.mana(p);
		float c = ultimate ? 1f : ChargeMath.affordableCharge(mana, cost.mana(), requested);
		int need = ChargeMath.cost(cost.mana(), ultimate ? 1f : Math.max(c, 0f), ultimate);
		if (c < 0 || need > mana) {
			return Checked.fail(Result.NO_MANA, noManaMessage(mana, ChargeMath.cost(cost.mana(), 0f, ultimate)));
		}
		if (ultimate && p.experienceLevel < cost.xpLevels()) {
			return Checked.fail(Result.NO_LEVELS, Component.translatable("magic.office.no_levels", name(spell), cost.xpLevels()));
		}
		return new Checked(null, c, need);
	}

	/**
	 * Runs {@code spell.cast} with the caster's rank ({@link #rank}: its damage folded into the power, the rank on the context and
	 * in the fx). A fizzle spends nothing ({@code magic.office.busy} → BUSY, else FIZZLED; a spell that throws
	 * fizzles with {@code magic.office.spell_failed}). A cast spends {@code mana} and, for ultimates, XP levels (creative
	 * players spend neither), sets the cooldown unless the spell manages its own (never lowering one),
	 * adds grimoire xp (title on level-up), sends the one fx and starts the regen pause.
	 */
	static Result run(ServerPlayer p, Spell spell, float charge, int mana) {
		ServerLevel level = p.level();
		MagicData before = MagicAttachments.get(p);
		SpellRank rank = rank(before, spell);
		CastContext ctx = new CastContext(p, level, spell, charge, ChargeMath.power(charge, before.level()) * rank.mul(Knob.DAMAGE),
				before.level(), level.getRandom().nextLong(), CAST_IDS.incrementAndGet(), MagicServer.kit(), rank);
		CastOutcome outcome;
		try {
			outcome = spell.cast(ctx);
		} catch (RuntimeException ex) {
			LOGGER.error("[office] Spell {} failed while casting; treating it as a fizzle", spell.id(), ex);
			outcome = CastOutcome.fizzle(SPELL_FAILED_KEY);
		}
		if (outcome instanceof CastOutcome.Fizzle f) {
			MagicServer.sender().actionBar(p, Component.translatable(f.messageKey()));
			return BUSY_KEY.equals(f.messageKey()) ? Result.BUSY : Result.FIZZLED;
		}
		CastOutcome.Cast done = (CastOutcome.Cast) outcome;
		SpellCost cost = cost(before, spell);
		long now = level.getGameTime();

		if (!p.hasInfiniteMaterials()) {
			MagicAttachments.setMana(p, MagicAttachments.mana(p) - mana);
			if (isUltimate(spell) && cost.xpLevels() > 0) p.giveExperienceLevels(-cost.xpLevels());
		}

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
		// Recall (and Mark Home) cost no mana, so they must not pause regen.
		if (spell.slot() != Slots.RECALL) LAST_CAST.put(p.getUUID(), now);
		return Result.CAST;
	}

	private static void levelUp(ServerPlayer p, MagicData d, int oldLevel) {
		p.level().playSound(null, p.blockPosition(), SoundEvents.PLAYER_LEVELUP, SoundSource.PLAYERS, 1f, 1f);
		Spell unlocked = null;
		for (int slot = 0; slot < Slots.WHEEL_SLICES; slot++) {
			int at = Slots.unlockLevel(slot);
			if (at > oldLevel && at <= d.level()) {
				Optional<Spell> s = SpellRegistry.at(p.getUUID(), d.element().orElseThrow(), slot);
				if (s.isPresent()) unlocked = s.get();
			}
		}
		Component subtitle = unlocked != null ? Component.translatable("magic.office.unlocked", name(unlocked))
				: Component.translatable("magic.office.stronger");
		MagicServer.sender().title(p, Component.translatable("magic.office.level_up", d.level()), subtitle);
	}

	/**
	 * +1 mana every {@link ManaMath#regenInterval} ticks once {@link ManaMath#REGEN_PAUSE_TICKS} have passed since the last cast,
	 * added in steps of {@link #REGEN_STEP_TICKS}: each step adds the points of the ticks {@code t} in its window with
	 * {@code t % interval == 0}, counting only those after the pause.
	 */
	private static void regen(ServerPlayer p) {
		long now = p.level().getGameTime();
		if (now % REGEN_STEP_TICKS != 0 || !p.isAlive()) return;
		int level = MagicAttachments.get(p).level();
		int mana = MagicAttachments.mana(p);
		if (mana >= ManaMath.max(level)) return;
		long from = now - REGEN_STEP_TICKS; // the window is (from, now]
		Long last = LAST_CAST.get(p.getUUID());
		if (last != null) from = Math.max(from, last + ManaMath.REGEN_PAUSE_TICKS - 1);
		if (from >= now) return;
		int interval = ManaMath.regenInterval(level);
		long points = Math.floorDiv(now, interval) - Math.floorDiv(from, interval);
		if (points > 0) MagicAttachments.setMana(p, (int) Math.min(Integer.MAX_VALUE, mana + points));
	}

	private static Component cooldownMessage(Spell spell, long ticksLeft) {
		// Lang strings take only %s: one decimal, rounded up to the next tenth of a second.
		return Component.translatable("magic.office.cooldown", name(spell),
				String.format(java.util.Locale.ROOT, "%.1f", Math.ceil(ticksLeft / 2.0) / 10));
	}

	private static Component noManaMessage(int mana, int need) {
		return Component.translatable("magic.office.no_mana", mana, need);
	}

	/** The spell's display name ({@code spell.<namespace>.<path>}). */
	public static Component name(Spell s) {
		return Component.translatable("spell." + s.id().getNamespace() + "." + s.id().getPath());
	}
}
