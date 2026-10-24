package local.carter.office.client.magic.screen;

import java.util.ArrayList;
import java.util.List;

import local.carter.office.client.magic.screen.SignLayout.Rect;
import local.carter.office.magic.core.Element;
import local.carter.office.magic.core.Slots;
import local.carter.office.magic.data.MagicAttachments;
import local.carter.office.magic.net.MagicPayloads.ChooseElement;
import local.carter.office.magic.spell.SpellIds;

import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.client.resources.sounds.SimpleSoundInstance;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.util.FormattedCharSequence;
import net.minecraft.util.Util;

import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;

/**
 * "Choose your element": the grimoire opens in the middle of a dark vignette and the six element sigils orbit it on an ellipse
 * ({@link ChoiceLayout}, 4°/s). Hovering a sigil (or picking one with Tab / the arrow keys) pauses the orbit, grows it to 1.25×
 * with a pulsing glow in its colour, and the book's pages show that element: its name, motto, a turning summoning circle and
 * its five spells. Clicking (or Enter) turns the orbit so the sigil sits on top, fades the others out and asks to confirm
 * ({@code bind_q}, {@code bind_note}, Bind / Back). Bind sends {@link ChooseElement} and waits ("Binding…", buttons off) for
 * the server's binding to sync back; then it flashes in the element's colour for 20 ticks and closes. With no change after
 * {@link #BIND_TIMEOUT_TICKS} it returns to the confirm step with an error line. Opened by a Rebinding Seal, the current
 * element is marked and can't be chosen. Esc closes without binding. Not a pause screen.
 */
public final class ElementChoiceScreen extends Screen {
	public enum State { CHOOSING, CONFIRM, BINDING, BOUND }

	private static final float ORBIT_DEG_PER_S = 4f;
	private static final long BOOK_MS = 520;
	private static final long SIGIL_DELAY_MS = 260;
	private static final long SIGIL_STAGGER_MS = 70;
	private static final long SIGIL_FLY_MS = 520;
	private static final long TURN_MS = 650;
	public static final int BOUND_TICKS = 20;
	/** How long Bind waits for the server's synced element before giving up with an error (ticks). */
	public static final int BIND_TIMEOUT_TICKS = 40;
	private static final int GOLD = 0xFFFFD27A;
	private static final Element[] ELEMENTS = Element.values();

	private final long openedAt = Util.getMillis();
	private long lastFrame = openedAt;
	private float orbit;
	private int hover = -1;
	private int focus = -1;
	private double lastMouseX = Double.NaN, lastMouseY = Double.NaN;
	private final float[] grow = new float[ChoiceLayout.SIGILS];
	/** Element shown on the pages (-1 = the idle title page) and its fade (0..1). */
	private int page = -1;
	private float pageFade = 1f;
	private float aura;
	private int auraColour = GOLD;

	private State state = State.CHOOSING;
	private int chosen = -1;
	private long stateAt;
	private float turnFrom, turnTo;
	private int boundTicks;
	private int bindingTicks;
	private boolean bindFailed;
	/** The element bound when the screen opened (a Rebinding Seal), which can't be chosen again; -1 when unbound. */
	private final int current;
	private float othersFade = 1f;
	/** Spell-name wrapping for the right page, cached per (element, page size, GUI scale, language). */
	private Object spellsKey;
	private float spellsScale;
	private List<List<FormattedCharSequence>> spellsLines = List.of();
	private RuneButton bind, back;
	private final List<FormattedCharSequence> lines = new ArrayList<>();

	public ElementChoiceScreen() {
		super(Component.translatable("magic.office.choose"));
		var player = net.minecraft.client.Minecraft.getInstance().player;
		this.current = player == null ? -1 : MagicAttachments.get(player).element().map(Element::ordinal).orElse(-1);
	}

	// ---------------------------------------------------------------- state for tests

	public State state() { return state; }

	public float orbitDeg() { return orbit; }

	/** The layout as drawn now. */
	public ChoiceLayout.Layout layout() { return ChoiceLayout.layout(width, height, orbit); }

	/** True once the opening animation (book and every sigil) has finished. */
	public boolean introDone() {
		return Util.getMillis() - openedAt >= SIGIL_DELAY_MS + 5 * SIGIL_STAGGER_MS + SIGIL_FLY_MS + 50;
	}

