package local.carter.office.magic;

import java.util.List;
import java.util.Optional;

import local.carter.office.magic.cast.CastService;
import local.carter.office.magic.cast.CastService.Result;
import local.carter.office.magic.cast.SignChallenges;
import local.carter.office.magic.core.Dir;
import local.carter.office.magic.core.Element;
import local.carter.office.magic.core.HandSigns;
import local.carter.office.magic.data.MagicAttachments;
import local.carter.office.magic.item.GrimoireItem;
import local.carter.office.magic.item.MagicItems;
import local.carter.office.magic.net.MagicPayloads.SignChallenge;

import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.contents.TranslatableContents;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;

import net.fabricmc.fabric.api.gametest.v1.GameTest;

/**
 * Server GameTests for hand signs (Task 6): an ultimate issues a challenge instead of casting; a right answer in time casts it
 * after a re-check, anything else fizzles; death, disconnect and dimension change discard it for free.
 */
public class HandSignGameTests {
	private static final Identifier PROBE = Identifier.fromNamespaceAndPath("office-test", "probe_ultimate");
	/** Level 7 maximum mana. */
	private static final int MAX_MANA = 160;

	/** A level-7 FIRE caster (30 XP levels, full mana) with the slot-4 probe installed and selected. */
	private static ServerPlayer ultimateCaster(GameTestHelper h) {
		ServerPlayer p = MagicTestKit.caster(h, Element.FIRE, 7);
		MagicTestKit.override(h, MagicTestKit.probe("probe_ultimate", Element.FIRE, 4));
		MagicAttachments.set(p, MagicAttachments.get(p).withSelected(Optional.of(PROBE)));
		return p;
	}

	/** Casts the probe and returns the challenge it sent (asserting CHALLENGED). */
	private static SignChallenge challenge(GameTestHelper h, ServerPlayer p, MagicTestKit.Recorder rec) {
		h.assertValueEqual(MagicTestKit.cast(p, PROBE, 1f), Result.CHALLENGED, "cast result");
		SignChallenge c = rec.lastChallenge(p);
		h.assertTrue(c != null, "a challenge was sent");
		return c;
	}

	private static Dir[] right(SignChallenge c) {
		return HandSigns.sequence(c.seed(), c.length());
	}

	/** {@code right(c)} with its first arrow changed. */
	private static Dir[] wrong(SignChallenge c) {
		Dir[] d = right(c);
		d[0] = d[0] == Dir.UP ? Dir.DOWN : Dir.UP;
		return d;
	}

	/** Exactly one action-bar message: the fizzle with {@code reasonKey} folded in (the re-check sends nothing itself). */
	private static void assertOneFizzleBecause(GameTestHelper h, MagicTestKit.Recorder rec, String reasonKey) {
		h.assertValueEqual(rec.messageKeys(), List.of(CastService.SIGN_FIZZLE_REASON_KEY), "messages");
		TranslatableContents t = (TranslatableContents) rec.messages().get(0).getContents();
		Object reason = t.getArgs().length == 1 ? t.getArgs()[0] : null;
		h.assertTrue(reason instanceof Component r && r.getContents() instanceof TranslatableContents rt && rt.getKey().equals(reasonKey),
				"fizzle reason " + reasonKey + ", was " + reason);
	}

	private static long cooldown(GameTestHelper h, ServerPlayer p) {
		return MagicAttachments.get(p).cooldownLeft(PROBE, h.getLevel().getGameTime());
	}

	@GameTest(structure = MagicTestKit.ARENA)
	public void ultimateIssuesChallengeNotCast(GameTestHelper h) {
		ServerPlayer p = ultimateCaster(h);
		MagicTestKit.Recorder rec = MagicTestKit.recorder(h);
		SignChallenge c = challenge(h, p, rec);
		h.assertValueEqual(rec.fx().size(), 0, "fx sent");
		h.assertValueEqual(MagicAttachments.mana(p), MAX_MANA, "mana");
		h.assertValueEqual(p.experienceLevel, MagicTestKit.XP_LEVELS, "XP levels");
		h.assertValueEqual(cooldown(h, p), 0L, "cooldown");
		h.assertValueEqual(c.length(), 6, "length at level 7");
		h.assertValueEqual(c.limitTicks(), 92, "limit ticks");
		h.assertValueEqual(c.spell(), PROBE, "spell");
		h.assertTrue(SignChallenges.pending(p), "challenge open");
		// One open challenge per player: every other cast is BUSY until it is answered.
		h.assertValueEqual(MagicTestKit.cast(p, PROBE, 1f), Result.BUSY, "second cast while challenged");
		h.assertValueEqual(rec.challenges().size(), 1, "challenges sent");
		h.assertValueEqual(rec.messageKeys(), List.of("magic.office.signs"), "busy message");
		h.succeed();
	}

