package local.carter.office.client;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;
import local.carter.office.client.helper.HelperProtocol;
import local.carter.office.client.helper.HelperProtocol.*;
import org.junit.jupiter.api.Test;

/** Decodes the Swift-generated golden fixtures (helper-mac/Tests/fixtures) and re-encodes them byte-exactly. */
class HelperProtocolTest {
    private static final Path FIXTURES = Paths.get("helper-mac", "Tests", "fixtures");
    private static final String TOKEN = "00112233445566778899aabbccddeeff00112233445566778899aabbccddeeff";

    private static byte[] fixture(String name) throws IOException {
        return Files.readAllBytes(FIXTURES.resolve(name + ".bin"));
    }

    private static ByteBuffer px(int... bytes) {
        byte[] b = new byte[bytes.length];
        for (int i = 0; i < b.length; i++) b[i] = (byte) bytes[i];
        return ByteBuffer.wrap(b);
    }

    /** Decodes the fixture through a streaming reader and re-encodes it. */
    private static void check(String name, Message expected) throws IOException {
        byte[] bytes = fixture(name);
        FrameReader reader = FrameReader.forHelperMessages();
        reader.push(bytes, 0, bytes.length);
        Message decoded = reader.next();
        assertEquals(expected, decoded, name);
        assertNull(reader.next(), name + " leaves nothing behind");
        assertArrayEquals(bytes, HelperProtocol.encode(expected), name + " re-encodes byte-identically");
    }

    private static void checkModMessage(String name, Message expected) throws IOException {
        check(name, expected);
        // Mod -> helper messages must also fit the helper's 64 KiB control reader.
        byte[] bytes = fixture(name);
        FrameReader helperSide = FrameReader.forModMessages();
        helperSide.push(bytes, 0, bytes.length);
        assertEquals(expected, helperSide.next());
    }

    @Test
    void typeConstantsMatchSwift() {
        assertEquals(1, HelperProtocol.HELLO);
        assertEquals(2, HelperProtocol.LIST_WINDOWS);
        assertEquals(3, HelperProtocol.CREATE_DISPLAY);
        assertEquals(4, HelperProtocol.REMOVE_DISPLAY);
        assertEquals(5, HelperProtocol.CAPTURE);
        assertEquals(6, HelperProtocol.STOP);
        assertEquals(7, HelperProtocol.READY);
        assertEquals(8, HelperProtocol.ZOOM_IN);
        assertEquals(9, HelperProtocol.ZOOM_OUT);
        assertEquals(64, HelperProtocol.WELCOME);
        assertEquals(65, HelperProtocol.WINDOWS);
        assertEquals(66, HelperProtocol.DISPLAY);
        assertEquals(67, HelperProtocol.FRAME);
        assertEquals(68, HelperProtocol.PATCH);
        assertEquals(69, HelperProtocol.ENDED);
        assertEquals(70, HelperProtocol.EXIT_ZOOM);
        assertEquals(71, HelperProtocol.ERROR);
        assertEquals(1, HelperProtocol.VERSION);
        assertEquals(64 * 1024 * 1024, HelperProtocol.MAX_FRAME_LENGTH);
        assertEquals(64 * 1024, HelperProtocol.MAX_CONTROL_LENGTH);
        assertEquals(32, HelperProtocol.MAX_PATCH_RECTS);
    }

    // ---- mod -> helper fixtures ----

    @Test
    void hello() throws IOException {
        checkModMessage("hello", new Hello(TOKEN, 1, 4242));
    }

    @Test
    void listWindows() throws IOException {
        checkModMessage("list_windows", new ListWindows());
    }

    @Test
    void createDisplay() throws IOException {
        checkModMessage("create_display", new CreateDisplay(1, 1920, 1080));
    }

    @Test
    void removeDisplay() throws IOException {
        checkModMessage("remove_display", new RemoveDisplay(1));
    }

    @Test
    void captureDisplay() throws IOException {
        checkModMessage("capture_display", new Capture(7, CaptureKind.DISPLAY, 1, null, 1280, 5));
    }

    @Test
    void captureWindow() throws IOException {
        checkModMessage("capture_window", new Capture(8, CaptureKind.WINDOW, null, "com.apple.Notes|4321", 1280, 5));
    }

    @Test
    void captureMain() throws IOException {
        checkModMessage("capture_main", new Capture(9, CaptureKind.MAIN, null, null, 1920, 5));
    }

    @Test
    void stopReadyZoom() throws IOException {
        checkModMessage("stop", new Stop(7));
        checkModMessage("ready", new Ready(7));
        checkModMessage("zoom_in", new ZoomIn(7, new WindowRect(0, 25, 1440, 875)));
        checkModMessage("zoom_out", new ZoomOut());
    }

