package local.carter.office.client.helper;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import com.google.gson.JsonParser;
import com.google.gson.JsonPrimitive;
import java.io.IOException;
import java.math.BigDecimal;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import org.jspecify.annotations.Nullable;

/**
 * Pure codec for the Mac helper protocol (version 1). Mirrors {@code helper-mac/Sources/OfficeHelper/Protocol.swift}
 * and is verified byte-for-byte against {@code helper-mac/Tests/fixtures}.
 *
 * <p>Envelope, big-endian: {@code u32 length (= 1 + payload bytes) | u8 type | payload}. Control payloads are compact
 * UTF-8 JSON with sorted keys; FRAME and PATCH payloads are binary. No sockets, no threads: callers feed bytes to a
 * {@link FrameReader} and pass messages to {@link #encode(Message)}.
 */
public final class HelperProtocol {
    private HelperProtocol() {}

    public static final int VERSION = 1;
    /** Largest accepted value of the u32 length field (type byte + payload), either direction. */
    public static final int MAX_FRAME_LENGTH = 64 * 1024 * 1024;
    /** The helper reads only JSON from the mod, so its inbound limit is much smaller. */
    public static final int MAX_CONTROL_LENGTH = 64 * 1024;
    /** PATCH messages carry at most this many rectangles. */
    public static final int MAX_PATCH_RECTS = 32;

    // Mod -> helper
    public static final int HELLO = 1;
    public static final int LIST_WINDOWS = 2;
    public static final int CREATE_DISPLAY = 3;
    public static final int REMOVE_DISPLAY = 4;
    public static final int CAPTURE = 5;
    public static final int STOP = 6;
    public static final int READY = 7;
    public static final int ZOOM_IN = 8;
    public static final int ZOOM_OUT = 9;
    /** Ask macOS for the missing permissions now (native prompts); the helper answers with a fresh WELCOME. */
    public static final int REQUEST_PERMISSIONS = 10;
    /** In-world use (spec 5.9): a drag the helper drives, a click into typing mode, scrolling, leaving typing mode. */
    public static final int POINTER_DOWN = 11;
    public static final int POINTER_MOVE = 12;
    public static final int POINTER_UP = 13;
    public static final int SCROLL = 14;
    public static final int CLICK = 15;
    public static final int EXIT_TYPING = 16;
    // 17, 18: the retired WINDOWS_TO_DISPLAY / WINDOWS_BACK (windows never move by themselves any more, spec 5.10).
    /** The mini Dock (spec 5.10): the installed apps, their icons, opening an app onto a hologram's display. */
    public static final int LIST_APPS = 19;
    public static final int APP_ICONS = 20;
    public static final int OPEN_APP = 21;
    /** Work mode (spec 5.10 round 2): the real cursor goes to this point and the real mouse drives it until Esc. */
    public static final int WORK_BEGIN = 22;
    /** A live capture's new frame rate (no stream rebuild). */
    public static final int RATE = 23;
    /** The display map ("screens" feature only): every screen, please (DISPLAYS answers). */
    public static final int LIST_DISPLAYS = 24;
    /** Move Office displays to these origins, all in one display configuration (DISPLAYS answers). */
    public static final int SET_ORIGINS = 25;
    /** Show a big label on these screens for 3 s. */
    public static final int IDENTIFY = 26;
    // Helper -> mod
    public static final int WELCOME = 64;
    public static final int WINDOWS = 65;
    public static final int DISPLAY = 66;
    public static final int FRAME = 67;
    public static final int PATCH = 68;
    public static final int ENDED = 69;
    public static final int EXIT_ZOOM = 70;
    public static final int ERROR = 71;
    public static final int TYPING_EXIT = 72;
    public static final int DRAG_DELTA = 73;
    public static final int DRAG_ENDED = 74;
    public static final int APPS = 75;
    public static final int ICONS = 76;
    public static final int APP_RESULT = 77;
    /** While typing: secure input (a password field) went on / off; Esc can't be seen then, ⌘-Tab still returns. */
    public static final int SECURE_INPUT = 78;
    /** Work mode: where the real cursor is (slot -1: on none of the player's screens). */
    public static final int CURSOR = 79;
    /** Work mode, walk while dragging: the movement keys held for Minecraft (forward, back, left, right, jump, sneak). */
    public static final int WALK = 80;
    /** The player's screens: real monitors (slot 0) and Office displays (1-4), in global points. */
    public static final int DISPLAYS = 81;
    /** Pointer slot 0: the main display (a Mirror hologram); x, y are then a pixel of a w x h picture of it. */
    public static final int MAIN_SLOT = 0;

    /**
     * HELLO / WELCOME feature: the side understands LIST_DISPLAYS, SET_ORIGINS, IDENTIFY, DISPLAYS and the optional
     * {@code display} key. Each side sends those only when the other announced it, so a mod and helper one release apart
     * never close the connection on an unknown type.
     */
    public static final String FEATURE_SCREENS = "screens";
    /** Monitor ids (CGDisplay UUID string, Windows device name) are 1..64 characters. */
    public static final int MAX_DISPLAY_ID = 64;
    public static final int MAX_DISPLAYS = 16;
    public static final int MAX_IDENTIFY_LABELS = 16;

    private static final int IMAGE_HEADER = 20; // u32 surface, u64 generation, u32 width, u32 height
    private static final int PATCH_HEADER = IMAGE_HEADER + 4; // + u32 count
    private static final int RECT_BYTES = 16;

    /** Malformed frame, unknown type, oversize frame or bad payload. */
    public static final class ProtocolException extends IOException {
        public ProtocolException(String message) {
            super(message);
        }
    }

    // ---------------------------------------------------------------- messages

    public sealed interface Message permits Hello, ListWindows, CreateDisplay, RemoveDisplay, Capture, Stop, Ready,
            ZoomIn, ZoomOut, RequestPermissions, PointerDown, PointerMove, PointerUp, Scroll, Click, ExitTyping,
            ListApps, AppIcons, OpenApp, Welcome, Windows, Display, Frame, Patch, Ended, ExitZoom, HelperError, TypingExit,
            DragDelta, DragEnded, Apps, Icons, AppResult, SecureInput, WorkBegin, Cursor, Rate, Walk, ListDisplays,
            SetOrigins, Identify, Displays {
        /** The wire type byte of this message. */
        default int type() {
            return switch (this) {
                case Hello m -> HELLO;
                case ListWindows m -> LIST_WINDOWS;
                case CreateDisplay m -> CREATE_DISPLAY;
                case RemoveDisplay m -> REMOVE_DISPLAY;
                case Capture m -> CAPTURE;
                case Stop m -> STOP;
                case Ready m -> READY;
                case ZoomIn m -> ZOOM_IN;
                case ZoomOut m -> ZOOM_OUT;
                case RequestPermissions m -> REQUEST_PERMISSIONS;
                case PointerDown m -> POINTER_DOWN;
                case PointerMove m -> POINTER_MOVE;
                case PointerUp m -> POINTER_UP;
                case Scroll m -> SCROLL;
                case Click m -> CLICK;
                case ExitTyping m -> EXIT_TYPING;
                case ListApps m -> LIST_APPS;
                case AppIcons m -> APP_ICONS;
                case OpenApp m -> OPEN_APP;
                case Welcome m -> WELCOME;
                case Windows m -> WINDOWS;
                case Display m -> DISPLAY;
                case Frame m -> FRAME;
                case Patch m -> PATCH;
                case Ended m -> ENDED;
                case ExitZoom m -> EXIT_ZOOM;
                case HelperError m -> ERROR;
                case TypingExit m -> TYPING_EXIT;
                case DragDelta m -> DRAG_DELTA;
                case DragEnded m -> DRAG_ENDED;
                case Apps m -> APPS;
                case Icons m -> ICONS;
                case AppResult m -> APP_RESULT;
                case SecureInput m -> SECURE_INPUT;
                case WorkBegin m -> WORK_BEGIN;
                case Cursor m -> CURSOR;
                case Walk m -> WALK;
                case Rate m -> RATE;
                case ListDisplays m -> LIST_DISPLAYS;
                case SetOrigins m -> SET_ORIGINS;
                case Identify m -> IDENTIFY;
                case Displays m -> DISPLAYS;
            };
        }
    }

