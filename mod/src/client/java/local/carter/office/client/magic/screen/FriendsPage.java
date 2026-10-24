package local.carter.office.client.magic.screen;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

import org.jspecify.annotations.Nullable;

import local.carter.office.client.magic.TravelClient;
import local.carter.office.client.magic.screen.SignLayout.Rect;
import local.carter.office.magic.core.Element;
import local.carter.office.magic.data.MagicAttachments;
import local.carter.office.magic.data.MagicData;
import local.carter.office.magic.net.MagicPayloads.TravelRoster;
import local.carter.office.magic.spell.SpellIds;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.PlayerFaceExtractor;
import net.minecraft.client.multiplayer.PlayerInfo;
import net.minecraft.client.resources.DefaultPlayerSkin;
import net.minecraft.client.resources.sounds.SimpleSoundInstance;
import net.minecraft.network.chat.Component;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.util.FormattedCharSequence;
import net.minecraft.util.Util;
import net.minecraft.world.entity.player.PlayerSkin;

/**
 * The grimoire's Friends tab (Travel to a friend). Left page: a turning travel emblem, the travel state (ready, the cooldown it
 * shares with Recall, waiting for an answer, travelling) on a bar, a short explanation and the switch for accepting requests
 * ({@code /magic travel on|off}). Right page: "Friends online", up to {@link GrimoireLayout#FRIEND_ROWS} friends per page
 * (head, name, and why one is greyed: travel off, on cooldown, busy), arrows when there are more. Clicking an available friend
 * sends the request. The roster comes from the server ({@link TravelClient}), asked for about once a second while showing.
 */
public final class FriendsPage {
	/** Why a friend row is (un)available, most important first. */
	public enum Status {
		/** Click to ask. */
		AVAILABLE,
		/** Your request to this friend is waiting for an answer. */
		WAITING,
		/** You are travelling to this friend. */
		TRAVELLING,
		/** You are busy: another request is open, a channel runs, hand signs or a charge. */
		BUSY,
		/** Recall's (shared) cooldown is running. */
		COOLDOWN,
		/** This friend refuses travel requests. */
		OFF;

		public boolean available() {
			return this == AVAILABLE;
		}
	}

	/** Ask for a fresh roster this often while the page shows (client ticks). */
	static final int POLL_TICKS = 20;
	private static final int PAGE_INK = 0xFFF0E2BE;
	private static final long ROW_STAGGER_MS = 35;
	private static final long ROW_MS = 240;

	private final GrimoireScreen screen;
	private int page;
	private int focus = -1;
	private final float[] glow = new float[GrimoireLayout.FRIEND_ROWS];
	private int shakeRow = -1;
	private long shakeAt;
	private @Nullable UUID asked;
	private long askedAt;
	private long shownAt = Util.getMillis();
	private float knob = Float.NaN;
	private int ticks;
	private final List<FormattedCharSequence> lines = new ArrayList<>();

	FriendsPage(GrimoireScreen screen) {
		this.screen = screen;
	}

	/** {@code f}'s row state for a viewer whose roster is {@code r} and whose Recall cooldown has {@code cooldownLeft} ticks. */
	public static Status status(TravelRoster r, TravelRoster.Friend f, long cooldownLeft) {
		boolean mine = r.waitingFor().map(f.id()::equals).orElse(false);
		return switch (r.state()) {
			case TRAVELLING -> mine ? Status.TRAVELLING : Status.BUSY;
			case WAITING -> mine ? Status.WAITING : Status.BUSY;
			case BUSY -> Status.BUSY;
			case READY -> cooldownLeft > 0 ? Status.COOLDOWN : f.accepts() ? Status.AVAILABLE : Status.OFF;
		};
	}

	/** Pages needed for {@code friends} friends (at least one). */
	public static int pages(int friends) {
		return Math.max(1, (friends + GrimoireLayout.FRIEND_ROWS - 1) / GrimoireLayout.FRIEND_ROWS);
	}

	public int page() {
		return page;
	}

