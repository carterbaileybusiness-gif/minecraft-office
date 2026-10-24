package local.carter.office.client.pointer;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import local.carter.office.client.dock.AppSearchScreen;
import local.carter.office.client.dock.Dock;
import local.carter.office.client.dock.DockItem;
import local.carter.office.client.helper.HelperProtocol.PointerButton;
import local.carter.office.client.interact.HologramPicker;
import local.carter.office.client.interact.HologramPicker.Picked;
import local.carter.office.client.interact.HologramPreview;
import local.carter.office.client.platform.Platform;
import local.carter.office.core.HologramSpec;
import local.carter.office.core.Mode;
import local.carter.office.hologram.HologramBlockEntity;
import local.carter.office.hologram.OfficeBlocks;

import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.network.chat.Component;
import net.minecraft.util.Util;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientLifecycleEvents;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;

/**
 * In-world use of your own holograms (spec 5.9, 5.10, Mac): the crosshair is the mouse — Minecraft's own crosshair, no
 * cursor is drawn (Carter). Client thread only. Works on Extend holograms (virtual displays) and on the Mirror (the
 * laptop's main display): there Minecraft's window floats above the other apps and is click-through while you use it
 * ({@link MirrorWindow}), so the helper's events reach the app under it.
 *
 * <ul>
 * <li>Aimed at your own Extend or Mirror hologram with no emitter in the main hand: the crosshair is the pointer.</li>
 * <li>The mini Dock along the bottom of each Extend hologram ({@link Dock}) takes clicks before the screen behind it:
 * an icon opens its app onto that screen, "All apps" opens a search ({@link AppSearchScreen}).</li>
 * <li>Left button: a click there, or, once the crosshair moves while it is held, a drag ({@link PointerGesture}): the
 * helper drives it and sends the physical mouse motion back, which turns your head here ({@link #frame}); a drag
 * carries on across holograms. Blocks are never broken while aimed at a hologram or during a press or drag
 * ({@code MinecraftAttackMixin}).</li>
 * <li>Right button: a right-click there. Wheel: scrolls the app there (Fabric's hotbar-scroll event; the slot stays).</li>
 * <li>A click enters typing mode ({@link TypingMode}, {@link TypingScreen}): the app has the keyboard, Minecraft stands
 * still and says "Typing — Esc to return"; Esc (swallowed by the helper) or clicking back into Minecraft returns.</li>
 * <li>One-window holograms are view-only: a click says so. An emitter in the main hand keeps editing.</li>
 * </ul>
 * While typing or dragging, Minecraft keeps running unfocused (pauseOnLostFocus off in memory, AFK limit kept awake).
 */
public final class PointerClient {
	private static final Logger LOGGER = LoggerFactory.getLogger("office");
	/** Wheel notches → pixels of scrolling (a notch scrolls about three lines). */
	static final double SCROLL_PIXELS_PER_NOTCH = 48;
	public static final String WINDOW_VIEW_ONLY = "One-window screens are view-only here — switch to Extend to use it";

	private static final PointerClient INSTANCE = new PointerClient();

	private @Nullable PointerPort override;
	private boolean listening;
	private final PointerGesture gesture = new PointerGesture(new PointerGesture.Out() {
		@Override
		public void down(final PointerPoint p) {
			PointerPort port = port();
			if (port != null) {
				if (p.onMain()) {
					throughMirror(); // before the helper posts its down at the main display
				}
				port.pointerDown(p);
			}
		}

		@Override
		public void move(final PointerPoint p) {
			PointerPort port = port();
			if (port != null) {
				if (p.onMain() && !mirror.clickThrough(Util.getMillis())) {
					throughMirror(); // a drag from an Extend screen onto the Mirror: drops land in the app under Minecraft
				}
				port.pointerMove(p);
			}
		}

		@Override
		public void up(final PointerPoint p) {
			PointerPort port = port();
			if (port != null) {
				port.pointerUp(p);
			}
		}

		@Override
		public void click(final PointerPoint p, final PointerButton button) {
			clicked(p, button);
		}
	});
	private final TypingMode typing = new TypingMode(new TypingMode.Env() {
		@Override
		public void showHint() {
			Minecraft mc = Minecraft.getInstance();
			TypingScreen s = new TypingScreen();
			screen = s;
			mc.gui.setScreen(s); // releases the mouse and stops movement; not a pause screen
		}

		@Override
		public void hideHint() {
			Minecraft mc = Minecraft.getInstance();
			TypingScreen s = screen;
			screen = null;
			if (s != null && mc.gui.screen() == s) {
				try {
					mc.gui.setScreen(null);
				} catch (IllegalStateException e) {
					LOGGER.debug("Typing hint left to the disconnect flow: {}", e.toString());
				}
			}
		}

		@Override
		public void exitHelper() {
			PointerPort port = port();
			if (port != null) {
				port.exitTyping();
			}
		}

		@Override
		public void backToGame() {
			regrabWhenFocused = true;
		}
	});