    public enum CaptureKind {
        DISPLAY("display"),
        WINDOW("window"),
        /** Mirror: the main display, with the Minecraft app (HELLO's mcPid) excluded from the capture. */
        MAIN("main");

        public final String wire;

        CaptureKind(String wire) {
            this.wire = wire;
        }

        static CaptureKind of(String wire) throws ProtocolException {
            for (CaptureKind k : values()) if (k.wire.equals(wire)) return k;
            throw new ProtocolException("bad payload: unknown capture kind " + wire);
        }
    }

    /** Mouse button of a pointer message. */
    public enum PointerButton {
        LEFT("left"),
        RIGHT("right"),
        MIDDLE("middle");

        public final String wire;

        PointerButton(String wire) {
            this.wire = wire;
        }

        static PointerButton of(String wire) throws ProtocolException {
            for (PointerButton b : values()) if (b.wire.equals(wire)) return b;
            throw new ProtocolException("bad payload: unknown button " + wire);
        }
    }

    /** Global display coordinates in points, top-left origin (Quartz / CGWindow space). */
    public record WindowRect(int x, int y, int w, int h) {}

    public record PixelRect(int x, int y, int w, int h) {}

    /** {@code "<bundleId>|<CGWindowID>"}; the id follows the last '|' and the bundle id may be empty. */
    public record WindowKey(String bundleId, long windowId) {
        public static WindowKey parse(String key) {
            int bar = key.lastIndexOf('|');
            if (bar < 0) return null;
            String id = key.substring(bar + 1);
            if (id.isEmpty() || id.length() > 10) return null;
            for (int i = 0; i < id.length(); i++) if (id.charAt(i) < '0' || id.charAt(i) > '9') return null;
            long v = Long.parseLong(id);
            if (v > 0xFFFFFFFFL) return null;
            return new WindowKey(key.substring(0, bar), v);
        }

        @Override
        public String toString() {
            return bundleId + "|" + windowId;
        }
    }

    public record WindowInfo(String windowKey, String app, String title, int w, int h) {}

    // Mod -> helper
    /** {@code features}: what this mod understands beyond version 1 ({@link #FEATURE_SCREENS}); null from older mods. */
    public record Hello(String token, int version, int mcPid, @Nullable List<String> features) implements Message {
        public Hello {
            features = features == null ? null : List.copyOf(features);
        }

        public Hello(String token, int version, int mcPid) {
            this(token, version, mcPid, null);
        }
    }

    public record ListWindows() implements Message {}

    public record CreateDisplay(int slot, int w, int h) implements Message {}

    public record RemoveDisplay(int slot) implements Message {}

    /**
     * {@code slot} is required for DISPLAY captures, {@code windowKey} for WINDOW captures; the other is null (both for MAIN).
     * {@code display}: MAIN only, the monitor a Mirror copies (null = the main display; "screens" helpers only).
     */
    public record Capture(int surface, CaptureKind kind, Integer slot, String windowKey, int maxW, int fps,
            @Nullable String display) implements Message {
        public Capture(int surface, CaptureKind kind, Integer slot, String windowKey, int maxW, int fps) {
            this(surface, kind, slot, windowKey, maxW, fps, null);
        }
    }

    public record Stop(int surface) implements Message {}

    public record Ready(int surface) implements Message {}

    public record ZoomIn(int surface, WindowRect mcWindowFrame) implements Message {}

    public record ZoomOut() implements Message {}

    public record RequestPermissions() implements Message {}

    // In-world use: (x, y) is a pixel of slot's virtual display (0..w-1, 0..h-1). Slot 0 (MAIN_SLOT) is the main display
    // (a Mirror hologram): (x, y) is then a pixel of a w x h picture of it, and w, h are required; else they are null.
    // `display` (slot 0 only, "screens" helpers only): the monitor that Mirror copies, instead of the main display.
    /** Start a drag the helper drives at the press point; it ends when the physical button is released (DRAG_ENDED). */
    public record PointerDown(int slot, int x, int y, PointerButton button, Integer w, Integer h, @Nullable String display)
            implements Message {
        public PointerDown(int slot, int x, int y, PointerButton button, Integer w, Integer h) {
            this(slot, x, y, button, w, h, null);
        }

        public PointerDown(int slot, int x, int y, PointerButton button) {
            this(slot, x, y, button, null, null, null);
        }
    }

    /** The crosshair is over this pixel now (during a drag; any slot). */
    public record PointerMove(int slot, int x, int y, Integer w, Integer h, @Nullable String display) implements Message {
        public PointerMove(int slot, int x, int y, Integer w, Integer h) {
            this(slot, x, y, w, h, null);
        }

        public PointerMove(int slot, int x, int y) {
            this(slot, x, y, null, null, null);
        }
    }

    /** Cancel the drag from the mod's side. */
    public record PointerUp(int slot, int x, int y, PointerButton button, Integer w, Integer h, @Nullable String display)
            implements Message {
        public PointerUp(int slot, int x, int y, PointerButton button, Integer w, Integer h) {
            this(slot, x, y, button, w, h, null);
        }

        public PointerUp(int slot, int x, int y, PointerButton button) {
            this(slot, x, y, button, null, null, null);
        }
    }

    /** Scroll there, in pixels of content motion (dy &gt; 0 = up / away). */
    public record Scroll(int slot, int x, int y, int dx, int dy, Integer w, Integer h, @Nullable String display)
            implements Message {
        public Scroll(int slot, int x, int y, int dx, int dy, Integer w, Integer h) {
            this(slot, x, y, dx, dy, w, h, null);
        }

        public Scroll(int slot, int x, int y, int dx, int dy) {
            this(slot, x, y, dx, dy, null, null, null);
        }
    }

    /** A real click there: the app comes forward with the keyboard (typing mode). */
    public record Click(int slot, int x, int y, PointerButton button, Integer w, Integer h, @Nullable String display)
            implements Message {
        public Click(int slot, int x, int y, PointerButton button, Integer w, Integer h) {
            this(slot, x, y, button, w, h, null);
        }

        public Click(int slot, int x, int y, PointerButton button) {
            this(slot, x, y, button, null, null, null);
        }
    }

    /** The mod left typing mode (no reply). */
    public record ExitTyping() implements Message {}

    /** The installed apps, please (APPS answers). */
    public record ListApps() implements Message {}