	/** The tab was opened: ask at once, restart the row animation. */
	void shown() {
		shownAt = Util.getMillis();
		ticks = 0;
		TravelClient.query();
	}

	void tick() {
		if (++ticks % POLL_TICKS == 0) TravelClient.query();
		int max = pages(TravelClient.roster().friends().size()) - 1;
		if (page > max) page = max;
	}

	private Minecraft mc() {
		return Minecraft.getInstance();
	}

	private MagicData data() {
		return mc().player == null ? MagicData.EMPTY : MagicAttachments.get(mc().player);
	}

	private long cooldownLeft() {
		return mc().level == null ? 0 : data().cooldownLeft(SpellIds.RECALL, mc().level.getGameTime());
	}

	/** The friend shown in row {@code row} of the current page, or null. */
	public TravelRoster.@Nullable Friend friendAt(int row) {
		List<TravelRoster.Friend> all = TravelClient.roster().friends();
		int i = page * GrimoireLayout.FRIEND_ROWS + row;
		return row < 0 || i >= all.size() ? null : all.get(i);
	}

	// ---------------------------------------------------------------- input

	boolean click(GrimoireLayout.Layout l, double x, double y) {
		GrimoireLayout.Friends f = l.friends();
		int pages = pages(TravelClient.roster().friends().size());
		if (pages > 1 && GrimoireLayout.contains(f.prev(), x, y)) return turn(-1);
		if (pages > 1 && GrimoireLayout.contains(f.next(), x, y)) return turn(1);
		if (GrimoireLayout.contains(f.toggle(), x, y)) {
			toggleRequests();
			return true;
		}
		int row = f.rowAt(x, y);
		if (row >= 0 && friendAt(row) != null) {
			ask(row);
			return true;
		}
		return false;
	}

	/** Up/Down/Tab move between friends, Enter asks, Page Up/Down turn pages. */
	boolean key(net.minecraft.client.input.KeyEvent event) {
		int shown = shownRows();
		if (event.isUp() || event.isDown() || event.isCycleFocus()) {
			if (shown == 0) return true;
			boolean back = event.isUp() || (event.isCycleFocus() && event.hasShiftDown());
			focus = Math.floorMod((focus < 0 ? (back ? 0 : -1) : focus) + (back ? -1 : 1), shown);
			return true;
		}
		if ((event.isSelection() || event.isConfirmation()) && focus >= 0) {
			ask(focus);
			return true;
		}
		int k = event.key();
		if (k == com.mojang.blaze3d.platform.InputConstants.KEY_PAGEUP) return turn(-1);
		if (k == com.mojang.blaze3d.platform.InputConstants.KEY_PAGEDOWN) return turn(1);
		return false;
	}

	private int shownRows() {
		int n = TravelClient.roster().friends().size() - page * GrimoireLayout.FRIEND_ROWS;
		return Math.clamp(n, 0, GrimoireLayout.FRIEND_ROWS);
	}

	/** Turns to the previous ({@code -1}) or next page if there is one. */
	public boolean turn(int dir) {
		int to = Math.clamp(page + dir, 0, pages(TravelClient.roster().friends().size()) - 1);
		if (to == page) return true;
		page = to;
		focus = -1;
		shownAt = Util.getMillis();
		play(SimpleSoundInstance.forUI(SoundEvents.BOOK_PAGE_TURN, 1.0f, 0.9f));
		return true;
	}

	/** Asks to travel to the friend in {@code row}; an unavailable one shakes its head with a low note. */
	public void ask(int row) {
		TravelRoster.Friend f = friendAt(row);
		if (f == null) return;
		if (!status(TravelClient.roster(), f, cooldownLeft()).available()) {
			shakeRow = row;
			shakeAt = Util.getMillis();
			play(SimpleSoundInstance.forUI(SoundEvents.NOTE_BLOCK_BASS, 0.6f));
			return;
		}
		asked = f.id();
		askedAt = Util.getMillis();
		TravelClient.request(f.id());
		play(SimpleSoundInstance.forUI(SoundEvents.AMETHYST_BLOCK_CHIME, 1.3f, 0.8f));
	}

