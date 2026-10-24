package local.carter.office.client.magic.screen;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import org.jspecify.annotations.Nullable;

import com.mojang.blaze3d.platform.InputConstants;

import local.carter.office.OfficeMod;
import local.carter.office.magic.core.Dir;
import local.carter.office.magic.core.Element;
import local.carter.office.magic.core.HandSigns;
import local.carter.office.magic.data.MagicAttachments;
import local.carter.office.magic.net.MagicPayloads.SignAnswer;
import local.carter.office.magic.net.MagicPayloads.SignChallenge;
import local.carter.office.magic.spell.Spell;
import local.carter.office.magic.spell.SpellRegistry;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.client.resources.sounds.SimpleSoundInstance;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.util.Util;

import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;

/**
 * The hand-sign prompt for an ultimate. Not a pause screen: the world keeps running, but a screen is open, so movement and
 * looking stop (vanilla releases every key mapping when a screen opens) and WASD only type arrows. The arrows pop in one by
 * one in a row; each right arrow lights up in the element colour with a chime; the timer bar shrinks and turns red near the
 * end. A complete sequence sends {@link SignAnswer}, flashes and closes. The first wrong arrow, Esc or the time limit sends
 * what was typed (the server fizzles it): a wrong arrow shakes the row red first. The prompt also closes when the player
 * dies or changes level; anything that closes it early still sends the partial answer, which the server fizzles or ignores.
 */
public final class HandSignScreen extends Screen {
	private static final Identifier GLOW = OfficeMod.id("textures/gui/magic/glow.png");
	private static final long INTRO_MS = 180;
	private static final long STAGGER_MS = 45;
	private static final long POP_MS = 160;
	private static final long FLASH_MS = 320;
	private static final long SHAKE_MS = 480;
	private static final int RED = 0xFFFF4A4A;
	private static final int WHITE = 0xFFFFFFFF;

	private final SignChallenge challenge;
	private final HandSignInput input;
	private final int colour;
	private final Component spellName;
	private final Component hint = Component.translatable("magic.office.signs_hint");
	private final long openedAt;
	private @Nullable ClientLevel openedIn;
	private boolean sent;

	public HandSignScreen(SignChallenge c) {
		super(Component.translatable("magic.office.signs"));
		this.challenge = c;
		this.openedAt = Util.getMillis();
		List<Integer> held = new ArrayList<>();
		for (int key : HandSignInput.KEYS) if (InputConstants.isKeyDown(key)) held.add(key);
		this.input = new HandSignInput(HandSigns.sequence(c.seed(), c.length()), openedAt, c.limitTicks() * 50L, held);
		Optional<Spell> spell = SpellRegistry.get(c.spell());
		this.colour = colourOf(spell.orElse(null));
		this.spellName = Component.translatable("spell." + c.spell().getNamespace() + "." + c.spell().getPath());
	}

	/** ↑/W, ↓/S, ←/A, →/D → {@link Dir}; anything else null. */
	public static @Nullable Dir dirFor(int keyCode) {
		return HandSignInput.dirFor(keyCode);
	}

	private static int colourOf(@Nullable Spell spell) {
		if (spell != null) return spell.element() != null ? spell.element().argb() : Element.RECALL_ARGB;
		Minecraft mc = Minecraft.getInstance();
		if (mc.player != null) {
			Optional<Element> e = MagicAttachments.get(mc.player).element();
			if (e.isPresent()) return e.get().argb();
		}
		return WHITE;
	}

	public SignChallenge challenge() {
		return challenge;
	}

	@Override
	protected void init() {
		if (openedIn == null) openedIn = minecraft.level;
	}

	@Override
	public boolean isPauseScreen() {
		return false;
	}

	@Override
	public boolean shouldCloseOnEsc() {
		return false; // Esc is handled in keyPressed: it gives up (sends the partial answer)
	}

	// ---------------------------------------------------------------- input

	@Override
	public boolean keyPressed(final KeyEvent event) {
		long now = Util.getMillis();
		if (event.isEscape()) {
			input.escape(now);
			send();
			onClose();
			return true;
		}
		HandSignInput.State before = input.state();
		int correctBefore = input.correct();
		HandSignInput.State after = input.keyDown(event.key(), now);
		if (input.correct() > correctBefore) {
			float pitch = 0.9f + 0.12f * (input.correct() - 1);
			play(SimpleSoundInstance.forUI(SoundEvents.NOTE_BLOCK_CHIME, pitch));
		}
		if (after != before) {
			if (after == HandSignInput.State.COMPLETE) play(SimpleSoundInstance.forUI(SoundEvents.AMETHYST_BLOCK_RESONATE, 1.4f, 0.8f));
			if (after == HandSignInput.State.MISSED) play(SimpleSoundInstance.forUI(SoundEvents.NOTE_BLOCK_BASS, 0.5f));
			send();
		}
		return true; // no key does anything else while the prompt is open
	}

