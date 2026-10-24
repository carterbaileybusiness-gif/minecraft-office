package local.carter.office.client.magic.screen;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

import org.jspecify.annotations.Nullable;

import local.carter.office.client.magic.screen.SignLayout.Rect;
import local.carter.office.client.magic.screen.TreeLayout.Node;
import local.carter.office.magic.cast.CastService;
import local.carter.office.magic.core.Element;
import local.carter.office.magic.core.Slots;
import local.carter.office.magic.data.MagicAttachments;
import local.carter.office.magic.data.MagicData;
import local.carter.office.magic.item.MagicItems;
import local.carter.office.magic.net.MagicPayloads.ChooseFork;
import local.carter.office.magic.net.MagicPayloads.RankUp;
import local.carter.office.magic.net.MagicPayloads.TreeResult;
import local.carter.office.magic.spell.SpellCost;
import local.carter.office.magic.spell.SpellIds;
import local.carter.office.magic.tree.Fork;
import local.carter.office.magic.tree.RankCost;
import local.carter.office.magic.tree.SpellRank;
import local.carter.office.magic.tree.SpellTree;
import local.carter.office.magic.tree.TreeService;
import local.carter.office.magic.tree.Trees;

import com.mojang.blaze3d.platform.InputConstants;

import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.client.resources.sounds.SimpleSoundInstance;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.util.FormattedCharSequence;
import net.minecraft.util.Util;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;

import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;

/**
 * The grimoire's Skill tree tab. Left page: the tree ({@link TreeLayout}): one column per spell of your element (wheel order),
 * its icon and rank on top, five nodes below on a line that fills in your element's colour as ranks are bought; III and V are
 * split nodes (two options, the picked one glows, the other greys). Every node shows its state by shape, mark and colour:
 * <b>owned</b> a filled disc with a light core, <b>ready</b> a pulsing ring with a "+", <b>short</b> a thin ring with a grey "+"
 * and a red dot, <b>locked</b> a padlock, <b>later</b> a small dim ring, <b>not picked</b> a grey ring with a dash.
 *
 * <p>Right page: the selected node (hovering one shows a tip): the spell, the rank, exactly what changes before → after, the
 * fork's two cards (name, one line, the numbers that change), the cost with have/need per item (red when short), and one primary
 * button whose label says what it does or why it can't ("Need 3 more levels"). Clicking a node selects it; the button buys. Keys:
 * arrows move between nodes (Left past the first column turns to the previous tab), Enter or Space presses the button.
 *
 * <p>With a Rebinding Seal in the inventory a "Re-pick forks" switch appears under the tree: in that mode an owned fork's other
 * option can be chosen (ranks kept, one Seal opens a minute of re-picks, materials not refunded). The server decides every
 * purchase ({@code RankUp} / {@code ChooseFork}) and answers with a {@link TreeResult}: on success the node bursts, the line runs
 * up to it, the cost floats away and a level-up chime (a heavier anvil chime for a fork) plays in your element's pitch. A
 * one-time coach hint points at the first node you can buy.
 */
public final class TreePage {
	private static final int LOCK_RED = 0xFF9A3B2E;
	private static final int SHORT_RED = 0xFFC0392B;
	private static final int GREY = 0xFF85765F;
	/** "(have n)" when there is enough, and the levels row's XP orb. */
	private static final int HAVE_GREEN = 0xFF2E6B1F;
	private static final int XP_GREEN = 0xFF7FBF2A;
	/** The book's page and the cards' paper (text colours are checked against them for 4.5:1). */
	private static final int PAGE = 0xFFF8EED0;
	private static final int CARD = 0xFFF3E8CC;
	private static final long BURST_MS = 650;
	private static final long FLOAT_MS = 1300;
	private static final long PENDING_MS = 1500;
	/** How long a refusal shakes the button, and how long the server's reason stays up. */
	private static final long SHAKE_MS = 450;
	private static final long REFUSAL_MS = 2500;

	private final GrimoireScreen screen;
	/** The selected node: column, rank, option (0 = bump). */
	private int selCol = -1;
	private int selRank = 1;
	private int selOption;
	private @Nullable Node hover;
	private boolean repick;
	/** The keyboard is on the Re-pick switch (Down past rank V), not on a node. */
	private boolean switchFocus;
	private @Nullable Pending pending;
	private final List<Burst> bursts = new ArrayList<>();
	private final List<Floater> floaters = new ArrayList<>();
	private final float[] fill = new float[TreeLayout.COLS];
	private boolean filled;
	private long refusedAt;
	private @Nullable Component refusal;
	private long shownAt = Util.getMillis();
	private boolean coach;
	private final List<FormattedCharSequence> lines = new ArrayList<>();

	private record Pending(Identifier spell, int col, int rank, int option, List<Component> costs, boolean repick, long at) {}

	private record Burst(int col, int rank, int option, int argb, boolean fork, long at) {}

	private record Floater(Component text, long at, int index) {}

	TreePage(GrimoireScreen screen) {
		this.screen = screen;
	}

	private static Minecraft mc() {
		return Minecraft.getInstance();
	}

	private static MagicData data() {
		LocalPlayer p = mc().player;
		return p == null ? MagicData.EMPTY : MagicAttachments.get(p);
	}

	/** The tab was opened: select the first node that can be bought (else the wheel's spell's next one); maybe show the coach hint. */
	void shown() {
		shownAt = Util.getMillis();
		MagicData d = data();
		if (d.element().isEmpty()) return;
		Element e = d.element().get();
		int[] next = firstReady(d, e);
		if (next != null) {
			select(next[0], next[1], next[2]);
		} else if (selCol < 0) {
			int col = 0;
			for (int slot = 0; slot < TreeLayout.COLS; slot++) if (d.selected().equals(Optional.of(SpellIds.at(e, slot)))) col = slot;
			SpellRank r = d.rank(SpellIds.at(e, col));
			select(col, Math.min(SpellRank.MAX, r.rank() + 1), TreeLayout.isFork(Math.min(SpellRank.MAX, r.rank() + 1)) ? 1 : 0);
		}
		coach = next != null && mc().player != null && TreeHints.firstTime(mc().player.getUUID());
	}

	/** {col, rank, option} of the first node the player can buy now, or null. */
	private static int @Nullable [] firstReady(MagicData d, Element e) {
		for (int slot = 0; slot < TreeLayout.COLS; slot++) {
			Identifier id = SpellIds.at(e, slot);
			SpellRank r = d.rank(id);
			if (r.rank() >= SpellRank.MAX || d.level() < Slots.unlockLevel(slot)) continue;
			int next = r.rank() + 1;
			if (shortfall(d, e, slot, next) == null) return new int[] {slot, next, TreeLayout.isFork(next) ? 1 : 0};
		}
		return null;
	}

	private void toggleRepick() {
		repick = !repick;
		play(SimpleSoundInstance.forUI(SoundEvents.BOOK_PAGE_TURN, 0.9f, 0.8f));
	}

	public void select(int col, int rank, int option) {
		switchFocus = false;
		selCol = col;
		selRank = rank;
		selOption = TreeLayout.isFork(rank) ? Math.max(1, option) : 0;
	}

	// ---------------------------------------------------------------- the model

	enum State { OWNED, OTHER, READY, SHORT, LATER, LOCKED }

	/** What node (slot, rank, option) is for the player whose data is {@code d}. */
	static State state(MagicData d, Element e, int slot, int rank, int option) {
		SpellRank r = d.rank(SpellIds.at(e, slot));
		if (d.level() < Slots.unlockLevel(slot)) return State.LOCKED;
		if (rank <= r.rank()) return option == 0 || r.fork(rank) == option ? State.OWNED : State.OTHER;
		if (rank > r.rank() + 1) return State.LATER;
		return shortfall(d, e, slot, rank) == null ? State.READY : State.SHORT;
	}