    /** PNG icons for these apps, {@code size} pixels square (16..256, at most 64 bundle ids); ICONS answers. */
    public record AppIcons(List<String> bundles, int size) implements Message {}

    /**
     * Open {@code bundle}, or the https {@code url} in the default browser, onto slot's display (1..4); exactly one is set.
     * {@code bottom}: display pixels at the bottom that the in-world Dock covers (the window stops above them), or null.
     */
    public record OpenApp(int slot, String bundle, String url, Integer bottom) implements Message {
        public OpenApp(int slot, String bundle, String url) {
            this(slot, bundle, url, null);
        }
    }

    /** Every screen, please ("screens" helpers only); DISPLAYS answers. */
    public record ListDisplays() implements Message {}

    /** Where an Office display goes: its top-left corner in global points. */
    public record SlotOrigin(int slot, int x, int y) {}

    /** Move these Office displays (1..4 entries, slots 1..4, each once) in one configuration; DISPLAYS answers. */
    public record SetOrigins(List<SlotOrigin> origins) implements Message {
        public SetOrigins {
            origins = List.copyOf(origins);
        }
    }

    /** Show {@code label} (1..2 characters) big on the screen whose id is {@code display}. */
    public record IdentifyLabel(String display, String label) {}

    /** Identify: a label on each of these screens (1..16) for 3 s. */
    public record Identify(List<IdentifyLabel> labels) implements Message {
        public Identify {
            labels = List.copyOf(labels);
        }
    }

    // Helper -> mod
    /** The handshake reply; sent again whenever a permission changes while connected. {@code features}: as HELLO's. */
    public record Welcome(int version, boolean screenRecording, boolean accessibility, @Nullable List<String> features)
            implements Message {
        public Welcome {
            features = features == null ? null : List.copyOf(features);
        }

        public Welcome(int version, boolean screenRecording, boolean accessibility) {
            this(version, screenRecording, accessibility, null);
        }

        /** This helper announced {@code feature} (e.g. {@link #FEATURE_SCREENS}). */
        public boolean has(String feature) {
            return features != null && features.contains(feature);
        }
    }

    public record Windows(List<WindowInfo> windows) implements Message {}

    /**
     * {@code x}, {@code y}: the display's origin in the arrangement (global points); {@code side}: "left" or "right" of
     * the main display, the laptop screen's edge the cursor crosses to get there. All three null from older helpers.
     */
    public record Display(int slot, long displayId, int w, int h, Integer x, Integer y, String side) implements Message {
        public Display(int slot, long displayId, int w, int h) {
            this(slot, displayId, w, h, null, null, null);
        }
    }

    /** {@code rgba} is a read-only view of width*height*4 bytes (rows top to bottom); position 0, limit = size. */
    public record Frame(int surface, long generation, int width, int height, ByteBuffer rgba) implements Message {}

    /** {@code rgba} holds each rectangle's RGBA rows, concatenated in rectangle order. */
    public record Patch(int surface, long generation, int width, int height, List<PixelRect> rects, ByteBuffer rgba)
            implements Message {}

    public record Ended(int surface, String reason) implements Message {}

    public record ExitZoom() implements Message {}

    /** Typing mode ended in the helper: {@code "esc"} (Esc, swallowed) or {@code "minecraft"} (Minecraft active again). */
    public record TypingExit(String reason) implements Message {}

    /** Physical mouse motion (points) during a helper-driven drag: turn the player's head by it. */
    public record DragDelta(int dx, int dy) implements Message {}

    /** The helper-driven drag ended: {@code "released"} (the physical button came up) or {@code "cancelled"}. */
    public record DragEnded(String reason) implements Message {}

    public record AppInfo(String bundle, String name) {}

    public record Apps(List<AppInfo> apps) implements Message {}

    /** {@code png} is base64. */
    public record AppIcon(String bundle, String png) {}

    public record Icons(List<AppIcon> icons) implements Message {}

    /** How an OPEN_APP went; {@code target} is its bundle id or url. */
    public record AppResult(String target, boolean ok, String message) implements Message {}

    /** Secure input on / off while typing. */
    public record SecureInput(boolean on) implements Message {}

    /**
     * Enter work mode at this point (slot 0 = the main display, with the picture size w, h; with {@code display}, that
     * monitor instead).
     */
    public record WorkBegin(int slot, int x, int y, PointerButton button, Integer w, Integer h, @Nullable String display)
            implements Message {
        public WorkBegin(int slot, int x, int y, PointerButton button, Integer w, Integer h) {
            this(slot, x, y, button, w, h, null);
        }
    }

    /**
     * The real cursor while working: on display {@code slot} (0 = main, its points; 1-4 = a virtual display, its pixels;
     * -1 = none of them) at (x, y) of a w x h grid. {@code display} (slot 0, "screens" helpers): the monitor it is on
     * when that is not the main display.
     */
    public record Cursor(int slot, int x, int y, int w, int h, @Nullable String display) implements Message {
        public Cursor(int slot, int x, int y, int w, int h) {
            this(slot, x, y, w, h, null);
        }
    }

    /**
     * One of the player's screens in global points (top-left origin): a real monitor ({@code slot} 0; {@code main} for the
     * main display) or an Office display (1..4). {@code name} (monitors only, short) never leaves the player's computer.
     */
    public record ScreenInfo(String id, int slot, boolean main, @Nullable String name, int x, int y, int w, int h) {}

    /** Every screen (at most 16): the answer to LIST_DISPLAYS / SET_ORIGINS, sent again when the displays change. */
    public record Displays(List<ScreenInfo> displays) implements Message {
        public Displays {
            displays = List.copyOf(displays);
        }
    }

    /** Movement keys held for Minecraft while dragging in work mode (sorted names); empty = stop. */
    public record Walk(List<String> keys) implements Message {
        public Walk {
            keys = List.copyOf(keys);
        }
    }

    /** A live capture's new frame rate, 1..60. */
    public record Rate(int surface, int fps) implements Message {}

    /** ERROR message. Named HelperError so it does not clash with {@link java.lang.Error}. */
    public record HelperError(String code, String message) implements Message {
        public static final String BAD_SLOT = "bad_slot";
        public static final String BAD_SIZE = "bad_size";
        public static final String DISPLAY_FAILED = "display_failed";
        public static final String NOT_FOUND = "not_found";
        public static final String NO_ACCESSIBILITY = "no_accessibility";
        public static final String BAD_REQUEST = "bad_request";
        public static final String VERSION_MISMATCH = "version";
        /** Another authenticated client is already connected to the helper. */
        public static final String BUSY = "busy";

        public boolean isBusy() {
            return BUSY.equals(code);
        }
    }

    // ---------------------------------------------------------------- encoding