	private void toggleRequests() {
		if (mc().player == null) return;
		mc().player.connection.sendCommand(data().travel() ? "magic travel off" : "magic travel on");
		play(SimpleSoundInstance.forUI(SoundEvents.UI_BUTTON_CLICK, 1.0f));
	}

	private void play(SimpleSoundInstance s) {
		mc().getSoundManager().play(s);
	}

	// ---------------------------------------------------------------- drawing

	void draw(GuiGraphicsExtractor g, Font font, GrimoireLayout.Layout l, Element e, int mouseX, int mouseY, long now, float dt, float k) {
		TravelRoster r = TravelClient.roster();
		long cd = cooldownLeft();
		leftPage(g, font, l, e, r, cd, mouseX, mouseY, now, dt, k);
		rightPage(g, font, l, e, r, cd, mouseX, mouseY, now, dt, k);
	}

	private String waitingName(TravelRoster r) {
		UUID id = r.waitingFor().orElse(null);
		for (TravelRoster.Friend f : r.friends()) if (f.id().equals(id)) return f.name();
		return "?";
	}

	/** Seconds left on the open request, counted down smoothly between roster replies. */
	private static long waitingSeconds(TravelRoster r, long now) {
		long ticks = r.ticksLeft() - (now - TravelClient.receivedAt()) / 50;
		return Math.max(0, (ticks + 19) / 20);
	}