	/** Why rank {@code rank} of the spell in {@code slot} can't be bought now (levels or items), or null if it can. */
	static @Nullable Component shortfall(MagicData d, Element e, int slot, int rank) {
		LocalPlayer p = mc().player;
		if (p == null) return Component.empty();
		if (p.hasInfiniteMaterials()) return null;
		RankCost.Cost cost = RankCost.of(e, slot == Slots.ULTIMATE, rank);
		if (p.experienceLevel < cost.levels()) {
			int n = cost.levels() - p.experienceLevel;
			return n == 1 ? Component.translatable("tree.office.need_level") : Component.translatable("tree.office.need_levels", n);
		}
		List<RankCost.Shortfall> missing = RankCost.missing(p.getInventory(), cost);
		if (missing.isEmpty()) return null;
		RankCost.Shortfall s = missing.getFirst();
		return Component.translatable("tree.office.need_items", s.missing(), new ItemStack(s.line().icon()).getHoverName());
	}

	/** {@code current} carried on to rank {@code rank} (forks not reached yet previewed as A). */
	private static SpellRank upTo(SpellRank current, int rank) {
		SpellRank r = current;
		while (r.rank() < rank) r = r.next(SpellRank.A);
		return r;
	}

	/** The spell's rank as it would be after buying node (rank, option), from {@code current} (an owned fork: re-picked). */
	private static SpellRank after(SpellRank current, int rank, int option) {
		if (rank <= current.rank()) return option == 0 ? current : current.repick(rank, option);
		return upTo(current, rank - 1).next(option == 0 ? SpellRank.A : option);
	}

	/** The rank the node's change is measured from: the rank before it (a re-pick: the rank as owned). */
	private static SpellRank before(SpellRank current, int rank, int option) {
		if (rank <= current.rank()) return option != 0 && current.fork(rank) != option ? current : truncate(current, rank - 1);
		return upTo(current, rank - 1);
	}

	private static SpellRank truncate(SpellRank r, int rank) {
		return new SpellRank(r.spell(), rank, r.fork3(), r.fork5());
	}

	// ---------------------------------------------------------------- input

	boolean click(GrimoireLayout.Layout gl, double x, double y) {
		TreeLayout.Layout l = TreeLayout.of(gl);
		coach = false;
		MagicData d = data();
		if (d.element().isEmpty()) return false;
		Node n = l.nodeAt(x, y);
		if (n != null) {
			select(n.col(), n.rank(), n.option());
			play(SimpleSoundInstance.forUI(SoundEvents.BOOK_PAGE_TURN, 1.4f, 0.5f));
			return true;
		}
		int col = l.iconAt(x, y);
		if (col >= 0) {
			SpellRank r = d.rank(SpellIds.at(d.element().get(), col));
			int next = Math.min(SpellRank.MAX, r.rank() + 1);
			select(col, next, 1);
			play(SimpleSoundInstance.forUI(SoundEvents.BOOK_PAGE_TURN, 1.4f, 0.5f));
			return true;
		}
		if (GrimoireLayout.contains(l.button(), x, y)) {
			press(d);
			return true;
		}
		if (repickShown() && GrimoireLayout.contains(l.repick(), x, y)) {
			toggleRepick();
			return true;
		}
		return false;
	}

	/** Arrows move between nodes, Enter / Space presses the button. False lets the screen use the key (Left past the first column). */
	boolean key(KeyEvent event) {
		coach = false;
		MagicData d = data();
		if (d.element().isEmpty()) return false;
		if (selCol < 0) select(0, 1, 0);
		// R flips the Re-pick switch from anywhere; Down past rank V reaches it too, and Enter or Space then flips it.
		if (event.key() == InputConstants.KEY_R && repickShown()) {
			toggleRepick();
			return true;
		}
		if (event.isConfirmation() || event.isSelection()) {
			if (switchFocus && repickShown()) toggleRepick();
			else press(d);
			return true;
		}
		if (switchFocus) {
			if (event.isUp() || !repickShown()) switchFocus = false;
			return true;
		}
		if (event.isUp() || event.isDown()) {
			if (event.isDown() && selRank == TreeLayout.RANKS && repickShown()) {
				switchFocus = true;
				return true;
			}
			int rank = Math.clamp(selRank + (event.isUp() ? -1 : 1), 1, TreeLayout.RANKS);
			select(selCol, rank, selOption == 0 ? 1 : selOption);
			return true;
		}
		if (event.isLeft() || event.isRight()) {
			boolean left = event.isLeft();
			// Within a split node first: A ← → B.
			if (TreeLayout.isFork(selRank) && (left ? selOption == 2 : selOption == 1)) {
				selOption = left ? 1 : 2;
				return true;
			}
			int col = selCol + (left ? -1 : 1);
			if (col < 0 || col >= TreeLayout.COLS) return false;
			select(col, selRank, left ? 2 : 1);
			return true;
		}
		return false;
	}

	/** The primary button: buy the selected node (or re-pick it) if it can be; otherwise a short refusal shake. */
	private void press(MagicData d) {
		Element e = d.element().orElse(null);
		if (e == null || selCol < 0) return;
		Action a = action(d, e);
		if (a.label == null || pending != null) {
			refusedAt = Util.getMillis();
			refusal = null;
			play(SimpleSoundInstance.forUI(SoundEvents.NOTE_BLOCK_BASS, 0.6f));
			return;
		}
		Identifier id = SpellIds.at(e, selCol);
		List<Component> costs = new ArrayList<>();
		if (!a.repick && mc().player != null && !mc().player.hasInfiniteMaterials()) {
			RankCost.Cost cost = RankCost.of(e, selCol == Slots.ULTIMATE, selRank);
			costs.add(Component.translatable("tree.office.minus_levels", cost.levels()));
			for (RankCost.Line line : cost.lines()) {
				costs.add(Component.translatable("tree.office.minus_items", line.count(), new ItemStack(line.icon()).getHoverName()));
			}
		}
		pending = new Pending(id, selCol, selRank, selOption, costs, a.repick, Util.getMillis());
		if (TreeLayout.isFork(selRank)) {
			if (ClientPlayNetworking.canSend(ChooseFork.TYPE)) ClientPlayNetworking.send(new ChooseFork(id, selRank, selOption));
		} else if (ClientPlayNetworking.canSend(RankUp.TYPE)) {
			ClientPlayNetworking.send(new RankUp(id));
		}
		play(SimpleSoundInstance.forUI(SoundEvents.UI_BUTTON_CLICK.value(), 1.0f, 0.5f));
	}

	/** The server's answer: the burst and the chime on success, the reason on a refusal. */
	public void result(TreeResult r) {
		Pending p = pending;
		pending = null;
		MagicData d = data();
		Element e = d.element().orElse(Element.FIRE);
		long now = Util.getMillis();
		if (r.outcome() == TreeService.Outcome.OK) {
			SpellRank now0 = SpellRank.unpack(r.spell(), r.rank());
			int col = p != null && p.spell().equals(r.spell()) ? p.col() : slotOf(e, r.spell());
			int rank = p != null && p.spell().equals(r.spell()) ? p.rank() : now0.rank();
			int option = TreeLayout.isFork(rank) ? Math.max(1, now0.fork(rank)) : 0;
			bursts.add(new Burst(col, rank, option, e.argb(), option != 0, now));
			if (p != null) for (int i = 0; i < p.costs().size(); i++) floaters.add(new Floater(p.costs().get(i), now, i));
			float pitch = pitch(e);
			if (option != 0) {
				play(SimpleSoundInstance.forUI(SoundEvents.ANVIL_USE, 1.25f * pitch, 0.35f));
				play(SimpleSoundInstance.forUI(SoundEvents.BEACON_POWER_SELECT, 0.9f * pitch, 0.8f));
			} else {
				play(SimpleSoundInstance.forUI(SoundEvents.PLAYER_LEVELUP, pitch, 0.7f));
			}
			play(SimpleSoundInstance.forUI(SoundEvents.ENCHANTMENT_TABLE_USE, pitch, 0.9f));
			// Move on to what comes next in that column.
			if (rank < SpellRank.MAX && col == selCol) select(col, rank + 1, 1);
		} else {
			refusedAt = now;
			refusal = Component.translatable("tree.office.outcome." + r.outcome().name().toLowerCase(Locale.ROOT));
			play(SimpleSoundInstance.forUI(SoundEvents.NOTE_BLOCK_BASS, 0.6f));
		}
	}