	@GameTest(structure = MagicTestKit.ARENA)
	public void correctSignsCastUltimate(GameTestHelper h) {
		ServerPlayer p = ultimateCaster(h);
		MagicTestKit.Recorder rec = MagicTestKit.recorder(h);
		SignChallenge c = challenge(h, p, rec);
		h.assertValueEqual(CastService.answer(p, c.id(), right(c)), Result.CAST, "answer");
		h.assertValueEqual(rec.fx(p).size(), 1, "fx sent");
		h.assertValueEqual(rec.fx(p).get(0).charge(), 1.0f, "ultimate charge");
		h.assertValueEqual(MagicAttachments.mana(p), MAX_MANA - 60, "mana");
		h.assertValueEqual(p.experienceLevel, MagicTestKit.XP_LEVELS - 3, "XP levels");
		h.assertValueEqual(cooldown(h, p), 600L, "cooldown");
		h.assertFalse(SignChallenges.pending(p), "challenge closed");
		// The same id again is unknown now: ignored.
		h.assertValueEqual(CastService.answer(p, c.id(), right(c)), Result.IGNORED, "repeated answer");
		h.assertValueEqual(rec.fx(p).size(), 1, "fx after the repeat");
		h.succeed();
	}

	@GameTest(structure = MagicTestKit.ARENA, maxTicks = 60)
	public void chargeDoesNotRaiseUltimateCost(GameTestHelper h) {
		ServerPlayer p = ultimateCaster(h);
		MagicTestKit.Recorder rec = MagicTestKit.recorder(h);
		ItemStack grimoire = p.getOffhandItem();
		MagicItems.GRIMOIRE.use(h.getLevel(), p, InteractionHand.OFF_HAND);
		h.assertTrue(p.isUsingItem(), "charging");
		h.runAfterDelay(40, () -> {
			// The release after a full 40-tick charge (as the item reports it).
			MagicItems.GRIMOIRE.releaseUsing(grimoire, h.getLevel(), p, GrimoireItem.USE_DURATION - 40);
			p.stopUsingItem();
			SignChallenge c = rec.lastChallenge(p);
			h.assertTrue(c != null, "a challenge was sent");
			h.assertValueEqual(rec.fx().size(), 0, "fx before the answer");
			h.assertValueEqual(CastService.answer(p, c.id(), right(c)), Result.CAST, "answer");
			h.assertValueEqual(MagicAttachments.mana(p), MAX_MANA - 60, "mana (base cost, no charge scaling)");
			h.assertValueEqual(rec.fx(p).get(0).charge(), 1.0f, "ultimate charge");
			h.succeed();
		});
	}

	@GameTest(structure = MagicTestKit.ARENA)
	public void wrongSignsFizzleHalfMana(GameTestHelper h) {
		ServerPlayer p = ultimateCaster(h);
		MagicTestKit.Recorder rec = MagicTestKit.recorder(h);
		SignChallenge c = challenge(h, p, rec);
		h.assertValueEqual(CastService.answer(p, c.id(), wrong(c)), Result.FIZZLED, "answer");
		h.assertValueEqual(MagicAttachments.mana(p), MAX_MANA - 30, "mana");
		h.assertValueEqual(p.experienceLevel, MagicTestKit.XP_LEVELS, "XP levels");
		h.assertValueEqual(cooldown(h, p), 100L, "cooldown");
		h.assertValueEqual(rec.fx().size(), 0, "fx sent");
		h.assertValueEqual(rec.messageKeys(), List.of("magic.office.fizzle"), "message");
		h.assertFalse(SignChallenges.pending(p), "challenge closed");
		h.succeed();
	}

	@GameTest(structure = MagicTestKit.ARENA)
	public void partialOrInvalidAnswerFizzles(GameTestHelper h) {
		ServerPlayer p = ultimateCaster(h);
		MagicTestKit.Recorder rec = MagicTestKit.recorder(h);
		SignChallenge c = challenge(h, p, rec);
		Dir[] first = {right(c)[0]}; // Esc after one right arrow
		h.assertValueEqual(CastService.answer(p, c.id(), first), Result.FIZZLED, "partial answer");
		h.assertValueEqual(MagicAttachments.mana(p), MAX_MANA - 30, "mana");
		h.assertValueEqual(rec.fx().size(), 0, "fx sent");
		h.succeed();
	}

