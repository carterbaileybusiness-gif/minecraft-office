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
import local.carter.office.client.dock.DockLayout;
import local.carter.office.client.render.HologramLook;
import local.carter.office.client.render.ScreenTexture;
import local.carter.office.client.render.ScreenTextureRegistry;
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
 * Your own holograms in the world (spec 5.10, Mac). Client thread only.
 *
 * <ul>
 * <li><b>Work mode</b> (round 2, Carter: "like hooking up a laptop to monitors"): a click (left or right) on your own
 * Mirror or Extend hologram with no emitter in the main hand puts the real Mac cursor at that point and hands it the
 * real mouse, 1:1 with macOS's own acceleration, until Esc. The camera is frozen and Minecraft stands still (a hint
 * screen is open; Minecraft isn't the active app, so keys go to the app). Press, hold, drag and release are the real
 * button's; moving off the Mirror's edge goes onto the Office display next to it (the helper arranges them), so a
 * window drags from the Mirror onto an Extend screen like onto a real monitor. The hologram that shows the cursor's
 * display draws the cursor where the helper reports it ({@link #cursorOn}); the system cursor itself is hidden.
 * Minecraft's window floats above the apps and is click-through while working ({@link MirrorWindow}); Esc (swallowed by
 * the helper) or ⌘-Tab back to Minecraft ends it: focus, mouse grab, mouse-look and a normal window come back.</li>
 * <li>Outside work mode the crosshair is aimed with: the mini Dock along the bottom of each Extend hologram ({@link
 * Dock}) opens apps onto that screen, "All apps" opens a search ({@link AppSearchScreen}); the wheel scrolls the app
 * under the crosshair (the hotbar slot stays). Blocks behind a hologram are never broken ({@code MinecraftAttackMixin}).</li>
 * <li>One-window holograms are view-only: a click says so. An emitter in the main hand keeps editing.</li>
 * </ul>
 * While working, Minecraft keeps running unfocused (pauseOnLostFocus off in memory, AFK limit kept awake).
 */
public final class PointerClient {
	private static final Logger LOGGER = LoggerFactory.getLogger("office");
	/** Wheel notches → pixels of scrolling (a notch scrolls about three lines). */
	static final double SCROLL_PIXELS_PER_NOTCH = 48;
	public static final String WINDOW_VIEW_ONLY = "One-window screens are view-only here — switch to Extend to use it";

	private static final PointerClient INSTANCE = new PointerClient();

	private @Nullable PointerPort override;
	private boolean listening;
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
	/** The layer last applied to Minecraft's window (null: none yet, or the last try failed), and the focus it was for. */
	private MirrorWindow.@Nullable Layer applied;
	private boolean appliedFocus;
	/** Secure input (a password field) is on while working: Esc can't be seen, ⌘-Tab still returns. */
	private boolean secureInput;
	/** The real cursor while working (helper's CURSOR) as of this frame's start, or null. */
	private int @Nullable [] cursor;
	/** The monitor of a slot-0 {@link #cursor} when it isn't the main display, or null. */
	private @Nullable String cursorDisplay;
	/** Measurements: draw the cursor as of the frame's start (round 2) instead of the newest at draw time. */
	private static volatile boolean cursorAtFrameStart;
	/** Walk while dragging: the movement keys the helper holds for Minecraft now (names), and those set down by us. */
	private java.util.Set<String> walking = java.util.Set.of();
	/** Frames so far, and the cursor as drawn this frame (taken at the frame's first draw). */
	private long frameNo;
	private long cursorSnapFrame = -1;
	private int @Nullable [] cursorSnap;
	private @Nullable String cursorSnapDisplay;
	private final java.util.Set<String> walkApplied = new java.util.HashSet<>();
	/** When the view last followed the cursor (System.nanoTime), for the smoothing; 0 = not following. */
	private long followNanos;
	/** Work mode as Dynamic FPS was last told about it. */
	private boolean dynamicFpsWorking;
	private long workSince; // Util.getMillis() when work mode began (Dynamic FPS hook check)
	/** Tests: Minecraft's window focus as the mod sees it (null = the real one). */
	private @Nullable Boolean focusOverride;
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
		ClientTickEvents.START_CLIENT_TICK.register(INSTANCE::applyWalk); // before the player reads its movement keys
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
			workEnded();
			if (was) {
				LOGGER.info("Work mode: back to the game ({})", reason);
				if (HELPER_GONE.equals(reason)) {
					bringBack(); // the helper died while working: nobody re-activates Minecraft for us
				}
			}
		});
		port.onWalk(keys -> walking = typing.active() ? keys : java.util.Set.of());
		port.onSecureInput(on -> {
			secureInput = on && typing.active();
			if (secureInput) {
				LocalPlayer player = Minecraft.getInstance().player;
				if (player != null) {
					player.sendOverlayMessage(Component.literal(PASSWORD_HINT));
				}
			}
		});
		port.onAppProblem(message -> {
			LocalPlayer player = Minecraft.getInstance().player;
			if (player != null) {
				player.sendOverlayMessage(Component.literal(message));
			}
		});
	}

	/** Work mode is over (any way): Minecraft's window back to normal on the next frame, no cursor drawn. */
	private void workEnded() {
		mirror.workEnded();
		secureInput = false;
		cursor = null;
		walking = java.util.Set.of();
		applyWalk(Minecraft.getInstance()); // never leave the player walking
	}

	/**
	 * Walk while dragging (round 3, Carter's ruling): while the real mouse button is held in work mode, the helper takes
	 * W A S D, Space and Shift from the Mac app and reports them (WALK); they press Minecraft's own movement keys here,
	 * each tick before the player reads them, so the player walks (the view keeps following the cursor). Keys we pressed
	 * are released as soon as they're no longer reported, and when work mode ends. Keys the player pressed in Minecraft
	 * itself are never touched.
	 */
	private void applyWalk(final Minecraft mc) {
		java.util.Set<String> want = typing.active() ? walking : java.util.Set.of();
		if (want.isEmpty() && walkApplied.isEmpty()) {
			return;
		}
		net.minecraft.client.Options o = mc.options;
		walkKey(o.keyUp, "forward", want);
		walkKey(o.keyDown, "back", want);
		walkKey(o.keyLeft, "left", want);
		walkKey(o.keyRight, "right", want);
		walkKey(o.keyJump, "jump", want);
		walkKey(o.keyShift, "sneak", want);
	}

	private void walkKey(final net.minecraft.client.KeyMapping key, final String name, final java.util.Set<String> want) {
		// the down state written directly: KeyMapping.setDown on a toggle key (Sneak: Toggle) flips it on every call
		local.carter.office.mixin.client.KeyMappingAccessor raw = (local.carter.office.mixin.client.KeyMappingAccessor) key;
		if (want.contains(name)) {
			if (!walkApplied.contains(name)) {
				if (key.isDown()) {
					return; // already down by the player's own doing: theirs, we never release it
				}
				walkApplied.add(name);
			}
			raw.office$setDownRaw(true); // every tick: vanilla may release all keys on a focus or screen change
		} else if (walkApplied.remove(name)) {
			raw.office$setDownRaw(false);
		}
	}

	/** Movement keys pressed for the helper now (tests). */
	public static java.util.Set<String> walkingForTesting() {
		return java.util.Set.copyOf(INSTANCE.walkApplied);
	}

	/**
	 * Minecraft's window layer for now (floating / click-through), applied when it changed or Minecraft's focus changed
	 * (macOS / SDL may reorder or reset the window then), and tried again next frame when applying failed.
	 */
	private void applyWindow(final long now) {
		PointerPort port = port();
		if (port == null) {
			return;
		}
		Minecraft mc = Minecraft.getInstance();
		boolean focused = focused(mc);
		MirrorWindow.Layer layer = mirror.layer(now);
		if (!layer.equals(applied) || focused != appliedFocus) {
			applied = port.windowLayerApplied(layer.floating(), layer.clickThrough()) ? layer : null;
			appliedFocus = focused;
		}
	}

	/** The work-mode hint; a password field (secure input) hides Esc, so then it says ⌘-Tab. */
	public static String typingHint() {
		return INSTANCE.secureInput ? PASSWORD_HINT : WORK_HINT;
	}

	public static final String WORK_HINT = "Working on your Mac — Esc (or ⌘-Tab) to return";
	public static final String PASSWORD_HINT = "Password field: press ⌘-Tab to return";

	/** In work mode right now. */
	public static boolean working() {
		return INSTANCE.typing.active();
	}

	private static volatile boolean dynamicFpsAwakeOff;

	/** Dynamic FPS should treat the game as focused (DynamicFpsFocusMixin): while working, unless a test turned it off. */
	public static boolean keepsDynamicFpsAwake() {
		return working() && !dynamicFpsAwakeOff;
	}

	/** Measurements: switch the Dynamic FPS integration off (true) to see the throttle it prevents, or on again. */
	public static void setDynamicFpsAwakeOffForTesting(final boolean off) {
		dynamicFpsAwakeOff = off;
		DynamicFps.recheck();
	}

	/** Working and the real cursor is on the screen this hologram shows (that one captures at 60 fps). */
	public static boolean cursorOnScreen(final HologramSpec spec) {
		return cursorOn(spec) != null;
	}

	/** Tests: pretend Minecraft's window has (true) or lacks (false) the focus; null = the real focus again. */
	public static void setFocusForTesting(final @Nullable Boolean focused) {
		INSTANCE.focusOverride = focused;
	}

	private boolean focused(final Minecraft mc) {
		return focusOverride != null ? focusOverride : mc.isWindowActive();
	}

	/**
	 * Where to draw the real cursor on a hologram drawn with {@code spec} this frame: {u, v} of its picture, or null
	 * (not working, or the cursor is on another screen). Only the owner's client calls this.
	 */
	public static double @Nullable [] cursorOn(final HologramSpec spec) {
		PointerClient c = INSTANCE;
		if (!c.typing.active()) {
			return null;
		}
		// the newest report at draw time (round 3: not the one from the frame's start), unsmoothed; one snapshot per frame,
		// taken by its first draw, so every hologram of the frame shows the same position
		if (c.cursorSnapFrame != c.frameNo) {
			PointerPort port = cursorAtFrameStart ? null : port();
			PointerPort.CursorReport r = port != null ? port.workCursorReport() : null; // one report: no tearing
			c.cursorSnap = r != null ? r.at() : c.cursor;
			c.cursorSnapDisplay = r != null ? r.display() : c.cursorDisplay;
			c.cursorSnapFrame = c.frameNo;
		}
		int[] now = c.cursorSnap;
		double[] at = WorkCursor.on(spec, now, c.cursorSnapDisplay, mirrorShown(spec));
		if (at != null && now != null) {
			local.carter.office.client.render.FrameStats.cursorDrawn(now[1], now[2]);
		}
		return at;
	}

	/** The monitor a Mirror spec shows right now (null = the main display, or not a Mirror). */
	private static @Nullable String mirrorShown(final HologramSpec spec) {
		PointerPort port = port();
		return spec.mode() == local.carter.office.core.Mode.MIRROR && port != null ? port.mirrorShown(spec.mirrorDisplay()) : null;
	}

	/** Measurements: true = draw the cursor as of the frame's start (the round-2 way), false = the newest at draw time. */
	public static void setCursorAtFrameStartForTesting(final boolean atFrameStart) {
		cursorAtFrameStart = atFrameStart;
	}

	/** TYPING_EXIT reason the mod reports for itself when the helper disconnects mid-typing. */
	public static final String HELPER_GONE = "helper";

	private void bringBack() {
		PointerPort port = port();
		if (port != null && !focused(Minecraft.getInstance())) {
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

	public static TypingMode typing() {
		return INSTANCE.typing;
	}

	// ---------------------------------------------------------------- per frame (MinecraftFrameMixin)

	/** Once per frame: where the crosshair points, the real cursor while working, Minecraft's window layer. */
	public static void frame() {
		INSTANCE.onFrame();
	}

	private void onFrame() {
		frameNo++;
		Minecraft mc = Minecraft.getInstance();
		PointerPort port = port();
		if (port == null || mc.player == null || mc.level == null) {
			dockHologram = null;
			aim = null;
			return;
		}
		if (typing.active()) {
			mc.getFramerateLimitTracker().onInputReceived(); // input goes to the computer: no AFK frame-rate drop
			PointerPort.CursorReport r = port.workCursorReport(); // position and monitor from the same report
			cursor = r.at();
			cursorDisplay = r.display();
			followCursor(mc);
		} else {
			followNanos = 0;
		}
		float partial = mc.getDeltaTracker().getGameTimeDeltaPartialTick(true);
		Aim a = typing.active() ? null : computeAim(mc, partial);
		aim = a;
		long now = Util.getMillis();
		int hover = a == null ? -1 : dockIndex(a, port);
		dockHologram = hover >= 0 ? a.be().hologramId() : null;
		dockHover = hover;
		applyWindow(now);
	}

	/**
	 * Work mode: the view follows the real cursor (Carter's ruling, round 2 fix) — the camera turns so the crosshair sits
	 * on the cursor's point on the own hologram that shows its screen (the nearest, if several do), smoothed by {@link
	 * ViewFollow}. On a real monitor (slot -1) or with no report yet, the view holds still. The body doesn't move.
	 */
	private void followCursor(final Minecraft mc) {
		LocalPlayer player = mc.player;
		int[] c = cursor;
		long now = System.nanoTime();
		double dtMs = followNanos == 0 ? 16 : Math.min(100, (now - followNanos) / 1e6);
		followNanos = now;
		if (player == null || c == null || c[0] < 0) {
			return;
		}
		Vec3 eye = player.getEyePosition(1f);
		local.carter.office.core.Vec3d eyeD = new local.carter.office.core.Vec3d(eye.x, eye.y, eye.z);
		local.carter.office.core.Vec3d target = null;
		double best = Double.MAX_VALUE;
		for (HologramBlockEntity be : local.carter.office.client.interact.HologramTracker.loaded()) {
			HologramSpec spec = HologramPreview.effective(be);
			if (spec == null || !be.isOwner(player.getUUID())) {
				continue;
			}
			double[] uv = WorkCursor.on(spec, c, cursorDisplay, mirrorShown(spec));
			if (uv == null) {
				continue;
			}
			net.minecraft.core.BlockPos pos = be.getBlockPos();
			local.carter.office.core.HologramGeometry.Frame f =
					local.carter.office.client.render.HologramPlacement.frame(pos.getX(), pos.getY(), pos.getZ(), spec);
			local.carter.office.core.Vec3d point = f.center().add(f.right().scale((uv[0] - 0.5) * f.w())).add(f.up().scale((0.5 - uv[1]) * f.h()));
			double d = point.sub(eyeD).length();
			if (d < best) {
				best = d;
				target = point;
			}
		}
		if (target == null) {
			return;
		}
		float[] aim = ViewFollow.aim(eyeD, target);
		float[] next = ViewFollow.step(player.getYRot(), player.getXRot(), aim[0], aim[1], dtMs);
		player.setYRot(next[0]);
		player.setXRot(Math.max(-90f, Math.min(90f, next[1])));
		player.yRotO = player.getYRot(); // our own smoothing: no extra interpolation lag
		player.xRotO = player.getXRot();
	}

	/** Where the view is aimed while following: tests compare with {@link ViewFollow#aim}. */
	public static float[] look() {
		LocalPlayer player = Minecraft.getInstance().player;
		return player == null ? new float[] {0, 0} : new float[] {player.getYRot(), player.getXRot()};
	}

	/** The Dock icon of aim {@code a}'s hologram under the crosshair, or -1 (only on a live screen: the Dock is drawn there). */
	private static int dockIndex(final Aim a, final PointerPort port) {
		return Dock.hit(a.spec(), true, live(a), Dock.items(port), a.picked().hit().u(), a.picked().hit().v());
	}

	/** The hologram shows the owner's live screen (what the renderer draws the Dock on). */
	private static boolean live(final Aim a) {
		HologramBlockEntity be = a.be();
		boolean frames = ScreenTextureRegistry.get(be.hologramId()).map(ScreenTexture::hasFrame).orElse(false);
		LocalPlayer player = Minecraft.getInstance().player;
		return player != null && HologramLook.forViewer(a.spec(), player.getUUID(), be.ownerOnline(), frames,
				HologramLook.ownSource(be.hologramId())) == HologramLook.State.LIVE;
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
		DockLayout layout = Dock.layout(a.spec(), items.size());
		int bottom = (int) Math.ceil((1 - layout.bar().v0()) * a.spec().shape().pxH()); // the Dock's band, display pixels
		if (item.allApps()) {
			mc.gui.setScreen(new AppSearchScreen(port, slot, bottom));
			return;
		}
		String problem = port.problem();
		if (problem != null) {
			player.sendOverlayMessage(Component.literal(problem));
			return;
		}
		port.openApp(slot, item, bottom);
		player.sendOverlayMessage(Component.literal("Opening " + item.name() + " on this screen…"));
		LOGGER.info("Dock: {} onto display {}", item.bundle() != null ? item.bundle() : item.url(), slot);
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
	public static Optional<Picked> inFront(final Minecraft mc, final float partial) {
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
	 * Any hologram in front swallows it (never break the block behind a screen); on your own one it is the Dock's, or it
	 * starts work mode with a left press there.
	 */
	public static boolean onAttack(final Minecraft mc) {
		LocalPlayer player = mc.player;
		if (player == null || mc.level == null) {
			return false;
		}
		if (INSTANCE.typing.active()) {
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
			INSTANCE.work(p, PointerButton.LEFT);
		}
		return true;
	}

	/** {@code Minecraft.continueAttack}: never mine while working, or aiming at a hologram. */
	public static boolean blocksAttack(final Minecraft mc) {
		if (INSTANCE.typing.active()) {
			return true;
		}
		return mc.player != null && inFront(mc, mc.getDeltaTracker().getGameTimeDeltaPartialTick(true)).isPresent();
	}

	/**
	 * Right-click on your own hologram without an emitter in the main hand, where in-world use is available. Always
	 * consumed: work mode with a right-click there, or a note why not.
	 */
	public static boolean onUse(final Minecraft mc, final HologramBlockEntity be) {
		LocalPlayer player = mc.player;
		PointerPort port = port();
		if (player == null || port == null) {
			return false;
		}
		if (INSTANCE.typing.active()) {
			return true;
		}
		Aim a = computeAim(mc, mc.getDeltaTracker().getGameTimeDeltaPartialTick(true));
		if (a == null || a.be() != be || dockIndex(a, port) >= 0) {
			return true; // a right-click on the Dock does nothing
		}
		PointerPoint p = usable(player, a, port);
		if (p != null) {
			INSTANCE.work(p, PointerButton.RIGHT);
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
		if (problem == null && mode == Mode.MIRROR) {
			problem = port.mirrorProblem(); // in macOS's own full-screen Space the other apps aren't there
		}
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
		if (port.problem() != null || (a.point().onMain() && port.mirrorProblem() != null)) {
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

	/** Work mode at {@code p}: the press there is the first thing the real mouse does on the Mac. */
	private void work(final PointerPoint p, final PointerButton button) {
		PointerPort port = port();
		if (port == null) {
			return;
		}
		// The hint screen first: it releases the mouse (Minecraft centres the cursor and stops turning the camera), then
		// Minecraft's window lets clicks through, then the helper puts the real cursor at the point and presses there.
		typing.enter(Util.getMillis());
		dynamicFpsWorking = true;
		workSince = Util.getMillis();
		DynamicFps.recheck(); // Dynamic FPS: "focused" from now on, so the game keeps rendering at its normal rate
		secureInput = false;
		cursor = null;
		mirror.workBegan();
		applyWindow(Util.getMillis());
		port.workBegin(p, button);
		Minecraft.getInstance().options.keyAttack.setDown(false); // the real button belongs to the Mac now
		LOGGER.info("Work mode: {} at display {} {},{}", button.wire, p.slot(), p.x(), p.y());
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
		if (typing.active() != dynamicFpsWorking) {
			dynamicFpsWorking = typing.active();
			DynamicFps.recheck(); // full frame rate while working, its own rules again after
		}
		if (typing.active() && DynamicFps.checkHook(now - workSince)) {
			mc.gui.hud.getChat().addClientSystemMessage(Component.literal("[Office] ").withStyle(net.minecraft.ChatFormatting.DARK_AQUA)
					.append(Component.literal(DynamicFps.BROKEN_CHAT).withStyle(net.minecraft.ChatFormatting.YELLOW)));
		}
		boolean active = focused(mc);
		typing.focus(active, now); // focus left → typing; came back (the player clicked Minecraft) → back to the game
		typing.tick(now);
		if (typing.active() && typing.state() == TypingMode.State.TYPING && screen != null && mc.gui.screen() != screen) {
			typing.leave(); // another screen replaced the hint (death, a disconnect screen)
			bringBack(); // the helper ends work mode without re-activating Minecraft: do it ourselves
		}
		if (!typing.active()) {
			workEnded(); // every way work mode ended (Esc, focus back, timeouts, the hint replaced, the helper gone)
		}
		applyWindow(now);
		pause.hold(mc, typing.active());
		if (regrabWhenFocused && active && !typing.active()) {
			regrabWhenFocused = false;
			if (mc.gui.screen() == null) {
				// Minecraft lost focus with its mouse grabbed: grab again so mouse-look works at once
				mc.mouseHandler.releaseMouse();
				mc.mouseHandler.grabMouse();
			}
		}
	}

	/** World left / port changed / game closing: leave work mode, restore pause-on-focus-loss and the window. */
	private void abort() {
		typing.leave();
		cursor = null;
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