	private static int slotOf(Element e, Identifier spell) {
		for (int slot = 0; slot < TreeLayout.COLS; slot++) if (SpellIds.at(e, slot).equals(spell)) return slot;
		return 0;
	}

	/** Each element's chime: low and heavy for Earth and Shadow, bright for Wind and Lightning. */
	private static float pitch(Element e) {
		return switch (e) {
			case FIRE -> 0.95f;
			case WATER -> 1.1f;
			case WIND -> 1.3f;
			case LIGHTNING -> 1.45f;
			case EARTH -> 0.75f;
			case SHADOW -> 0.85f;
		};
	}

	/** The Re-pick switch shows with a Seal in the inventory, while re-picking, or while a spent Seal's minute runs. */
	private boolean repickShown() {
		return repick || hasSeal() || repickLeftTicks(data()) > 0;
	}

	/**
	 * Ticks left in the re-pick window a spent Seal opened: the server's end time from the synced data ({@code repickUntil}) less
	 * this client's game time, so closing and opening the book never loses it.
	 */
	static long repickLeftTicks(MagicData d) {
		var level = mc().level;
		return level == null ? 0 : repickLeft(d, level.getGameTime());
	}

	/** Ticks left of {@code d}'s re-pick window at game time {@code gameTime} (0 when closed). */
	static long repickLeft(MagicData d, long gameTime) {
		return Math.max(0, d.repickUntil() - gameTime);
	}

	private static boolean hasSeal() {
		LocalPlayer p = mc().player;
		if (p == null) return false;
		for (int i = 0; i < p.getInventory().getContainerSize(); i++) if (p.getInventory().getItem(i).is(MagicItems.REBINDING_SEAL)) return true;
		return false;
	}

	private void play(SimpleSoundInstance s) {
		mc().getSoundManager().play(s);
	}

	// ---------------------------------------------------------------- the button

	/** What the primary button does for the selected node: its label (null = disabled) and the reason when disabled. */
	private record Action(@Nullable Component label, @Nullable Component reason, boolean repick) {}

	private Action action(MagicData d, Element e) {
		int slot = selCol;
		if (pending != null && Util.getMillis() - pending.at() < PENDING_MS) return new Action(null, Component.literal("…"), false);
		if (pending != null) pending = null;
		SpellTree tree = Trees.get(SpellIds.at(e, slot));
		if (tree == null) return new Action(null, Component.empty(), false);
		State s = state(d, e, slot, selRank, selOption);
		SpellRank r = d.rank(tree.spell());
		Component forkName = selOption == 0 ? null : forkName(tree, selRank, selOption);
		return switch (s) {
			case READY -> new Action(forkName == null ? Component.translatable("tree.office.rank_up")
					: Component.translatable("tree.office.choose", forkName), null, false);
			case SHORT -> new Action(null, shortfall(d, e, slot, selRank), false);
			case LATER -> new Action(null, Component.translatable("tree.office.first", SpellRank.roman(r.rank() + 1)), false);
			case LOCKED -> new Action(null, Component.translatable("tree.office.unlock_at", Slots.unlockLevel(slot)), false);
			case OWNED -> new Action(null, Component.translatable(selOption == 0 ? "tree.office.owned" : "tree.office.picked"), false);
			case OTHER -> {
				if (!repick) {
					yield new Action(null, Component.translatable(hasSeal() ? "tree.office.repick_first" : "tree.office.repick_need"), false);
				}
				boolean free = repickLeftTicks(d) > 0 || (mc().player != null && mc().player.hasInfiniteMaterials());
				if (!free && !hasSeal()) yield new Action(null, Component.translatable("tree.office.repick_need"), true);
				yield new Action(Component.translatable("tree.office.repick_button", forkName), null, true);
			}
		};
	}

	private static Component forkName(SpellTree tree, int rank, int option) {
		Fork f = tree.forks(rank).get(option - 1);
		return Component.translatable("fork.office." + tree.spell().getPath() + "." + f.name());
	}

	private static Component forkDesc(SpellTree tree, int rank, int option) {
		Fork f = tree.forks(rank).get(option - 1);
		return Component.translatable("fork.office." + tree.spell().getPath() + "." + f.name() + ".desc");
	}

	// ---------------------------------------------------------------- drawing

	void draw(GuiGraphicsExtractor g, Font font, GrimoireLayout.Layout gl, MagicData d, Element e, int mouseX, int mouseY, long now, float dt,
			float k) {
		TreeLayout.Layout l = TreeLayout.of(gl);
		if (selCol < 0) shown();
		hover = l.nodeAt(mouseX, mouseY);
		bursts.removeIf(b -> now - b.at() > BURST_MS);
		floaters.removeIf(f -> now - f.at() > FLOAT_MS);
		float appear = UiKit.easeOut((now - shownAt) / 320f);
		tree(g, font, l, d, e, mouseX, mouseY, now, dt, k * appear);
		panel(g, font, l, d, e, mouseX, mouseY, now, k * appear);
		if (coach) coach(g, font, l, d, e, now, k);
	}

