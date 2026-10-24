package local.carter.office.client.magic.screen;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import com.mojang.blaze3d.platform.InputConstants;
import org.jspecify.annotations.Nullable;

import local.carter.office.client.magic.MagicClient;
import local.carter.office.client.magic.screen.SignLayout.Rect;
import local.carter.office.magic.cast.CastService;
import local.carter.office.magic.core.Element;
import local.carter.office.magic.core.LevelCurve;
import local.carter.office.magic.core.Slots;
import local.carter.office.magic.data.MagicAttachments;
import local.carter.office.magic.data.MagicData;
import local.carter.office.magic.item.GrimoireItem;
import local.carter.office.magic.net.MagicPayloads.SelectSpell;
import local.carter.office.magic.spell.SpellCost;
import local.carter.office.magic.spell.SpellIds;
import local.carter.office.magic.spell.SpellRegistry;

import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.client.resources.sounds.SimpleSoundInstance;
import net.minecraft.core.GlobalPos;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.util.FormattedCharSequence;
import net.minecraft.util.Util;

import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;

/**
 * Reading your grimoire (main-hand use while bound). Index tabs on the book's left edge switch between Spells, Friends, How
 * to cast and Skill tree (click them, or Left / Right step through them); the Friends tab is {@link FriendsPage} (Travel to a
 * friend), How to cast is two pages of handbook text, Skill tree is {@link TreePage} (ranks and forks; there the arrows move
 * between nodes, and Left past the first column turns back a tab). Spells tab: left page: your element's sigil, the grimoire level, xp to the next
 * level and your Recall home. Right page: the six spells of your wheel (icon, name, mana, cooldown; locked ones greyed with a
 * padlock and the level that unlocks them); clicking an unlocked one (or Enter / 1–6) selects it, marked by a ribbon in your
 * element's colour. Footer: how to cast from the off hand. Nothing scrolls; not a pause screen.
 */
public final class GrimoireScreen extends Screen {
	private static final long OPEN_MS = 380;
	private static final long ROW_DELAY_MS = 200;
	private static final long ROW_STAGGER_MS = 45;
	private static final long ROW_MS = 260;
	private static final int MANA_BLUE = 0xFF3D7BE0;
	private static final int LOCK_RED = 0xFF9A3B2E;

	private final long openedAt = Util.getMillis();
	private long lastFrame = openedAt;
	private int hover = -1;
	private int focus = -1;
	private final float[] glow = new float[GrimoireLayout.ROWS];
	private @Nullable Identifier pending;
	private long pendingAt;
	private int shakeRow = -1;
	private long shakeAt;
	private float ribbonY = Float.NaN;
	private final List<FormattedCharSequence> lines = new ArrayList<>();
	/** {@link #TAB_SPELLS}, {@link #TAB_FRIENDS} or {@link #TAB_HOWTO}. */
	private int tab;
	/** When the spell rows start sliding in (the opening, or a switch back to Spells). */
	private long rowsFrom = openedAt;
	private long tabAt = openedAt - 1000;
	private final float[] tabHover = new float[GrimoireLayout.TABS];
	private final FriendsPage friends = new FriendsPage(this);
	private final TreePage tree = new TreePage(this);
	public static final int TAB_SPELLS = 0, TAB_FRIENDS = 1, TAB_HOWTO = 2, TAB_TREE = 3;

	public GrimoireScreen() {
		super(Component.translatable("item.office.grimoire"));
	}

	public GrimoireLayout.Layout layout() {
		return GrimoireLayout.layout(width, height);
	}

	public int tab() {
		return tab;
	}

	public FriendsPage friendsPage() {
		return friends;
	}

	public TreePage treePage() {
		return tree;
	}

	/** Shows tab {@code t} (0 to {@link GrimoireLayout#TABS} - 1). */
	public void setTab(int t) {
		if (t == tab || t < 0 || t >= GrimoireLayout.TABS) return;
		tab = t;
		tabAt = Util.getMillis();
		focus = -1;
		if (t == TAB_FRIENDS) friends.shown();
		else if (t == TAB_TREE) tree.shown();
		else rowsFrom = tabAt - ROW_DELAY_MS;
		play(SimpleSoundInstance.forUI(SoundEvents.BOOK_PAGE_TURN, 1.0f, 0.8f));
	}

	public boolean introDone() {
		return Util.getMillis() - openedAt >= ROW_DELAY_MS + 5 * ROW_STAGGER_MS + ROW_MS + 30;
	}

