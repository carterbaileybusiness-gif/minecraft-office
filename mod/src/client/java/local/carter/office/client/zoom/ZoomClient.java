package local.carter.office.client.zoom;

import java.util.Optional;
import java.util.UUID;

import com.mojang.blaze3d.platform.Window;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import local.carter.office.client.capture.Target;
import local.carter.office.client.interact.HologramInteractions;
import local.carter.office.client.platform.Platform;
import local.carter.office.client.render.HologramLook;
import local.carter.office.client.render.HologramPlacement;
import local.carter.office.client.render.HologramRenderer;
import local.carter.office.client.render.ScreenTexture;
import local.carter.office.client.render.ScreenTextureRegistry;
import local.carter.office.client.zoom.ZoomController.Candidate;
import local.carter.office.client.zoom.ZoomController.State;
import local.carter.office.client.zoom.ZoomPose.Pose;
import local.carter.office.core.HologramSpec;
import local.carter.office.core.Mode;
import local.carter.office.core.Vec3d;
import local.carter.office.hologram.HologramBlockEntity;
import local.carter.office.net.OfficePayloads.SetWorking;

import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.network.chat.Component;
import net.minecraft.util.Util;
import net.minecraft.world.phys.Vec3;

import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;

/**
 * Minecraft side of "zoom in to work" ({@link ZoomController} is the state machine). Client thread only.
 *
 * <ul>
 * <li>Camera: {@code CameraZoomMixin} asks {@link #cameraPose} / {@link #fov} every frame and blends the player's own
 * view into {@link ZoomPose#framing} of the hologram (350 ms ease-in-out each way); in the world it returns null and the
 * camera is vanilla's, so leaving restores the view exactly.</li>
 * <li>While zoomed: the HUD, hand and crosshair are hidden (the F1 state), {@code pauseOnLostFocus} is off (focus moves
 * to the app on the virtual display / the placed window), and a {@link WorkScreen} is open. The screen releases the
 * mouse (the helper moves the real cursor), blocks game input, swallows a single Esc, and while WORKING in EXTEND mode
 * draws the hologram's 30-fps local capture over the whole window, letterboxed.</li>
 * <li>Arriving: {@link Platform#zoomIn} (MIRROR: Minecraft steps aside, hidden on Mac / minimized on Windows, so the
 * player is on their real desktop; Mac EXTEND: cursor onto the virtual display, clamped; WINDOW: the real window placed
 * over Minecraft and focused) and {@code SetWorking(true)}. Leaving: {@link Platform#zoomOut} (focus
 * back to Minecraft) and {@code SetWorking(false)}.</li>
 * <li>Leaves on: double-Esc in Minecraft, the platform's exit hotkey, capture ended / window closed, the hologram
 * removed, unloaded, given away or retargeted, the helper going away, another screen replacing ours. World change or
 * disconnect aborts with no animation.</li>
 * </ul>
 */
public final class ZoomClient implements ZoomController.Env {
	private static final Logger LOGGER = LoggerFactory.getLogger("office");
	/** Log {@code FRAMES fps=…} every 5 s in the world too (always logged while working). */
	public static final String FRAMES_PROPERTY = "office.frames";
	private static final long FRAMES_EVERY_MS = 5_000;

	private static final ZoomClient INSTANCE = new ZoomClient();

	private final ZoomController controller = new ZoomController(this);
	private @Nullable HologramBlockEntity be;
	private @Nullable ClientLevel level;
	private @Nullable WorkScreen screen;
	private boolean hidHud;
	private boolean pauseSaved;
	private boolean savedPauseOnLostFocus;
	private long workingSince;
	private long lastFramesLog;
	/** Minecraft's window frame last sent with zoomIn, and a changed one waiting to settle before it is re-sent. */
	private Platform.@Nullable Rect zoomRect;
	private Platform.@Nullable Rect pendingRect;
	private long pendingSince;
	private boolean lastActive = true;
	/** EXTEND: focus has left Minecraft since WORKING began (the player clicked into the virtual display). */
	private boolean focusLeft;
	/** The work screen drew the full-window overlay on the last frame. */
	private boolean overlayDrawn;
	/** {@code Options.save} is running with the player's own pauseOnLostFocus swapped in. */
	private boolean savingWithRealPause;
	/** Eased progress computed for this frame's camera pose, reused for the FOV of the same frame. */
	private double frameEase;