	/** The left page: icons and ranks, the lines and the nodes, bursts, the Re-pick switch. */
	private void tree(GuiGraphicsExtractor g, Font font, TreeLayout.Layout l, MagicData d, Element e, int mouseX, int mouseY, long now, float dt,
			float k) {
		float u = l.unit();
		int ink = UiKit.readable(UiKit.inkOf(e.argb()), PAGE);
		float pulse = 0.5f + 0.5f * (float) Math.sin(now / 260.0);
		int shown = selCol;
		int hoverIcon = l.iconAt(mouseX, mouseY);
		for (int c = 0; c < TreeLayout.COLS; c++) {
			Identifier id = SpellIds.at(e, c);
			SpellRank r = d.rank(id);
			boolean locked = d.level() < Slots.unlockLevel(c);
			if (!filled) fill[c] = r.rank();
			fill[c] = UiKit.approach(fill[c], r.rank(), 7f, dt);
			Rect col = l.columns().get(c);
			if (c == shown) g.fill(col.x(), col.y(), col.right(), col.bottom(), UiKit.alpha(e.argb(), 0.09f * k));

			// The icon, and its rank under it ("Lv 5" while locked).
			Rect ic = l.icons().get(c);
			float pop = 1f;
			for (Burst b : bursts) if (b.col() == c) pop = Math.max(pop, 1f + 0.18f * (1f - (now - b.at()) / (float) BURST_MS));
			if (pop > 1f) UiKit.glow(g, ic.cx(), ic.cy(), ic.w() * 2.2f, UiKit.alpha(e.argb(), 0.6f * (pop - 1f) / 0.18f * k));
			UiKit.centredTexture(g, UiKit.spellIcon(id), 32, ic.x() + ic.w() / 2f, ic.y() + ic.h() / 2f, ic.w() * pop, 0f,
					UiKit.alpha(locked ? 0x70FFFFFF : UiKit.WHITE, k));
			if (locked) UiKit.centredTexture(g, UiKit.PADLOCK, 16, ic.right() - ic.w() * 0.2f, ic.bottom() - ic.h() * 0.2f, ic.w() * 0.55f, 0f,
					UiKit.alpha(UiKit.WHITE, k));
			Component under = locked ? Component.translatable("tree.office.lv", Slots.unlockLevel(c))
					: Component.literal(r.rank() == 0 ? "–" : SpellRank.roman(r.rank()));
			float us = fitAtLeastOne(font.width(under), col.w() - 2, u);
			UiKit.centred(g, font, under, ic.x() + ic.w() / 2f, l.rankY().get(c), us,
					UiKit.alpha(locked ? LOCK_RED : r.rank() == 0 ? UiKit.INK_SOFT : ink, k), false);
			if (c == hoverIcon) {
				List<Component> tip = new ArrayList<>();
				tip.add(UiKit.spellName(id));
				tip.add(Component.translatable(r.rank() == 0 ? "tree.office.unranked" : "tree.office.rank_n", SpellRank.roman(r.rank())));
				g.setTooltipForNextFrame(font, tip, Optional.empty(), mouseX, mouseY);
			}

			// The line, from under the icon down through every rank, filling up to the owned rank (animated).
			int thick = Math.max(1, Math.round(u));
			{
				float cx = ic.x() + ic.w() / 2f;
				float y0 = l.rankY().get(c) + 9 * u + 2 * u, y1 = centreY(l, c, 1);
				if (y1 > y0) {
					g.fill(Math.round(cx - thick), Math.round(y0), Math.round(cx + thick), Math.round(y1), UiKit.alpha(UiKit.INK_SOFT, 0.28f * k));
					float lit = Math.clamp(fill[c], 0f, 1f);
					if (lit > 0f) g.fill(Math.round(cx - thick), Math.round(y0), Math.round(cx + thick), Math.round(y0 + (y1 - y0) * lit),
							UiKit.alpha(e.argb(), k));
				}
			}
			for (int rank = 1; rank < TreeLayout.RANKS; rank++) {
				float y0 = centreY(l, c, rank), y1 = centreY(l, c, rank + 1);
				float cx = l.icons().get(c).x() + l.icons().get(c).w() / 2f;
				g.fill(Math.round(cx - thick), Math.round(y0), Math.round(cx + thick), Math.round(y1), UiKit.alpha(UiKit.INK_SOFT, 0.28f * k));
				float lit = Math.clamp(fill[c] - rank, 0f, 1f);
				if (lit > 0f) g.fill(Math.round(cx - thick), Math.round(y0), Math.round(cx + thick), Math.round(y0 + (y1 - y0) * lit),
						UiKit.alpha(e.argb(), k));
			}
			// The nodes.
			for (int rank = 1; rank <= TreeLayout.RANKS; rank++) {
				int[] options = TreeLayout.isFork(rank) ? new int[] {1, 2} : new int[] {0};
				for (int o : options) {
					Node n = l.node(c, rank, o);
					State s = state(d, e, c, rank, o);
					boolean sel = !switchFocus && c == selCol && rank == selRank && o == selOption;
					boolean hov = hover != null && hover.col() == c && hover.rank() == rank && hover.option() == o;
					node(g, n, s, e, u, pulse, sel, hov, repick, k);
					if (hov) g.setTooltipForNextFrame(font, nodeTip(d, e, c, rank, o, s), Optional.empty(), mouseX, mouseY);
				}
			}
		}
		filled = true;
		// Bursts: a ring flying out of the node and sparks, in the element's colour.
		for (Burst b : bursts) {
			Node n = l.node(b.col(), b.rank(), b.option());
			float t = (now - b.at()) / (float) BURST_MS;
			float rr = n.r() + (b.fork() ? 16 : 11) * u * UiKit.easeOut(t);
			Disc.ring(g, n.cx(), n.cy(), rr, Math.max(1f, u * (1.6f - t)), UiKit.alpha(b.argb(), (1f - t) * k));
			UiKit.glow(g, n.cx(), n.cy(), n.r() * (4f + 4f * t), UiKit.alpha(b.argb(), 0.8f * (1f - t) * k));
			for (int i = 0; i < 10; i++) {
				double a = i * Math.PI * 2 / 10 + b.at() % 7;
				float dist = n.r() + 14 * u * UiKit.easeOut(t);
				int sx = Math.round(n.cx() + (float) Math.cos(a) * dist), sy = Math.round(n.cy() + (float) Math.sin(a) * dist);
				int sz = Math.max(1, Math.round(u * (1.5f - t)));
				g.fill(sx - sz, sy - sz, sx + sz, sy + sz, UiKit.alpha(UiKit.mix(b.argb(), UiKit.WHITE, 0.4f), (1f - t) * k));
			}
		}
		// The legend and the Re-pick switch (the coach hint covers both while it shows).
		if (coach) return;
		legend(g, font, l, e, mouseX, mouseY, k);
		if (repickShown()) {
			Rect rp = l.repick();
			boolean over = GrimoireLayout.contains(rp, mouseX, mouseY) || switchFocus;
			Component t = Component.translatable(repick ? "tree.office.repick_on" : "tree.office.repick");
			float ts = fitAtLeastOne(font.width(t), rp.w(), u);
			int col = repick ? ink : over ? UiKit.INK : UiKit.INK_SOFT;
			UiKit.centred(g, font, t, rp.cx(), rp.y() + (rp.h() - 9 * ts) / 2f, ts, UiKit.alpha(col, k), false);
			if (over || repick) {
				float w = font.width(t) * ts;
				g.fill(Math.round(rp.cx() - w / 2f), Math.round(rp.y() + (rp.h() + 9 * ts) / 2f), Math.round(rp.cx() + w / 2f),
						Math.round(rp.y() + (rp.h() + 9 * ts) / 2f + Math.max(1, u * 0.6f)), UiKit.alpha(col, 0.6f * k));
			}
			if (switchFocus) {
				float w = font.width(t) * ts + 6 * u;
				UiKit.roundedOutline(g, Math.round(rp.cx() - w / 2f), rp.y(), Math.round(rp.cx() + w / 2f), rp.bottom(), UiKit.alpha(UiKit.INK, 0.9f * k));
			}
		}
	}

	/** A hovered node's tip: "Rank III · Scatter" and what it is now (owned, ready, what is short, locked). */
	private List<Component> nodeTip(MagicData d, Element e, int col, int rank, int option, State s) {
		SpellTree tree = Trees.get(SpellIds.at(e, col));
		List<Component> tip = new ArrayList<>();
		Component head = Component.translatable("tree.office.rank_n", SpellRank.roman(rank));
		if (tree != null && option != 0) head = head.copy().append(" · ").append(forkName(tree, rank, option));
		tip.add(head);
		Component what = switch (s) {
			case OWNED -> Component.translatable(option == 0 ? "tree.office.owned" : "tree.office.picked");
			case READY -> Component.translatable("tree.office.tip_ready");
			case SHORT -> shortfall(d, e, col, rank);
			case LATER -> Component.translatable("tree.office.first", SpellRank.roman(d.rank(SpellIds.at(e, col)).rank() + 1));
			case LOCKED -> Component.translatable("tree.office.unlock_at", Slots.unlockLevel(col));
			case OTHER -> Component.translatable("tree.office.repick_need");
		};
		if (what != null) tip.add(what.copy().withStyle(ChatFormatting.GRAY));
		return tip;
	}

	/** The middle of rank row {@code rank} in column {@code col} (a split row's is between its two options). */
	private static float centreY(TreeLayout.Layout l, int col, int rank) {
		return l.node(col, rank, TreeLayout.isFork(rank) ? 1 : 0).cy();
	}