	private void leftPage(GuiGraphicsExtractor g, Font font, GrimoireLayout.Layout l, Element e, TravelRoster r, long cd, int mouseX,
			int mouseY, long now, float dt, float k) {
		float u = l.unit();
		Rect p = l.leftPage();
		Rect s = l.sigil();
		int gold = Element.RECALL_ARGB;
		int ink = UiKit.inkOf(gold);
		boolean travelling = r.state() == TravelRoster.State.TRAVELLING;
		boolean waiting = r.state() == TravelRoster.State.WAITING;
		float pulse = 0.5f + 0.5f * (float) Math.sin(now / (waiting ? 260.0 : 650.0));
		float spin = (float) (now / (travelling ? 900.0 : 5200.0));

		// Emblem: a glow, two counter-turning rune rings and Recall's sigil-like icon in the middle.
		UiKit.glow(g, s.cx(), s.cy(), s.w() * (1.6f + 0.2f * pulse), UiKit.alpha(gold, (0.32f + (travelling ? 0.3f : 0.12f) * pulse) * k));
		UiKit.centredTexture(g, UiKit.ring(2), 128, s.cx(), s.cy(), s.w() * 1.04f, spin, UiKit.alpha(ink, 0.6f * k));
		UiKit.centredTexture(g, UiKit.ring(1), 128, s.cx(), s.cy(), s.w() * 0.78f, -spin * 1.6f, UiKit.alpha(UiKit.inkOf(e.argb()), 0.45f * k));
		UiKit.centredTexture(g, UiKit.spellIcon(SpellIds.RECALL), 32, s.cx(), s.cy() + 1, s.w() * 0.5f, 0f, UiKit.alpha(0xFF000000, 0.3f * k));
		UiKit.centredTexture(g, UiKit.spellIcon(SpellIds.RECALL), 32, s.cx(), s.cy(), s.w() * 0.5f, 0f, UiKit.alpha(UiKit.WHITE, k));

		Component title = Component.translatable("magic.office.travel_title");
		float ts = UiKit.fit(font.width(title), p.w(), 1.5f * u);
		UiKit.centred(g, font, title, p.cx(), l.levelY() + (9 * 1.5f * u - 9 * ts) / 2f, ts, UiKit.alpha(ink, k), false);

		// The state bar (where the xp bar sits on the Spells tab) and its line.
		Rect bar = l.xpBar();
		float frac;
		int fill;
		Component line;
		switch (r.state()) {
			case TRAVELLING -> {
				frac = 1f;
				fill = gold;
				line = Component.translatable("magic.office.panel_travelling", waitingName(r));
			}
			case WAITING -> {
				frac = Math.clamp(waitingSeconds(r, now) / 30f, 0f, 1f);
				fill = UiKit.mix(e.argb(), UiKit.inkOf(e.argb()), 0.25f);
				line = Component.translatable("magic.office.panel_waiting", waitingName(r));
			}
			case BUSY -> {
				frac = 0f;
				fill = 0xFF9A8A70;
				line = Component.translatable("magic.office.panel_busy");
			}
			default -> {
				if (cd > 0) {
					frac = 1f - Math.clamp(cd / 1200f, 0f, 1f);
					fill = 0xFFB59A6A;
					line = Component.translatable("magic.office.panel_cooldown", (cd + 19) / 20);
				} else {
					frac = 1f;
					fill = UiKit.mix(gold, ink, 0.2f);
					line = Component.translatable("magic.office.panel_ready");
				}
			}
		}
		UiKit.rounded(g, bar.x() - 1, bar.y() - 1, bar.right() + 1, bar.bottom() + 1, UiKit.alpha(0xFF5A4630, k));
		g.fill(bar.x(), bar.y(), bar.right(), bar.bottom(), UiKit.alpha(0xFFE9DCB8, k));
		int fw = Math.round(bar.w() * frac);
		if (fw > 0) {
			g.fill(bar.x(), bar.y(), bar.x() + fw, bar.bottom(), UiKit.alpha(fill, k));
			g.fill(bar.x(), bar.y(), bar.x() + fw, bar.y() + Math.max(1, Math.round(u)), UiKit.alpha(UiKit.mix(fill, UiKit.WHITE, 0.4f), k));
			if (cd <= 0 && r.state() != TravelRoster.State.BUSY) {
				float run = (now % (travelling ? 700 : 2400)) / (travelling ? 700f : 2400f);
				int gx = bar.x() + Math.round(fw * run);
				g.fill(gx, bar.y(), Math.min(bar.x() + fw, gx + Math.max(1, Math.round(2 * u))), bar.bottom(), UiKit.alpha(UiKit.WHITE, 0.4f * k));
			}
		}
		float ls = UiKit.fit(font.width(line), p.w(), u);
		int lineCol = waiting || travelling ? UiKit.inkOf(e.argb()) : cd > 0 ? UiKit.INK_SOFT : ink;
		UiKit.centred(g, font, line, p.cx(), l.xpTextY(), ls, UiKit.alpha(lineCol, k), false);

		// What travel is, between the state line and the switch.
		Rect t = l.friends().toggle();
		float top = l.xpTextY() + 9 * u + 3 * u;
		float room = t.y() - top - 2 * u;
		Component blurb = Component.translatable("magic.office.travel_blurb");
		int maxLines = Math.max(1, (int) Math.floor(room / (10 * u)));
		float bs = UiKit.wrap(font, blurb, p.w() - 2 * u, u, Math.min(3, maxLines), lines);
		float by = top + Math.max(0f, (room - lines.size() * 10 * bs) / 2f);
		for (FormattedCharSequence seq : lines) {
			UiKit.centred(g, font, seq, p.cx(), by, bs, UiKit.alpha(UiKit.INK_SOFT, k), false);
			by += 10 * bs;
		}

		toggle(g, font, t, u, data().travel(), GrimoireLayout.contains(t, mouseX, mouseY), dt, k);
	}