	public int active() { return hover >= 0 ? hover : focus; }

	// ---------------------------------------------------------------- screen

	@Override
	protected void init() {
		ChoiceLayout.Layout l = layout();
		bind = new RuneButton(l.bind().x(), l.bind().y(), l.bind().w(), l.bind().h(), Component.translatable("magic.office.bind"), true,
				this::chosenColour, this::bindNow);
		back = new RuneButton(l.back().x(), l.back().y(), l.back().w(), l.back().h(), Component.translatable("magic.office.back"), false,
				this::chosenColour, this::backToOrbit);
		addRenderableWidget(bind);
		addRenderableWidget(back);
		syncButtons();
	}

	@Override
	public boolean isPauseScreen() {
		return false;
	}

	@Override
	public void tick() {
		if (minecraft.player == null || !minecraft.player.isAlive()) {
			onClose();
			return;
		}
		if (state == State.BINDING) {
			// The flash plays only once the server's binding has synced back; no change in time → back to confirm, with an error.
			if (MagicAttachments.get(minecraft.player).element().equals(java.util.Optional.of(ELEMENTS[chosen]))) {
				orbit = currentTurn();
				state = State.BOUND;
				stateAt = Util.getMillis();
				boundTicks = 0;
				syncButtons();
				play(SimpleSoundInstance.forUI(SoundEvents.AMETHYST_BLOCK_RESONATE, 0.9f, 1f));
				play(SimpleSoundInstance.forUI(SoundEvents.BEACON_ACTIVATE, 1.6f, 0.6f));
			} else if (++bindingTicks >= BIND_TIMEOUT_TICKS) {
				state = State.CONFIRM;
				stateAt = Util.getMillis() - 1000; // no re-run of the confirm animations
				turnFrom = turnTo = orbit;
				bindFailed = true;
				syncButtons();
				setFocused(bind);
				play(SimpleSoundInstance.forUI(SoundEvents.NOTE_BLOCK_BASS, 0.6f));
			}
			return;
		}
		if (state == State.BOUND && ++boundTicks >= BOUND_TICKS) onClose();
	}

	private int chosenColour() {
		return chosen >= 0 ? ELEMENTS[chosen].argb() : GOLD;
	}

	private void syncButtons() {
		if (bind == null) return;
		boolean on = state == State.CONFIRM;
		bind.visible = back.visible = on || state == State.BINDING || state == State.BOUND;
		bind.active = back.active = on;
		bind.setMessage(Component.translatable(state == State.BINDING ? "magic.office.binding" : "magic.office.bind"));
	}

	// ---------------------------------------------------------------- input