	/** One node in its state (shape and mark first, colour second: readable without colour). */
	private static void node(GuiGraphicsExtractor g, Node n, State s, Element e, float u, float pulse, boolean selected, boolean hovered,
			boolean repick, float k) {
		float cx = n.cx(), cy = n.cy(), r = n.r();
		int col = e.argb();
		int ink = UiKit.inkOf(col);
		float th = Math.max(1f, u);
		// A plate behind every node so the line never shows through it.
		Disc.fill(g, cx, cy, r + th * 0.5f, UiKit.alpha(FriendsPage.paper(), k));
		switch (s) {
			case OWNED -> {
				UiKit.glow(g, cx, cy, r * 3.2f, UiKit.alpha(col, 0.45f * k));
				Disc.fill(g, cx, cy, r, UiKit.alpha(col, k));
				Disc.ring(g, cx, cy, r, th, UiKit.alpha(ink, k));
				Disc.fill(g, cx, cy, Math.max(1f, r * 0.35f), UiKit.alpha(UiKit.mix(col, UiKit.WHITE, 0.75f), k));
			}
			case READY -> {
				UiKit.glow(g, cx, cy, r * (2.6f + 1.2f * pulse), UiKit.alpha(col, (0.25f + 0.35f * pulse) * k));
				Disc.ring(g, cx, cy, r + th * pulse * 0.6f, th * 1.4f, UiKit.alpha(ink, k));
				plus(g, cx, cy, r * 0.55f, th, UiKit.alpha(ink, k));
			}
			case SHORT -> {
				// Missing materials or levels: a grey "+" and a red "!" beside the ring (the legend says so).
				Disc.ring(g, cx, cy, r, th, UiKit.alpha(UiKit.INK_SOFT, k));
				plus(g, cx, cy, r * 0.5f, th, UiKit.alpha(GREY, k));
				int bw = Math.max(1, Math.round(th));
				int bx = Math.round(cx + r + th);
				int top = Math.round(cy - r), stem = Math.round(cy + r * 0.3f);
				g.fill(bx, top, bx + bw, stem, UiKit.alpha(SHORT_RED, k));
				g.fill(bx, stem + bw, bx + bw, stem + 2 * bw, UiKit.alpha(SHORT_RED, k));
			}
			case LATER -> Disc.ring(g, cx, cy, r * 0.6f, th, UiKit.alpha(UiKit.INK_SOFT, 0.55f * k));
			case LOCKED -> {
				if (n.rank() == 1) UiKit.centredTexture(g, UiKit.PADLOCK, 16, cx, cy, Math.max(6f, r * 1.7f), 0f, UiKit.alpha(UiKit.WHITE, 0.85f * k));
				else Disc.ring(g, cx, cy, r * 0.6f, th, UiKit.alpha(UiKit.INK_SOFT, 0.35f * k));
			}
			case OTHER -> {
				int c = repick ? ink : GREY;
				if (repick) UiKit.glow(g, cx, cy, r * (2.4f + pulse), UiKit.alpha(col, 0.25f * pulse * k));
				Disc.ring(g, cx, cy, r, th, UiKit.alpha(c, k));
				int w = Math.max(1, Math.round(r * 0.5f));
				int t = Math.max(1, Math.round(th * 0.5f));
				g.fill(Math.round(cx) - w, Math.round(cy) - t, Math.round(cx) + w, Math.round(cy) + t, UiKit.alpha(c, k));
			}
		}
		if (selected || hovered) {
			float pad = r + 2.5f * u;
			int x0 = Math.round(cx - pad), y0 = Math.round(cy - pad), x1 = Math.round(cx + pad), y1 = Math.round(cy + pad);
			UiKit.roundedOutline(g, x0, y0, x1, y1, UiKit.alpha(selected ? UiKit.INK : UiKit.INK_SOFT, (selected ? 0.9f : 0.5f) * k));
		}
	}

	private static void plus(GuiGraphicsExtractor g, float cx, float cy, float arm, float th, int argb) {
		int a = Math.max(1, Math.round(arm));
		int t = Math.max(1, Math.round(th * 0.5f));
		int x = Math.round(cx), y = Math.round(cy);
		g.fill(x - a, y - t, x + a, y + t, argb);
		g.fill(x - t, y - a, x + t, y + a, argb);
	}

	/** The right page: the selected node, what it changes, its cost and the button (the button says why it can't act). */
	private void panel(GuiGraphicsExtractor g, Font font, TreeLayout.Layout l, MagicData d, Element e, int mouseX, int mouseY, long now, float k) {
		float u = l.unit();
		Rect p = l.panel();
		if (selCol < 0) return;
		Identifier id = SpellIds.at(e, selCol);
		SpellTree tree = Trees.get(id);
		if (tree == null) return;
		State s = state(d, e, selCol, selRank, selOption);
		int ink = UiKit.readable(UiKit.inkOf(e.argb()), PAGE);
		Action a = action(d, e);
		boolean shaking = now - refusedAt < SHAKE_MS;
		Component reason = refusal != null && now - refusedAt < REFUSAL_MS ? refusal : a.reason();
		TreePanel.Content content = content(d, e, tree, s, a, reason);
		TreePanel.Plan plan = TreePanel.plan(p, l.button(), u, content, font::width);
		List<Component> tip = null;

		// The top: the name, the rank line, what the rank improves, the spell's line.
		UiKit.text(g, font, Component.literal(plan.title()), p.x(), plan.titleY(), u, UiKit.alpha(selCol == Slots.ULTIMATE ? ink : UiKit.INK, k), false);
		UiKit.text(g, font, Component.literal(plan.subtitle()), p.x(), plan.subtitleY(), u,
				UiKit.alpha(s == State.LOCKED ? LOCK_RED : UiKit.INK_SOFT, k), false);
		tip = cutTip(tip, content.title(), plan.title(), p.x(), plan.titleY(), p.w(), plan.line(), mouseX, mouseY);
		tip = cutTip(tip, content.subtitle(), plan.subtitle(), p.x(), plan.subtitleY(), p.w(), plan.line(), mouseX, mouseY);
		if (TreePanel.Plan.shown(plan.improvesY())) {
			UiKit.text(g, font, Component.literal(plan.improves()), p.x(), plan.improvesY(), u, UiKit.alpha(ink, k), false);
			tip = cutTip(tip, content.improves(), plan.improves(), p.x(), plan.improvesY(), p.w(), plan.line(), mouseX, mouseY);
		}
		if (TreePanel.Plan.shown(plan.descY())) {
			UiKit.text(g, font, Component.literal(plan.desc()), p.x(), plan.descY(), u, UiKit.alpha(UiKit.INK, k), false);
			tip = cutTip(tip, content.desc(), plan.desc(), p.x(), plan.descY(), p.w(), plan.line(), mouseX, mouseY);
		}

		// The body: the fork's two cards, or the before → after table.
		if (content.cards() != null) {
			tip = forkCards(g, font, plan, content, tree, d.rank(id), selRank, selOption, e, u, mouseX, mouseY, k, tip);
		} else {
			drawChanges(g, font, p.x(), plan.bodyY(), p.w(), content.table(), plan.tableRows(), u, k, ink);
		}
		if (TreePanel.Plan.shown(plan.milestoneY())) {
			UiKit.text(g, font, Component.literal(plan.milestone()), p.x(), plan.milestoneY(), u, UiKit.alpha(UiKit.INK_SOFT, k), false);
			tip = cutTip(tip, content.milestone(), plan.milestone(), p.x(), plan.milestoneY(), p.w(), plan.line(), mouseX, mouseY);
		}

		// The bottom: one row per cost, or the note; then the button.
		if (TreePanel.Plan.shown(plan.noteY())) {
			float ny = plan.noteY();
			for (String ln : plan.note()) {
				UiKit.text(g, font, Component.literal(ln), p.x(), ny, u, UiKit.alpha(UiKit.INK_SOFT, k), false);
				ny += plan.line();
			}
		}
		tip = costRows(g, font, p, plan, u, mouseX, mouseY, k, tip);
		button(g, font, l, a, plan.button(), e, mouseX, mouseY, now, shaking, k);
		if (plan.buttonCut() && GrimoireLayout.contains(l.button(), mouseX, mouseY)) tip = List.of(Component.literal(content.button()));
		if (tip != null) g.setTooltipForNextFrame(font, tip, Optional.empty(), mouseX, mouseY);

		// What the last purchase cost, floating up from the button.
		int line = plan.line();
		for (Floater f : floaters) {
			float t = (now - f.at()) / (float) FLOAT_MS;
			float fy = l.button().y() - 4 * u - (f.index() + 1) * line - 18 * u * UiKit.easeOut(t);
			UiKit.text(g, font, f.text(), p.right() - font.width(f.text()) * u, fy, u, UiKit.alpha(UiKit.mix(ink, UiKit.WHITE, 0.1f), (1f - t) * k), false);
		}
	}