	@GameTest(structure = MagicTestKit.ARENA, maxTicks = 130)
	public void lateAnswerFizzles(GameTestHelper h) {
		ServerPlayer p = ultimateCaster(h);
		MagicTestKit.Recorder rec = MagicTestKit.recorder(h);
		SignChallenge c = challenge(h, p, rec);
		h.runAfterDelay(103, () -> {
			h.assertValueEqual(CastService.answer(p, c.id(), right(c)), Result.FIZZLED, "answer after 103 ticks");
			h.assertValueEqual(MagicAttachments.mana(p), MAX_MANA - 30, "mana");
			h.assertValueEqual(p.experienceLevel, MagicTestKit.XP_LEVELS, "XP levels");
			h.assertValueEqual(cooldown(h, p), 100L, "cooldown");
			h.assertValueEqual(rec.fx().size(), 0, "fx sent");
			h.succeed();
		});
	}

	@GameTest(structure = MagicTestKit.ARENA, maxTicks = 130)
	public void answerWithinGraceCasts(GameTestHelper h) {
		ServerPlayer p = ultimateCaster(h);
		MagicTestKit.Recorder rec = MagicTestKit.recorder(h);
		SignChallenge c = challenge(h, p, rec);
		// limit 92 + grace 10: the last tick that still counts.
		h.runAfterDelay(102, () -> {
			h.assertValueEqual(CastService.answer(p, c.id(), right(c)), Result.CAST, "answer after 102 ticks");
			h.assertValueEqual(rec.fx(p).size(), 1, "fx sent");
			h.succeed();
		});
	}

	@GameTest(structure = MagicTestKit.ARENA, maxTicks = 130)
	public void unansweredChallengeExpiresAsFizzle(GameTestHelper h) {
		ServerPlayer p = ultimateCaster(h);
		MagicTestKit.Recorder rec = MagicTestKit.recorder(h);
		SignChallenge c = challenge(h, p, rec);
		h.runAfterDelay(102, () -> h.assertTrue(SignChallenges.pending(p), "still open at limit + grace"));
		h.runAfterDelay(104, () -> {
			h.assertFalse(SignChallenges.pending(p), "expired");
			h.assertValueEqual(MagicAttachments.mana(p), MAX_MANA - 30, "mana");
			h.assertTrue(cooldown(h, p) > 95, "fizzle cooldown, was " + cooldown(h, p));
			h.assertValueEqual(rec.messageKeys(), List.of("magic.office.fizzle"), "message");
			h.assertValueEqual(CastService.answer(p, c.id(), right(c)), Result.IGNORED, "answer after expiry");
			h.assertValueEqual(MagicAttachments.mana(p), MAX_MANA - 30, "mana after the late answer");
			h.assertValueEqual(rec.fx().size(), 0, "fx sent");
			h.succeed();
		});
	}

	@GameTest(structure = MagicTestKit.ARENA)
	public void notEnoughXpLevels(GameTestHelper h) {
		ServerPlayer p = ultimateCaster(h);
		p.setExperienceLevels(2);
		MagicTestKit.Recorder rec = MagicTestKit.recorder(h);
		h.assertValueEqual(MagicTestKit.cast(p, PROBE, 1f), Result.NO_LEVELS, "result");
		h.assertValueEqual(rec.challenges().size(), 0, "challenges sent");
		h.assertFalse(SignChallenges.pending(p), "challenge open");
		h.assertValueEqual(MagicAttachments.mana(p), MAX_MANA, "mana");
		h.assertValueEqual(p.experienceLevel, 2, "XP levels");
		h.succeed();
	}

	@GameTest(structure = MagicTestKit.ARENA)
	public void manaSpentElsewhereBeforeAnswerFizzles(GameTestHelper h) {
		ServerPlayer p = ultimateCaster(h);
		MagicTestKit.Recorder rec = MagicTestKit.recorder(h);
		SignChallenge c = challenge(h, p, rec);
		MagicAttachments.setMana(p, 10);
		h.assertValueEqual(CastService.answer(p, c.id(), right(c)), Result.FIZZLED, "answer");
		h.assertValueEqual(MagicAttachments.mana(p), 0, "mana (the 10 left, less than the 30 fizzle cost)");
		h.assertValueEqual(p.experienceLevel, MagicTestKit.XP_LEVELS, "XP levels");
		h.assertValueEqual(cooldown(h, p), 100L, "cooldown");
		h.assertValueEqual(rec.fx().size(), 0, "fx sent");
		assertOneFizzleBecause(h, rec, "magic.office.no_mana");
		h.succeed();
	}