    /** The complete envelope: {@code u32 length | u8 type | payload}. */
    public static byte[] encode(Message message) throws ProtocolException {
        switch (message) {
            case Frame f -> {
                checkFrame(f);
                ByteBuffer rgba = f.rgba().duplicate();
                ByteBuffer out = begin(FRAME, IMAGE_HEADER + rgba.remaining());
                imageHeader(out, f.surface(), f.generation(), f.width(), f.height());
                out.put(rgba);
                return out.array();
            }
            case Patch p -> {
                checkPatch(p);
                ByteBuffer rgba = p.rgba().duplicate();
                ByteBuffer out = begin(PATCH, PATCH_HEADER + p.rects().size() * RECT_BYTES + rgba.remaining());
                imageHeader(out, p.surface(), p.generation(), p.width(), p.height());
                out.putInt(p.rects().size());
                for (PixelRect r : p.rects()) out.putInt(r.x()).putInt(r.y()).putInt(r.w()).putInt(r.h());
                out.put(rgba);
                return out.array();
            }
            default -> {
                String problem = screensProblem(message);
                if (problem != null) throw bad(problem);
                byte[] json = Json.write(payloadOf(message)).getBytes(StandardCharsets.UTF_8);
                ByteBuffer out = begin(message.type(), json.length);
                out.put(json);
                return out.array();
            }
        }
    }

    private static ByteBuffer begin(int type, int payloadLength) throws ProtocolException {
        long length = 1L + payloadLength;
        if (length > MAX_FRAME_LENGTH) throw new ProtocolException("frame too large: " + length);
        ByteBuffer out = ByteBuffer.allocate(4 + (int) length);
        out.putInt((int) length).put((byte) type);
        return out;
    }

    private static void imageHeader(ByteBuffer out, int surface, long generation, int width, int height) {
        out.putInt(surface).putLong(generation).putInt(width).putInt(height);
    }

    private static void checkFrame(Frame f) throws ProtocolException {
        if (f.width() < 0 || f.height() < 0 || (long) f.width() * f.height() * 4 != f.rgba().remaining()) {
            throw new ProtocolException("bad payload: frame size");
        }
    }

    private static void checkPatch(Patch p) throws ProtocolException {
        checkRects(p.rects(), p.width(), p.height(), p.rgba().remaining());
    }

    private static void checkRects(List<PixelRect> rects, int width, int height, long pixelBytes)
            throws ProtocolException {
        if (rects.isEmpty() || rects.size() > MAX_PATCH_RECTS) throw new ProtocolException("bad payload: patch rect count");
        long total = 0;
        for (PixelRect r : rects) {
            if (r.x() < 0 || r.y() < 0 || r.w() <= 0 || r.h() <= 0
                    || (long) r.x() + r.w() > width || (long) r.y() + r.h() > height) {
                throw new ProtocolException("bad payload: patch rect out of bounds");
            }
            total += (long) r.w() * r.h() * 4;
        }
        if (total != pixelBytes) throw new ProtocolException("bad payload: patch pixel bytes");
    }

    private static Map<String, Object> map(Object... keyValues) {
        Map<String, Object> m = new TreeMap<>();
        for (int i = 0; i < keyValues.length; i += 2) {
            if (keyValues[i + 1] != null) m.put((String) keyValues[i], keyValues[i + 1]);
        }
        return m;
    }

    /** JSON value (Map / List / String / Integer / Long / Boolean) of a control message. */
    private static Object payloadOf(Message message) {
        return switch (message) {
            case Hello m -> map("features", m.features(), "mcPid", m.mcPid(), "token", m.token(), "version", m.version());
            case ListWindows m -> map();
            case CreateDisplay m -> map("h", m.h(), "slot", m.slot(), "w", m.w());
            case RemoveDisplay m -> map("slot", m.slot());
            case Capture m -> map("display", m.display(), "fps", m.fps(), "kind", m.kind().wire, "maxW", m.maxW(),
                    "slot", m.slot(), "surface", m.surface(), "windowKey", m.windowKey());
            case Stop m -> map("surface", m.surface());
            case Ready m -> map("surface", m.surface());
            case ZoomIn m -> map("mcWindowFrame",
                    map("h", m.mcWindowFrame().h(), "w", m.mcWindowFrame().w(), "x", m.mcWindowFrame().x(),
                            "y", m.mcWindowFrame().y()),
                    "surface", m.surface());
            case ZoomOut m -> map();
            case RequestPermissions m -> map();
            case PointerDown m -> map("button", m.button().wire, "display", m.display(), "h", m.h(), "slot", m.slot(),
                    "w", m.w(), "x", m.x(), "y", m.y());
            case PointerMove m -> map("display", m.display(), "h", m.h(), "slot", m.slot(), "w", m.w(), "x", m.x(), "y", m.y());
            case PointerUp m -> map("button", m.button().wire, "display", m.display(), "h", m.h(), "slot", m.slot(),
                    "w", m.w(), "x", m.x(), "y", m.y());
            case Scroll m -> map("display", m.display(), "dx", m.dx(), "dy", m.dy(), "h", m.h(), "slot", m.slot(),
                    "w", m.w(), "x", m.x(), "y", m.y());
            case Click m -> map("button", m.button().wire, "display", m.display(), "h", m.h(), "slot", m.slot(),
                    "w", m.w(), "x", m.x(), "y", m.y());
            case ExitTyping m -> map();
            case ListApps m -> map();
            case AppIcons m -> map("bundles", List.copyOf(m.bundles()), "size", m.size());
            case OpenApp m -> map("bottom", m.bottom(), "bundle", m.bundle(), "slot", m.slot(), "url", m.url());
            case SecureInput m -> map("on", m.on());
            case WorkBegin m -> map("button", m.button().wire, "display", m.display(), "h", m.h(), "slot", m.slot(),
                    "w", m.w(), "x", m.x(), "y", m.y());
            case Cursor m -> map("display", m.display(), "h", m.h(), "slot", m.slot(), "w", m.w(), "x", m.x(), "y", m.y());
            case ListDisplays m -> map();
            case SetOrigins m -> {
                List<Object> list = new ArrayList<>();
                for (SlotOrigin o : m.origins()) list.add(map("slot", o.slot(), "x", o.x(), "y", o.y()));
                yield map("origins", list);
            }
            case Identify m -> {
                List<Object> list = new ArrayList<>();
                for (IdentifyLabel l : m.labels()) list.add(map("display", l.display(), "label", l.label()));
                yield map("labels", list);
            }
            case Displays m -> {
                List<Object> list = new ArrayList<>();
                for (ScreenInfo d : m.displays()) {
                    list.add(map("h", d.h(), "id", d.id(), "main", d.main(), "name", d.name(), "slot", d.slot(), "w", d.w(),
                            "x", d.x(), "y", d.y()));
                }
                yield map("displays", list);
            }
            case Walk m -> map("keys", List.copyOf(m.keys()));
            case Rate m -> map("fps", m.fps(), "surface", m.surface());
            case Apps m -> {
                List<Object> list = new ArrayList<>();
                for (AppInfo a : m.apps()) list.add(map("bundle", a.bundle(), "name", a.name()));
                yield map("apps", list);
            }
            case Icons m -> {
                List<Object> list = new ArrayList<>();
                for (AppIcon i : m.icons()) list.add(map("bundle", i.bundle(), "png", i.png()));
                yield map("icons", list);
            }
            case AppResult m -> map("message", m.message(), "ok", m.ok(), "target", m.target());
            case TypingExit m -> map("reason", m.reason());
            case DragDelta m -> map("dx", m.dx(), "dy", m.dy());
            case DragEnded m -> map("reason", m.reason());
            case Welcome m -> map("accessibility", m.accessibility(), "features", m.features(),
                    "screenRecording", m.screenRecording(), "version", m.version());
            case Windows m -> {
                List<Object> list = new ArrayList<>();
                for (WindowInfo w : m.windows()) {
                    list.add(map("app", w.app(), "h", w.h(), "title", w.title(), "w", w.w(), "windowKey", w.windowKey()));
                }
                yield list;
            }
            case Display m -> map("displayId", m.displayId(), "h", m.h(), "side", m.side(), "slot", m.slot(), "w", m.w(),
                    "x", m.x(), "y", m.y());
            case Ended m -> map("reason", m.reason(), "surface", m.surface());
            case ExitZoom m -> map();
            case HelperError m -> map("code", m.code(), "message", m.message());
            case Frame m -> throw new IllegalStateException("binary message");
            case Patch m -> throw new IllegalStateException("binary message");
        };
    }