	/** A tooltip with {@code full} when the point is over a line that had to be cut to {@code shown}; else {@code tip} as it was. */
	private static @Nullable List<Component> cutTip(@Nullable List<Component> tip, @Nullable String full, String shown, float x, float y, float w,
			int line, int mx, int my) {
		if (full == null || full.equals(shown) || mx < x || mx >= x + w || my < y || my >= y + line) return tip;
		return List.of(Component.literal(full));
	}

	/** What the panel says for the selected node (see {@link TreePanel}). */
	private TreePanel.Content content(MagicData d, Element e, SpellTree tree, State s, Action a, @Nullable Component reason) {
		int rank = selRank, option = selOption;
		SpellRank cur = d.rank(tree.spell());
		boolean fork = TreeLayout.isFork(rank);
		String title = UiKit.spellName(tree.spell()).getString();
		String subtitle = subtitle(s, rank, option).getString();
		String desc = Component.translatable("spell.office." + tree.spell().getPath() + ".desc").getString();
		String improves = null;
		List<TreePanel.Row> table = List.of();
		List<TreePanel.Card> cards = null;
		if (fork) {
			cards = new ArrayList<>(2);
			SpellRank base = rank <= cur.rank() ? truncate(cur, rank - 1) : before(cur, rank, option);
			for (int o = 1; o <= 2; o++) {
				SpellRank to = rank <= cur.rank() ? truncate(cur, rank).repick(rank, o) : after(cur, rank, o);
				String name = forkName(tree, rank, o).getString();
				if (rank <= cur.rank() && cur.fork(rank) == o) name += Component.translatable("tree.office.picked_mark").getString();
				cards.add(new TreePanel.Card(name, forkDesc(tree, rank, o).getString(), changes(tree, base, to, d.level(), false)));
			}
		} else {
			SpellRank from = before(cur, rank, 0), to = after(cur, rank, 0);
			if (s == State.OWNED) {
				from = truncate(cur, rank - 1);
				to = truncate(cur, rank);
			}
			table = changes(tree, from, to, d.level(), true);
			improves = improvements(tree, from, to, d.level());
		}
		// The bottom: the re-pick note, "free" in creative, or one row per cost.
		String note = null;
		List<TreePanel.Cost> costs = new ArrayList<>();
		LocalPlayer pl = mc().player;
		if (s == State.OTHER && repick) {
			long left = repickLeftTicks(d);
			note = (left > 0 ? Component.translatable("tree.office.repick_free", (left + 19) / 20) : Component.translatable("tree.office.repick_warn")).getString();
		} else if (pl != null && s != State.OWNED && s != State.OTHER) {
			if (pl.hasInfiniteMaterials()) {
				note = Component.translatable("tree.office.free").getString();
			} else {
				RankCost.Cost cost = RankCost.of(e, selCol == Slots.ULTIMATE, rank);
				for (RankCost.Line ln : cost.lines()) {
					int have = RankCost.have(pl.getInventory(), ln);
					costs.add(new TreePanel.Cost(true, Integer.toString(ln.count()), new ItemStack(ln.icon()).getHoverName().getString(),
							Component.translatable("tree.office.have", Math.min(have, 999)).getString(), have >= ln.count()));
				}
				costs.add(new TreePanel.Cost(true, Integer.toString(cost.levels()),
						Component.translatable(cost.levels() == 1 ? "tree.office.level_word" : "tree.office.levels_word").getString(),
						Component.translatable("tree.office.have", pl.experienceLevel).getString(), pl.experienceLevel >= cost.levels()));
			}
		}
		Component label = a.label() != null ? a.label() : reason != null ? reason : Component.translatable("tree.office.rank_up");
		return new TreePanel.Content(title, subtitle, improves, desc, table, cards, milestone(tree, cur, rank), costs, note, label.getString());
	}

	/** "+15 % damage, +15 % reach, −10 % mana": each stat line the rank changes, as a share of what it was (null if none). */
	private static @Nullable String improvements(SpellTree tree, SpellRank from, SpellRank to, int level) {
		List<String> out = new ArrayList<>();
		for (SpellTree.Stat s : tree.stats()) {
			double a = s.value().at(from, level), b = s.value().at(to, level);
			String label = Component.translatable("stat.office." + s.key()).getString().toLowerCase(Locale.ROOT);
			pct(out, a, b, label);
		}
		SpellCost base = SpellCost.forSlot(tree.slot());
		SpellCost ca = CastService.cost(base, from), cb = CastService.cost(base, to);
		pct(out, ca.mana(), cb.mana(), Component.translatable("stat.office.mana").getString().toLowerCase(Locale.ROOT));
		pct(out, ca.cooldownTicks(), cb.cooldownTicks(), Component.translatable("stat.office.cooldown").getString().toLowerCase(Locale.ROOT));
		return out.isEmpty() ? null : String.join(", ", out);
	}

	private static void pct(List<String> out, double a, double b, String label) {
		if (a <= 0 || Math.abs(b - a) < 1.0E-6) return;
		long p = Math.round((b / a - 1) * 100);
		if (p == 0) return;
		out.add(Component.translatable("tree.office.improve", (p > 0 ? "+" : "−") + Math.abs(p), label).getString());
	}

	/** The next fork ahead of rank {@code rank}: "Rank V: choose Lance or Flare" (or what was picked there); null past V. */
	private static @Nullable String milestone(SpellTree tree, SpellRank cur, int rank) {
		int at = rank < 3 ? 3 : rank < 5 ? 5 : 0;
		if (at == 0) return null;
		String r = SpellRank.roman(at);
		if (cur.fork(at) != 0) return Component.translatable("tree.office.milestone_picked", r, forkName(tree, at, cur.fork(at))).getString();
		return Component.translatable("tree.office.milestone", r, forkName(tree, at, 1), forkName(tree, at, 2)).getString();
	}

	private Component subtitle(State s, int rank, int option) {
		String r = SpellRank.roman(rank);
		return switch (s) {
			case LOCKED -> Component.translatable("tree.office.unlock_at", Slots.unlockLevel(selCol));
			case OWNED -> option == 0 ? Component.translatable("tree.office.sub_owned", r) : Component.translatable("tree.office.sub_picked", r);
			case OTHER -> Component.translatable(repick ? "tree.office.sub_repick" : "tree.office.sub_other", r);
			default -> Component.translatable(option == 0 ? "tree.office.sub_bump" : "tree.office.sub_fork", r);
		};
	}