	@Override
	public boolean keyReleased(final KeyEvent event) {
		input.keyUp(event.key());
		return true;
	}

	@Override
	public boolean mouseClicked(final MouseButtonEvent event, final boolean doubleClick) {
		return true; // clicks never cast or attack through the prompt
	}

	@Override
	public void tick() {
		Minecraft mc = minecraft;
		if (mc.player == null || !mc.player.isAlive() || mc.level != openedIn) {
			onClose(); // died, left or changed level: the server already discarded the challenge
			return;
		}
		long now = Util.getMillis();
		if (input.tick(now) == HandSignInput.State.TIMED_OUT && !sent) {
			play(SimpleSoundInstance.forUI(SoundEvents.NOTE_BLOCK_BASS, 0.5f));
			send();
		}
		if (input.state().finished() && now - input.endedAt() >= outroMs()) onClose();
	}

	@Override
	public void removed() {
		send(); // closed by anything else (another screen, a level change): the server fizzles or ignores it
	}

	@Override
	public void onClose() {
		if (minecraft.gui.screen() == this) minecraft.gui.setScreen(null);
	}

	private long outroMs() {
		return switch (input.state()) {
			case COMPLETE -> FLASH_MS;
			case MISSED, TIMED_OUT -> SHAKE_MS;
			default -> 0;
		};
	}

	private void send() {
		if (sent) return;
		sent = true;
		if (ClientPlayNetworking.canSend(SignAnswer.TYPE)) ClientPlayNetworking.send(SignAnswer.of(challenge.id(), input.answer()));
	}

	private void play(SimpleSoundInstance sound) {
		minecraft.getSoundManager().play(sound);
	}

	// ---------------------------------------------------------------- drawing

	@Override
	public void extractBackground(final GuiGraphicsExtractor g, final int mouseX, final int mouseY, final float a) {
		// No blur: the world stays visible behind a light dim that fades in (and out after the flash).
		float k = visibility(Util.getMillis());
		g.fillGradient(0, 0, width, height, alpha(0x30000000, k), alpha(0x70000000, k));
	}

	@Override
	public void extractRenderState(final GuiGraphicsExtractor g, final int mouseX, final int mouseY, final float a) {
		long now = Util.getMillis();
		float k = visibility(now);
		if (k <= 0.01f) return;
		int titleW = font.width(title);
		int lineW = Math.max(font.width(spellName), font.width(hint));
		SignLayout.Layout l = SignLayout.layout(width, height, input.length(), titleW, lineW);
		HandSignInput.State state = input.state();
		long sinceEnd = state.finished() ? now - input.endedAt() : 0;

		// Panel: slides up a few units as it fades in.
		float intro = easeOut(Math.min(1f, (now - openedAt) / (float) INTRO_MS));
		g.pose().pushMatrix();
		g.pose().translate(0, (1 - intro) * 6);
		SignLayout.Rect p = l.panel();
		panel(g, p, k);

		// Spell name (element colour) and the title.
		g.centeredText(font, spellName, p.cx(), l.nameY(), alpha(colour, k));
		g.pose().pushMatrix();
		g.pose().translate(p.cx(), l.titleY());
		g.pose().scale(l.titleScale(), l.titleScale());
		g.centeredText(font, title, 0, 0, alpha(WHITE, k));
		g.pose().popMatrix();

		// The row shakes after a miss or a timeout.
		float shake = 0;
		if (state == HandSignInput.State.MISSED || state == HandSignInput.State.TIMED_OUT) {
			float t = Math.min(1f, sinceEnd / (float) SHAKE_MS);
			shake = (float) Math.sin(sinceEnd / 18.0) * 5f * (1 - t) * (1 - t);
		}
		Dir[] dirs = input.expected();
		for (int i = 0; i < dirs.length; i++) arrow(g, l.arrows().get(i), i, dirs[i], now, shake, k);

		timer(g, l.timer(), now, k);
		g.centeredText(font, hint, p.cx(), l.hintY(), alpha(0xFFB8B8C8, k));
		g.pose().popMatrix();
	}

	/** Overall opacity: fades in on open, fades out at the end of the outro. */
	private float visibility(long now) {
		float in = Math.min(1f, (now - openedAt) / (float) INTRO_MS);
		if (!input.state().finished()) return in;
		long out = outroMs();
		if (out == 0) return in;
		float t = (now - input.endedAt()) / (float) out;
		return in * Math.clamp(1f - Math.max(0f, t - 0.6f) / 0.4f, 0f, 1f);
	}