	@Override
	public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
		if (state == State.CONFIRM) return super.mouseClicked(event, doubleClick);
		if (state != State.CHOOSING || !introDone()) return true;
		int i = layout().sigilAt(event.x(), event.y());
		if (i >= 0) confirm(i);
		return true;
	}

	@Override
	public boolean keyPressed(KeyEvent event) {
		if (event.isEscape()) {
			if (state != State.BOUND) onClose();
			return true;
		}
		if (state == State.CHOOSING) {
			int dir = 0;
			if (event.isCycleFocus()) dir = event.hasShiftDown() ? -1 : 1;
			else if (event.isRight() || event.isDown()) dir = 1;
			else if (event.isLeft() || event.isUp()) dir = -1;
			if (dir != 0) {
				int from = active();
				focus = from < 0 ? (dir > 0 ? 0 : ChoiceLayout.SIGILS - 1) : Math.floorMod(from + dir, ChoiceLayout.SIGILS);
				hover = -1;
				lastMouseX = Double.NaN; // a later mouse move takes over again
				onActiveChanged(focus);
				return true;
			}
			if (event.isSelection() || event.isConfirmation()) {
				if (active() >= 0) confirm(active());
				return true;
			}
			return true;
		}
		if (state == State.CONFIRM) {
			if (event.isSelection() || event.isConfirmation()) {
				if (getFocused() == back) backToOrbit();
				else bindNow();
				return true;
			}
			if (event.isLeft() || event.isRight() || event.isCycleFocus()) {
				setFocused(getFocused() == bind ? back : bind);
				return true;
			}
		}
		return super.keyPressed(event);
	}

	private void confirm(int i) {
		if (i == current) {
			play(SimpleSoundInstance.forUI(SoundEvents.NOTE_BLOCK_BASS, 0.6f)); // already your element: a seal would be wasted
			return;
		}
		bindFailed = false;
		chosen = i;
		focus = i;
		state = State.CONFIRM;
		stateAt = Util.getMillis();
		// Turn the orbit the short way so the chosen sigil comes to rest at the top.
		turnFrom = orbit;
		float target = -i * 60f;
		float delta = ((target - orbit) % 360f + 540f) % 360f - 180f;
		turnTo = orbit + delta;
		syncButtons();
		setFocused(bind);
		play(SimpleSoundInstance.forUI(SoundEvents.BOOK_PAGE_TURN, 1f, 1f));
	}

	private void backToOrbit() {
		if (state != State.CONFIRM) return;
		orbit = currentTurn(); // before leaving CONFIRM: the sigils stay where the turn left them
		state = State.CHOOSING;
		stateAt = Util.getMillis();
		hover = -1;
		syncButtons();
		setFocused(null);
		play(SimpleSoundInstance.forUI(SoundEvents.BOOK_PAGE_TURN, 0.85f, 0.8f));
	}

	/** Sends the choice and waits (BINDING) for the server's binding to sync back; the flash plays then (see {@link #tick}). */
	private void bindNow() {
		if (state != State.CONFIRM || chosen < 0) return;
		orbit = currentTurn();
		turnFrom = turnTo = orbit;
		state = State.BINDING;
		bindingTicks = 0;
		bindFailed = false;
		syncButtons();
		if (ClientPlayNetworking.canSend(ChooseElement.TYPE)) ClientPlayNetworking.send(new ChooseElement(ELEMENTS[chosen]));
		play(SimpleSoundInstance.forUI(SoundEvents.BOOK_PAGE_TURN, 1.2f, 0.7f));
	}

	/** Orbit angle while the confirm turn animates. */
	private float currentTurn() {
		if (state != State.CONFIRM) return orbit;
		float t = UiKit.easeInOut((Util.getMillis() - stateAt) / (float) TURN_MS);
		return turnFrom + (turnTo - turnFrom) * t;
	}

	private void onActiveChanged(int i) {
		if (i >= 0) play(SimpleSoundInstance.forUI(SoundEvents.AMETHYST_BLOCK_CHIME, 0.8f + 0.1f * i, 0.5f));
	}

	private void play(SimpleSoundInstance s) {
		minecraft.getSoundManager().play(s);
	}

	// ---------------------------------------------------------------- per-frame animation

	private void animate(int mouseX, int mouseY, long now) {
		float dt = Math.min(0.1f, (now - lastFrame) / 1000f);
		lastFrame = now;
		if (state == State.CHOOSING) {
			boolean moved = Double.isNaN(lastMouseX) ? false : (mouseX != lastMouseX || mouseY != lastMouseY);
			if (Double.isNaN(lastMouseX) && focus < 0) moved = true;
			lastMouseX = mouseX;
			lastMouseY = mouseY;
			int before = active();
			if (moved || focus < 0) {
				int h = introDone() ? ChoiceLayout.layout(width, height, orbit).sigilAt(mouseX, mouseY) : -1;
				if (moved) focus = -1;
				hover = h;
			}
			if (active() != before) onActiveChanged(active());
			if (active() < 0 && introDone()) orbit = (orbit + ORBIT_DEG_PER_S * dt) % 360f;
			othersFade = UiKit.approach(othersFade, 1f, 10f, dt);
		} else if (state == State.CONFIRM || state == State.BINDING) {
			othersFade = UiKit.approach(othersFade, 0f, 9f, dt);
		}
		for (int i = 0; i < grow.length; i++) {
			boolean big = state == State.CHOOSING ? i == active() : i == chosen;
			grow[i] = UiKit.approach(grow[i], big ? 1f : 0f, 16f, dt);
		}
		// Pages cross-fade: out, swap, in.
		int want = state == State.CHOOSING ? active() : chosen;
		if (want != page) {
			pageFade -= dt * 9f;
			if (pageFade <= 0f) {
				pageFade = 0f;
				page = want;
			}
		} else {
			pageFade = Math.min(1f, pageFade + dt * 7f);
		}
		int wantColour = want >= 0 ? ELEMENTS[want].argb() : GOLD;
		aura = UiKit.approach(aura, 1f, 4f, dt);
		auraColour = UiKit.mix(auraColour, wantColour, 1f - (float) Math.exp(-6f * dt));
	}

	// ---------------------------------------------------------------- drawing

	@Override
	public void extractBackground(GuiGraphicsExtractor g, int mouseX, int mouseY, float a) {
		extractBlurredBackground(g);
		float k = UiKit.easeOut((Util.getMillis() - openedAt) / 300f);
		g.fill(0, 0, width, height, UiKit.alpha(0xA8080610, k));
		UiKit.vignette(g, width, height, k);
	}

	@Override
	public void extractRenderState(GuiGraphicsExtractor g, int mouseX, int mouseY, float a) {
		long now = Util.getMillis();
		animate(mouseX, mouseY, now);
		float drawOrbit = currentTurn();
		ChoiceLayout.Layout l = ChoiceLayout.layout(width, height, drawOrbit);
		Rect book = l.book();
		long t = now - openedAt;
		float bookIn = UiKit.easeOut((t - 40) / (float) BOOK_MS);
		float flash = state == State.BOUND ? (now - stateAt) / (BOUND_TICKS * 50f) : 0f;

		// Aura behind the book, in the shown element's colour (gold when idle); it swells when bound.
		float auraSize = Math.max(book.w(), book.h()) * (2.1f + 0.08f * (float) Math.sin(now / 900.0)) * (1f + 0.6f * UiKit.easeOut(flash));
		UiKit.glow(g, book.cx(), book.cy(), auraSize, UiKit.alpha(auraColour, 0.32f * aura * bookIn));

		motes(g, l, now, bookIn);
		orbitPath(g, l, drawOrbit, now, Math.min(bookIn, othersFade));
		book(g, book, bookIn, 1f);
		if (bookIn > 0.6f) pages(g, l, now, UiKit.clamp01((bookIn - 0.6f) / 0.4f) * pageFade);

		for (int i = 0; i < ChoiceLayout.SIGILS; i++) sigil(g, l, i, now, t, flash);

		if (state == State.CONFIRM || state == State.BINDING || state == State.BOUND) {
			float f = UiKit.easeOut((now - stateAt - (state == State.CONFIRM ? 220 : 0)) / 260f);
			float fade = state == State.CONFIRM ? f : state == State.BINDING ? 1f : 1f - UiKit.clamp01(flash * 3f);
			bind.fade = back.fade = fade;
		}
		super.extractRenderState(g, mouseX, mouseY, a); // the Bind / Back buttons

		if (state == State.BOUND) boundFlash(g, l, now, flash);
	}

	/** The opening book: each half swings out from the spine. */
	static void book(GuiGraphicsExtractor g, Rect book, float open, float alpha) {
		if (open <= 0.01f || alpha <= 0.01f) return;
		float sy = book.h() / (float) ChoiceLayout.TEX_H;
		float sx = book.w() / (float) ChoiceLayout.TEX_W;
		float scale = 0.86f + 0.14f * open;
		float spine = book.x() + book.w() / 2f;
		int col = UiKit.alpha(UiKit.WHITE, alpha * Math.min(1f, open * 2.5f));
		// Shadow under the book.
		g.pose().pushMatrix();
		g.pose().translate(spine, book.cy());
		g.pose().scale(scale, scale);
		UiKit.rounded(g, Math.round(-book.w() / 2f * open) + 2, -book.h() / 2 + 4, Math.round(book.w() / 2f * open) + 3,
				book.h() / 2 + 4, UiKit.alpha(0xFF000000, 0.45f * alpha * open));
		for (int side = 0; side < 2; side++) {
			g.pose().pushMatrix();
			g.pose().scale(sx * open, sy);
			int u = side == 0 ? 0 : ChoiceLayout.TEX_W / 2;
			int x = side == 0 ? -ChoiceLayout.TEX_W / 2 : 0;
			g.blit(net.minecraft.client.renderer.RenderPipelines.GUI_TEXTURED, UiKit.BOOK, x, -ChoiceLayout.TEX_H / 2, u, 0f,
					ChoiceLayout.TEX_W / 2, ChoiceLayout.TEX_H, ChoiceLayout.TEX_W / 2, ChoiceLayout.TEX_H, ChoiceLayout.TEX_W, ChoiceLayout.TEX_H, col);
			g.pose().popMatrix();
		}
		g.pose().popMatrix();
	}

	/** A faint dotted ellipse the sigils travel on. */
	private void orbitPath(GuiGraphicsExtractor g, ChoiceLayout.Layout l, float orbitDeg, long now, float k) {
		if (k <= 0.01f) return;
		int dots = 90;
		float rx = ChoiceLayout.RX * width, ry = ChoiceLayout.RY * height;
		int dot = Math.max(1, Math.round(Math.min(width, height) / 300f));
		for (int d = 0; d < dots; d++) {
			double ang = Math.toRadians(orbitDeg * 0.5 + d * 360.0 / dots);
			float x = width / 2f + rx * (float) Math.cos(ang), y = height / 2f + ry * (float) Math.sin(ang);
			float tw = 0.5f + 0.5f * (float) Math.sin(now / 400.0 + d * 0.7);
			g.fill(Math.round(x), Math.round(y), Math.round(x) + dot, Math.round(y) + dot, UiKit.alpha(auraColour, k * (0.12f + 0.16f * tw)));
		}
	}

	/** Slow motes of light drifting up around the book. */
	private void motes(GuiGraphicsExtractor g, ChoiceLayout.Layout l, long now, float k) {
		if (k <= 0.01f) return;
		int n = 26;
		float unit = Math.min(width, height);
		for (int i = 0; i < n; i++) {
			float seed = i * 12.9898f;
			float fx = frac((float) Math.sin(seed) * 43758.55f);
			float speed = 0.04f + 0.05f * frac((float) Math.sin(seed * 1.7f) * 24634.63f);
			float phase = frac(fx * 7.31f + now / 1000f * speed);
			float x = width * (0.08f + 0.84f * fx) + (float) Math.sin(now / 1300.0 + i) * unit * 0.02f;
			float y = height * (1.05f - 1.1f * phase);
			float life = (float) Math.sin(Math.PI * phase);
			float size = unit * (0.012f + 0.014f * frac(fx * 3.3f));
			UiKit.glow(g, x, y, size * 3f, UiKit.alpha(auraColour, 0.35f * life * k));
			g.fill(Math.round(x), Math.round(y), Math.round(x) + 1, Math.round(y) + 1, UiKit.alpha(0xFFFFF4D8, 0.7f * life * k));
		}
	}

	private static float frac(float v) {
		return v - (float) Math.floor(v);
	}

	private void sigil(GuiGraphicsExtractor g, ChoiceLayout.Layout l, int i, long now, long t, float flash) {
		Element e = ELEMENTS[i];
		float appear = UiKit.clamp01((t - SIGIL_DELAY_MS - i * SIGIL_STAGGER_MS) / (float) SIGIL_FLY_MS);
		if (appear <= 0f) return;
		boolean isChosen = i == chosen && state != State.CHOOSING;
		float vis = isChosen ? 1f : othersFade;
		if (vis <= 0.01f) return;
		// Fly out from the book's centre.
		float fly = UiKit.easeOutBack(appear);
		float cx = l.book().cx() + (l.cx(i) - l.book().cx()) * fly;
		float cy = l.book().cy() + (l.cy(i) - l.book().cy()) * fly;
		float scale = (0.35f + 0.65f * UiKit.easeOut(appear)) * (1f + (ChoiceLayout.HOVER_SCALE - 1f) * grow[i]);
		float size = l.sigilSize() * scale;
		float alpha = UiKit.clamp01(appear * 2.2f) * vis;
		if (isChosen && state == State.BOUND) {
			float f = UiKit.easeOut(flash * 1.6f);
			size *= 1f + 0.9f * f;
			alpha *= 1f - UiKit.clamp01((flash - 0.35f) / 0.65f);
		}
		// Glow: faint always, pulsing in the element colour when hovered or chosen.
		float pulse = 0.5f + 0.5f * (float) Math.sin(now / (state == State.BINDING && isChosen ? 70.0 : 170.0));
		if (i == current && state == State.CHOOSING) alpha *= 0.55f; // your element now: shown, but not choosable
		float glowK = 0.22f + grow[i] * (0.45f + 0.35f * pulse);
		UiKit.glow(g, cx, cy, size * (1.9f + 0.35f * grow[i] * pulse), UiKit.alpha(e.argb(), glowK * alpha));
		// A slowly turning rune ring behind the hovered one.
		if (grow[i] > 0.02f) {
			UiKit.centredTexture(g, UiKit.ring(1), 128, cx, cy, size * 1.32f, (float) (now / 2600.0), UiKit.alpha(e.argb(), 0.75f * grow[i] * alpha));
		}
		UiKit.centredTexture(g, UiKit.sigil(e), 64, cx, cy + 1f, size, 0f, UiKit.alpha(0xFF000000, 0.45f * alpha)); // shadow
		UiKit.centredTexture(g, UiKit.sigil(e), 64, cx, cy, size, 0f, UiKit.alpha(UiKit.WHITE, alpha));
		// Name under the hovered sigil (white, outlined) so it reads even before the pages turn.
		boolean isCurrent = i == current && state == State.CHOOSING;
		if ((grow[i] > 0.05f || isCurrent) && state == State.CHOOSING) {
			Component name = isCurrent ? Component.translatable("magic.office.current") : UiKit.elementName(e);
			float s = UiKit.crisp(Math.max(1f, l.sigilSize() / 44f));
			// On the side away from the book, clear of the hover ring (0.66 × size); skipped when the screen edge leaves no
			// room (the pages show the name anyway).
			boolean below = cy >= height / 2f;
			float y = below ? cy + size * 0.66f + 1f : cy - size * 0.66f - 1f - 9 * s;
			if (y >= 1 && y + 9 * s <= height - 1) {
				float k = isCurrent ? Math.max(grow[i], 0.8f) : grow[i];
				UiKit.centred(g, font, name, cx, y, s, UiKit.alpha(UiKit.mix(e.argb(), UiKit.WHITE, 0.55f), k * alpha), true);
			}
		}
	}

	// ---------------------------------------------------------------- pages

	private void pages(GuiGraphicsExtractor g, ChoiceLayout.Layout l, long now, float k) {
		if (k <= 0.01f) return;
		float unit = l.book().w() / 270f;
		if (page < 0) {
			idlePages(g, l, now, k, unit);
			return;
		}
		Element e = ELEMENTS[page];
		elementPage(g, l.leftPage(), e, now, k, unit);
		if (state == State.CHOOSING || chosen != page) spellsPage(g, l.rightPage(), e, k, unit);
		else confirmPage(g, l.rightPage(), e, k, unit);
	}

	private void idlePages(GuiGraphicsExtractor g, ChoiceLayout.Layout l, long now, float k, float unit) {
		Rect p = l.leftPage();
		float y = p.y() + p.h() * 0.12f;
		float s = UiKit.wrap(font, title, p.w(), 2.5f * unit, 3, lines);
		for (FormattedCharSequence line : lines) {
			UiKit.centred(g, font, line, p.cx(), y, s, UiKit.alpha(UiKit.INK, k), false);
			y += 10 * s;
		}
		y += 4 * unit;
		// A small divider: a diamond between two rules.
		int rule = Math.round(p.w() * 0.3f);
		int ry = Math.round(y);
		g.fill(p.cx() - rule, ry, p.cx() - 3, ry + 1, UiKit.alpha(UiKit.INK_SOFT, k));
		g.fill(p.cx() + 3, ry, p.cx() + rule, ry + 1, UiKit.alpha(UiKit.INK_SOFT, k));
		g.fill(p.cx() - 1, ry - 1, p.cx() + 1, ry + 2, UiKit.alpha(UiKit.inkOf(GOLD), k));
		y += 6 * unit;
		Component hint = Component.translatable("magic.office.choose_hint");
		float hs = UiKit.wrap(font, hint, p.w(), unit, 4, lines);
		for (FormattedCharSequence line : lines) {
			UiKit.centred(g, font, line, p.cx(), y, hs, UiKit.alpha(UiKit.INK_SOFT, k), false);
			y += 10 * hs;
		}
		// Right page: a slowly turning gold circle.
		Rect r = l.rightPage();
		float size = Math.min(r.w(), r.h()) * 0.92f;
		circle(g, r.cx(), r.cy(), size, UiKit.inkOf(GOLD), now, k);
	}

	/** Layered rings turning against each other, and a sigil-free centre rune. */
	private static void circle(GuiGraphicsExtractor g, float cx, float cy, float size, int argb, long now, float k) {
		float turn = (float) (now / 4000.0);
		UiKit.centredTexture(g, UiKit.ring(4), 128, cx, cy, size, turn * 0.6f, UiKit.alpha(argb, 0.8f * k));
		UiKit.centredTexture(g, UiKit.ring(2), 128, cx, cy, size * 0.78f, -turn, UiKit.alpha(argb, 0.85f * k));
		UiKit.centredTexture(g, UiKit.ring(0), 128, cx, cy, size * 0.6f, turn * 1.4f, UiKit.alpha(argb, 0.9f * k));
	}

	private void elementPage(GuiGraphicsExtractor g, Rect p, Element e, long now, float k, float unit) {
		int ink = UiKit.inkOf(e.argb());
		Component name = UiKit.elementName(e);
		float ns = UiKit.fit(font.width(name), p.w() * 0.96f, 2f * unit);
		float y = p.y() + p.h() * 0.04f;
		UiKit.centred(g, font, name, p.cx(), y, ns, UiKit.alpha(ink, k), false);
		y += 10 * ns + 1;
		Component motto = Component.translatable("element.office." + e.key() + ".motto").withStyle(s -> s.withItalic(true));
		float ms = UiKit.wrap(font, motto, p.w() * 0.96f, unit, 2, lines);
		for (FormattedCharSequence line : lines) {
			UiKit.centred(g, font, line, p.cx(), y, ms, UiKit.alpha(UiKit.INK_SOFT, k), false);
			y += 10 * ms;
		}
		float room = p.bottom() - y - p.h() * 0.03f;
		float size = Math.min(p.w() * 0.86f, room);
		if (size > 8) {
			float cx = p.cx(), cy = y + room / 2f + 1;
			UiKit.glow(g, cx, cy, size * 1.25f, UiKit.alpha(e.argb(), 0.28f * k));
			circle(g, cx, cy, size, ink, now, k);
			UiKit.centredTexture(g, UiKit.sigil(e), 64, cx, cy, size * 0.42f, 0f, UiKit.alpha(UiKit.WHITE, 0.9f * k));
		}
	}

	private void spellsPage(GuiGraphicsExtractor g, Rect p, Element e, float k, float unit) {
		int rows = Slots.ULTIMATE + 1;
		float rowH = p.h() / (float) rows;
		float icon = Math.min(rowH * 0.78f, 18f * unit);
		float textRoom = p.w() - icon - 3 * unit;
		// One scale for all five names: the largest crisp one at which every name fits its row, wrapping onto a second
		// line when the row is tall enough (small books keep readable text that way).
		Object key = List.of(e, p, Math.round(unit * 64), UiKit.guiScale(), minecraft.options.languageCode);
		if (!key.equals(spellsKey)) {
			wrapSpells(e, rowH, textRoom, unit);
			spellsKey = key;
		}
		float s = spellsScale;
		List<List<FormattedCharSequence>> wrapped = spellsLines;
		for (int slot = 0; slot < rows; slot++) {
			Identifier id = SpellIds.at(e, slot);
			float cy = p.y() + rowH * (slot + 0.5f);
			UiKit.centredTexture(g, UiKit.spellIcon(id), 32, p.x() + icon / 2f, cy, icon, 0f, UiKit.alpha(UiKit.WHITE, k));
			int col = slot == Slots.ULTIMATE ? UiKit.inkOf(e.argb()) : UiKit.INK;
			List<FormattedCharSequence> nl = slot < wrapped.size() ? wrapped.get(slot) : List.of(UiKit.spellName(id).getVisualOrderText());
			float y = cy - (nl.size() * 9.5f * s - 1.5f * s) / 2f;
			for (FormattedCharSequence line : nl) {
				UiKit.text(g, font, line, p.x() + icon + 3 * unit, y, s, UiKit.alpha(col, k), false);
				y += 9.5f * s;
			}
		}
	}

	/** Finds the spell-name scale and lines for {@link #spellsPage} (run only when the key changes). */
	private void wrapSpells(Element e, float rowH, float textRoom, float unit) {
		int rows = Slots.ULTIMATE + 1;
		float s = UiKit.crisp(unit);
		float step = 1f / UiKit.guiScale();
		List<List<FormattedCharSequence>> wrapped = new ArrayList<>();
		for (int guard = 0; guard < 32; guard++) {
			int maxLines = rowH >= 2 * 9.5f * s ? 2 : 1;
			wrapped.clear();
			boolean ok = true;
			for (int slot = 0; slot < rows && ok; slot++) {
				List<FormattedCharSequence> nl = font.split(UiKit.spellName(SpellIds.at(e, slot)), Math.max(1, (int) Math.floor(textRoom / s)));
				for (FormattedCharSequence line : nl) ok &= font.width(line) * s <= textRoom + 0.01f;
				ok &= nl.size() <= maxLines;
				wrapped.add(nl);
			}
			if (ok || s <= step * 1.01f) break;
			s = UiKit.crisp(s - step);
		}
		spellsScale = s;
		spellsLines = List.copyOf(wrapped);
	}

	private void confirmPage(GuiGraphicsExtractor g, Rect p, Element e, float k, float unit) {
		Component q = Component.translatable("magic.office.bind_q", UiKit.elementName(e).copy().withStyle(st -> st.withColor(UiKit.inkOf(e.argb()))));
		float y = p.y() + p.h() * 0.14f;
		float qs = UiKit.wrap(font, q, p.w(), 1.5f * unit, 2, lines);
		for (FormattedCharSequence line : lines) {
			UiKit.centred(g, font, line, p.cx(), y, qs, UiKit.alpha(UiKit.INK, k), false);
			y += 10 * qs;
		}
		y += 3 * unit;
		int rule = Math.round(p.w() * 0.32f);
		g.fill(p.cx() - rule, Math.round(y), p.cx() + rule, Math.round(y) + 1, UiKit.alpha(UiKit.INK_SOFT, 0.6f * k));
		y += 5 * unit;
		Component note = Component.translatable("magic.office.bind_note");
		float ns = UiKit.wrap(font, note, p.w(), unit, 5, lines);
		for (FormattedCharSequence line : lines) {
			UiKit.centred(g, font, line, p.cx(), y, ns, UiKit.alpha(UiKit.INK_SOFT, k), false);
			y += 10 * ns;
		}
		if (bindFailed && state == State.CONFIRM) {
			y += 4 * unit;
			Component err = Component.translatable("magic.office.bind_failed");
			float es = UiKit.wrap(font, err, p.w(), unit, 3, lines);
			for (FormattedCharSequence line : lines) {
				UiKit.centred(g, font, line, p.cx(), y, es, UiKit.alpha(0xFFA8321E, k), false);
				y += 10 * es;
			}
		}
	}

	/** Binding: a burst of rings from the sigil and a full-screen flash in the element's colour. */
	private void boundFlash(GuiGraphicsExtractor g, ChoiceLayout.Layout l, long now, float flash) {
		Element e = ELEMENTS[chosen];
		float cx = l.cx(chosen), cy = l.cy(chosen);
		// Three rings ripple out from the sigil (kept under ~4 texels per GUI unit so they stay crisp, not blocky).
		float reach = Math.min(Math.min(width, height) * 0.9f, 128f * 4f / UiKit.guiScale() * 2f);
		for (int r = 0; r < 3; r++) {
			float t = UiKit.clamp01(flash * 1.6f - r * 0.16f);
			if (t <= 0f || t >= 1f) continue;
			float a = (1f - t) * (1f - t);
			UiKit.centredTexture(g, UiKit.ring(r == 1 ? 3 : r * 2), 128, cx, cy, l.sigilSize() * 1.2f + reach * UiKit.easeOut(t),
					t * (r % 2 == 0 ? 1.2f : -1.2f), UiKit.alpha(UiKit.mix(e.argb(), UiKit.WHITE, 0.35f), 0.85f * a));
		}
		UiKit.glow(g, cx, cy, l.sigilSize() * (2f + 4f * UiKit.easeOut(flash)), UiKit.alpha(e.argb(), 0.6f * (1f - UiKit.clamp01(flash))));
		float white = 1f - UiKit.clamp01(flash / 0.18f);
		float tint = (float) Math.sin(Math.PI * UiKit.clamp01(flash)) * 0.35f;
		g.fill(0, 0, width, height, UiKit.alpha(e.argb(), tint));
		g.fill(0, 0, width, height, UiKit.alpha(UiKit.WHITE, 0.55f * white));
	}
}