    // ---------------------------------------------------------------- decoding

    /** Decodes one message from its type byte and payload bytes (no envelope). */
    public static Message decode(int type, byte[] payload) throws ProtocolException {
        return decode(type, payload, 0, payload.length);
    }

    /** As {@link #decode(int, byte[])} for {@code payload[offset, offset+length)}; FRAME/PATCH pixels are views, not copies. */
    public static Message decode(int type, byte[] payload, int offset, int length) throws ProtocolException {
        switch (type) {
            case FRAME:
                return decodeFrame(payload, offset, length);
            case PATCH:
                return decodePatch(payload, offset, length);
            default:
                break;
        }
        Message m = decodeControl(type, parseJson(payload, offset, length));
        String problem = screensProblem(m);
        if (problem != null) throw bad(problem);
        return m;
    }

    private static Message decodeControl(int type, JsonElement root) throws ProtocolException {
        switch (type) {
            case HELLO: {
                JsonObject o = object(root);
                return new Hello(str(o, "token"), integer(o, "version"), integer(o, "mcPid"), optStrings(o, "features"));
            }
            case LIST_WINDOWS:
                return new ListWindows();
            case CREATE_DISPLAY: {
                JsonObject o = object(root);
                return new CreateDisplay(integer(o, "slot"), integer(o, "w"), integer(o, "h"));
            }
            case REMOVE_DISPLAY:
                return new RemoveDisplay(integer(object(root), "slot"));
            case CAPTURE:
                return decodeCapture(object(root));
            case STOP:
                return new Stop(integer(object(root), "surface"));
            case READY:
                return new Ready(integer(object(root), "surface"));
            case ZOOM_IN: {
                JsonObject o = object(root);
                JsonObject r = object(required(o, "mcWindowFrame"));
                return new ZoomIn(integer(o, "surface"),
                        new WindowRect(integer(r, "x"), integer(r, "y"), integer(r, "w"), integer(r, "h")));
            }
            case ZOOM_OUT:
                return new ZoomOut();
            case REQUEST_PERMISSIONS:
                return new RequestPermissions();
            case POINTER_DOWN: {
                JsonObject o = object(root);
                int slot = integer(o, "slot");
                Integer[] wh = picture(o, slot);
                return new PointerDown(slot, integer(o, "x"), integer(o, "y"), PointerButton.of(str(o, "button")), wh[0], wh[1],
                        optStr(o, "display"));
            }
            case POINTER_MOVE: {
                JsonObject o = object(root);
                int slot = integer(o, "slot");
                Integer[] wh = picture(o, slot);
                return new PointerMove(slot, integer(o, "x"), integer(o, "y"), wh[0], wh[1], optStr(o, "display"));
            }
            case POINTER_UP: {
                JsonObject o = object(root);
                int slot = integer(o, "slot");
                Integer[] wh = picture(o, slot);
                return new PointerUp(slot, integer(o, "x"), integer(o, "y"), PointerButton.of(str(o, "button")), wh[0], wh[1],
                        optStr(o, "display"));
            }
            case SCROLL: {
                JsonObject o = object(root);
                int slot = integer(o, "slot");
                Integer[] wh = picture(o, slot);
                return new Scroll(slot, integer(o, "x"), integer(o, "y"), integer(o, "dx"), integer(o, "dy"), wh[0], wh[1],
                        optStr(o, "display"));
            }
            case CLICK: {
                JsonObject o = object(root);
                int slot = integer(o, "slot");
                Integer[] wh = picture(o, slot);
                return new Click(slot, integer(o, "x"), integer(o, "y"), PointerButton.of(str(o, "button")), wh[0], wh[1],
                        optStr(o, "display"));
            }
            case EXIT_TYPING:
                return new ExitTyping();
            case LIST_APPS:
                return new ListApps();
            case APP_ICONS: {
                JsonObject o = object(root);
                List<String> bundles = strings(o, "bundles");
                int size = integer(o, "size");
                if (size < 16 || size > 256 || bundles.size() > 64 || !bundles.stream().allMatch(HelperProtocol::isBundleId)) {
                    throw bad("APP_ICONS needs 16...256 px and at most 64 bundle ids");
                }
                return new AppIcons(bundles, size);
            }
            case OPEN_APP: {
                JsonObject o = object(root);
                OpenApp m = new OpenApp(integer(o, "slot"), optStr(o, "bundle"), optStr(o, "url"), optInteger(o, "bottom"));
                String problem = openAppProblem(m);
                if (problem != null) throw bad(problem);
                return m;
            }
            case APPS: {
                JsonObject o = object(root);
                JsonElement arr = required(o, "apps");
                if (!arr.isJsonArray()) throw bad("apps must be an array");
                List<AppInfo> list = new ArrayList<>();
                for (JsonElement e : arr.getAsJsonArray()) {
                    JsonObject a = object(e);
                    list.add(new AppInfo(str(a, "bundle"), str(a, "name")));
                }
                return new Apps(Collections.unmodifiableList(list));
            }
            case ICONS: {
                JsonObject o = object(root);
                JsonElement arr = required(o, "icons");
                if (!arr.isJsonArray()) throw bad("icons must be an array");
                List<AppIcon> list = new ArrayList<>();
                for (JsonElement e : arr.getAsJsonArray()) {
                    JsonObject a = object(e);
                    list.add(new AppIcon(str(a, "bundle"), str(a, "png")));
                }
                return new Icons(Collections.unmodifiableList(list));
            }
            case APP_RESULT: {
                JsonObject o = object(root);
                return new AppResult(str(o, "target"), bool(o, "ok"), str(o, "message"));
            }
            case SECURE_INPUT:
                return new SecureInput(bool(object(root), "on"));
            case WORK_BEGIN: {
                JsonObject o = object(root);
                int slot = integer(o, "slot");
                Integer[] wh = picture(o, slot);
                return new WorkBegin(slot, integer(o, "x"), integer(o, "y"), PointerButton.of(str(o, "button")), wh[0], wh[1],
                        optStr(o, "display"));
            }
            case WALK:
                return new Walk(strings(object(root), "keys"));
            case CURSOR: {
                JsonObject o = object(root);
                return new Cursor(integer(o, "slot"), integer(o, "x"), integer(o, "y"), integer(o, "w"), integer(o, "h"),
                        optStr(o, "display"));
            }
            case LIST_DISPLAYS:
                return new ListDisplays();
            case SET_ORIGINS: {
                List<SlotOrigin> list = new ArrayList<>();
                for (JsonElement e : array(object(root), "origins")) {
                    JsonObject s = object(e);
                    list.add(new SlotOrigin(integer(s, "slot"), integer(s, "x"), integer(s, "y")));
                }
                return new SetOrigins(list);
            }
            case IDENTIFY: {
                List<IdentifyLabel> list = new ArrayList<>();
                for (JsonElement e : array(object(root), "labels")) {
                    JsonObject l = object(e);
                    list.add(new IdentifyLabel(str(l, "display"), str(l, "label")));
                }
                return new Identify(list);
            }
            case DISPLAYS: {
                List<ScreenInfo> list = new ArrayList<>();
                for (JsonElement e : array(object(root), "displays")) {
                    JsonObject d = object(e);
                    list.add(new ScreenInfo(str(d, "id"), integer(d, "slot"), bool(d, "main"), optStr(d, "name"),
                            integer(d, "x"), integer(d, "y"), integer(d, "w"), integer(d, "h")));
                }
                return new Displays(list);
            }
            case RATE: {
                JsonObject o = object(root);
                int fps = integer(o, "fps");
                if (fps < 1 || fps > 60) throw bad("RATE fps must be 1...60");
                return new Rate(integer(o, "surface"), fps);
            }
            case TYPING_EXIT:
                return new TypingExit(str(object(root), "reason"));
            case DRAG_DELTA: {
                JsonObject o = object(root);
                return new DragDelta(integer(o, "dx"), integer(o, "dy"));
            }
            case DRAG_ENDED:
                return new DragEnded(str(object(root), "reason"));
            case WELCOME: {
                JsonObject o = object(root);
                return new Welcome(integer(o, "version"), bool(o, "screenRecording"), bool(o, "accessibility"),
                        optStrings(o, "features"));
            }
            case WINDOWS: {
                if (!root.isJsonArray()) throw bad("expected JSON array");
                JsonArray arr = root.getAsJsonArray();
                List<WindowInfo> list = new ArrayList<>(arr.size());
                for (JsonElement e : arr) {
                    JsonObject o = object(e);
                    list.add(new WindowInfo(str(o, "windowKey"), str(o, "app"), str(o, "title"), integer(o, "w"),
                            integer(o, "h")));
                }
                return new Windows(Collections.unmodifiableList(list));
            }
            case DISPLAY: {
                JsonObject o = object(root);
                long id = number(o, "displayId").longValueExact0();
                if (id < 0 || id > 0xFFFFFFFFL) throw bad("displayId out of range");
                return new Display(integer(o, "slot"), id, integer(o, "w"), integer(o, "h"), optInteger(o, "x"), optInteger(o, "y"),
                        optStr(o, "side"));
            }
            case ENDED: {
                JsonObject o = object(root);
                return new Ended(integer(o, "surface"), str(o, "reason"));
            }
            case EXIT_ZOOM:
                return new ExitZoom();
            case ERROR: {
                JsonObject o = object(root);
                return new HelperError(str(o, "code"), str(o, "message"));
            }
            default:
                throw new ProtocolException("unknown message type " + type);
        }
    }