	/** The requests switch: a pill with a sliding knob, the state on its right, "click to change" on hover. */
	private void toggle(GuiGraphicsExtractor g, Font font, Rect t, float u, boolean on, boolean hovered, float dt, float k) {
		knob = Float.isNaN(knob) ? (on ? 1f : 0f) : UiKit.approach(knob, on ? 1f : 0f, 16f, dt);
		int ph = Math.max(6, Math.round(8 * u));
		int pw = Math.round(ph * 1.9f);
		Component label = Component.translatable(on ? "magic.office.panel_requests_on" : "magic.office.panel_requests_off");
		Component action = Component.translatable(on ? "magic.office.toggle_off" : "magic.office.toggle_on");
		float room = t.w() - pw - 3 * u;
		float s = UiKit.fit(font.width(label), room, u);
		float textW = font.width(label) * s;
		float total = pw + 3 * u + textW;
		int x0 = Math.round(t.cx() - total / 2f);
		int y0 = Math.round(t.y() + (9 * s - ph) / 2f + 1);
		int onCol = 0xFF4E9A52;
		int track = UiKit.mix(0xFF9A8A70, onCol, knob);
		if (hovered) UiKit.glow(g, x0 + pw / 2f, y0 + ph / 2f, pw * 2.2f, UiKit.alpha(track, 0.35f * k));
		UiKit.rounded(g, x0 - 1, y0 - 1, x0 + pw + 1, y0 + ph + 1, UiKit.alpha(0xFF5A4630, k));
		UiKit.rounded(g, x0, y0, x0 + pw, y0 + ph, UiKit.alpha(track, k));
		int kd = ph - 2;
		int kx = Math.round(x0 + 1 + (pw - 2 - kd) * knob);
		UiKit.rounded(g, kx, y0 + 1, kx + kd, y0 + 1 + kd, UiKit.alpha(0xFFFBF3DC, k));
		UiKit.text(g, font, label, x0 + pw + 3 * u, t.y() + 1, s, UiKit.alpha(on ? UiKit.INK : UiKit.INK_SOFT, k), false);
		float as = UiKit.fit(font.width(action), t.w(), Math.max(UiKit.crisp(u * 0.75f), 1f / UiKit.guiScale()));
		int actionCol = hovered ? UiKit.inkOf(Element.RECALL_ARGB) : UiKit.alpha(UiKit.INK_SOFT, 0.8f);
		UiKit.centred(g, font, action, t.cx(), t.y() + 10 * s + u, as, UiKit.alpha(actionCol, k), false);
	}