	private ZoomClient() {}

	public static void register() {
		ZoomController.install(INSTANCE.controller);
		WorkingNames.register();
		HologramRenderer.setZoomedPredicate(INSTANCE.controller::isZoomedInto);
		// Mac calls this on the client thread, Windows on its own executor: always hop to the client thread.
		Platform.current().onExitRequested(() -> Minecraft.getInstance().execute(() -> {
			if (INSTANCE.controller.exit()) {
				LOGGER.info("Zoom: leaving (platform exit gesture)");
			}
		}));
		ClientTickEvents.END_CLIENT_TICK.register(INSTANCE::tick);
		ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> client.execute(INSTANCE.controller::abort));
	}

	public static ZoomController controller() {
		return INSTANCE.controller;
	}

	// ---------------------------------------------------------------- frame / tick

	/** Once per frame, before the camera is set up ({@code MinecraftFrameMixin}). */
	public static void frame() {
		if (INSTANCE.controller.state() != State.WORLD) {
			INSTANCE.controller.update();
		}
	}

	private void tick(final Minecraft mc) {
		logFrames(mc);
		boolean active = mc.isWindowActive();
		if (active != lastActive) {
			lastActive = active;
			controller.focusChanged(active); // an Esc release may have gone to the other app; Mirror: back in Minecraft = exit
			if (!active && controller.state() == State.WORKING) {
				focusLeft = true;
			}
		}
		if (pauseSaved && controller.state() == State.WORLD && active) {
			restorePause(mc); // deferred from end(): Minecraft has focus again
		}
		if (controller.state() == State.WORLD) {
			return;
		}
		if (mc.level == null || mc.player == null || mc.level != level) {
			LOGGER.info("Zoom: aborted (world changed)");
			controller.abort();
			return;
		}
		String why = leaveReason(mc);
		if (why != null) {
			controller.exit(); // no-op if captureEnded already left
			LOGGER.info("Zoom: leaving ({})", why);
		}
		followWindow(mc);
		controller.update(); // also leaves on Platform.workProblem (helper gone / zoom refused) with a notice
	}

	/** Minecraft's window moved or resized while WORKING: re-send zoomIn once the new frame has settled (250 ms). */
	private void followWindow(final Minecraft mc) {
		Candidate t = controller.target();
		if (controller.state() != State.WORKING || t == null) {
			pendingRect = null;
			return;
		}
		Platform.Rect now = windowRect(mc);
		if (now.equals(zoomRect)) {
			pendingRect = null;
			return;
		}
		long ms = Util.getMillis();
		if (!now.equals(pendingRect)) {
			pendingRect = now;
			pendingSince = ms;
			return;
		}
		if (ms - pendingSince >= 250) {
			pendingRect = null;
			zoomRect = now;
			LOGGER.info("Zoom: Minecraft's window changed to {}x{} at {},{}; re-placing", now.w(), now.h(), now.x(), now.y());
			try {
				Platform.current().zoomIn(t.spec(), now);
			} catch (RuntimeException e) {
				LOGGER.warn("Zoom: re-placing after a window change failed", e);
				controller.exit();
			}
		}
	}

	private static Platform.Rect windowRect(final Minecraft mc) {
		Window w = mc.getWindow();
		return new Platform.Rect(w.getX(), w.getY(), w.getScreenWidth(), w.getScreenHeight());
	}

	/** Why the zoom must end now, or null. Also follows the hologram if it moved. */
	private @Nullable String leaveReason(final Minecraft mc) {
		Candidate t = controller.target();
		HologramBlockEntity b = be;
		boolean leaving = controller.state() == State.ZOOMING_OUT;
		if (t == null || b == null || b.isRemoved()) {
			return leaving ? null : "hologram removed";
		}
		HologramSpec spec = b.spec();
		if (spec == null || mc.player == null || !mc.player.getUUID().equals(spec.owner())) {
			return leaving ? null : "hologram no longer yours";
		}
		if (!Target.of(spec).equals(Target.of(t.spec()))) {
			return leaving ? null : "hologram shows something else now";
		}
		controller.retarget(spec);
		if (leaving) {
			return null;
		}
		UUID id = t.hologramId();
		Optional<ScreenTexture> tex = ScreenTextureRegistry.get(id);
		String ended = tex.map(ScreenTexture::endedReason).orElse(null);
		if (ended != null) {
			controller.captureEnded(id, ended);
			return "capture ended: " + ended;
		}
		if (tex.isEmpty()) {
			return "capture stopped";
		}
		HologramLook.OwnSource own = HologramLook.ownSource(id);
		if (own != HologramLook.OwnSource.READY) {
			return "capture " + own;
		}
		if (mc.gui.screen() != screen) {
			return "another screen opened";
		}
		return null;
	}

	private void logFrames(final Minecraft mc) {
		boolean working = controller.state() == State.WORKING;
		if (!working && !Boolean.getBoolean(FRAMES_PROPERTY)) {
			return;
		}
		long now = Util.getMillis();
		if (now - lastFramesLog >= FRAMES_EVERY_MS) {
			lastFramesLog = now;
			LOGGER.info("FRAMES fps={} state={}", mc.getFps(), controller.state());
		}
	}

	// ---------------------------------------------------------------- camera (CameraZoomMixin, client thread)

	/**
	 * The camera pose for this frame, blending the vanilla pose ({@code pos}, {@code yaw}, {@code pitch}) towards the
	 * working pose; null in the world (vanilla camera untouched).
	 */
	public static @Nullable Pose cameraPose(final Camera camera, final Vec3 pos, final float yaw, final float pitch) {
		return INSTANCE.pose(camera, pos, yaw, pitch);
	}

	/** The FOV for this frame (blend from vanilla's to the base FOV the working pose is framed for). */
	public static float fov(final float vanillaFov) {
		ZoomClient z = INSTANCE;
		if (z.controller.state() == State.WORLD || z.frameEase <= 0) {
			return vanillaFov;
		}
		return (float) (vanillaFov + (baseFov() - vanillaFov) * z.frameEase);
	}

	private @Nullable Pose pose(final Camera camera, final Vec3 pos, final float yaw, final float pitch) {
		Minecraft mc = Minecraft.getInstance();
		Candidate t = controller.target();
		HologramBlockEntity b = be;
		frameEase = 0;
		if (controller.state() == State.WORLD || t == null || b == null || camera != mc.gameRenderer.mainCamera()) {
			return null;
		}
		double e = controller.progress();
		frameEase = e;
		if (e <= 0) {
			return null;
		}
		Window w = mc.getWindow();
		double aspect = w.getHeight() > 0 ? (double) w.getWidth() / w.getHeight() : 16.0 / 9.0;
		Pose target = ZoomPose.framing(HologramPlacement.frame(b.getBlockPos().getX(), b.getBlockPos().getY(), b.getBlockPos().getZ(), t.spec()),
				baseFov(), aspect);
		Vec3d from = new Vec3d(pos.x, pos.y, pos.z);
		return new Pose(ZoomPose.lerp(from, target.eye(), e), ZoomPose.lerpDegrees(yaw, target.yaw(), e),
				(float) (pitch + (target.pitch() - pitch) * e));
	}

	private static double baseFov() {
		return Minecraft.getInstance().options.fov().get();
	}

	// ---------------------------------------------------------------- ZoomController.Env

	@Override
	public long nowMs() {
		return Util.getMillis();
	}

	@Override
	public @Nullable UUID viewer() {
		Minecraft mc = Minecraft.getInstance();
		return mc.player == null ? null : mc.player.getUUID();
	}

	@Override
	public Candidate candidate(final HologramBlockEntity hologram) {
		HologramSpec spec = hologram.spec();
		UUID id = hologram.hologramId();
		boolean hasFrames = ScreenTextureRegistry.get(id).filter(ScreenTexture::hasFrame).isPresent();
		HologramLook.State look = spec == null ? HologramLook.State.CONNECTING
				: HologramLook.forViewer(spec, viewer(), hologram.ownerOnline(), hasFrames, HologramLook.ownSource(id));
		return new Candidate(id, spec, look == HologramLook.State.LIVE);
	}

	/** {@link ZoomController#request(HologramBlockEntity)} plus remembering which block entity to follow. */
	public static ZoomController.Request request(final HologramBlockEntity hologram) {
		ZoomClient z = INSTANCE;
		if (z.controller.state() != State.WORLD) {
			return ZoomController.Request.BUSY;
		}
		z.be = hologram;
		ZoomController.Request r = z.controller.request(hologram);
		if (r != ZoomController.Request.STARTED) {
			z.be = null;
		}
		return r;
	}

	/** Why the local player can't work on {@code hologram} right now, if that is why a request was refused. */
	public static @Nullable String problem(final HologramBlockEntity hologram) {
		HologramSpec spec = hologram.spec();
		return spec == null ? null : Platform.current().workProblem(spec);
	}

	@Override
	public @Nullable String workProblem(final Candidate target) {
		return Platform.current().workProblem(target.spec());
	}

	@Override
	public void begin(final Candidate target) {
		Minecraft mc = Minecraft.getInstance();
		level = mc.level;
		HologramInteractions.cancel();
		if (!mc.gui.hud.isHidden()) {
			mc.gui.hud.toggle(); // hides HUD, hand, crosshair and block outline, like F1
			hidHud = true;
		}
		if (!pauseSaved) {
			savedPauseOnLostFocus = mc.options.pauseOnLostFocus;
			pauseSaved = true;
		}
		mc.options.pauseOnLostFocus = false; // not saved to options.txt; restored on the way out
		WorkScreen s = new WorkScreen(controller);
		screen = s;
		mc.gui.setScreen(s);
		LOGGER.info("Zoom: in to hologram {} ({})", target.hologramId(), target.spec().mode());
	}

	@Override
	public void startWorking(final Candidate target) {
		Minecraft mc = Minecraft.getInstance();
		Platform.Rect rect = windowRect(mc);
		workingSince = Util.getMillis();
		lastFramesLog = 0;
		focusLeft = !mc.isWindowActive();
		overlayDrawn = false;
		zoomRect = rect;
		pendingRect = null;
		sendWorking(true);
		Platform.current().zoomIn(target.spec(), rect); // a throw here leaves via the controller (stopWorking runs)
		LOGGER.info("Zoom: working (window {}x{} at {},{} pt)", rect.w(), rect.h(), rect.x(), rect.y());
	}

	@Override
	public void stopWorking(final Candidate target) {
		zoomRect = null;
		pendingRect = null;
		overlayDrawn = false;
		try {
			Platform.current().zoomOut();
		} finally {
			sendWorking(false); // always, even if the platform failed
			LOGGER.info("Zoom: stopped working after {} s", (Util.getMillis() - workingSince) / 1000);
		}
	}

	@Override
	public void keepAwake() {
		// Input goes to the computer, not Minecraft: without this, 26.3's inactivity limit drops to 30 fps after
		// 60 s and 10 fps after 10 min of "AFK" while the player is busy working.
		Minecraft.getInstance().getFramerateLimitTracker().onInputReceived();
	}

	@Override
	public void notice(final String message) {
		Minecraft mc = Minecraft.getInstance();
		if (mc.player != null) {
			mc.player.sendOverlayMessage(Component.literal(message));
		}
	}

	@Override
	public void end(final Candidate target, final boolean aborted) {
		Minecraft mc = Minecraft.getInstance();
		if (hidHud && mc.gui.hud.isHidden()) {
			mc.gui.hud.toggle();
		}
		hidHud = false;
		// Minecraft's "last active" time is minutes old after working: restoring pauseOnLostFocus before the window has
		// focus again would open the pause menu at once, so that waits for focus (tick) unless the zoom was aborted.
		if (aborted || mc.isWindowActive()) {
			restorePause(mc);
		}
		WorkScreen s = screen;
		screen = null;
		be = null;
		level = null;
		frameEase = 0;
		overlayDrawn = false;
		if (s != null && mc.gui.screen() == s) {
			try {
				mc.gui.setScreen(null); // also after an abort, if nothing replaced it (e.g. a dimension change)
			} catch (IllegalStateException e) {
				LOGGER.debug("Zoom: work screen left to the disconnect flow: {}", e.toString());
			}
		}
		LOGGER.info("Zoom: back in the world{}", aborted ? " (aborted)" : "");
	}

	private void restorePause(final Minecraft mc) {
		if (pauseSaved) {
			mc.options.pauseOnLostFocus = savedPauseOnLostFocus;
			pauseSaved = false;
		}
	}

	/** {@code Options.save} is about to write: put the player's own pauseOnLostFocus in for the write. */
	public static void beforeOptionsSave() {
		ZoomClient z = INSTANCE;
		Minecraft mc = Minecraft.getInstance();
		if (z.pauseSaved && mc != null) {
			mc.options.pauseOnLostFocus = z.savedPauseOnLostFocus;
			z.savingWithRealPause = true;
		}
	}

	/** {@code Options.save} has written: zoomed again (options.txt never stores the zoom's false). */
	public static void afterOptionsSave() {
		ZoomClient z = INSTANCE;
		if (z.savingWithRealPause) {
			z.savingWithRealPause = false;
			Minecraft.getInstance().options.pauseOnLostFocus = false;
		}
	}

	/** The work screen reports whether it drew the full-window overlay this frame. */
	static void overlayDrawn(final boolean drawn) {
		INSTANCE.overlayDrawn = drawn;
	}

	/**
	 * True while the opaque EXTEND overlay covers the whole window (WORKING, after the bars faded in): the world isn't
	 * visible, so {@code GameRendererMixin} skips rendering it. {@code -Doffice.zoomKeepWorld=true} turns this off.
	 */
	public static boolean overlayCoversWindow() {
		ZoomClient z = INSTANCE;
		if (Boolean.getBoolean("office.zoomKeepWorld") || z.controller.state() != State.WORKING) {
			return false;
		}
		if (mirrorStepAside()) {
			return true; // Minecraft is hidden / minimized: nobody sees the world, don't spend the GPU on it
		}
		return z.overlayDrawn && workingForMs() >= WorkScreen.BAR_FADE_MS;
	}

	/** WORKING on a MIRROR hologram and Minecraft's window has stepped aside (no focus): the real desktop is in front. */
	static boolean mirrorStepAside() {
		ZoomClient z = INSTANCE;
		Candidate t = z.controller.target();
		return z.controller.state() == State.WORKING && t != null && t.spec().mode() == Mode.MIRROR
				&& !Minecraft.getInstance().isWindowActive();
	}

	/** EXTEND: the player hasn't clicked into the virtual display yet, so keys still go to Minecraft. */
	static boolean needsClickToType() {
		return INSTANCE.controller.state() == State.WORKING && !INSTANCE.focusLeft;
	}

	/** Milliseconds since WORKING began (for the screen's hint), or -1 when not working. */
	static long workingForMs() {
		return INSTANCE.controller.state() == State.WORKING ? Util.getMillis() - INSTANCE.workingSince : -1;
	}

	private static void sendWorking(final boolean working) {
		try {
			if (ClientPlayNetworking.canSend(SetWorking.TYPE)) {
				ClientPlayNetworking.send(new SetWorking(working));
			}
		} catch (RuntimeException e) {
			LOGGER.debug("Office: SetWorking({}) not sent: {}", working, e.toString());
		}
	}
}