    /** {w, h} of a pointer message: required (positive) for slot 0, the main display; read if present otherwise. */
    private static Integer[] picture(JsonObject o, int slot) throws ProtocolException {
        Integer w = optInteger(o, "w");
        Integer h = optInteger(o, "h");
        if (slot == MAIN_SLOT && (w == null || h == null || w <= 0 || h <= 0)) {
            throw bad("slot 0 (the main display) needs w and h");
        }
        return new Integer[] {w, h};
    }

    /**
     * Why this message breaks a "screens" rule (null = fine), as the helper checks it: SET_ORIGINS 1..4 entries, slots
     * 1..4, each once; IDENTIFY 1..16 labels of 1..2 characters; DISPLAYS at most 16, slot 0..4, w and h positive; every
     * display id 1..64 characters; {@code display} on CAPTURE only with kind main, on pointer / scroll / cursor only on slot 0.
     */
    public static @Nullable String screensProblem(Message m) {
        return switch (m) {
            case SetOrigins s -> {
                if (s.origins().isEmpty() || s.origins().size() > 4) yield "SET_ORIGINS needs 1...4 origins";
                boolean[] seen = new boolean[5];
                for (SlotOrigin o : s.origins()) {
                    if (o.slot() < 1 || o.slot() > 4) yield "SET_ORIGINS slot must be 1...4";
                    if (seen[o.slot()]) yield "SET_ORIGINS slots must be unique";
                    seen[o.slot()] = true;
                }
                yield null;
            }
            case Identify i -> {
                if (i.labels().isEmpty() || i.labels().size() > MAX_IDENTIFY_LABELS) yield "IDENTIFY needs 1...16 labels";
                for (IdentifyLabel l : i.labels()) {
                    if (l.label().isEmpty() || l.label().length() > 2) yield "IDENTIFY label must be 1...2 characters";
                    if (!isDisplayId(l.display())) yield "IDENTIFY display must be 1...64 characters";
                }
                yield null;
            }
            case Displays d -> {
                if (d.displays().size() > MAX_DISPLAYS) yield "DISPLAYS holds at most 16 screens";
                for (ScreenInfo s : d.displays()) {
                    if (s.slot() < 0 || s.slot() > 4) yield "DISPLAYS slot must be 0...4";
                    if (s.w() <= 0 || s.h() <= 0) yield "DISPLAYS w and h must be positive";
                    if (!isDisplayId(s.id())) yield "DISPLAYS id must be 1...64 characters";
                }
                yield null;
            }
            case Capture c -> c.display() == null ? null
                    : c.kind() != CaptureKind.MAIN ? "CAPTURE display needs kind main" : displayProblem(c.display());
            case PointerDown p -> slotZeroDisplay(p.slot(), p.display());
            case PointerMove p -> slotZeroDisplay(p.slot(), p.display());
            case PointerUp p -> slotZeroDisplay(p.slot(), p.display());
            case Scroll p -> slotZeroDisplay(p.slot(), p.display());
            case Click p -> slotZeroDisplay(p.slot(), p.display());
            case WorkBegin p -> slotZeroDisplay(p.slot(), p.display());
            case Cursor p -> slotZeroDisplay(p.slot(), p.display());
            default -> null;
        };
    }

    private static @Nullable String slotZeroDisplay(int slot, @Nullable String display) {
        if (display == null) return null;
        return slot != MAIN_SLOT ? "display needs slot 0" : displayProblem(display);
    }

    private static @Nullable String displayProblem(String display) {
        return isDisplayId(display) ? null : "display must be 1...64 characters";
    }

    private static boolean isDisplayId(@Nullable String id) {
        return id != null && !id.isEmpty() && id.length() <= MAX_DISPLAY_ID;
    }

    private static JsonArray array(JsonObject o, String key) throws ProtocolException {
        JsonElement arr = required(o, key);
        if (!arr.isJsonArray()) throw bad(key + " must be an array");
        return arr.getAsJsonArray();
    }

    private static @Nullable List<String> optStrings(JsonObject o, String key) throws ProtocolException {
        JsonElement e = o.get(key);
        return e == null || e.isJsonNull() ? null : strings(o, key);
    }

    private static List<String> strings(JsonObject o, String key) throws ProtocolException {
        JsonElement arr = required(o, key);
        if (!arr.isJsonArray()) throw bad(key + " must be an array");
        List<String> list = new ArrayList<>();
        for (JsonElement e : arr.getAsJsonArray()) {
            if (!e.isJsonPrimitive() || !e.getAsJsonPrimitive().isString()) throw bad(key + " must hold strings");
            list.add(e.getAsString());
        }
        return Collections.unmodifiableList(list);
    }