	private void rightPage(GuiGraphicsExtractor g, Font font, GrimoireLayout.Layout l, Element e, TravelRoster r, long cd, int mouseX,
			int mouseY, long now, float dt, float k) {
		float u = l.unit();
		GrimoireLayout.Friends f = l.friends();
		Rect h = f.header();
		List<TravelRoster.Friend> all = r.friends();
		int pages = pages(all.size());

		// Header: title on the left, pager on the right, a thin rule under it.
		Component title = Component.translatable("magic.office.friends_title");
		float titleRoom = (pages > 1 ? f.prev().x() : h.right()) - h.x() - 2 * u;
		float hs = UiKit.fit(font.width(title), titleRoom, u);
		UiKit.text(g, font, title, h.x() + u, h.cy() - 4.5f * hs, hs, UiKit.alpha(UiKit.INK, k), false);
		g.fill(h.x(), h.bottom() - 1, h.right(), h.bottom(), UiKit.alpha(UiKit.INK_SOFT, 0.45f * k));
		if (pages > 1) {
			Component label = Component.translatable("magic.office.friends_page", page + 1, pages);
			float ps = UiKit.fit(font.width(label), f.next().x() - f.prev().right(), u);
			UiKit.centred(g, font, label, (f.prev().right() + f.next().x()) / 2f, h.cy() - 4.5f * ps, ps, UiKit.alpha(UiKit.INK_SOFT, k), false);
			arrow(g, f.prev(), -1, page > 0, GrimoireLayout.contains(f.prev(), mouseX, mouseY), u, k);
			arrow(g, f.next(), 1, page < pages - 1, GrimoireLayout.contains(f.next(), mouseX, mouseY), u, k);
		}

		Rect rp = l.rightPage();
		if (all.isEmpty()) {
			Component none = TravelClient.received() ? Component.translatable("magic.office.friends_none") : Component.literal("…");
			float ns = UiKit.wrap(font, none, rp.w() - 4 * u, u, 3, lines);
			float y = (h.bottom() + rp.bottom()) / 2f - lines.size() * 5f * ns;
			for (FormattedCharSequence seq : lines) {
				UiKit.centred(g, font, seq, rp.cx(), y, ns, UiKit.alpha(UiKit.INK_SOFT, k), false);
				y += 10 * ns;
			}
			return;
		}

		int mouseRow = f.rowAt(mouseX, mouseY);
		if (mouseRow >= 0 && friendAt(mouseRow) == null) mouseRow = -1;
		if (mouseRow >= 0) focus = -1;
		int lit = mouseRow >= 0 ? mouseRow : focus;
		for (int i = 0; i < glow.length; i++) glow[i] = UiKit.approach(glow[i], i == lit ? 1f : 0f, 18f, dt);
		if (asked != null && (now - askedAt > 1500 || r.waitingFor().map(asked::equals).orElse(false))) asked = null;

		for (int row = 0; row < GrimoireLayout.FRIEND_ROWS; row++) {
			TravelRoster.Friend fr = friendAt(row);
			if (fr == null) break;
			Rect rr = f.rows().get(row);
			float appear = UiKit.easeOut((now - shownAt - row * ROW_STAGGER_MS) / (float) ROW_MS);
			if (appear <= 0f) continue;
			float a = appear * k;
			Status st = Objects.equals(fr.id(), asked) && r.state() == TravelRoster.State.READY ? Status.WAITING : status(r, fr, cd);
			boolean on = st == Status.WAITING || st == Status.TRAVELLING;
			boolean grey = !st.available() && !on;
			g.pose().pushMatrix();
			g.pose().translate((1f - appear) * 8f * u, 0);
			if (on) {
				float pulse = 0.5f + 0.5f * (float) Math.sin(now / 260.0);
				g.fill(rr.x() - 1, rr.y(), rr.right() + 1, rr.bottom(), UiKit.alpha(Element.RECALL_ARGB, (0.18f + 0.1f * pulse) * a));
				g.fill(rr.x() - 1, rr.y(), rr.x() + Math.max(1, Math.round(u)), rr.bottom(), UiKit.alpha(UiKit.inkOf(Element.RECALL_ARGB), 0.85f * a));
			}
			if (glow[row] > 0.01f) {
				int wash = grey ? 0xFF6A5A48 : UiKit.inkOf(e.argb());
				UiKit.roundedOutline(g, rr.x() - 1, rr.y(), rr.right() + 1, rr.bottom(), UiKit.alpha(wash, 0.55f * glow[row] * a));
				g.fill(rr.x(), rr.y() + 1, rr.right(), rr.bottom() - 1, UiKit.alpha(wash, 0.08f * glow[row] * a));
			}
			// Head (shaken when an unavailable friend is clicked).
			float shake = row == shakeRow ? (float) Math.sin((now - shakeAt) / 25.0) * 2f * u * Math.max(0f, 1f - (now - shakeAt) / 350f) : 0f;
			int head = Math.max(8, Math.min(rr.h() - 2 * Math.max(1, Math.round(u)), Math.round(16 * u)));
			float hx = rr.x() + 2 * u + shake, hy = rr.y() + (rr.h() - head) / 2f;
			face(g, skin(fr.id()), hx, hy, head, grey ? 0x90FFFFFF : UiKit.WHITE, a);

			// Why it is unavailable (or the travel glyph), right-aligned; the name fills what is left.
			float tx = rr.x() + 2 * u + head + 4 * u;
			float right = rr.right() - 2 * u;
			Component why = why(st, r, now);
			float ws = 0f, ww = 0f;
			if (why != null) {
				ws = UiKit.fit(font.width(why), (right - tx) * 0.55f, Math.max(1f / UiKit.guiScale(), UiKit.crisp(u * 0.8f)));
				ww = font.width(why) * ws;
				int wc = on ? UiKit.inkOf(e.argb()) : st == Status.OFF ? 0xFF9A3B2E : UiKit.INK_SOFT;
				UiKit.text(g, font, why, right - ww, rr.y() + (rr.h() - 9 * ws) / 2f, ws, UiKit.alpha(wc, a), false);
			} else {
				float gs = Math.min(rr.h() - 2f * u, 12f * u);
				float gk = 0.35f + 0.65f * glow[row];
				UiKit.centredTexture(g, UiKit.spellIcon(SpellIds.RECALL), 32, right - gs / 2f, rr.y() + rr.h() / 2f, gs * (1f + 0.08f * glow[row]), 0f,
						UiKit.alpha(UiKit.WHITE, gk * a));
				ww = gs;
			}
			Component name = Component.literal(fr.name());
			float nameRoom = right - ww - 3 * u - tx;
			float ns = UiKit.fit(font.width(name), nameRoom, u);
			int nameCol = grey ? UiKit.alpha(UiKit.INK_SOFT, 0.8f) : UiKit.INK;
			UiKit.text(g, font, name, tx, rr.y() + (rr.h() - 9 * ns) / 2f, ns, UiKit.alpha(nameCol, a), false);
			g.pose().popMatrix();
			if (row == mouseRow && st.available()) g.setTooltipForNextFrame(font, Component.translatable("magic.office.friend_ask"), mouseX, mouseY);
		}
	}