	private void panel(GuiGraphicsExtractor g, SignLayout.Rect r, float k) {
		int x0 = r.x(), y0 = r.y(), x1 = r.right(), y1 = r.bottom();
		// Rounded corners: the body plus 1-unit insets on the top and bottom rows.
		g.fill(x0 + 1, y0, x1 - 1, y1, alpha(0xD0101018, k));
		g.fill(x0, y0 + 1, x0 + 1, y1 - 1, alpha(0xD0101018, k));
		g.fill(x1 - 1, y0 + 1, x1, y1 - 1, alpha(0xD0101018, k));
		int edge = alpha(mix(colour, 0xFF000000, 0.35f), k * 0.9f);
		g.fill(x0 + 2, y0, x1 - 2, y0 + 1, edge);
		g.fill(x0 + 2, y1 - 1, x1 - 2, y1, edge);
		g.fill(x0, y0 + 2, x0 + 1, y1 - 2, edge);
		g.fill(x1 - 1, y0 + 2, x1, y1 - 2, edge);
		// Element accent along the top.
		g.fillGradient(x0 + 2, y0 + 1, x1 - 2, y0 + 4, alpha(colour, k * 0.45f), alpha(colour, 0));
	}

	private void arrow(GuiGraphicsExtractor g, SignLayout.Rect r, int i, Dir dir, long now, float shake, float k) {
		HandSignInput.State state = input.state();
		int correct = input.correct();
		boolean lit = i < correct;
		boolean missed = i == input.missedAt();
		boolean next = !state.finished() && i == correct;

		// Pop in one after another.
		float appear = Math.clamp((now - openedAt - i * STAGGER_MS) / (float) POP_MS, 0f, 1f);
		if (appear <= 0f) return;
		float scale = 0.55f + 0.45f * easeOutBack(appear);
		int tile, border, glyph;
		if (missed) {
			tile = 0xE0601010;
			border = RED;
			glyph = 0xFFFFD0D0;
		} else if (lit) {
			tile = mix(colour, 0xFF101018, 0.62f);
			border = colour;
			glyph = mix(WHITE, colour, 0.25f);
			float pop = Math.clamp((now - input.typedAt(i)) / (float) POP_MS, 0f, 1f);
			scale *= 1f + 0.22f * (1f - easeOut(pop));
		} else if (next) {
			float pulse = 0.5f + 0.5f * (float) Math.sin(now / 140.0);
			tile = 0xE0222230;
			border = alpha(WHITE, 0.45f + 0.45f * pulse);
			glyph = WHITE;
		} else {
			tile = 0xC0181822;
			border = 0x50FFFFFF;
			glyph = 0x90C8C8D8;
		}
		if (state == HandSignInput.State.COMPLETE) {
			// Flash: white burst that settles back to the element colour, everything swelling slightly.
			float t = Math.clamp((now - input.endedAt()) / (float) FLASH_MS, 0f, 1f);
			float white = 1f - t;
			tile = mix(tile, WHITE, white * 0.7f);
			glyph = mix(glyph, WHITE, white);
			scale *= 1f + 0.12f * easeOut(t);
		}
		float cx = r.x() + r.w() / 2f + shake;
		float cy = r.y() + r.h() / 2f;
		if (next) cy -= (float) Math.sin(now / 160.0) * Math.max(1f, r.h() / 24f); // gentle bob on the arrow to type

		g.pose().pushMatrix();
		g.pose().translate(cx, cy);
		g.pose().scale(scale, scale);
		int half = r.w() / 2;
		if (lit || missed || state == HandSignInput.State.COMPLETE) glow(g, half, missed ? RED : colour, k * appear);
		int a = Math.round(255 * k * appear);
		g.fill(-half + 1, -half, half - 1, half, alphaMul(tile, a));
		g.fill(-half, -half + 1, -half + 1, half - 1, alphaMul(tile, a));
		g.fill(half - 1, -half + 1, half, half - 1, alphaMul(tile, a));
		outline(g, half, alphaMul(border, a));
		glyph(g, dir, half, alphaMul(0xFF000000, a / 2), 1); // drop shadow
		glyph(g, dir, half, alphaMul(glyph, a), 0);
		g.pose().popMatrix();
	}

	private static void outline(GuiGraphicsExtractor g, int half, int argb) {
		g.fill(-half + 2, -half, half - 2, -half + 1, argb);
		g.fill(-half + 2, half - 1, half - 2, half, argb);
		g.fill(-half, -half + 2, -half + 1, half - 2, argb);
		g.fill(half - 1, -half + 2, half, half - 2, argb);
		g.fill(-half + 1, -half + 1, -half + 2, -half + 2, argb);
		g.fill(half - 2, -half + 1, half - 1, -half + 2, argb);
		g.fill(-half + 1, half - 2, -half + 2, half - 1, argb);
		g.fill(half - 2, half - 2, half - 1, half - 1, argb);
	}