    /** A plausible bundle id (letters, digits, dots, dashes), as the helper accepts. */
    public static boolean isBundleId(String s) {
        return s != null && s.matches("[A-Za-z0-9.-]{3,200}");
    }

    /** Why the helper would refuse this OPEN_APP (null = fine): slot 1..4, exactly one of bundle / url, the url https. */
    public static String openAppProblem(OpenApp m) {
        if (m.slot() < 1 || m.slot() > 4) return "OPEN_APP slot must be 1...4";
        if (m.bottom() != null && (m.bottom() < 0 || m.bottom() > 4096)) return "OPEN_APP bottom must be 0...4096 pixels";
        if ((m.bundle() == null) == (m.url() == null)) return "OPEN_APP needs exactly one of bundle and url";
        if (m.bundle() != null) return isBundleId(m.bundle()) ? null : "OPEN_APP bundle is not a bundle id";
        try {
            java.net.URI u = new java.net.URI(m.url());
            if (m.url().length() > 2048 || !"https".equalsIgnoreCase(u.getScheme()) || u.getHost() == null || u.getHost().isEmpty()) {
                return "OPEN_APP url must be https";
            }
        } catch (java.net.URISyntaxException e) {
            return "OPEN_APP url must be https";
        }
        return null;
    }

    private static Capture decodeCapture(JsonObject o) throws ProtocolException {
        CaptureKind kind = CaptureKind.of(str(o, "kind"));
        Integer slot = optInteger(o, "slot");
        String windowKey = optStr(o, "windowKey");
        String display = optStr(o, "display");
        switch (kind) {
            case DISPLAY -> {
                if (slot == null) throw bad("display capture needs slot");
            }
            case WINDOW -> {
                if (windowKey == null || WindowKey.parse(windowKey) == null) throw bad("window capture needs windowKey");
            }
            case MAIN -> {
                // needs neither slot nor windowKey
            }
        }
        return new Capture(integer(o, "surface"), kind, slot, windowKey, integer(o, "maxW"), integer(o, "fps"), display);
    }

    private static Frame decodeFrame(byte[] b, int off, int len) throws ProtocolException {
        if (len < IMAGE_HEADER) throw bad("frame size");
        ByteBuffer h = ByteBuffer.wrap(b, off, len);
        int surface = h.getInt();
        long generation = h.getLong();
        int width = h.getInt();
        int height = h.getInt();
        if (width < 0 || height < 0 || (long) width * height * 4 != len - IMAGE_HEADER) throw bad("frame size");
        return new Frame(surface, generation, width, height, pixels(b, off + IMAGE_HEADER, len - IMAGE_HEADER));
    }

    private static Patch decodePatch(byte[] b, int off, int len) throws ProtocolException {
        if (len < PATCH_HEADER) throw bad("patch header");
        ByteBuffer h = ByteBuffer.wrap(b, off, len);
        int surface = h.getInt();
        long generation = h.getLong();
        int width = h.getInt();
        int height = h.getInt();
        long count = h.getInt() & 0xFFFFFFFFL;
        if (width < 0 || height < 0 || count > MAX_PATCH_RECTS) throw bad("patch header");
        if (len < PATCH_HEADER + count * RECT_BYTES) throw bad("patch rects");
        List<PixelRect> rects = new ArrayList<>((int) count);
        for (int i = 0; i < count; i++) {
            int x = h.getInt(), y = h.getInt(), w = h.getInt(), rh = h.getInt();
            rects.add(new PixelRect(x, y, w, rh));
        }
        int pixelOffset = PATCH_HEADER + (int) count * RECT_BYTES;
        checkRects(rects, width, height, len - pixelOffset);
        return new Patch(surface, generation, width, height, Collections.unmodifiableList(rects),
                pixels(b, off + pixelOffset, len - pixelOffset));
    }

    private static ByteBuffer pixels(byte[] b, int off, int len) {
        return ByteBuffer.wrap(b, off, len).slice().asReadOnlyBuffer();
    }

    // ---------------------------------------------------------------- JSON helpers

    private static ProtocolException bad(String what) {
        return new ProtocolException("bad payload: " + what);
    }

    private static JsonElement parseJson(byte[] b, int off, int len) throws ProtocolException {
        try {
            return JsonParser.parseString(new String(b, off, len, StandardCharsets.UTF_8));
        } catch (JsonParseException | IllegalStateException e) {
            throw bad("invalid JSON (" + e.getMessage() + ")");
        }
    }

    private static JsonObject object(JsonElement e) throws ProtocolException {
        if (e == null || !e.isJsonObject()) throw bad("expected JSON object");
        return e.getAsJsonObject();
    }

    private static JsonElement required(JsonObject o, String key) throws ProtocolException {
        JsonElement e = o.get(key);
        if (e == null || e.isJsonNull()) throw bad("missing " + key);
        return e;
    }

    private static String str(JsonObject o, String key) throws ProtocolException {
        JsonElement e = required(o, key);
        if (!e.isJsonPrimitive() || !e.getAsJsonPrimitive().isString()) throw bad(key + " must be a string");
        return e.getAsString();
    }

    private static String optStr(JsonObject o, String key) throws ProtocolException {
        JsonElement e = o.get(key);
        return e == null || e.isJsonNull() ? null : str(o, key);
    }

    private static boolean bool(JsonObject o, String key) throws ProtocolException {
        JsonElement e = required(o, key);
        if (!e.isJsonPrimitive() || !e.getAsJsonPrimitive().isBoolean()) throw bad(key + " must be a boolean");
        return e.getAsBoolean();
    }

    /** A JSON integer (no fraction) as BigDecimal wrapper. */
    private record Num(BigDecimal value, String key) {
        int intValueExact0() throws ProtocolException {
            try {
                return value.intValueExact();
            } catch (ArithmeticException e) {
                throw bad(key + " must be a 32-bit integer");
            }
        }

        long longValueExact0() throws ProtocolException {
            try {
                return value.longValueExact();
            } catch (ArithmeticException e) {
                throw bad(key + " must be an integer");
            }
        }
    }

    private static Num number(JsonObject o, String key) throws ProtocolException {
        JsonElement e = required(o, key);
        if (!e.isJsonPrimitive()) throw bad(key + " must be a number");
        JsonPrimitive p = e.getAsJsonPrimitive();
        if (!p.isNumber()) throw bad(key + " must be a number");
        return new Num(p.getAsBigDecimal(), key);
    }

    private static int integer(JsonObject o, String key) throws ProtocolException {
        return number(o, key).intValueExact0();
    }

    private static Integer optInteger(JsonObject o, String key) throws ProtocolException {
        JsonElement e = o.get(key);
        return e == null || e.isJsonNull() ? null : integer(o, key);
    }

    /** Compact JSON writer matching Swift's {@code JSONEncoder} with {@code .sortedKeys, .withoutEscapingSlashes}. */
    private static final class Json {
        static String write(Object value) {
            StringBuilder sb = new StringBuilder();
            write(sb, value);
            return sb.toString();
        }