	private @Nullable TypingScreen screen;
	private final PauseGuard pause = new PauseGuard();
	private final MirrorWindow mirror = new MirrorWindow();
	/** The layer last applied to Minecraft's window (null: none yet). */
	private MirrorWindow.@Nullable Layer applied;
	private boolean regrabWhenFocused;
	private double scrollX;
	private double scrollY;
	// the Dock icon under the crosshair this frame
	private @Nullable UUID dockHologram;
	private int dockHover = -1;
	private @Nullable Aim aim;

	/** The own hologram under the crosshair, in front of whatever vanilla aims at, and its display pixel (null: none). */
	public record Aim(HologramBlockEntity be, HologramSpec spec, Picked picked, @Nullable PointerPoint point) {}

	private PointerClient() {}

	public static void register() {
		ClientTickEvents.END_CLIENT_TICK.register(INSTANCE::tick);
		ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> client.execute(INSTANCE::abort));
		ClientLifecycleEvents.CLIENT_STOPPING.register(client -> INSTANCE.abort()); // never leave the window click-through
		INSTANCE.listen(Platform.current().pointer());
	}

	/** Tests: use {@code port} instead of the platform's (null: back to the platform's). */
	public static void setPortForTesting(final @Nullable PointerPort port) {
		INSTANCE.abort();
		INSTANCE.override = port;
		INSTANCE.listening = false;
		INSTANCE.listen(port());
	}

	private void listen(final @Nullable PointerPort port) {
		if (port == null || listening) {
			return;
		}
		listening = true;
		port.onTypingExit(reason -> {
			boolean was = typing.active();
			typing.helperExited();
			mirror.interactionEnded();
			if (was) {
				LOGGER.info("Typing: back to the game ({})", reason);
				if (HELPER_GONE.equals(reason)) {
					bringBack(); // the helper died while typing: nobody re-activates Minecraft for us
				}
			}
		});
		port.onDragEnded(reason -> {
			boolean was = gesture.state() == PointerGesture.State.DRAGGING;
			dragEnded();
			mirror.interactionEnded();
			if (was && "cancelled".equals(reason)) {
				bringBack(); // a drag cancelled (helper gone, or the mod's POINTER_UP): make sure Minecraft has the mouse
			}
		});
		port.onAppProblem(message -> {
			LocalPlayer player = Minecraft.getInstance().player;
			if (player != null) {
				player.sendOverlayMessage(Component.literal(message));
			}
		});
	}

	/** A click or drag on the Mirror is about to reach the helper: Minecraft's window lets it through, now. */
	private void throughMirror() {
		mirror.mirrorPressed();
		applyWindow(Util.getMillis());
	}

	/** Minecraft's window layer for now (floating / click-through), applied when it changed. */
	private void applyWindow(final long now) {
		PointerPort port = port();
		if (port == null) {
			return;
		}
		Minecraft mc = Minecraft.getInstance();
		MirrorWindow.Layer layer = mirror.layer(now, mc.isWindowActive(), gesture.busy() || typing.active());
		if (!layer.equals(applied)) {
			port.windowLayer(layer.floating(), layer.clickThrough());
			applied = layer;
		}
	}

	/** TYPING_EXIT reason the mod reports for itself when the helper disconnects mid-typing. */
	public static final String HELPER_GONE = "helper";

	private void bringBack() {
		PointerPort port = port();
		if (port != null && !Minecraft.getInstance().isWindowActive()) {
			port.activateMinecraft();
		}
		regrabWhenFocused = true;
	}

	static @Nullable PointerPort port() {
		return INSTANCE.override != null ? INSTANCE.override : Platform.current().pointer();
	}

	/** The pointer port in use (the platform's, or a test's), or null. */
	public static @Nullable PointerPort currentPort() {
		return port();
	}

	/** In-world use is available on this computer. */
	public static boolean available() {
		return port() != null;
	}

	public static PointerGesture gesture() {
		return INSTANCE.gesture;
	}

	public static TypingMode typing() {
		return INSTANCE.typing;
	}

	// ---------------------------------------------------------------- per frame (MinecraftFrameMixin)

	/** Once per frame: head turn from a helper-driven drag, then where the crosshair points. */
	public static void frame() {
		INSTANCE.onFrame();
	}

	private void onFrame() {
		Minecraft mc = Minecraft.getInstance();
		PointerPort port = port();
		if (port == null || mc.player == null || mc.level == null) {
			dockHologram = null;
			aim = null;
			return;
		}
		if (gesture.state() == PointerGesture.State.DRAGGING) {
			int[] d = port.drainDragDelta();
			if (d[0] != 0 || d[1] != 0) {
				turn(mc, d[0], d[1]); // Minecraft has no mouse now: the helper's physical motion turns the head
			}
		}
		if (typing.active() || gesture.busy()) {
			mc.getFramerateLimitTracker().onInputReceived(); // input goes to the computer: no AFK frame-rate drop
		}
		float partial = mc.getDeltaTracker().getGameTimeDeltaPartialTick(true);
		Aim a = typing.active() ? null : computeAim(mc, partial);
		aim = a;
		long now = Util.getMillis();
		gesture.aim(a == null ? null : a.point(), now);
		int hover = a == null || gesture.busy() ? -1 : dockIndex(a, port);
		dockHologram = hover >= 0 ? a.be().hologramId() : null;
		dockHover = hover;
		applyWindow(now);
	}

	/** The Dock icon of aim {@code a}'s hologram under the crosshair, or -1. */
	private static int dockIndex(final Aim a, final PointerPort port) {
		return Dock.hit(a.spec(), true, Dock.items(port), a.picked().hit().u(), a.picked().hit().v());
	}

	/** The Dock icon under the crosshair on hologram {@code id} this frame, or -1 (the renderer highlights it). */
	public static int dockHover(final UUID id) {
		PointerClient c = INSTANCE;
		return id.equals(c.dockHologram) ? c.dockHover : -1;
	}

	/** A click on the Dock: the app opens on that screen, or "All apps" opens the search. */
	private static void dockClicked(final Minecraft mc, final Aim a, final int index, final PointerPort port) {
		List<DockItem> items = Dock.items(port);
		LocalPlayer player = mc.player;
		if (items == null || index < 0 || index >= items.size() || player == null) {
			return;
		}
		DockItem item = items.get(index);
		int slot = a.spec().displaySlot();
		if (item.allApps()) {
			mc.gui.setScreen(new AppSearchScreen(port, slot));
			return;
		}
		String problem = port.problem();
		if (problem != null) {
			player.sendOverlayMessage(Component.literal(problem));
			return;
		}
		port.openApp(slot, item);
		player.sendOverlayMessage(Component.literal("Opening " + item.name() + " on this screen…"));
		LOGGER.info("Dock: {} onto display {}", item.bundle() != null ? item.bundle() : item.url(), slot);
	}

	/** Vanilla's mouse-look with the helper's deltas (points, like SDL's relative motion). */
	private static void turn(final Minecraft mc, final int dx, final int dy) {
		double ss = mc.options.sensitivity().get() * 0.6 + 0.2;
		double sens = ss * ss * ss * 8.0;
		double xo = dx * sens;
		double yo = dy * sens;
		mc.player.turn(mc.options.invertMouseX().get() ? -xo : xo, mc.options.invertMouseY().get() ? -yo : yo);
	}

	private static @Nullable Aim computeAim(final Minecraft mc, final float partial) {
		LocalPlayer player = mc.player;
		if (player == null || mc.gui.screen() != null || holdingEmitter(player)) {
			return null;
		}
		Optional<Picked> picked = inFront(mc, partial);
		if (picked.isEmpty() || !picked.get().be().isOwner(player.getUUID())) {
			return null;
		}
		HologramBlockEntity be = picked.get().be();
		HologramSpec spec = HologramPreview.effective(be);
		if (spec == null) {
			return null;
		}
		return new Aim(be, spec, picked.get(), PointerPoint.of(spec, picked.get().hit()));
	}

	/** The hologram under the crosshair when it is in front of what vanilla aims at (any owner). */
	static Optional<Picked> inFront(final Minecraft mc, final float partial) {
		LocalPlayer player = mc.player;
		if (player == null || mc.level == null) {
			return Optional.empty();
		}
		Vec3 eye = player.getEyePosition(partial);
		double vanilla = mc.hitResult != null && mc.hitResult.getType() != HitResult.Type.MISS
				? mc.hitResult.getLocation().distanceTo(eye) : Double.POSITIVE_INFINITY;
		return HologramPicker.pick(partial).filter(p -> p.hit().distance() <= vanilla + 1e-3);
	}

	private static boolean holdingEmitter(final LocalPlayer player) {
		return player.getItemInHand(InteractionHand.MAIN_HAND).getItem() == OfficeBlocks.HOLOGRAM_EMITTER_ITEM;
	}

	// ---------------------------------------------------------------- input hooks

	/**
	 * The attack key was pressed ({@code Minecraft.startAttack}). True = it was for a hologram: vanilla must not attack.
	 * Any hologram in front swallows it (never break the block behind a screen); your own Extend hologram starts a press.
	 */
	public static boolean onAttack(final Minecraft mc) {
		LocalPlayer player = mc.player;
		if (player == null || mc.level == null) {
			return false;
		}
		if (INSTANCE.gesture.busy() || INSTANCE.typing.active()) {
			return true;
		}
		float partial = mc.getDeltaTracker().getGameTimeDeltaPartialTick(true);
		Optional<Picked> picked = inFront(mc, partial);
		if (picked.isEmpty()) {
			return false;
		}
		PointerPort port = port();
		if (port == null || holdingEmitter(player) || !picked.get().be().isOwner(player.getUUID())) {
			return true;
		}
		Aim a = computeAim(mc, partial);
		if (a == null) {
			return true;
		}
		int dock = dockIndex(a, port);
		if (dock >= 0) {
			dockClicked(mc, a, dock, port); // the Dock is in front of the screen behind it
			return true;
		}
		PointerPoint p = usable(player, a, port);
		if (p != null) {
			INSTANCE.gesture.press(p, Util.getMillis());
		}
		return true;
	}

	/** {@code Minecraft.continueAttack}: never mine while pressing or dragging on a hologram, or aiming at one. */
	public static boolean blocksAttack(final Minecraft mc) {
		if (INSTANCE.gesture.busy() || INSTANCE.typing.active()) {
			return true;
		}
		return mc.player != null && inFront(mc, mc.getDeltaTracker().getGameTimeDeltaPartialTick(true)).isPresent();
	}

	/**
	 * Right-click on your own hologram without an emitter in the main hand, where in-world use is available. Always
	 * consumed: a right-click there, or a note why not.
	 */
	public static boolean onUse(final Minecraft mc, final HologramBlockEntity be) {
		LocalPlayer player = mc.player;
		PointerPort port = port();
		if (player == null || port == null) {
			return false;
		}
		if (INSTANCE.gesture.busy() || INSTANCE.typing.active()) {
			return true;
		}
		Aim a = computeAim(mc, mc.getDeltaTracker().getGameTimeDeltaPartialTick(true));
		if (a == null || a.be() != be || dockIndex(a, port) >= 0) {
			return true; // a right-click on the Dock does nothing
		}
		PointerPoint p = usable(player, a, port);
		if (p != null) {
			INSTANCE.gesture.rightClick(p);
		}
		return true;
	}

	/** The pixel to use, or null after telling the player (action bar) why this hologram can't be used now. */
	private static @Nullable PointerPoint usable(final LocalPlayer player, final Aim a, final PointerPort port) {
		Mode mode = a.spec().mode();
		if (mode == Mode.WINDOW) {
			player.sendOverlayMessage(Component.literal(WINDOW_VIEW_ONLY));
			return null;
		}
		String problem = port.problem();
		if (problem != null) {
			player.sendOverlayMessage(Component.literal(problem));
			return null;
		}
		return a.point(); // null just past a corner (the owner's grab zone): nothing to click
	}

	/**
	 * Mouse wheel in the world, in notches (Fabric's {@code ClientHotbarScrollEvents.ALLOW}). True = it scrolled the app
	 * under the crosshair, so the hotbar slot must stay.
	 */
	public static boolean onScroll(final double xoffset, final double yoffset) {
		PointerClient c = INSTANCE;
		PointerPort port = port();
		Aim a = c.aim;
		if (port == null || c.typing.active() || a == null || a.point() == null || a.spec().mode() == Mode.WINDOW) {
			return false;
		}
		if (port.problem() != null) {
			return false;
		}
		if (dockIndex(a, port) >= 0) {
			return true; // over the Dock: nothing scrolls, the hotbar stays
		}
		c.scrollX += xoffset * SCROLL_PIXELS_PER_NOTCH;
		c.scrollY += yoffset * SCROLL_PIXELS_PER_NOTCH;
		int dx = (int) c.scrollX;
		int dy = (int) c.scrollY;
		c.scrollX -= dx;
		c.scrollY -= dy;
		if (dx != 0 || dy != 0) {
			if (a.point().onMain()) {
				c.mirror.mirrorScrolled(Util.getMillis()); // a moment of click-through so the scroll reaches the app under Minecraft
				c.applyWindow(Util.getMillis());
			}
			port.scroll(a.point(), dx, dy);
		}
		return true;
	}

	private void clicked(final PointerPoint p, final PointerButton button) {
		PointerPort port = port();
		if (port == null) {
			return;
		}
		// The hint screen first: it releases the mouse (Minecraft centres the cursor), then the helper's real click puts
		// the cursor on the display and brings the app forward with the keyboard.
		typing.enter(Util.getMillis());
		if (p.onMain()) {
			throughMirror(); // typing in an app under Minecraft: click-through until Esc / back in Minecraft
		}
		port.click(p, button);
		LOGGER.info("Typing: {} click on display {} at {},{}", button.wire, p.slot(), p.x(), p.y());
	}

	private void dragEnded() {
		if (gesture.state() == PointerGesture.State.DRAGGING) {
			gesture.dragEnded();
			Minecraft mc = Minecraft.getInstance();
			mc.options.keyAttack.setDown(false); // the helper took the physical release; Minecraft never saw it
			regrabWhenFocused = true;
		}
	}

	// ---------------------------------------------------------------- tick

	private void tick(final Minecraft mc) {
		if (port() == null) {
			return;
		}
		long now = Util.getMillis();
		if (mc.level == null || mc.player == null) {
			abort();
			return;
		}
		mirror.enterWorld();
		boolean active = mc.isWindowActive();
		typing.focus(active, now); // focus left → typing; came back (the player clicked Minecraft) → back to the game
		typing.tick(now);
		if (typing.active() && typing.state() == TypingMode.State.TYPING && screen != null && mc.gui.screen() != screen) {
			typing.leave(); // another screen replaced the hint (death, a disconnect screen)
		}
		if (gesture.busy() && !mc.options.keyAttack.isDown()) {
			gesture.release(active, now);
		}
		gesture.tick(now);
		if (!gesture.busy() && !typing.active()) {
			mirror.interactionEnded(); // every other way a drag or typing ended (timeouts, the hint replaced, focus back)
		}
		applyWindow(now);
		pause.hold(mc, typing.active() || gesture.state() == PointerGesture.State.DRAGGING);
		if (regrabWhenFocused && active && !typing.active() && !gesture.busy()) {
			regrabWhenFocused = false;
			if (mc.gui.screen() == null) {
				// Minecraft lost focus with its mouse grabbed: grab again so mouse-look works at once
				mc.mouseHandler.releaseMouse();
				mc.mouseHandler.grabMouse();
			}
		}
	}

	/** World left / port changed / game closing: release a drag, leave typing, restore pause-on-focus-loss and the window. */
	private void abort() {
		gesture.cancel();
		typing.leave();
		pause.hold(Minecraft.getInstance(), false);
		mirror.leaveWorld();
		PointerPort port = port();
		if (port != null && !MirrorWindow.Layer.NORMAL.equals(applied)) {
			port.windowLayer(false, false); // never left floating or click-through outside a world
		}
		applied = MirrorWindow.Layer.NORMAL;
		dockHologram = null;
		aim = null;
		regrabWhenFocused = false;
	}

	// ---------------------------------------------------------------- options save (OptionsSaveMixin)

	public static void beforeOptionsSave() {
		INSTANCE.pause.beforeSave(Minecraft.getInstance());
	}

	public static void afterOptionsSave() {
		INSTANCE.pause.afterSave(Minecraft.getInstance());
	}

	/** The hint screen this client opened (null: none). */
	static @Nullable TypingScreen hintScreen() {
		return INSTANCE.screen;
	}
}