    @Test
    void requestPermissions() throws IOException {
        checkModMessage("request_permissions", new RequestPermissions());
        assertEquals(10, new RequestPermissions().type());
    }

    // ---- helper -> mod fixtures ----

    @Test
    void welcome() throws IOException {
        check("welcome", new Welcome(1, true, false));
    }

    @Test
    void windows() throws IOException {
        Message m = new Windows(List.of(
                new WindowInfo("com.apple.Notes|4321", "Notes", "Groceries — List", 800, 600),
                new WindowInfo("com.apple.Safari|99", "Safari", "", 1200, 900)));
        check("windows", m);
    }

    @Test
    void display() throws IOException {
        check("display", new Display(1, 5L, 1920, 1080));
    }

    @Test
    void frame() throws IOException {
        Frame expected = new Frame(7, 3L, 2, 2, px(
                0xff, 0x00, 0x00, 0xff, 0x00, 0xff, 0x00, 0xff,
                0x00, 0x00, 0xff, 0xff, 0xff, 0xff, 0xff, 0x80));
        check("frame", expected);
    }

    @Test
    void frameExposesReadOnlyPixelView() throws IOException {
        byte[] bytes = fixture("frame");
        FrameReader reader = FrameReader.forHelperMessages();
        reader.push(bytes, 0, bytes.length);
        Frame f = (Frame) reader.next();
        assertEquals(16, f.rgba().remaining());
        assertTrue(f.rgba().isReadOnly());
        assertEquals((byte) 0x80, f.rgba().get(15));
        assertEquals(0, f.rgba().position());
    }

    @Test
    void patch() throws IOException {
        Patch expected = new Patch(7, 3L, 4, 2,
                List.of(new PixelRect(0, 0, 1, 1), new PixelRect(2, 1, 2, 1)),
                px(0x0a, 0x14, 0x1e, 0xff, 0x28, 0x32, 0x3c, 0xff, 0x46, 0x50, 0x5a, 0xff));
        check("patch", expected);
    }

    @Test
    void endedExitZoomError() throws IOException {
        check("ended", new Ended(8, "window closed"));
        check("exit_zoom", new ExitZoom());
        check("error", new HelperError("bad_slot", "slot must be 1...4"));
    }

    @Test
    void busyErrorIsRecognisable() throws IOException {
        byte[] bytes = HelperProtocol.encode(new HelperError(HelperError.BUSY, "another client is connected"));
        FrameReader reader = FrameReader.forHelperMessages();
        reader.push(bytes, 0, bytes.length);
        HelperError e = (HelperError) reader.next();
        assertEquals("busy", e.code());
        assertTrue(e.isBusy());
        assertFalse(new HelperError("bad_slot", "x").isBusy());
        // Raw JSON as Swift writes it.
        byte[] raw = envelope(HelperProtocol.ERROR, "{\"code\":\"busy\",\"message\":\"already connected\"}");
        FrameReader r2 = FrameReader.forHelperMessages();
        r2.push(raw, 0, raw.length);
        assertTrue(((HelperError) r2.next()).isBusy());
    }

    @Test
    void everyFixtureDecodesAndReencodes() throws IOException {
        List<byte[]> all = allFixtures();
        assertEquals(20, all.size()); // one per message type, plus a second and third CAPTURE (window, main)
        for (byte[] bytes : all) {
            FrameReader reader = FrameReader.forHelperMessages();
            reader.push(bytes, 0, bytes.length);
            Message m = reader.next();
            assertArrayEquals(bytes, HelperProtocol.encode(m));
        }
    }

    // ---- streaming ----

    private static List<byte[]> allFixtures() throws IOException {
        List<Path> paths = new ArrayList<>();
        try (Stream<Path> s = Files.list(FIXTURES)) {
            s.filter(p -> p.toString().endsWith(".bin")).sorted().forEach(paths::add);
        }
        List<byte[]> out = new ArrayList<>();
        for (Path p : paths) out.add(Files.readAllBytes(p));
        return out;
    }

    @Test
    void oneByteAtATime() throws IOException {
        List<byte[]> fixtures = allFixtures();
        FrameReader reader = FrameReader.forHelperMessages();
        List<Message> got = new ArrayList<>();
        for (byte[] f : fixtures) {
            for (int i = 0; i < f.length; i++) {
                reader.push(new byte[] {f[i]}, 0, 1);
                Message m = reader.next();
                if (i < f.length - 1) assertNull(m, "no message before the last byte");
                else got.add(m);
            }
        }
        assertEquals(fixtures.size(), got.size());
        for (int i = 0; i < got.size(); i++) assertArrayEquals(fixtures.get(i), HelperProtocol.encode(got.get(i)));
    }