	/** Closing it with the Grimoire still in the main hand: it only opens here, so say where it casts from (rate limited). */
	@Override
	public void onClose() {
		super.onClose();
		var player = minecraft.player;
		if (player != null && player.isAlive() && GrimoireItem.isGrimoire(player.getMainHandItem())
				&& data().element().isPresent() && MagicClient.holdHint().tryShow()) {
			minecraft.gui.hud.setOverlayMessage(Component.translatable("magic.office.hold_hint",
					minecraft.options.keySwapOffhand.getTranslatedKeyMessage()), false);
		}
	}

	@Override
	public boolean isPauseScreen() {
		return false;
	}

	@Override
	public void tick() {
		if (minecraft.player == null || !minecraft.player.isAlive() || data().element().isEmpty()) {
			onClose();
			return;
		}
		if (tab == TAB_FRIENDS) friends.tick();
	}

	private MagicData data() {
		return minecraft.player == null ? MagicData.EMPTY : MagicAttachments.get(minecraft.player);
	}

	private Element element() {
		return data().element().orElse(Element.FIRE);
	}

	private long gameTime() {
		return minecraft.level == null ? 0 : minecraft.level.getGameTime();
	}

	// ---------------------------------------------------------------- input

	@Override
	public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
		GrimoireLayout.Layout l = layout();
		int t = l.tabAt(event.x(), event.y());
		if (t >= 0) {
			setTab(t);
			return true;
		}
		if (tab == TAB_FRIENDS) return friends.click(l, event.x(), event.y()) || super.mouseClicked(event, doubleClick);
		if (tab == TAB_TREE) return tree.click(l, event.x(), event.y()) || super.mouseClicked(event, doubleClick);
		if (tab != TAB_SPELLS) return super.mouseClicked(event, doubleClick);
		int row = l.rowAt(event.x(), event.y());
		if (row >= 0) {
			select(row);
			return true;
		}
		return super.mouseClicked(event, doubleClick);
	}

	@Override
	public boolean keyPressed(KeyEvent event) {
		// The tree's own arrows first: they move between its nodes (Left past the first column falls through to the tabs).
		if (tab == TAB_TREE && tree.key(event)) return true;
		if (event.isLeft() || event.isRight()) {
			setTab(Math.clamp(tab + (event.isLeft() ? -1 : 1), 0, GrimoireLayout.TABS - 1));
			return true;
		}
		if (tab == TAB_FRIENDS) return friends.key(event) || super.keyPressed(event);
		if (tab != TAB_SPELLS) return super.keyPressed(event);
		if (event.isUp() || event.isDown() || event.isCycleFocus()) {
			boolean back = event.isUp() || (event.isCycleFocus() && event.hasShiftDown());
			int from = focus >= 0 ? focus : selectedRow();
			focus = Math.floorMod((from < 0 ? 0 : from) + (back ? -1 : 1), GrimoireLayout.ROWS);
			hover = -1;
			return true;
		}
		if ((event.isSelection() || event.isConfirmation()) && focus >= 0) {
			select(focus);
			return true;
		}
		int k = event.key();
		if (k >= InputConstants.KEY_1 && k <= InputConstants.KEY_6) {
			focus = k - InputConstants.KEY_1;
			select(focus);
			return true;
		}
		return super.keyPressed(event);
	}

	/** Selects the spell in {@code row} (slot) if it is unlocked; a locked one shakes its padlock. */
	public void select(int row) {
		MagicData d = data();
		if (d.element().isEmpty()) return;
		Identifier id = SpellIds.at(d.element().get(), row);
		if (d.level() < Slots.unlockLevel(row)) {
			shakeRow = row;
			shakeAt = Util.getMillis();
			play(SimpleSoundInstance.forUI(SoundEvents.NOTE_BLOCK_BASS, 0.6f));
			return;
		}
		if (d.selected().equals(Optional.of(id))) return;
		pending = id;
		pendingAt = Util.getMillis();
		if (ClientPlayNetworking.canSend(SelectSpell.TYPE)) ClientPlayNetworking.send(new SelectSpell(id));
		play(SimpleSoundInstance.forUI(SoundEvents.BOOK_PAGE_TURN, 1.1f, 0.9f));
	}

	private void play(SimpleSoundInstance s) {
		minecraft.getSoundManager().play(s);
	}

	/** The row the ribbon marks: a just-clicked spell for a moment (until the server answers), else the synced selection. */
	private int selectedRow() {
		MagicData d = data();
		if (d.element().isEmpty()) return -1;
		Identifier sel = d.selected().orElse(null);
		if (pending != null) {
			if (pending.equals(sel) || Util.getMillis() - pendingAt > 1500) pending = null;
			else sel = pending;
		}
		for (int slot = 0; slot < GrimoireLayout.ROWS; slot++) if (SpellIds.at(d.element().get(), slot).equals(sel)) return slot;
		return -1;
	}

	// ---------------------------------------------------------------- drawing

	@Override
	public void extractBackground(GuiGraphicsExtractor g, int mouseX, int mouseY, float a) {
		extractBlurredBackground(g);
		float k = UiKit.easeOut((Util.getMillis() - openedAt) / 250f);
		g.fill(0, 0, width, height, UiKit.alpha(0x98080610, k));
		UiKit.vignette(g, width, height, k);
	}

	@Override
	public void extractRenderState(GuiGraphicsExtractor g, int mouseX, int mouseY, float a) {
		long now = Util.getMillis();
		float dt = Math.min(0.1f, (now - lastFrame) / 1000f);
		lastFrame = now;
		MagicData d = data();
		Element e = element();
		GrimoireLayout.Layout l = layout();
		float u = l.unit();
		int mouseRow = tab == TAB_SPELLS ? l.rowAt(mouseX, mouseY) : -1;
		if (mouseRow != hover && mouseRow >= 0) focus = -1;
		hover = mouseRow;
		int lit = hover >= 0 ? hover : focus;
		for (int i = 0; i < glow.length; i++) glow[i] = UiKit.approach(glow[i], i == lit ? 1f : 0f, 18f, dt);

		float open = UiKit.easeOut((now - openedAt) / (float) OPEN_MS);
		Rect book = l.book();
		UiKit.glow(g, book.cx(), book.cy(), Math.max(book.w(), book.h()) * (1.9f + 0.06f * (float) Math.sin(now / 900.0)),
				UiKit.alpha(e.argb(), 0.26f * open));
		ElementChoiceScreen.book(g, book, open, 1f);
		tabs(g, l, e, mouseX, mouseY, now, dt, open);
		float content = UiKit.clamp01((now - openedAt - OPEN_MS * 0.55f) / 220f) * UiKit.easeOut((now - tabAt) / 200f);
		if (content > 0f) {
			// An explicit case per tab: adding a tab without its page fails loudly instead of drawing the wrong one.
			switch (tab) {
				case TAB_SPELLS -> {
					leftPage(g, l, d, e, now, content);
					rightPage(g, l, d, e, now, dt, content);
				}
				case TAB_FRIENDS -> friends.draw(g, font, l, e, mouseX, mouseY, now, dt, content);
				case TAB_HOWTO -> howToPages(g, l, content);
				case TAB_TREE -> tree.draw(g, font, l, d, e, mouseX, mouseY, now, dt, content);
				default -> throw new IllegalStateException("no page for tab " + tab);
			}
		}
		footer(g, l, open);
		super.extractRenderState(g, mouseX, mouseY, a);
	}

	private void leftPage(GuiGraphicsExtractor g, GrimoireLayout.Layout l, MagicData d, Element e, long now, float k) {
		float u = l.unit();
		Rect p = l.leftPage();
		Rect s = l.sigil();
		int ink = UiKit.inkOf(e.argb());
		float pulse = 0.5f + 0.5f * (float) Math.sin(now / 650.0);
		UiKit.glow(g, s.cx(), s.cy(), s.w() * (1.7f + 0.15f * pulse), UiKit.alpha(e.argb(), (0.35f + 0.15f * pulse) * k));
		UiKit.centredTexture(g, UiKit.ring(3), 128, s.cx(), s.cy(), s.w() * 1.12f, (float) (now / 5200.0), UiKit.alpha(ink, 0.55f * k));
		UiKit.centredTexture(g, UiKit.sigil(e), 64, s.cx(), s.cy() + 1, s.w(), 0f, UiKit.alpha(0xFF000000, 0.35f * k));
		UiKit.centredTexture(g, UiKit.sigil(e), 64, s.cx(), s.cy(), s.w(), 0f, UiKit.alpha(UiKit.WHITE, k));

		Component level = Component.translatable("magic.office.level", d.level());
		float ls = UiKit.fit(font.width(level), p.w(), 1.5f * u);
		UiKit.centred(g, font, level, p.cx(), l.levelY() + (9 * 1.5f * u - 9 * ls) / 2f, ls, UiKit.alpha(ink, k), false);

		// Grimoire xp toward the next level.
		Rect bar = l.xpBar();
		boolean max = d.level() >= LevelCurve.MAX_LEVEL;
		int next = LevelCurve.xpToNext(d.level());
		float frac = max ? 1f : Math.clamp(d.xp() / (float) next, 0f, 1f);
		UiKit.rounded(g, bar.x() - 1, bar.y() - 1, bar.right() + 1, bar.bottom() + 1, UiKit.alpha(0xFF5A4630, k));
		g.fill(bar.x(), bar.y(), bar.right(), bar.bottom(), UiKit.alpha(0xFFE9DCB8, k));
		int fw = Math.round(bar.w() * frac);
		int fill = max ? UiKit.inkOf(0xFFFFD27A) : UiKit.mix(e.argb(), ink, 0.25f);
		if (fw > 0) {
			g.fill(bar.x(), bar.y(), bar.x() + fw, bar.bottom(), UiKit.alpha(fill, k));
			g.fill(bar.x(), bar.y(), bar.x() + fw, bar.y() + Math.max(1, Math.round(u)), UiKit.alpha(UiKit.mix(fill, UiKit.WHITE, 0.4f), k));
			// A glint that runs along the filled part.
			float run = (now % 2400) / 2400f;
			int gx = bar.x() + Math.round(fw * run);
			g.fill(gx, bar.y(), Math.min(bar.x() + fw, gx + Math.max(1, Math.round(2 * u))), bar.bottom(), UiKit.alpha(UiKit.WHITE, 0.35f * k));
		}
		if (!max) {
			Component xp = Component.literal(d.xp() + " / " + next);
			float xs = UiKit.fit(font.width(xp), p.w(), u);
			UiKit.centred(g, font, xp, p.cx(), l.xpTextY(), xs, UiKit.alpha(UiKit.INK_SOFT, k), false);
		}

		// Mana, between the xp numbers and home.
		int maxMana = local.carter.office.magic.core.ManaMath.max(d.level());
		Component mana = Component.translatable("magic.office.mana", MagicAttachments.mana(minecraft.player), maxMana);
		float mns = UiKit.fit(font.width(mana) + 10, p.w(), u);
		float manaY = (l.xpTextY() + 9 * u + l.homeY()) / 2f - 4.5f * mns;
		float mw = (font.width(mana) + 10) * mns;
		float mx = p.cx() - mw / 2f;
		g.pose().pushMatrix();
		g.pose().translate(mx + 3 * mns, manaY + 4 * mns);
		g.pose().rotate((float) (Math.PI / 4));
		int dm = Math.max(1, Math.round(2.2f * mns));
		g.fill(-dm, -dm, dm, dm, UiKit.alpha(MANA_BLUE, k));
		g.pose().popMatrix();
		UiKit.text(g, font, mana, mx + 10 * mns, manaY, mns, UiKit.alpha(UiKit.mix(MANA_BLUE, UiKit.INK, 0.35f), k), false);

		// Home.
		Component home = d.home().map(GrimoireScreen::homeText).orElseGet(() -> Component.translatable("magic.office.no_home_short"));
		float hs = UiKit.wrap(font, home, p.w(), u, 2, lines);
		float y = l.homeY() + (lines.size() == 1 ? 5 * u : 0);
		int houseCol = d.home().isPresent() ? UiKit.inkOf(Element.RECALL_ARGB) : UiKit.INK_SOFT;
		for (FormattedCharSequence line : lines) {
			UiKit.centred(g, font, line, p.cx(), y, hs, UiKit.alpha(houseCol, k), false);
			y += 10 * hs;
		}
	}

	/** "Home: x y z (Dimension)"; the dimension from {@code magic.office.dim.<path>}, else its path made readable. */
	static Component homeText(GlobalPos h) {
		Identifier id = h.dimension().identifier();
		StringBuilder nice = new StringBuilder();
		for (String w : id.getPath().replace('/', ' ').replace('_', ' ').split(" ")) {
			if (w.isEmpty()) continue;
			if (nice.length() > 0) nice.append(' ');
			nice.append(Character.toUpperCase(w.charAt(0))).append(w.substring(1));
		}
		String key = id.getNamespace().equals("minecraft") ? "magic.office.dim." + id.getPath() : "magic.office.dim." + id.getNamespace() + "." + id.getPath();
		Component dim = Component.translatableWithFallback(key, nice.toString());
		return Component.translatable("magic.office.home", h.pos().getX(), h.pos().getY(), h.pos().getZ(), dim);
	}

	/**
	 * The index tabs: leather when idle, page-coloured and pulled out when active, sliding out a little on hover. Spells shows
	 * your sigil; Friends shows two friends' faces and how many are online.
	 */
	private void tabs(GuiGraphicsExtractor g, GrimoireLayout.Layout l, Element e, int mouseX, int mouseY, long now, float dt, float open) {
		if (open < 0.6f) return;
		float k = UiKit.clamp01((open - 0.6f) / 0.4f);
		float u = l.unit();
		int hovered = l.tabAt(mouseX, mouseY);
		var conn = minecraft.getConnection();
		List<java.util.UUID> others = new ArrayList<>();
		if (conn != null && minecraft.player != null) {
			for (net.minecraft.client.multiplayer.PlayerInfo info : conn.getOnlinePlayers()) {
				java.util.UUID id = info.getProfile().id();
				if (!id.equals(minecraft.player.getUUID()) && info.getGameMode() != net.minecraft.world.level.GameType.SPECTATOR) others.add(id);
			}
		}
		for (int i = 0; i < GrimoireLayout.TABS; i++) {
			Rect r = l.tabs().get(i);
			boolean active = i == tab;
			tabHover[i] = UiKit.approach(tabHover[i], i == hovered && !active ? 1f : 0f, 16f, dt);
			int out = active ? 0 : Math.round((3 - 2 * tabHover[i]) * u);
			// Drawn over the book up to its cover edge (the texture's transparent rim), so the tab comes out from under the cover.
			int x0 = r.x() + out, x1 = l.book().x() + (int) Math.ceil(3 * l.book().w() / 192f), y0 = r.y(), y1 = r.bottom();
			int body = active ? FriendsPage.paper() : UiKit.mix(0xFF6E4E33, 0xFF8E6A47, tabHover[i]);
			UiKit.rounded(g, x0, y0 + 1, x1, y1 + 1, UiKit.alpha(0xFF000000, 0.4f * k));
			UiKit.rounded(g, x0, y0, x1, y1, UiKit.alpha(body, k));
			int edge = UiKit.alpha(active ? 0xFF7A6248 : 0xFF3E2A1A, k);
			g.fill(x0 + 1, y0, x1, y0 + 1, edge);
			g.fill(x0 + 1, y1 - 1, x1, y1, edge);
			g.fill(x0, y0 + 1, x0 + 1, y1 - 1, edge);
			if (active) g.fill(x0 + 1, y0 + 1, x0 + Math.max(1, Math.round(u)) + 1, y1 - 1, UiKit.alpha(e.argb(), 0.9f * k));
			float visible = l.book().x() - x0;
			float cx = x0 + visible / 2f + Math.max(1, Math.round(u)) / 2f, cy = (y0 + y1) / 2f;
			float icon = Math.min(visible - 3 * u, r.h() - 8 * u);
			float ik = (active ? 1f : 0.75f + 0.25f * tabHover[i]) * k;
			switch (i) {
			case TAB_SPELLS: {
				UiKit.centredTexture(g, UiKit.sigil(e), 64, cx, cy, icon, 0f, UiKit.alpha(UiKit.WHITE, ik));
				break;
			}
			case TAB_HOWTO: {
				// A "?" in the tab's own ink.
				Component q = Component.literal("?");
				float qs = UiKit.crisp(icon / 9f);
				UiKit.centred(g, font, q, cx, cy - 4.5f * qs, qs, UiKit.alpha(active ? UiKit.INK : 0xFFE9DCB8, ik), false);
				break;
			}
			case TAB_TREE: {
				// A little tree: a trunk with three nodes on it, the top one lit in the element's colour.
				int ink = active ? UiKit.INK : 0xFFE9DCB8;
				float nr = Math.max(1.5f, icon * 0.13f);
				float top = cy - icon * 0.36f, bottom = cy + icon * 0.36f;
				int th = Math.max(1, Math.round(icon * 0.06f));
				g.fill(Math.round(cx) - th, Math.round(top), Math.round(cx) + th, Math.round(bottom), UiKit.alpha(ink, 0.7f * ik));
				Disc.fill(g, cx, bottom, nr, UiKit.alpha(ink, ik));
				Disc.fill(g, cx - icon * 0.22f, cy, nr * 0.85f, UiKit.alpha(ink, ik));
				Disc.fill(g, cx + icon * 0.22f, cy, nr * 0.85f, UiKit.alpha(ink, ik));
				g.fill(Math.round(cx - icon * 0.22f), Math.round(cy) - th / 2 - 1, Math.round(cx + icon * 0.22f), Math.round(cy) + th / 2,
						UiKit.alpha(ink, 0.7f * ik));
				Disc.fill(g, cx, top, nr, UiKit.alpha(e.argb(), ik));
				break;
			}
			case TAB_FRIENDS: {
				float fs = Math.round(icon * 0.78f);
				java.util.UUID a = others.size() > 0 ? others.get(0) : null, b = others.size() > 1 ? others.get(1) : null;
				var skinA = a != null ? FriendsPage.skin(a) : net.minecraft.client.resources.DefaultPlayerSkin.getDefaultSkin();
				if (b != null) FriendsPage.face(g, FriendsPage.skin(b), cx - fs / 2f + icon * 0.16f, cy - fs / 2f - icon * 0.14f, fs, 0xFFC8C8C8, ik);
				FriendsPage.face(g, skinA, cx - fs / 2f - (b != null ? icon * 0.12f : 0f), cy - fs / 2f + (b != null ? icon * 0.12f : 0f), fs,
						a == null ? 0x90FFFFFF : UiKit.WHITE, ik);
				if (!others.isEmpty()) {
					Component n = Component.literal(Integer.toString(others.size()));
					float bs = UiKit.crisp(u * 0.75f);
					float bw = Math.max(9 * bs, font.width(n) * bs + 4 * bs);
					float bx = x0 + 1, by = y0 + 1;
					UiKit.rounded(g, Math.round(bx), Math.round(by), Math.round(bx + bw), Math.round(by + 9 * bs), UiKit.alpha(0xFFB33A2A, k));
					UiKit.centred(g, font, n, bx + bw / 2f, by + 0.5f * bs, bs, UiKit.alpha(UiKit.WHITE, k), false);
				}
				break;
			}
			default:
				throw new IllegalStateException("no icon for tab " + i);
			}
			if (i == hovered) {
				g.setTooltipForNextFrame(font, Component.translatable(GrimoireLayout.TAB_TIPS[i]), mouseX, mouseY);
			}
		}
	}

	/** How to cast: the handbook's Casting text on the left page, the hand-signs sentence and its keys on the right. */
	private void howToPages(GuiGraphicsExtractor g, GrimoireLayout.Layout l, float k) {
		pageText(g, l.leftPage(), Component.translatable("magic.office.howto_cast"), l.unit(), k);
		pageText(g, l.rightPage(), Component.translatable("magic.office.howto_signs").append("\n\n")
				.append(Component.translatable("magic.office.signs_hint")), l.unit(), k);
	}

	/** Wraps {@code text} into {@code page}, shrinking the (crisp) scale until it fits; nothing scrolls. */
	private void pageText(GuiGraphicsExtractor g, Rect page, Component text, float unit, float k) {
		int maxLines = Math.max(1, (int) (page.h() / ((GrimoireLayout.LINE + 1) * unit)));
		float s = UiKit.wrap(font, text, page.w(), unit, maxLines, lines);
		float y = page.y();
		for (FormattedCharSequence line : lines) {
			UiKit.text(g, font, line, page.x(), y, s, UiKit.alpha(UiKit.INK, k), false);
			y += 10 * s;
		}
	}

	private void rightPage(GuiGraphicsExtractor g, GrimoireLayout.Layout l, MagicData d, Element e, long now, float dt, float fade) {
		float u = l.unit();
		int sel = selectedRow();
		long gt = gameTime();
		// The ribbon glides to the selected row.
		if (sel >= 0) {
			Rect r = l.rows().get(sel);
			float target = r.y();
			ribbonY = Float.isNaN(ribbonY) ? target : UiKit.approach(ribbonY, target, 16f, dt);
		}
		// One name scale for every row: the largest crisp one at which the longest name fits.
		Rect r0 = l.rows().get(0);
		float icon = iconSize(r0, u);
		int widest = 0;
		for (int slot = 0; slot < GrimoireLayout.ROWS; slot++) widest = Math.max(widest, font.width(UiKit.rankedName(d, SpellIds.at(e, slot))));
		float nameRoom = r0.w() - icon - 6 * u;
		float ns = UiKit.fit(widest, nameRoom, u);
		float ss = ns;
		if (r0.h() < 9 * ns + 9 * ss + 3) ss = UiKit.crisp(ns * 0.75f);
		boolean twoLines = r0.h() >= 9 * ns + 9 * ss + 3;
		if (!twoLines) ss = Math.min(ss, UiKit.crisp((r0.h() - 9 * ns - 1) / 9f));

		for (int slot = 0; slot < GrimoireLayout.ROWS; slot++) {
			Rect r = l.rows().get(slot);
			float appear = UiKit.easeOut((now - rowsFrom - ROW_DELAY_MS - slot * ROW_STAGGER_MS) / (float) ROW_MS);
			if (appear <= 0f) continue;
			float k = appear * fade;
			float dx = (1f - appear) * 8f * u;
			Identifier id = SpellIds.at(e, slot);
			boolean locked = d.level() < Slots.unlockLevel(slot);
			boolean selected = slot == sel;
			// What a cast costs at the spell's skill-tree rank.
			SpellCost cost = SpellRegistry.get(id).map(sp -> CastService.cost(d, sp)).orElse(SpellCost.forSlot(slot));

			g.pose().pushMatrix();
			g.pose().translate(dx, 0);
			if (selected) {
				g.fill(r.x() - 1, r.y(), r.right() + 1, r.bottom(), UiKit.alpha(e.argb(), 0.22f * k));
				g.fill(r.x() - 1, r.y(), r.x() + Math.max(1, Math.round(u)), r.bottom(), UiKit.alpha(UiKit.inkOf(e.argb()), 0.8f * k));
			}
			if (glow[slot] > 0.01f) {
				int wash = locked ? 0xFF6A5A48 : UiKit.inkOf(e.argb());
				UiKit.roundedOutline(g, r.x() - 1, r.y(), r.right() + 1, r.bottom(), UiKit.alpha(wash, 0.55f * glow[slot] * k));
				g.fill(r.x(), r.y() + 1, r.right(), r.bottom() - 1, UiKit.alpha(wash, 0.08f * glow[slot] * k));
			}
			float iconCx = r.x() + 2 * u + icon / 2f, iconCy = r.y() + r.h() / 2f;
			float pop = selected ? 1f + 0.08f * (float) Math.sin(now / 300.0) * 0.5f : 1f + 0.06f * glow[slot];
			int tint = locked ? 0x70FFFFFF : UiKit.WHITE;
			UiKit.centredTexture(g, UiKit.spellIcon(id), 32, iconCx, iconCy, icon * pop, 0f, UiKit.alpha(tint, k));
			long left = d.cooldownLeft(id, gt);
			if (!locked && left > 0) {
				// Cooldown: a dark veil over the icon that drains from the top.
				float frac = Math.clamp(left / (float) Math.max(left, cost.cooldownTicks()), 0f, 1f);
				int x0 = Math.round(iconCx - icon / 2f), y1 = Math.round(iconCy + icon / 2f);
				int y0 = Math.round(y1 - icon * frac);
				g.fill(x0, y0, Math.round(iconCx + icon / 2f), y1, UiKit.alpha(0xFF201810, 0.55f * k));
			}
			if (locked) {
				float shake = slot == shakeRow ? (float) Math.sin((now - shakeAt) / 25.0) * 2.5f * u * Math.max(0f, 1f - (now - shakeAt) / 350f) : 0f;
				float ps = Math.max(8f, icon * 0.55f);
				UiKit.centredTexture(g, UiKit.PADLOCK, 16, iconCx + icon * 0.28f + shake, iconCy + icon * 0.22f, ps, 0f, UiKit.alpha(UiKit.WHITE, k));
			}

			float tx = r.x() + 2 * u + icon + 4 * u;
			Component name = UiKit.rankedName(d, id);
			int nameCol = locked ? UiKit.alpha(UiKit.INK_SOFT, 0.75f) : (slot == Slots.ULTIMATE ? UiKit.inkOf(e.argb()) : UiKit.INK);
			float nameY = twoLines ? iconCy - (9 * ns + 9 * ss + 1) / 2f : iconCy - 9 * ns / 2f;
			if (!twoLines) nameY = r.y() + 1;
			UiKit.text(g, font, name, tx, nameY, ns, UiKit.alpha(nameCol, k), false);
			float statY = nameY + 9 * ns + 1;
			if (locked) {
				Component lv = Component.translatable("magic.office.lv", Slots.unlockLevel(slot));
				UiKit.text(g, font, lv, tx, statY, ss, UiKit.alpha(LOCK_RED, k), false);
			} else {
				stats(g, tx, statY, ss, cost, left, slot, k, r.right() - 2 * u);
			}
			g.pose().popMatrix();
		}
		// Ribbon bookmark hanging out of the book's right edge at the selected row.
		if (sel >= 0 && !Float.isNaN(ribbonY)) {
			Rect r = l.rows().get(sel);
			int x0 = l.book().right() - Math.round(3 * u), x1 = l.book().right() + Math.round(5 * u);
			int y0 = Math.round(ribbonY) + 1, y1 = y0 + r.h() - 2;
			int c = e.argb();
			g.fill(x0, y0 + 1, x1, y1 + 1, UiKit.alpha(0xFF000000, 0.35f * fade));
			int mid = (y0 + y1) / 2;
			int notch = Math.max(2, Math.round(2.5f * u));
			int body = UiKit.alpha(UiKit.mix(c, 0xFF000000, 0.2f), fade);
			g.fill(x0, y0, x1 - notch, y1, body);
			// Swallow-tail end: each column of the tail leaves a wider notch around the middle.
			for (int i = 0; i < notch; i++) {
				int cut = i + 1;
				g.fill(x1 - notch + i, y0, x1 - notch + i + 1, mid - cut, body);
				g.fill(x1 - notch + i, mid + cut, x1 - notch + i + 1, y1, body);
			}
			g.fill(x0, y0, x1, y0 + Math.max(1, Math.round(u)), UiKit.alpha(UiKit.mix(c, UiKit.WHITE, 0.35f), fade));
		}
	}

	private static float iconSize(Rect r, float u) {
		return Math.max(8f, Math.min(r.h() - 4f * u, 32f * u));
	}

	/** "◆ 25 · 3s" (and "+3 lvl" for the ultimate); while recharging, the seconds left on the right. */
	private void stats(GuiGraphicsExtractor g, float x, float y, float s, SpellCost cost, long left, int slot, float k, float right) {
		// Mana diamond.
		float d = 3f * s;
		float cx = x + d, cy = y + 4f * s;
		g.pose().pushMatrix();
		g.pose().translate(cx, cy);
		g.pose().rotate((float) (Math.PI / 4));
		g.fill(-Math.round(d * 0.7f), -Math.round(d * 0.7f), Math.round(d * 0.7f), Math.round(d * 0.7f), UiKit.alpha(MANA_BLUE, k));
		g.pose().popMatrix();
		float tx = x + 2 * d + 2 * s;
		StringBuilder sb = new StringBuilder();
		sb.append(cost.mana()).append("  ").append(seconds(cost.cooldownTicks())).append('s');
		Component line = Component.literal(sb.toString());
		if (slot == Slots.ULTIMATE && cost.xpLevels() > 0) {
			line = line.copy().append("  ").append(Component.translatable("magic.office.plus_levels", cost.xpLevels()));
		}
		// The line shrinks (crisp) to fit the row; the seconds left only show when they fit beside it.
		float ls = UiKit.fit(font.width(line), right - tx, s);
		UiKit.text(g, font, line, tx, y + 4f * (s - ls), ls, UiKit.alpha(UiKit.INK_SOFT, k), false);
		if (left > 0) {
			Component cd = Component.literal(seconds(left) + "s");
			float w = font.width(cd) * ls;
			if (tx + font.width(line) * ls + 4 * ls < right - w) UiKit.text(g, font, cd, right - w, y + 4f * (s - ls), ls, UiKit.alpha(LOCK_RED, k), false);
		}
	}

	/** Ticks as seconds: whole when even ("3"), else one decimal ("0.5"). */
	static String seconds(long ticks) {
		if (ticks % 20 == 0) return Long.toString(ticks / 20);
		return String.format(java.util.Locale.ROOT, "%.1f", ticks / 20f);
	}

	private void footer(GuiGraphicsExtractor g, GrimoireLayout.Layout l, float k) {
		Rect f = l.footer();
		Component hint = Component.translatable(switch (tab) {
			case TAB_SPELLS, TAB_HOWTO -> "magic.office.hint";
			case TAB_FRIENDS -> "magic.office.friends_hint";
			case TAB_TREE -> "magic.office.tree_hint";
			default -> throw new IllegalStateException("no footer for tab " + tab);
		});
		float s = UiKit.wrap(font, hint, f.w(), l.unit(), 2, lines);
		float total = lines.size() * 10 * s - s;
		float y = f.y() + (f.h() - total) / 2f;
		for (FormattedCharSequence line : lines) {
			UiKit.centred(g, font, line, f.cx(), y, s, UiKit.alpha(0xFFD9D2E6, k), true);
			y += 10 * s;
		}
	}
}