	/** The cost rows: icon (item, or an XP orb for levels), the amount in bold, the name, "(have n)" green or red at the right. */
	private @Nullable List<Component> costRows(GuiGraphicsExtractor g, Font font, Rect p, TreePanel.Plan plan, float u, int mx, int my, float k,
			@Nullable List<Component> tip) {
		LocalPlayer pl = mc().player;
		RankCost.Cost cost = pl == null || plan.costs().isEmpty() ? null : RankCost.of(data().element().orElse(Element.FIRE), selCol == Slots.ULTIMATE, selRank);
		float icon = TreePanel.ICON * u;
		for (int i = 0; i < plan.costs().size(); i++) {
			TreePanel.CostRow row = plan.costs().get(i);
			float x = p.x(), y = row.y();
			Item item = cost != null && i < cost.lines().size() ? cost.lines().get(i).icon() : null;
			if (item != null) {
				g.pose().pushMatrix();
				g.pose().translate(x, y - 0.5f * u);
				g.pose().scale(icon / 16f, icon / 16f);
				g.fakeItem(new ItemStack(item), 0, 0);
				g.pose().popMatrix();
			} else {
				// Levels: a little XP orb.
				Disc.fill(g, x + icon / 2f, y + icon / 2f - 0.5f * u, icon * 0.38f, UiKit.alpha(XP_GREEN, k));
				Disc.fill(g, x + icon / 2f - u * 0.6f, y + icon / 2f - 1.2f * u, Math.max(0.8f, icon * 0.12f), UiKit.alpha(0xFFE8FFB0, k));
			}
			x += icon + u;
			Component need = Component.literal(row.cost().need()).withStyle(ChatFormatting.BOLD);
			UiKit.text(g, font, need, x, y, u, UiKit.alpha(UiKit.INK, k), false);
			x += font.width(need) * u + font.width(" ") * u;
			UiKit.text(g, font, Component.literal(row.name()), x, y, u, UiKit.alpha(UiKit.INK, k), false);
			Component have = Component.literal(row.cost().have());
			float hw = font.width(have) * u;
			UiKit.text(g, font, have, p.right() - hw, y, u, UiKit.alpha(row.cost().ok() ? HAVE_GREEN : SHORT_RED, k), false);
			if (!row.name().equals(row.cost().name()) && mx >= p.x() && mx < p.right() && my >= y && my < y + plan.line()) {
				tip = List.of(Component.literal(row.cost().need() + " " + row.cost().name() + " " + row.cost().have()));
			}
		}
		return tip;
	}

	/** The primary button: filled in the element's colour when it can act, a quiet outline with the reason when it can't. */
	private void button(GuiGraphicsExtractor g, Font font, TreeLayout.Layout l, Action a, String text, Element e, int mouseX, int mouseY, long now,
			boolean shaking, float k) {
		float u = l.unit();
		Rect b = l.button();
		float dx = shaking ? (float) Math.sin((now - refusedAt) / 25.0) * 2.5f * u * Math.max(0f, 1f - (now - refusedAt) / (float) SHAKE_MS) : 0f;
		boolean enabled = a.label() != null;
		boolean over = GrimoireLayout.contains(b, mouseX, mouseY);
		int x0 = Math.round(b.x() + dx), x1 = Math.round(b.right() + dx);
		int ink = UiKit.inkOf(e.argb());
		if (enabled) {
			int deep = UiKit.deepen(e.argb());
			int body = over ? UiKit.mix(deep, e.argb(), 0.25f) : deep;
			if (UiKit.contrast(UiKit.WHITE, body) < 4.5) body = deep;
			UiKit.rounded(g, x0, b.y() + 1, x1, b.bottom() + 1, UiKit.alpha(0xFF000000, 0.35f * k));
			UiKit.rounded(g, x0, b.y(), x1, b.bottom(), UiKit.alpha(body, k));
			g.fill(x0 + 1, b.y(), x1 - 1, b.y() + Math.max(1, Math.round(u)), UiKit.alpha(UiKit.mix(e.argb(), UiKit.WHITE, 0.45f), k));
			UiKit.roundedOutline(g, x0, b.y(), x1, b.bottom(), UiKit.alpha(ink, k));
		} else {
			UiKit.rounded(g, x0, b.y(), x1, b.bottom(), UiKit.alpha(0xFFE3D5B4, 0.6f * k));
			UiKit.roundedOutline(g, x0, b.y(), x1, b.bottom(), UiKit.alpha(UiKit.INK_SOFT, 0.7f * k));
		}
		int col = enabled ? UiKit.WHITE : LOCK_RED;
		UiKit.centred(g, font, Component.literal(text), (x0 + x1) / 2f, b.y() + (b.h() - 9 * u) / 2f + 0.5f, u, UiKit.alpha(col, k), enabled);
	}

	/**
	 * The two cards of a fork (rank III or V), where {@link TreePanel} put them: the name, its line and the numbers it changes
	 * (before → after). The card shown (hovered or selected) is outlined; the owned one is filled; the one not picked is dimmed.
	 * Hovering a card shows all of it (its whole line and every number) in a tooltip.
	 */
	private @Nullable List<Component> forkCards(GuiGraphicsExtractor g, Font font, TreePanel.Plan plan, TreePanel.Content content, SpellTree tree,
			SpellRank cur, int rank, int option, Element e, float u, int mx, int my, float k, @Nullable List<Component> tip) {
		int line = plan.line();
		for (int o = 1; o <= 2; o++) {
			TreePanel.CardBox box = plan.cards().get(o - 1);
			TreePanel.Card card = content.cards().get(o - 1);
			boolean picked = rank <= cur.rank() && cur.fork(rank) == o;
			boolean dim = rank <= cur.rank() && !picked && !repick;
			boolean focus = o == option;
			int x0 = Math.round(box.x()), y0 = Math.round(box.y()), x1 = Math.round(box.x() + box.w()), y1 = Math.round(box.y() + box.h());
			// The option not picked dims (its frame and paper most; its text stays readable).
			float ck = (dim ? 0.75f : 1f) * k;
			int paper = picked ? UiKit.mix(CARD, e.argb(), 0.14f) : CARD;
			UiKit.rounded(g, x0, y0, x1, y1, UiKit.alpha(paper, (dim ? 0.5f : 1f) * k));
			UiKit.roundedOutline(g, x0, y0, x1, y1, UiKit.alpha(focus ? UiKit.inkOf(e.argb()) : UiKit.INK_SOFT, (focus ? 1f : 0.5f) * ck));
			if (focus) g.fill(x0 + 1, y0 + 1, x0 + 1 + Math.max(1, Math.round(u)), y1 - 1, UiKit.alpha(e.argb(), ck));
			float tx = box.x() + plan.cardInset(), ty = box.y() + plan.cardPad();
			float tw = box.w() - plan.cardInset() - plan.cardPad();
			int accent = UiKit.readable(UiKit.inkOf(e.argb()), paper);
			UiKit.text(g, font, Component.literal(box.name()), tx, ty, u, UiKit.alpha(accent, ck), false);
			ty += line;
			for (String ln : box.desc()) {
				UiKit.text(g, font, Component.literal(ln), tx, ty, u, UiKit.alpha(UiKit.INK, ck), false);
				ty += line;
			}
			drawChanges(g, font, tx, ty, tw, card.changes(), box.rows(), u, ck, accent);
			if (mx >= x0 && mx < x1 && my >= y0 && my < y1) {
				List<Component> t = new ArrayList<>();
				t.add(Component.literal(card.name()).withStyle(s -> s.withColor(UiKit.inkOf(e.argb()) & 0xFFFFFF)));
				t.add(Component.literal(card.desc()));
				for (TreePanel.Row r : card.changes()) {
					t.add(Component.literal(r.label() + "  " + r.before() + " → " + r.after()).withStyle(ChatFormatting.GRAY));
				}
				tip = t;
			}
		}
		return tip;
	}