    @Test
    void multipleMessagesInOneChunk() throws IOException {
        List<byte[]> fixtures = allFixtures();
        int total = fixtures.stream().mapToInt(b -> b.length).sum();
        ByteBuffer all = ByteBuffer.allocate(total);
        for (byte[] f : fixtures) all.put(f);
        FrameReader reader = FrameReader.forHelperMessages();
        reader.push(all.array(), 0, total);
        for (byte[] f : fixtures) assertArrayEquals(f, HelperProtocol.encode(reader.next()));
        assertNull(reader.next());
    }

    @Test
    void messageSplitAtEveryPosition() throws IOException {
        byte[] frame = fixture("frame");
        byte[] welcome = fixture("welcome");
        byte[] stream = new byte[frame.length + welcome.length];
        System.arraycopy(frame, 0, stream, 0, frame.length);
        System.arraycopy(welcome, 0, stream, frame.length, welcome.length);
        for (int cut = 1; cut < stream.length; cut++) {
            FrameReader reader = FrameReader.forHelperMessages();
            reader.push(stream, 0, cut);
            List<Message> got = new ArrayList<>();
            for (Message m; (m = reader.next()) != null; ) got.add(m);
            reader.push(stream, cut, stream.length - cut);
            for (Message m; (m = reader.next()) != null; ) got.add(m);
            assertEquals(2, got.size(), "cut at " + cut);
            assertTrue(got.get(0) instanceof Frame && got.get(1) instanceof Welcome);
        }
    }

    // ---- rejection ----

    private static byte[] envelope(int type, String json) {
        byte[] payload = json.getBytes(StandardCharsets.UTF_8);
        ByteBuffer b = ByteBuffer.allocate(5 + payload.length);
        b.putInt(1 + payload.length).put((byte) type).put(payload);
        return b.array();
    }

    private static byte[] head(int length, int type) {
        return ByteBuffer.allocate(5).putInt(length).put((byte) type).array();
    }

    @Test
    void oversizeFrameRejectedBeforeBodyArrives() {
        FrameReader reader = FrameReader.forHelperMessages();
        byte[] head = head(HelperProtocol.MAX_FRAME_LENGTH + 1, HelperProtocol.FRAME);
        reader.push(head, 0, head.length);
        assertThrows(HelperProtocol.ProtocolException.class, reader::next);
        // Poisoned: stays failed.
        assertThrows(HelperProtocol.ProtocolException.class, reader::next);
    }

    @Test
    void maxSizedLengthIsAccepted() throws IOException {
        FrameReader reader = FrameReader.forHelperMessages();
        byte[] head = head(HelperProtocol.MAX_FRAME_LENGTH, HelperProtocol.FRAME);
        reader.push(head, 0, head.length);
        assertNull(reader.next());
    }

    @Test
    void controlReaderRejectsOver64KiB() {
        FrameReader reader = FrameReader.forModMessages();
        byte[] head = head(HelperProtocol.MAX_CONTROL_LENGTH + 1, HelperProtocol.HELLO);
        reader.push(head, 0, head.length);
        assertThrows(HelperProtocol.ProtocolException.class, reader::next);
    }

    @Test
    void zeroLengthRejected() {
        FrameReader reader = FrameReader.forHelperMessages();
        reader.push(new byte[] {0, 0, 0, 0}, 0, 4);
        assertThrows(HelperProtocol.ProtocolException.class, reader::next);
    }

    @Test
    void unknownTypeRejected() {
        for (int type : new int[] {0, 11, 63, 72, 255}) {
            FrameReader reader = FrameReader.forHelperMessages();
            byte[] bytes = envelope(type, "{}");
            reader.push(bytes, 0, bytes.length);
            HelperProtocol.ProtocolException e = assertThrows(HelperProtocol.ProtocolException.class, reader::next, "type " + type);
            assertTrue(e.getMessage().contains("unknown"), e.getMessage());
        }
    }

