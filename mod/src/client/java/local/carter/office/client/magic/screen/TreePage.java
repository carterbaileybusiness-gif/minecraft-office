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
	/** The book's page and the cards' paper (text colours are checked against them for 4.5:1). */
	private static final int PAGE = 0xFFF8EED0;
	private static final int CARD = 0xFFF3E8CC;
	private static final long BURST_MS = 650;
	private static final long FLOAT_MS = 1300;
	private static final long PENDING_MS = 1500;
	/** How long a refusal shakes the button, and how long the server's reason stays up. */
	private static final long SHAKE_MS = 450;
	private static final long REFUSAL_MS = 2500;
	private static final long REPICK_MS = TreeService.REPICK_TICKS * 50;

	private final GrimoireScreen screen;
	/** The selected node: column, rank, option (0 = bump). */
	private int selCol = -1;
	private int selRank = 1;
	private int selOption;
	private @Nullable Node hover;
	private boolean repick;
	private long repickUntil;
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

	public void select(int col, int rank, int option) {
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
			repick = !repick;
			play(SimpleSoundInstance.forUI(SoundEvents.BOOK_PAGE_TURN, 0.9f, 0.8f));
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
		if (event.isConfirmation() || event.isSelection()) {
			press(d);
			return true;
		}
		if (event.isUp() || event.isDown()) {
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
			// A re-pick that spent a Seal opened the server's window: the client counts it down too.
			if (p != null && p.repick() && repickUntil < now) repickUntil = now + REPICK_MS;
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
		return repick || hasSeal() || repickUntil > Util.getMillis();
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
				boolean free = repickUntil > Util.getMillis() || (mc().player != null && mc().player.hasInfiniteMaterials());
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
					boolean sel = c == selCol && rank == selRank && o == selOption;
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
		// The Re-pick switch.
		if (repickShown()) {
			Rect rp = l.repick();
			boolean over = GrimoireLayout.contains(rp, mouseX, mouseY);
			Component t = Component.translatable(repick ? "tree.office.repick_on" : "tree.office.repick");
			float ts = fitAtLeastOne(font.width(t), rp.w(), u);
			int col = repick ? ink : over ? UiKit.INK : UiKit.INK_SOFT;
			UiKit.centred(g, font, t, rp.cx(), rp.y() + (rp.h() - 9 * ts) / 2f, ts, UiKit.alpha(col, k), false);
			if (over || repick) {
				float w = font.width(t) * ts;
				g.fill(Math.round(rp.cx() - w / 2f), Math.round(rp.y() + (rp.h() + 9 * ts) / 2f), Math.round(rp.cx() + w / 2f),
						Math.round(rp.y() + (rp.h() + 9 * ts) / 2f + Math.max(1, u * 0.6f)), UiKit.alpha(col, 0.6f * k));
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
				Disc.ring(g, cx, cy, r, th, UiKit.alpha(UiKit.INK_SOFT, k));
				plus(g, cx, cy, r * 0.5f, th, UiKit.alpha(GREY, k));
				Disc.fill(g, cx + r * 0.75f, cy + r * 0.75f, Math.max(1.2f, r * 0.32f), UiKit.alpha(SHORT_RED, k));
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
		int col = selCol;
		int rank = selRank;
		int option = selOption;
		if (col < 0) return;
		Identifier id = SpellIds.at(e, col);
		SpellTree tree = Trees.get(id);
		if (tree == null) return;
		SpellRank cur = d.rank(id);
		State s = state(d, e, col, rank, option);
		int ink = UiKit.readable(UiKit.inkOf(e.argb()), PAGE);
		int line = Math.round(10 * u);
		float y = p.y();

		// Title: the spell, then "Rank III · pick one" (or what the node is).
		Component name = UiKit.spellName(id);
		float ns = fitAtLeastOne(font.width(name), p.w(), u);
		UiKit.text(g, font, name, p.x(), y, ns, UiKit.alpha(col == Slots.ULTIMATE ? ink : UiKit.INK, k), false);
		y += 10 * ns;
		Component sub = subtitle(s, rank, option, tree);
		y = wrapped(g, font, sub, p.x(), y, p.w(), u, 2, s == State.LOCKED ? LOCK_RED : UiKit.INK_SOFT, k) + 2 * u;

		// The bottom block: cost (or the re-pick warning), the reason, the button. Drawn from the bottom up.
		Action a = action(d, e);
		boolean shaking = now - refusedAt < SHAKE_MS;
		Component reason = refusal != null && now - refusedAt < REFUSAL_MS ? refusal : a.reason();
		boolean reasonOnButton = reason != null && font.width(reason) * u <= l.button().w() - 6 * u;
		float bottom = l.button().y() - 2 * u;
		if (reason != null && !reasonOnButton) {
			int n = UiKit.wrapLines(font, reason, p.w(), u, 2, lines);
			bottom -= n * line;
			float ry = bottom;
			for (FormattedCharSequence ln : lines) {
				UiKit.text(g, font, ln, p.x(), ry, u, UiKit.alpha(LOCK_RED, k), false);
				ry += line;
			}
			bottom -= 2 * u;
		}
		button(g, font, l, a, reasonOnButton ? reason : null, e, mouseX, mouseY, now, shaking, k);
		bottom = costBlock(g, font, p, bottom, d, e, col, rank, s, u, mouseX, mouseY, k);

		// The body, between the title and the cost: before → after, or the fork's two cards.
		float room = bottom - y - 2 * u;
		if (TreeLayout.isFork(rank)) {
			forkCards(g, font, p, y, room, tree, cur, rank, option, s, d.level(), e, u, k);
		} else {
			SpellRank from = before(cur, rank, 0), to = after(cur, rank, 0);
			if (s == State.OWNED) {
				from = truncate(cur, rank - 1);
				to = truncate(cur, rank);
			}
			int max = Math.max(1, (int) (room / line));
			List<String[]> changes = changes(tree, from, to, d.level(), true);
			drawChanges(g, font, p.x(), y, p.w(), changes, max, u, k, ink);
		}

		// What the last purchase cost, floating up from the button.
		for (Floater f : floaters) {
			float t = (now - f.at()) / (float) FLOAT_MS;
			float fy = l.button().y() - 4 * u - (f.index() + 1) * line - 18 * u * UiKit.easeOut(t);
			UiKit.text(g, font, f.text(), p.right() - font.width(f.text()) * u, fy, u, UiKit.alpha(UiKit.mix(ink, UiKit.WHITE, 0.1f), (1f - t) * k), false);
		}
	}

	private Component subtitle(State s, int rank, int option, SpellTree tree) {
		String r = SpellRank.roman(rank);
		return switch (s) {
			case OWNED -> option == 0 ? Component.translatable("tree.office.sub_owned", r) : Component.translatable("tree.office.sub_picked", r);
			case OTHER -> Component.translatable(repick ? "tree.office.sub_repick" : "tree.office.sub_other", r);
			default -> Component.translatable(option == 0 ? "tree.office.sub_bump" : "tree.office.sub_fork", r);
		};
	}

	/** The cost line (have/need per item, red when short; levels), or the re-pick warning; returns the y above it. */
	private float costBlock(GuiGraphicsExtractor g, Font font, Rect p, float bottom, MagicData d, Element e, int col, int rank, State s,
			float u, int mx, int my, float k) {
		int line = Math.round(10 * u);
		LocalPlayer pl = mc().player;
		if (pl == null) return bottom;
		if (s == State.OTHER && repick) {
			boolean free = repickUntil > Util.getMillis();
			Component warn = free ? Component.translatable("tree.office.repick_free", (repickUntil - Util.getMillis()) / 1000)
					: Component.translatable("tree.office.repick_warn");
			int n = UiKit.wrapLines(font, warn, p.w(), u, 3, lines);
			float y = bottom - n * line;
			float yy = y;
			for (FormattedCharSequence ln : lines) {
				UiKit.text(g, font, ln, p.x(), yy, u, UiKit.alpha(UiKit.INK_SOFT, k), false);
				yy += line;
			}
			return y - 2 * u;
		}
		if (s == State.OWNED || s == State.OTHER) return bottom;
		if (pl.hasInfiniteMaterials()) {
			Component free = Component.translatable("tree.office.free");
			UiKit.text(g, font, free, p.x(), bottom - line, u, UiKit.alpha(UiKit.INK_SOFT, k), false);
			return bottom - line - 2 * u;
		}
		RankCost.Cost cost = RankCost.of(e, col == Slots.ULTIMATE, rank);
		// Entries: [icon] have/need … then "lv have/need"; they wrap onto a second line if the page is narrow.
		List<Entry> entries = new ArrayList<>();
		for (RankCost.Line l : cost.lines()) {
			int have = RankCost.have(pl.getInventory(), l);
			entries.add(new Entry(l.icon(), Component.literal(Math.min(have, 999) + "/" + l.count()), have >= l.count(),
					new ItemStack(l.icon()).getHoverName()));
		}
		entries.add(new Entry(null, Component.translatable("tree.office.levels", pl.experienceLevel, cost.levels()), pl.experienceLevel >= cost.levels(),
				null));
		float icon = 9 * u;
		float gap = 4 * u;
		List<List<Entry>> rows = new ArrayList<>();
		List<Entry> row = new ArrayList<>();
		float w = font.width(Component.translatable("tree.office.cost")) * u + gap;
		for (Entry en : entries) {
			float ew = (en.icon != null ? icon + u : 0) + font.width(en.text) * u + gap;
			if (!row.isEmpty() && w + ew > p.w()) {
				rows.add(row);
				row = new ArrayList<>();
				w = 0;
			}
			row.add(en);
			w += ew;
		}
		rows.add(row);
		float y0 = bottom - rows.size() * line;
		float y = y0;
		for (int i = 0; i < rows.size(); i++) {
			float x = p.x();
			if (i == 0) {
				Component label = Component.translatable("tree.office.cost");
				UiKit.text(g, font, label, x, y, u, UiKit.alpha(UiKit.INK_SOFT, k), false);
				x += font.width(label) * u + gap;
			}
			for (Entry en : rows.get(i)) {
				float x0 = x;
				if (en.icon != null) {
					g.pose().pushMatrix();
					g.pose().translate(x, y - 0.5f * u);
					g.pose().scale(icon / 16f, icon / 16f);
					g.fakeItem(new ItemStack(en.icon), 0, 0);
					g.pose().popMatrix();
					x += icon + u;
				}
				UiKit.text(g, font, en.text, x, y, u, UiKit.alpha(en.ok ? UiKit.INK : SHORT_RED, k), false);
				x += font.width(en.text) * u;
				if (en.name != null && mx >= x0 && mx < x && my >= y && my < y + line) g.setTooltipForNextFrame(font, en.name, mx, my);
				x += gap;
			}
			y += line;
		}
		return y0 - 2 * u;
	}

	private record Entry(@Nullable Item icon, Component text, boolean ok, @Nullable Component name) {}

	/** The primary button: filled in the element's colour when it can act, a quiet outline with the reason when it can't. */
	private void button(GuiGraphicsExtractor g, Font font, TreeLayout.Layout l, Action a, @Nullable Component reason, Element e, int mouseX,
			int mouseY, long now, boolean shaking, float k) {
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
		Component text = enabled ? a.label() : reason != null ? reason : Component.translatable("tree.office.rank_up");
		float ts = fitAtLeastOne(font.width(text), b.w() - 6 * u, u);
		int col = enabled ? UiKit.WHITE : (reason != null ? LOCK_RED : UiKit.INK_SOFT);
		UiKit.centred(g, font, text, (x0 + x1) / 2f, b.y() + (b.h() - 9 * ts) / 2f + 0.5f, ts, UiKit.alpha(col, k), enabled);
	}

	/**
	 * The two cards of a fork (rank III or V): side by side when the page is wide enough, else stacked. Each: the name, its one
	 * line and the numbers it changes (before → after). The card shown (hovered or selected) is outlined; the owned one is
	 * filled; the one not picked is dimmed.
	 */
	private void forkCards(GuiGraphicsExtractor g, Font font, Rect p, float y, float room, SpellTree tree, SpellRank cur, int rank, int option,
			State s, int level, Element e, float u, float k) {
		int line = Math.round(10 * u);
		float pad = 2 * u;
		boolean side = p.w() >= 150 * u;
		float cardW = side ? (p.w() - 3 * u) / 2f : p.w();
		float textW = cardW - 2 * pad;
		SpellRank base = rank <= cur.rank() ? truncate(cur, rank - 1) : before(cur, rank, option);
		int[] descLines = new int[3];
		List<List<String[]>> changes = new ArrayList<>(2);
		for (int o = 1; o <= 2; o++) {
			SpellRank to = rank <= cur.rank() ? truncate(cur, rank).repick(rank, o) : after(cur, rank, o);
			changes.add(changes(tree, base, to, level, false));
		}
		// Fit: as many lines of the description and changes as the room allows (stacked cards share it).
		int maxDesc = 3, maxChanges = 2;
		while (true) {
			float h = 0;
			for (int o = 1; o <= 2; o++) {
				int dl = UiKit.wrapLines(font, forkDesc(tree, rank, o), textW, u, maxDesc, lines);
				descLines[o] = dl;
				float ch = line + dl * line + Math.min(maxChanges, changes.get(o - 1).size()) * line + 2 * pad;
				h = side ? Math.max(h, ch) : h + ch + 2 * u;
			}
			if (h <= room || (maxDesc == 1 && maxChanges == 0)) break;
			if (maxChanges > 0 && maxChanges >= maxDesc) maxChanges--;
			else if (maxDesc > 1) maxDesc--;
			else maxChanges = 0;
		}
		float cy = y;
		// Side by side, both cards take the taller one's height.
		float tallest = 0;
		for (int o = 1; o <= 2; o++) {
			tallest = Math.max(tallest, line + descLines[o] * line + Math.min(maxChanges, changes.get(o - 1).size()) * line + 2 * pad);
		}
		for (int o = 1; o <= 2; o++) {
			float cx = side ? p.x() + (o - 1) * (cardW + 3 * u) : p.x();
			int nChanges = Math.min(maxChanges, changes.get(o - 1).size());
			float ch = side ? tallest : line + descLines[o] * line + nChanges * line + 2 * pad;
			boolean picked = rank <= cur.rank() && cur.fork(rank) == o;
			boolean dim = rank <= cur.rank() && !picked && !repick;
			boolean focus = o == option;
			int x0 = Math.round(cx), y0 = Math.round(cy), x1 = Math.round(cx + cardW), y1 = Math.round(cy + ch);
			// The option not picked dims (its frame and paper most; its text stays readable).
			float ck = (dim ? 0.75f : 1f) * k;
			int paper = picked ? UiKit.mix(CARD, e.argb(), 0.14f) : CARD;
			UiKit.rounded(g, x0, y0, x1, y1, UiKit.alpha(paper, (dim ? 0.5f : 1f) * k));
			UiKit.roundedOutline(g, x0, y0, x1, y1, UiKit.alpha(focus ? UiKit.inkOf(e.argb()) : UiKit.INK_SOFT, (focus ? 1f : 0.5f) * ck));
			if (focus) g.fill(x0 + 1, y0 + 1, x0 + 1 + Math.max(1, Math.round(u)), y1 - 1, UiKit.alpha(e.argb(), ck));
			float tx = cx + pad + (focus ? u : 0), ty = cy + pad;
			Component name = forkName(tree, rank, o);
			if (picked) name = name.copy().append(Component.translatable("tree.office.picked_mark"));
			float ns = fitAtLeastOne(font.width(name), textW, u);
			UiKit.text(g, font, name, tx, ty, ns, UiKit.alpha(UiKit.readable(UiKit.inkOf(e.argb()), paper), ck), false);
			ty += line;
			UiKit.wrapLines(font, forkDesc(tree, rank, o), textW, u, descLines[o], lines);
			for (FormattedCharSequence ln : lines) {
				UiKit.text(g, font, ln, tx, ty, u, UiKit.alpha(UiKit.INK, ck), false);
				ty += line;
			}
			drawChanges(g, font, tx, ty, textW, changes.get(o - 1), nChanges, u, ck, UiKit.readable(UiKit.inkOf(e.argb()), paper));
			if (!side) cy += ch + 2 * u;
		}
	}

	/** Stat lines that differ between {@code from} and {@code to}: {label, before, after}; mana and cooldown with {@code costs}. */
	private static List<String[]> changes(SpellTree tree, SpellRank from, SpellRank to, int level, boolean costs) {
		List<String[]> out = new ArrayList<>();
		for (SpellTree.Stat s : tree.stats()) {
			double a = s.value().at(from, level), b = s.value().at(to, level);
			String fa = format(a, s.unit()), fb = format(b, s.unit());
			if (!fa.equals(fb)) out.add(new String[] {Component.translatable("stat.office." + s.key()).getString(), fa, fb});
		}
		SpellCost base = SpellCost.forSlot(tree.slot());
		SpellCost ca = CastService.cost(base, from), cb = CastService.cost(base, to);
		if (ca.mana() != cb.mana()) out.add(new String[] {Component.translatable("stat.office.mana").getString(), "" + ca.mana(), "" + cb.mana()});
		if (ca.cooldownTicks() != cb.cooldownTicks()) {
			out.add(new String[] {Component.translatable("stat.office.cooldown").getString(), seconds(ca.cooldownTicks()), seconds(cb.cooldownTicks())});
		}
		if (costs && out.isEmpty()) out.add(new String[] {Component.translatable("tree.office.grows").getString(), "", ""});
		return out;
	}

	/** "Label   before → after" lines, the values right-aligned. */
	private static void drawChanges(GuiGraphicsExtractor g, Font font, float x, float y, float w, List<String[]> changes, int max, float u, float k,
			int accent) {
		int line = Math.round(10 * u);
		for (int i = 0; i < Math.min(max, changes.size()); i++) {
			String[] c = changes.get(i);
			Component label = Component.literal(c[0]);
			UiKit.text(g, font, label, x, y, u, UiKit.alpha(UiKit.INK_SOFT, k), false);
			if (!c[1].isEmpty() || !c[2].isEmpty()) {
				Component after = Component.literal(c[2]);
				Component arrow = Component.literal(" → ");
				Component before = Component.literal(c[1]);
				float right = x + w;
				float aw = font.width(after) * u, rw = font.width(arrow) * u, bw = font.width(before) * u;
				float lw = font.width(label) * u;
				// No room for both: just the new value.
				if (lw + aw + rw + bw + 2 * u > w) {
					UiKit.text(g, font, after, right - aw, y, u, UiKit.alpha(accent, k), false);
				} else {
					UiKit.text(g, font, after, right - aw, y, u, UiKit.alpha(accent, k), false);
					UiKit.text(g, font, arrow, right - aw - rw, y, u, UiKit.alpha(UiKit.INK_SOFT, k), false);
					UiKit.text(g, font, before, right - aw - rw - bw, y, u, UiKit.alpha(UiKit.INK, k), false);
				}
			}
			y += line;
		}
	}

	/** Wraps {@code text} into at most {@code max} lines at {@code u} (never smaller), draws them; returns the y under them. */
	private float wrapped(GuiGraphicsExtractor g, Font font, Component text, float x, float y, float w, float u, int max, int argb, float k) {
		UiKit.wrapLines(font, text, w, u, max, lines);
		for (FormattedCharSequence ln : lines) {
			UiKit.text(g, font, ln, x, y, u, UiKit.alpha(argb, k), false);
			y += 10 * u;
		}
		return y;
	}

	/** The one-time coach hint: a bubble over the first node that can be bought, pointing at it. */
	private void coach(GuiGraphicsExtractor g, Font font, TreeLayout.Layout l, MagicData d, Element e, long now, float k) {
		int[] next = firstReady(d, e);
		if (next == null) {
			coach = false;
			return;
		}
		Node n = l.node(next[0], next[1], next[2]);
		float u = l.unit();
		Component text = Component.translatable("tree.office.coach");
		float w = font.width(text) * u + 8 * u, h = 9 * u + 6 * u;
		float bob = (float) Math.sin(now / 300.0) * u;
		float bx = Math.max(2, Math.min(n.cx() - w / 2f, mc().getWindow().getGuiScaledWidth() - w - 2));
		float by = n.cy() - n.r() - 5 * u - h + bob;
		float ck = UiKit.clamp01((now - shownAt - 600) / 300f) * k;
		if (ck <= 0f) return;
		int x0 = Math.round(bx), y0 = Math.round(by), x1 = Math.round(bx + w), y1 = Math.round(by + h);
		UiKit.rounded(g, x0, y0 + 1, x1, y1 + 1, UiKit.alpha(0xFF000000, 0.4f * ck));
		UiKit.rounded(g, x0, y0, x1, y1, UiKit.alpha(0xFF2A1E14, 0.95f * ck));
		UiKit.roundedOutline(g, x0, y0, x1, y1, UiKit.alpha(e.argb(), ck));
		// The pointer: a small triangle down to the node.
		int tip = Math.max(2, Math.round(3 * u));
		int px = Math.round(n.cx());
		for (int i = 0; i < tip; i++) g.fill(px - (tip - i), y1 + i, px + (tip - i), y1 + i + 1, UiKit.alpha(0xFF2A1E14, 0.95f * ck));
		UiKit.text(g, font, text, bx + 4 * u, by + 3 * u, u, UiKit.alpha(0xFFF0E2BE, ck), false);
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