	private static @Nullable Component why(Status st, TravelRoster r, long now) {
		return switch (st) {
			case AVAILABLE -> null;
			case WAITING -> Component.translatable("magic.office.why_waiting", r.state() == TravelRoster.State.WAITING ? waitingSeconds(r, now) : 30);
			case TRAVELLING -> Component.translatable("magic.office.why_travelling");
			case BUSY -> Component.translatable("magic.office.why_busy");
			case COOLDOWN -> Component.translatable("magic.office.why_cooldown");
			case OFF -> Component.translatable("magic.office.why_off");
		};
	}

	/** A small chevron button for the pager; dimmed when there is no page that way. */
	private static void arrow(GuiGraphicsExtractor g, Rect r, int dir, boolean enabled, boolean hovered, float u, float k) {
		int col = !enabled ? UiKit.alpha(UiKit.INK_SOFT, 0.3f) : hovered ? UiKit.inkOf(Element.RECALL_ARGB) : UiKit.INK;
		if (enabled && hovered) UiKit.roundedOutline(g, r.x(), r.y(), r.right(), r.bottom(), UiKit.alpha(UiKit.INK_SOFT, 0.6f * k));
		int half = Math.max(2, Math.round(Math.min(r.w(), r.h()) * 0.28f));
		int cx = r.cx(), cy = r.cy();
		int thick = Math.max(1, Math.round(u));
		for (int i = 0; i < half; i++) {
			int x = dir < 0 ? cx - half / 2 + i : cx + half / 2 - i;
			g.fill(x, cy - half + i, x + thick, cy - half + i + 1, UiKit.alpha(col, k));
			g.fill(x, cy + half - i - 1, x + thick, cy + half - i, UiKit.alpha(col, k));
		}
		int tip = dir < 0 ? cx - half / 2 + half : cx + half / 2 - half;
		g.fill(tip, cy - 1, tip + thick, cy + 1, UiKit.alpha(col, k));
	}

	/** The friend's skin (from the tab list), or the default skin for their UUID. */
	static PlayerSkin skin(UUID id) {
		var conn = Minecraft.getInstance().getConnection();
		PlayerInfo info = conn == null ? null : conn.getPlayerInfo(id);
		return info != null ? info.getSkin() : DefaultPlayerSkin.get(id);
	}

	/** A player's face (with hat layer) {@code size} wide at a float position, tinted. */
	static void face(GuiGraphicsExtractor g, PlayerSkin skin, float x, float y, float size, int argb, float k) {
		int col = UiKit.alpha(argb, ((argb >>> 24) & 0xFF) / 255f * k);
		if (((col >>> 24) & 0xFF) < 4) return;
		UiKit.rounded(g, Math.round(x) - 1, Math.round(y) + 1, Math.round(x + size) + 1, Math.round(y + size) + 2, UiKit.alpha(0xFF000000, 0.25f * k));
		g.pose().pushMatrix();
		g.pose().translate(x, y);
		g.pose().scale(size / 8f, size / 8f);
		PlayerFaceExtractor.extractRenderState(g, skin, 0, 0, 8, col);
		g.pose().popMatrix();
	}

	/** Paper colour of the active tab (matches the pages). */
	static int paper() {
		return PAGE_INK;
	}
}