    @Test
    void badJsonPayloadsRejected() {
        Object[][] cases = {
            {HelperProtocol.WELCOME, "not json"},
            {HelperProtocol.WELCOME, "{\"version\":1}"},
            {HelperProtocol.WELCOME, "[]"},
            {HelperProtocol.STOP, "{\"surface\":\"7\"}"},
            {HelperProtocol.STOP, "{\"surface\":7.5}"},
            {HelperProtocol.WINDOWS, "{}"},
            {HelperProtocol.CAPTURE, "{\"fps\":5,\"kind\":\"display\",\"maxW\":1280,\"surface\":7}"},
            {HelperProtocol.CAPTURE, "{\"fps\":5,\"kind\":\"window\",\"maxW\":1280,\"surface\":7,\"windowKey\":\"nobar\"}"},
            {HelperProtocol.CAPTURE, "{\"fps\":5,\"kind\":\"tv\",\"maxW\":1280,\"slot\":1,\"surface\":7}"},
        };
        for (Object[] c : cases) {
            FrameReader reader = FrameReader.forHelperMessages();
            byte[] bytes = envelope((Integer) c[0], (String) c[1]);
            reader.push(bytes, 0, bytes.length);
            assertThrows(HelperProtocol.ProtocolException.class, reader::next, (String) c[1]);
        }
    }

    @Test
    void badBinaryPayloadsRejected() {
        // FRAME whose pixel count does not match width x height.
        ByteBuffer b = ByteBuffer.allocate(5 + 20 + 3);
        b.putInt(1 + 20 + 3).put((byte) HelperProtocol.FRAME).putInt(7).putLong(3).putInt(2).putInt(2);
        FrameReader reader = FrameReader.forHelperMessages();
        reader.push(b.array(), 0, b.capacity());
        assertThrows(HelperProtocol.ProtocolException.class, reader::next);

        // PATCH with a rect outside the surface.
        ByteBuffer p = ByteBuffer.allocate(5 + 24 + 16 + 8);
        p.putInt(1 + 24 + 16 + 8).put((byte) HelperProtocol.PATCH).putInt(7).putLong(3).putInt(2).putInt(2).putInt(1);
        p.putInt(1).putInt(1).putInt(2).putInt(1);
        FrameReader r2 = FrameReader.forHelperMessages();
        r2.push(p.array(), 0, p.capacity());
        assertThrows(HelperProtocol.ProtocolException.class, r2::next);

        // PATCH with count 0.
        ByteBuffer z = ByteBuffer.allocate(5 + 24);
        z.putInt(1 + 24).put((byte) HelperProtocol.PATCH).putInt(7).putLong(3).putInt(2).putInt(2).putInt(0);
        FrameReader r3 = FrameReader.forHelperMessages();
        r3.push(z.array(), 0, z.capacity());
        assertThrows(HelperProtocol.ProtocolException.class, r3::next);

        // PATCH claiming more than 32 rects.
        ByteBuffer m = ByteBuffer.allocate(5 + 24);
        m.putInt(1 + 24).put((byte) HelperProtocol.PATCH).putInt(7).putLong(3).putInt(2).putInt(2).putInt(33);
        FrameReader r4 = FrameReader.forHelperMessages();
        r4.push(m.array(), 0, m.capacity());
        assertThrows(HelperProtocol.ProtocolException.class, r4::next);
    }

    @Test
    void encodeRejectsInvalidImages() {
        assertThrows(HelperProtocol.ProtocolException.class,
                () -> HelperProtocol.encode(new Frame(1, 1L, 2, 2, px(1, 2, 3))));
        assertThrows(HelperProtocol.ProtocolException.class,
                () -> HelperProtocol.encode(new Patch(1, 1L, 2, 2, List.of(), px())));
    }

    @Test
    void decodersIgnoreUnknownKeys() throws IOException {
        byte[] bytes = envelope(HelperProtocol.STOP, "{\"extra\":[1,2],\"surface\":9}");
        FrameReader reader = FrameReader.forHelperMessages();
        reader.push(bytes, 0, bytes.length);
        assertEquals(new Stop(9), reader.next());
    }

    @Test
    void jsonEscapingMatchesSwiftStyle() throws IOException {
        Message m = new Ended(2, "a/b \"q\" \\ \n é —");
        byte[] bytes = HelperProtocol.encode(m);
        String json = new String(bytes, 5, bytes.length - 5, StandardCharsets.UTF_8);
        assertEquals("{\"reason\":\"a/b \\\"q\\\" \\\\ \\n é —\",\"surface\":2}", json);
        FrameReader reader = FrameReader.forHelperMessages();
        reader.push(bytes, 0, bytes.length);
        assertEquals(m, reader.next());
    }

    @Test
    void windowKeyParsing() {
        assertEquals(new WindowKey("com.apple.Notes", 4321L), WindowKey.parse("com.apple.Notes|4321"));
        assertEquals(new WindowKey("", 5L), WindowKey.parse("|5"));
        assertEquals(new WindowKey("a|b", 7L), WindowKey.parse("a|b|7"));
        assertNull(WindowKey.parse("nobar"));
        assertNull(WindowKey.parse("a|x"));
    }
}