	@GameTest(structure = MagicTestKit.ARENA)
	public void levelsSpentElsewhereBeforeAnswerFizzles(GameTestHelper h) {
		ServerPlayer p = ultimateCaster(h);
		MagicTestKit.Recorder rec = MagicTestKit.recorder(h);
		SignChallenge c = challenge(h, p, rec);
		p.setExperienceLevels(1);
		h.assertValueEqual(CastService.answer(p, c.id(), right(c)), Result.FIZZLED, "answer");
		assertOneFizzleBecause(h, rec, "magic.office.no_levels");
		h.assertValueEqual(p.experienceLevel, 1, "XP levels");
		h.assertValueEqual(MagicAttachments.mana(p), MAX_MANA - 30, "mana");
		h.assertValueEqual(rec.fx().size(), 0, "fx sent");
		h.succeed();
	}

	@GameTest(structure = MagicTestKit.ARENA)
	public void creativeFizzleCostsNoManaButCoolsDown(GameTestHelper h) {
		ServerPlayer p = ultimateCaster(h);
		p.setGameMode(GameType.CREATIVE);
		MagicTestKit.Recorder rec = MagicTestKit.recorder(h);
		SignChallenge c = challenge(h, p, rec);
		h.assertValueEqual(CastService.answer(p, c.id(), wrong(c)), Result.FIZZLED, "wrong answer");
		h.assertValueEqual(MagicAttachments.mana(p), MAX_MANA, "mana");
		h.assertValueEqual(cooldown(h, p), 100L, "cooldown still applies");
		h.succeed();
	}

	@GameTest(structure = MagicTestKit.ARENA)
	public void wrongIdIsIgnored(GameTestHelper h) {
		ServerPlayer p = ultimateCaster(h);
		MagicTestKit.Recorder rec = MagicTestKit.recorder(h);
		SignChallenge c = challenge(h, p, rec);
		h.assertValueEqual(CastService.answer(p, c.id() + 1000, right(c)), Result.IGNORED, "unknown id");
		h.assertTrue(SignChallenges.pending(p), "the real challenge stays open");
		h.assertValueEqual(MagicAttachments.mana(p), MAX_MANA, "mana");
		h.assertValueEqual(CastService.answer(p, c.id(), right(c)), Result.CAST, "the real answer still works");
		h.succeed();
	}

	@GameTest(structure = MagicTestKit.ARENA)
	public void deathDiscardsChallenge(GameTestHelper h) {
		ServerPlayer p = ultimateCaster(h);
		MagicTestKit.Recorder rec = MagicTestKit.recorder(h);
		SignChallenge c = challenge(h, p, rec);
		MagicTestKit.kill(p);
		h.assertFalse(SignChallenges.pending(p), "challenge open after death");
		h.assertValueEqual(CastService.answer(p, c.id(), right(c)), Result.IGNORED, "answer after death");
		h.assertValueEqual(rec.fx().size(), 0, "fx sent");
		h.assertValueEqual(MagicAttachments.mana(p), MAX_MANA, "mana");
		h.assertValueEqual(cooldown(h, p), 0L, "cooldown");
		h.succeed();
	}

	@GameTest(structure = MagicTestKit.ARENA)
	public void disconnectDiscardsChallenge(GameTestHelper h) {
		ServerPlayer p = ultimateCaster(h);
		MagicTestKit.Recorder rec = MagicTestKit.recorder(h);
		SignChallenge c = challenge(h, p, rec);
		MagicTestKit.disconnect(p);
		h.assertTrue(h.getLevel().getServer().getPlayerList().getPlayer(p.getUUID()) == null, "left the player list");
		h.assertFalse(SignChallenges.pending(p), "challenge open after disconnect");
		h.assertValueEqual(CastService.answer(p, c.id(), right(c)), Result.IGNORED, "answer after disconnect");
		h.assertValueEqual(rec.fx().size(), 0, "fx sent");
		h.assertValueEqual(MagicAttachments.mana(p), MAX_MANA, "mana");
		h.succeed();
	}

	@GameTest(structure = MagicTestKit.ARENA)
	public void dimensionChangeDiscardsChallenge(GameTestHelper h) {
		ServerPlayer p = ultimateCaster(h);
		MagicTestKit.Recorder rec = MagicTestKit.recorder(h);
		SignChallenge c = challenge(h, p, rec);
		ServerLevel nether = h.getLevel().getServer().getLevel(Level.NETHER);
		MagicTestKit.teleportTo(p, nether, new Vec3(0.5, 64, 0.5));
		h.assertTrue(p.level() == nether, "in the Nether");
		h.assertFalse(SignChallenges.pending(p), "challenge open after a dimension change");
		h.assertValueEqual(CastService.answer(p, c.id(), right(c)), Result.IGNORED, "answer after a dimension change");
		h.assertValueEqual(rec.fx().size(), 0, "fx sent");
		h.assertValueEqual(MagicAttachments.mana(p), MAX_MANA, "mana");
		h.succeed();
	}
}