	/** A soft glow behind a tile ({@code glow.png} is white; the colour tints it). */
	private static void glow(GuiGraphicsExtractor g, int half, int argb, float k) {
		float size = half * 3.2f;
		g.pose().pushMatrix();
		g.pose().scale(size / 64f, size / 64f);
		g.blit(RenderPipelines.GUI_TEXTURED, GLOW, -32, -32, 0f, 0f, 64, 64, 64, 64, alpha(argb, 0.55f * k));
		g.pose().popMatrix();
	}

	/**
	 * A blocky arrow centred on the origin, pointing {@code dir}, drawn from rectangles so it stays crisp at every GUI scale.
	 * Local coordinates: {@code u} along the arrow (towards the tip), {@code v} across it.
	 */
	private static void glyph(GuiGraphicsExtractor g, Dir dir, int half, int argb, int offset) {
		int s = Math.max(6, Math.round(half * 1.15f)); // arrow length
		int tip = s / 2;
		int headLen = Math.max(3, s / 2);
		int headHalf = Math.max(3, Math.round(s * 0.45f));
		int shaftHalf = Math.max(1, s / 7);
		int tail = -s / 2;
		// Shaft.
		rect(g, dir, tail, tip - headLen + 1, -shaftHalf, shaftHalf, argb, offset);
		// Head: one strip per unit, widening away from the tip.
		for (int k = 0; k < headLen; k++) {
			int hw = Math.max(1, Math.round(headHalf * (k + 1) / (float) headLen));
			rect(g, dir, tip - k - 1, tip - k, -hw, hw, argb, offset);
		}
	}

	/** Fills {@code u0..u1 × v0..v1} (arrow-local) turned to {@code dir}; {@code offset} shifts it right and down (shadow). */
	private static void rect(GuiGraphicsExtractor g, Dir dir, int u0, int u1, int v0, int v1, int argb, int offset) {
		int x0, x1, y0, y1;
		switch (dir) {
			case UP -> { x0 = v0; x1 = v1; y0 = -u1; y1 = -u0; }
			case DOWN -> { x0 = -v1; x1 = -v0; y0 = u0; y1 = u1; }
			case RIGHT -> { x0 = u0; x1 = u1; y0 = v0; y1 = v1; }
			default -> { x0 = -u1; x1 = -u0; y0 = -v1; y1 = -v0; } // LEFT
		}
		g.fill(x0 + offset, y0 + offset, x1 + offset, y1 + offset, argb);
	}

	private void timer(GuiGraphicsExtractor g, SignLayout.Rect r, long now, float k) {
		float left = input.remaining(now);
		g.fill(r.x(), r.y(), r.right(), r.bottom(), alpha(0xA0000000, k));
		int fillW = Math.round(r.w() * left);
		if (fillW <= 0) return;
		int c = left > 0.35f ? colour : mix(colour, RED, (0.35f - left) / 0.35f);
		float blink = left < 0.15f && !input.state().finished() ? 0.55f + 0.45f * (float) Math.sin(now / 60.0) : 1f;
		// Shrinks towards the centre so the bar stays balanced under the row.
		int x0 = r.cx() - fillW / 2;
		g.fill(x0, r.y(), x0 + fillW, r.bottom(), alpha(c, k * blink));
		g.fill(x0, r.y(), x0 + fillW, r.y() + 1, alpha(mix(c, WHITE, 0.5f), k * blink));
	}

	// ---------------------------------------------------------------- colour and easing helpers

	/** {@code argb} with its alpha multiplied by {@code k} (0..1). */
	static int alpha(int argb, float k) {
		int a = Math.round(((argb >>> 24) & 0xFF) * Math.clamp(k, 0f, 1f));
		return (a << 24) | (argb & 0xFFFFFF);
	}

	private static int alphaMul(int argb, int a255) {
		int a = ((argb >>> 24) & 0xFF) * Math.clamp(a255, 0, 255) / 255;
		return (a << 24) | (argb & 0xFFFFFF);
	}

	/** Linear mix of two ARGB colours ({@code t} = 0 → {@code a}, 1 → {@code b}). */
	static int mix(int a, int b, float t) {
		t = Math.clamp(t, 0f, 1f);
		int out = 0;
		for (int shift = 0; shift <= 24; shift += 8) {
			int ca = (a >>> shift) & 0xFF;
			int cb = (b >>> shift) & 0xFF;
			out |= Math.round(ca + (cb - ca) * t) << shift;
		}
		return out;
	}

	private static float easeOut(float t) {
		float u = 1f - t;
		return 1f - u * u * u;
	}

	private static float easeOutBack(float t) {
		float c1 = 1.70158f;
		float c3 = c1 + 1f;
		float u = t - 1f;
		return 1f + c3 * u * u * u + c1 * u * u;
	}
}
