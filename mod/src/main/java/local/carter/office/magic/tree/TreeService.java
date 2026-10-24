package local.carter.office.magic.tree;

import java.util.Optional;


import local.carter.office.magic.core.Element;
import local.carter.office.magic.core.Slots;
import local.carter.office.magic.data.MagicAttachments;
import local.carter.office.magic.data.MagicData;
import local.carter.office.magic.item.MagicItems;
import local.carter.office.magic.net.MagicPayloads.TreeResult;
import local.carter.office.magic.net.MagicServer;
import local.carter.office.magic.spell.Spell;
import local.carter.office.magic.spell.SpellRegistry;

import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.ItemStack;

/**
 * Buying ranks, server side (the client only asks: {@code RankUp}, {@code ChooseFork}). Checks run in order and the first
 * failure wins with nothing taken: the spell is one of the bound element's five ({@link Outcome#NOT_YOURS}), not maxed
 * ({@link Outcome#MAXED}), the request fits the next rank (a bump for I, II and IV, a fork choice for III and V:
 * {@link Outcome#WRONG_RANK}), rank I's spell is unlocked by grimoire level ({@link Outcome#LOCKED}), XP levels
 * ({@link Outcome#NO_LEVELS}) and every material line ({@link Outcome#NO_ITEMS}) are there. Then the levels and items are taken
 * in one go (the server thread: nothing can change between the check and the take) and the rank is stored. Creative players pay
 * nothing.
 *
 * <p><b>Re-pick.</b> A fork choice for a fork rank already owned re-picks it: the same option is {@link Outcome#SAME}; otherwise
 * it needs an open re-pick window, or a Rebinding Seal anywhere in the inventory, which is spent and opens a
 * {@link #REPICK_TICKS} window in which every fork of the element may be re-picked ({@link Outcome#NO_SEAL} without either).
 * Ranks are kept and materials are not refunded.
 *
 * <p>Every request is answered with a {@link TreeResult} (the screen's burst or its reason); the new rank reaches the client in
 * the synced {@code MagicData}.
 */
public final class TreeService {
	public enum Outcome { OK, NOT_YOURS, MAXED, WRONG_RANK, LOCKED, NO_LEVELS, NO_ITEMS, NO_SEAL, SAME }

	/** How long a spent Seal lets forks be re-picked (game ticks): 60 s. */
	public static final long REPICK_TICKS = 1200;

	private TreeService() {}

	/** Buys the next bump (rank I, II or IV) of {@code spell}. */
	public static Outcome rankUp(ServerPlayer p, Identifier spell) {
		return reply(p, spell, request(p, spell, 0, 0));
	}

	/** Buys rank {@code rank} (3 or 5) of {@code spell} as fork {@code option} (1 = A, 2 = B), or re-picks it if owned. */
	public static Outcome chooseFork(ServerPlayer p, Identifier spell, int rank, int option) {
		return reply(p, spell, request(p, spell, rank, option));
	}

	private static Outcome reply(ServerPlayer p, Identifier spell, Outcome o) {
		MagicServer.sender().treeResult(p, new TreeResult(spell, MagicAttachments.get(p).rank(spell).packed(), o));
		return o;
	}

	/**
	 * True while a spent Seal's re-pick window is open for {@code p}: its end time lives in the player's synced {@code MagicData}
	 * ({@code repickUntil}), so the client shows the same window after the book is closed and opened again.
	 */
	public static boolean repickOpen(ServerPlayer p) {
		return MagicAttachments.get(p).repickOpen(p.level().getGameTime());
	}

	/** {@code option} 0 = a bump ({@code RankUp}); else a fork choice at {@code rank}. */
	private static Outcome request(ServerPlayer p, Identifier id, int rank, int option) {
		MagicData d = MagicAttachments.get(p);
		Optional<Spell> found = SpellRegistry.get(p.getUUID(), id);
		if (d.element().isEmpty() || found.isEmpty()) return Outcome.NOT_YOURS;
		Spell spell = found.get();
		Element e = d.element().get();
		if (spell.element() != e || spell.slot() < 0 || spell.slot() > Slots.ULTIMATE || Trees.get(id) == null) return Outcome.NOT_YOURS;
		SpellRank current = d.rank(id);
		if (option != 0) {
			if ((rank != 3 && rank != 5) || (option != SpellRank.A && option != SpellRank.B)) return Outcome.WRONG_RANK;
			if (rank <= current.rank()) return repick(p, d, current, rank, option);
		}
		if (current.rank() >= SpellRank.MAX) return Outcome.MAXED;
		int next = current.rank() + 1;
		if (option == 0 ? SpellRank.isFork(next) : rank != next) return Outcome.WRONG_RANK;
		if (next == 1 && d.level() < Slots.unlockLevel(spell.slot())) return Outcome.LOCKED;
		RankCost.Cost cost = RankCost.of(e, spell.slot() == Slots.ULTIMATE, next);
		Inventory inv = p.getInventory();
		if (!p.hasInfiniteMaterials()) {
			if (p.experienceLevel < cost.levels()) return Outcome.NO_LEVELS;
			if (!RankCost.missing(inv, cost).isEmpty()) return Outcome.NO_ITEMS;
			p.giveExperienceLevels(-cost.levels());
			RankCost.take(inv, cost);
		}
		MagicAttachments.set(p, d.withRank(current.next(option)));
		return Outcome.OK;
	}

	private static Outcome repick(ServerPlayer p, MagicData d, SpellRank current, int rank, int option) {
		if (current.fork(rank) == option) return Outcome.SAME;
		if (!repickOpen(p) && !p.hasInfiniteMaterials()) {
			if (!spendSeal(p.getInventory())) return Outcome.NO_SEAL;
			d = d.withRepickUntil(p.level().getGameTime() + REPICK_TICKS);
		}
		MagicAttachments.set(p, d.withRank(current.repick(rank, option)));

		return Outcome.OK;
	}

	/** Spends one Rebinding Seal from anywhere in the inventory; false if there is none. */
	private static boolean spendSeal(Inventory inv) {
		for (int i = 0; i < inv.getContainerSize(); i++) {
			ItemStack s = inv.getItem(i);
			if (s.is(MagicItems.REBINDING_SEAL)) {
				s.shrink(1);
				inv.setChanged();
				return true;
			}
		}
		return false;
	}
}
