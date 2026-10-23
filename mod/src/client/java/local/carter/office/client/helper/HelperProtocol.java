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
    // Helper -> mod
    public static final int WELCOME = 64;
    public static final int WINDOWS = 65;
    public static final int DISPLAY = 66;
    public static final int FRAME = 67;
    public static final int PATCH = 68;
    public static final int ENDED = 69;
    public static final int EXIT_ZOOM = 70;
    public static final int ERROR = 71;

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
            ZoomIn, ZoomOut, RequestPermissions, Welcome, Windows, Display, Frame, Patch, Ended, ExitZoom, HelperError {
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
                case Welcome m -> WELCOME;
                case Windows m -> WINDOWS;
                case Display m -> DISPLAY;
                case Frame m -> FRAME;
                case Patch m -> PATCH;
                case Ended m -> ENDED;
                case ExitZoom m -> EXIT_ZOOM;
                case HelperError m -> ERROR;
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
    public record Hello(String token, int version, int mcPid) implements Message {}

    public record ListWindows() implements Message {}

    public record CreateDisplay(int slot, int w, int h) implements Message {}

    public record RemoveDisplay(int slot) implements Message {}

    /** {@code slot} is required for DISPLAY captures, {@code windowKey} for WINDOW captures; the other is null (both for MAIN). */
    public record Capture(int surface, CaptureKind kind, Integer slot, String windowKey, int maxW, int fps)
            implements Message {}

    public record Stop(int surface) implements Message {}

    public record Ready(int surface) implements Message {}

    public record ZoomIn(int surface, WindowRect mcWindowFrame) implements Message {}

    public record ZoomOut() implements Message {}

    public record RequestPermissions() implements Message {}

    // Helper -> mod
    /** The handshake reply; sent again whenever a permission changes while connected. */
    public record Welcome(int version, boolean screenRecording, boolean accessibility) implements Message {}

    public record Windows(List<WindowInfo> windows) implements Message {}

    public record Display(int slot, long displayId, int w, int h) implements Message {}

    /** {@code rgba} is a read-only view of width*height*4 bytes (rows top to bottom); position 0, limit = size. */
    public record Frame(int surface, long generation, int width, int height, ByteBuffer rgba) implements Message {}

    /** {@code rgba} holds each rectangle's RGBA rows, concatenated in rectangle order. */
    public record Patch(int surface, long generation, int width, int height, List<PixelRect> rects, ByteBuffer rgba)
            implements Message {}

    public record Ended(int surface, String reason) implements Message {}

    public record ExitZoom() implements Message {}

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
            case Hello m -> map("mcPid", m.mcPid(), "token", m.token(), "version", m.version());
            case ListWindows m -> map();
            case CreateDisplay m -> map("h", m.h(), "slot", m.slot(), "w", m.w());
            case RemoveDisplay m -> map("slot", m.slot());
            case Capture m -> map("fps", m.fps(), "kind", m.kind().wire, "maxW", m.maxW(), "slot", m.slot(),
                    "surface", m.surface(), "windowKey", m.windowKey());
            case Stop m -> map("surface", m.surface());
            case Ready m -> map("surface", m.surface());
            case ZoomIn m -> map("mcWindowFrame",
                    map("h", m.mcWindowFrame().h(), "w", m.mcWindowFrame().w(), "x", m.mcWindowFrame().x(),
                            "y", m.mcWindowFrame().y()),
                    "surface", m.surface());
            case ZoomOut m -> map();
            case RequestPermissions m -> map();
            case Welcome m -> map("accessibility", m.accessibility(), "screenRecording", m.screenRecording(),
                    "version", m.version());
            case Windows m -> {
                List<Object> list = new ArrayList<>();
                for (WindowInfo w : m.windows()) {
                    list.add(map("app", w.app(), "h", w.h(), "title", w.title(), "w", w.w(), "windowKey", w.windowKey()));
                }
                yield list;
            }
            case Display m -> map("displayId", m.displayId(), "h", m.h(), "slot", m.slot(), "w", m.w());
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
        JsonElement root = parseJson(payload, offset, length);
        switch (type) {
            case HELLO: {
                JsonObject o = object(root);
                return new Hello(str(o, "token"), integer(o, "version"), integer(o, "mcPid"));
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
            case WELCOME: {
                JsonObject o = object(root);
                return new Welcome(integer(o, "version"), bool(o, "screenRecording"), bool(o, "accessibility"));
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
                return new Display(integer(o, "slot"), id, integer(o, "w"), integer(o, "h"));
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

    private static Capture decodeCapture(JsonObject o) throws ProtocolException {
        CaptureKind kind = CaptureKind.of(str(o, "kind"));
        Integer slot = optInteger(o, "slot");
        String windowKey = optStr(o, "windowKey");
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
        return new Capture(integer(o, "surface"), kind, slot, windowKey, integer(o, "maxW"), integer(o, "fps"));
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
        private int payloadGot;

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
                payload = new byte[(int) length - 1];
                payloadGot = 0;
            }
            int n = Math.min(end - pos, payload.length - payloadGot);
            System.arraycopy(data, pos, payload, payloadGot, n);
            payloadGot += n;
            pos += n;
            if (payloadGot == payload.length) {
                Message m = decode(type, payload);
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
        return (t >= HELLO && t <= REQUEST_PERMISSIONS) || (t >= WELCOME && t <= ERROR);
    }
}