        @SuppressWarnings("unchecked")
        private static void write(StringBuilder sb, Object v) {
            if (v instanceof Map<?, ?> m) {
                sb.append('{');
                boolean first = true;
                for (Map.Entry<String, Object> e : ((Map<String, Object>) m).entrySet()) { // TreeMap: sorted keys
                    if (!first) sb.append(',');
                    first = false;
                    string(sb, e.getKey());
                    sb.append(':');
                    write(sb, e.getValue());
                }
                sb.append('}');
            } else if (v instanceof List<?> l) {
                sb.append('[');
                for (int i = 0; i < l.size(); i++) {
                    if (i > 0) sb.append(',');
                    write(sb, l.get(i));
                }
                sb.append(']');
            } else if (v instanceof String s) {
                string(sb, s);
            } else {
                sb.append(v); // Integer, Long, Boolean
            }
        }

        private static void string(StringBuilder sb, String s) {
            sb.append('"');
            for (int i = 0; i < s.length(); i++) {
                char c = s.charAt(i);
                switch (c) {
                    case '"' -> sb.append("\\\"");
                    case '\\' -> sb.append("\\\\");
                    case '\n' -> sb.append("\\n");
                    case '\r' -> sb.append("\\r");
                    case '\t' -> sb.append("\\t");
                    case '\b' -> sb.append("\\b");
                    case '\f' -> sb.append("\\f");
                    default -> {
                        if (c < 0x20) sb.append(String.format("\\u%04x", (int) c));
                        else sb.append(c);
                    }
                }
            }
            sb.append('"');
        }
    }

    // ---------------------------------------------------------------- streaming reader

    /**
     * Streaming envelope decoder. {@link #push} accepts arbitrary byte chunks (never throws); {@link #next} yields
     * complete messages in order. A bad length or type fails as soon as that header byte arrives, before the body is
     * buffered, so an oversize frame is never allocated. After a failure {@link #next} first drains the messages that
     * were already complete, then throws {@link ProtocolException} on every call (the stream cannot be resynchronised;
     * close the connection).
     *
     * <p>Each frame's body is received into one exact-size array and FRAME/PATCH pixels are exposed as a read-only
     * view of it: bytes are copied once from the socket buffer and never again.
     */
    public static final class FrameReader {
        private final int maxLength;
        private final ArrayDeque<Message> ready = new ArrayDeque<>();
        private ProtocolException failure;

        private final byte[] lengthBytes = new byte[4];
        private int lengthGot;
        private long length = -1; // 1 + payload bytes, once known
        private int type = -1;
        private byte[] payload;
        private int payloadLength;
        private int payloadGot;
        /**
         * The newest FRAME / PATCH payloads handed out and not yet {@link #release released} (bounded: a caller that never
         * releases just leaves them to the GC, as before), and released ones to reuse.
         */
        private final ArrayDeque<Lent> lent = new ArrayDeque<>();
        private final ArrayDeque<byte[]> spare = new ArrayDeque<>();
        private static final int SPARES = 3;
        private static final int LENT = 4;

        private record Lent(Message message, byte[] payload) {}

        public FrameReader(int maxLength) {
            this.maxLength = maxLength;
        }

        /** Reader for helper -> mod traffic (64 MiB frames). */
        public static FrameReader forHelperMessages() {
            return new FrameReader(MAX_FRAME_LENGTH);
        }

        /** Reader for mod -> helper traffic as the helper enforces it (64 KiB). */
        public static FrameReader forModMessages() {
            return new FrameReader(MAX_CONTROL_LENGTH);
        }

        public void push(byte[] data, int offset, int length) {
            if (offset < 0 || length < 0 || offset + length > data.length) throw new IndexOutOfBoundsException();
            int pos = offset;
            int end = offset + length;
            try {
                while (pos < end && failure == null) pos = consume(data, pos, end);
            } catch (ProtocolException e) {
                failure = e;
            }
        }

        public void push(ByteBuffer data) {
            if (data.hasArray()) {
                push(data.array(), data.arrayOffset() + data.position(), data.remaining());
                data.position(data.limit());
            } else {
                byte[] tmp = new byte[data.remaining()];
                data.get(tmp);
                push(tmp, 0, tmp.length);
            }
        }

        /**
         * The caller is done with {@code m} (a FRAME / PATCH whose pixels it no longer reads): its payload array is reused
         * for a later picture, so a steady stream of frames allocates nothing. Without it each picture gets a fresh array.
         */
        public void release(Message m) {
            for (java.util.Iterator<Lent> it = lent.iterator(); it.hasNext(); ) {
                Lent l = it.next();
                if (l.message() == m) {
                    it.remove();
                    if (spare.size() >= SPARES) spare.pollFirst();
                    spare.addLast(l.payload());
                    return;
                }
            }
        }

        /** Payloads lent out and tracked (tests). */
        int lent() {
            return lent.size();
        }

        /** A payload array of at least {@code n} bytes: a spare one that fits, else a new one. */
        private byte[] payloadArray(int n, boolean picture) {
            if (picture) {
                for (java.util.Iterator<byte[]> it = spare.iterator(); it.hasNext(); ) {
                    byte[] b = it.next();
                    if (b.length >= n && b.length <= n + (n >> 2)) {
                        it.remove();
                        return b;
                    }
                }
            }
            return new byte[n];
        }

        /** Spare arrays held (tests). */
        int spares() {
            return spare.size();
        }

        /** The next complete message, or null when more bytes are needed. */
        public Message next() throws ProtocolException {
            Message m = ready.poll();
            if (m != null) return m;
            if (failure != null) throw failure;
            return null;
        }

        private int consume(byte[] data, int pos, int end) throws ProtocolException {
            if (length < 0) {
                while (lengthGot < 4 && pos < end) lengthBytes[lengthGot++] = data[pos++];
                if (lengthGot < 4) return pos;
                long l = ((lengthBytes[0] & 0xFFL) << 24) | ((lengthBytes[1] & 0xFF) << 16)
                        | ((lengthBytes[2] & 0xFF) << 8) | (lengthBytes[3] & 0xFF);
                if (l < 1) throw new ProtocolException("empty frame");
                if (l > maxLength) throw new ProtocolException("frame too large: " + l + " > " + maxLength);
                length = l;
                return pos;
            }
            if (type < 0) {
                int t = data[pos++] & 0xFF;
                if (!isKnownType(t)) throw new ProtocolException("unknown message type " + t);
                type = t;
                payloadLength = (int) length - 1;
                payload = payloadArray(payloadLength, t == FRAME || t == PATCH);
                payloadGot = 0;
            }
            int n = Math.min(end - pos, payloadLength - payloadGot);
            System.arraycopy(data, pos, payload, payloadGot, n);
            payloadGot += n;
            pos += n;
            if (payloadGot == payloadLength) {
                Message m = decode(type, payload, 0, payloadLength);
                if (m instanceof Frame || m instanceof Patch) {
                    if (lent.size() >= LENT) lent.pollFirst(); // never released: forget it (no leak)
                    lent.addLast(new Lent(m, payload));
                }
                ready.add(m);
                lengthGot = 0;
                length = -1;
                type = -1;
                payload = null;
            }
            return pos;
        }
    }

    private static boolean isKnownType(int t) {
        return (t >= HELLO && t <= EXIT_TYPING) || (t >= LIST_APPS && t <= IDENTIFY) || (t >= WELCOME && t <= DISPLAYS);
    }
}