	/** Stat lines that differ between {@code from} and {@code to}: {label, before, after}; mana and cooldown with {@code costs}. */
	private static List<TreePanel.Row> changes(SpellTree tree, SpellRank from, SpellRank to, int level, boolean costs) {
		List<TreePanel.Row> out = new ArrayList<>();
		for (SpellTree.Stat s : tree.stats()) {
			double a = s.value().at(from, level), b = s.value().at(to, level);
			String fa = format(a, s.unit()), fb = format(b, s.unit());
			if (!fa.equals(fb)) out.add(new TreePanel.Row(Component.translatable("stat.office." + s.key()).getString(), fa, fb));
		}
		SpellCost base = SpellCost.forSlot(tree.slot());
		SpellCost ca = CastService.cost(base, from), cb = CastService.cost(base, to);
		if (ca.mana() != cb.mana()) out.add(new TreePanel.Row(Component.translatable("stat.office.mana").getString(), "" + ca.mana(), "" + cb.mana()));
		if (ca.cooldownTicks() != cb.cooldownTicks()) {
			out.add(new TreePanel.Row(Component.translatable("stat.office.cooldown").getString(), seconds(ca.cooldownTicks()), seconds(cb.cooldownTicks())));
		}
		if (costs && out.isEmpty()) out.add(new TreePanel.Row(Component.translatable("tree.office.grows").getString(), "", ""));
		return out;
	}

	/** "Label   before → after" lines, the values right-aligned; the label cut with "…" where it would meet them. */
	private static void drawChanges(GuiGraphicsExtractor g, Font font, float x, float y, float w, List<TreePanel.Row> changes, int max, float u, float k,
			int accent) {
		int line = Math.round(10 * u);
		for (int i = 0; i < Math.min(max, changes.size()); i++) {
			TreePanel.Row c = changes.get(i);
			float right = x + w;
			float valuesW = 0;
			if (!c.before().isEmpty() || !c.after().isEmpty()) {
				Component after = Component.literal(c.after());
				Component arrow = Component.literal(" → ");
				Component before = Component.literal(c.before());
				float aw = font.width(after) * u, rw = font.width(arrow) * u, bw = font.width(before) * u;
				// No room for both and a short label: just the new value.
				boolean both = font.width("Damage") * u + aw + rw + bw + 2 * u <= w;
				UiKit.text(g, font, after, right - aw, y, u, UiKit.alpha(accent, k), false);
				valuesW = aw;
				if (both) {
					UiKit.text(g, font, arrow, right - aw - rw, y, u, UiKit.alpha(UiKit.INK_SOFT, k), false);
					UiKit.text(g, font, before, right - aw - rw - bw, y, u, UiKit.alpha(UiKit.INK, k), false);
					valuesW = aw + rw + bw;
				}
			}
			String label = TreePanel.fit(c.label(), w - valuesW - (valuesW > 0 ? 2 * u : 0), u, font::width);
			UiKit.text(g, font, Component.literal(label), x, y, u, UiKit.alpha(UiKit.INK_SOFT, k), false);
			y += line;
		}
	}

	/**
	 * The one-time coach hint, over the legend and the Re-pick line (it hides no node and no icon): a dark note with a small pointer
	 * on its top edge under the first node that can be bought, which also gets a beckoning ring.
	 */
	private void coach(GuiGraphicsExtractor g, Font font, TreeLayout.Layout l, MagicData d, Element e, long now, float k) {
		int[] next = firstReady(d, e);
		if (next == null) {
			coach = false;
			return;
		}
		Node n = l.node(next[0], next[1], next[2]);
		float u = l.unit();
		Rect box = l.coach();
		float ck = UiKit.clamp01((now - shownAt - 600) / 300f) * k;
		if (ck <= 0f) return;
		Component text = Component.translatable("tree.office.coach");
		int lines = UiKit.wrapLines(font, text, box.w() - 8 * u, u, Math.max(1, (int) ((box.h() - 4 * u) / (10 * u))), this.lines);
		float h = lines * 10 * u + 5 * u;
		int x0 = box.x(), x1 = box.right();
		int y1 = box.bottom(), y0 = Math.round(y1 - h);
		UiKit.rounded(g, x0, y0 + 1, x1, y1 + 1, UiKit.alpha(0xFF000000, 0.4f * ck));
		UiKit.rounded(g, x0, y0, x1, y1, UiKit.alpha(0xFF2A1E14, 0.95f * ck));
		UiKit.roundedOutline(g, x0, y0, x1, y1, UiKit.alpha(e.argb(), ck));
		// The pointer: a small triangle up from the note's top edge, under the node's column.
		int tip = Math.max(2, Math.round(3 * u));
		int px = Math.clamp(Math.round(n.cx()), x0 + tip + 2, x1 - tip - 2);
		for (int i = 0; i < tip; i++) g.fill(px - (tip - i), y0 - i - 1, px + (tip - i), y0 - i, UiKit.alpha(0xFF2A1E14, 0.95f * ck));
		float ty = y0 + 3 * u;
		for (FormattedCharSequence ln : this.lines) {
			UiKit.text(g, font, ln, x0 + 4 * u, ty, u, UiKit.alpha(0xFFF0E2BE, ck), false);
			ty += 10 * u;
		}
		// The node it means beckons.
		float bob = 0.5f + 0.5f * (float) Math.sin(now / 200.0);
		Disc.ring(g, n.cx(), n.cy(), n.r() + (2 + 2 * bob) * u, Math.max(1f, u), UiKit.alpha(e.argb(), (0.5f + 0.5f * bob) * ck));
	}

	/**
	 * The legend under the tree: what each node mark means (owned, can rank up now, missing materials, locked), drawn with the
	 * marks themselves. The long words where they fit, else the short ones; hovering an entry shows its long words.
	 */
	private void legend(GuiGraphicsExtractor g, Font font, TreeLayout.Layout l, Element e, int mouseX, int mouseY, float k) {
		float u = l.unit();
		State[] states = {State.OWNED, State.READY, State.SHORT, State.LOCKED};
		String[] keys = {"owned", "ready", "short", "locked"};
		int hover = l.legendAt(mouseX, mouseY);
		for (int i = 0; i < TreeLayout.LEGEND; i++) {
			Rect cell = l.legend().get(i);
			float r = Math.min(3.5f * u, cell.h() * 0.32f);
			float mx = cell.x() + r + u, my = cell.y() + cell.h() / 2f;
			// The locked mark is the padlock of a first rank.
			node(g, new Node(0, states[i] == State.LOCKED ? 1 : 2, 0, mx, my, r), states[i], e, u, 0.6f, false, false, false, k);
			Component word = Component.translatable("tree.office.legend_" + keys[i]);
			float room = cell.w() - (2 * r + 3 * u);

			if (font.width(word) * u > room) word = Component.translatable("tree.office.legend_" + keys[i] + "_short");
			String shown = TreePanel.fit(word.getString(), room, u, font::width);
			UiKit.text(g, font, Component.literal(shown), mx + r + 2 * u, cell.y() + (cell.h() - 9 * u) / 2f, u, UiKit.alpha(UiKit.INK_SOFT, k), false);
			if (i == hover) g.setTooltipForNextFrame(font, Component.translatable("tree.office.legend_" + keys[i] + "_tip"), mouseX, mouseY);
		}
	}

	/** The crisp scale at most {@code max} at which {@code width} fits {@code room}, but never below 1 (the vanilla font size). */
	static float fitAtLeastOne(float width, float room, float max) {
		return Math.max(1f, UiKit.fit(width, room, max));
	}

	static String format(double v, SpellTree.Stat.Unit unit) {
		return switch (unit) {
			case DAMAGE, BLOCKS -> one(v);
			case SECONDS -> Component.translatable("tree.office.unit_s", one(v)).getString();
			case COUNT -> Long.toString(Math.round(v));
			case PERCENT -> Component.translatable("tree.office.unit_pct", Math.round(v)).getString();
			case DEGREES -> Component.translatable("tree.office.unit_deg", Math.round(v)).getString();
		};
	}

	private static String one(double v) {
		double r = Math.round(v * 10) / 10.0;
		return r == Math.rint(r) ? Long.toString(Math.round(r)) : String.format(Locale.ROOT, "%.1f", r);
	}

	static String seconds(long ticks) {
		return Component.translatable("tree.office.unit_s", one(ticks / 20.0)).getString();
	}
}
