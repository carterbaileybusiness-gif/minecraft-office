import Foundation

enum ProtocolTests {
    static let token = "00112233445566778899aabbccddeeff00112233445566778899aabbccddeeff"

    /// One deterministic example per message type. These are also written as the golden fixtures that the Java
    /// side (Task 9) decodes byte-exactly; Tests/fixtures/README.md lists every field value.
    static let examples: [(file: String, message: Message)] = [
        ("hello", .hello(HelloPayload(token: token, version: 1, mcPid: 4242))),
        ("list_windows", .listWindows),
        ("create_display", .createDisplay(CreateDisplayPayload(slot: 1, w: 1920, h: 1080))),
        ("remove_display", .removeDisplay(SlotPayload(slot: 1))),
        ("capture_display", .capture(CapturePayload(surface: 7, kind: .display, slot: 1, windowKey: nil, maxW: 1280, fps: 5))),
        ("capture_window", .capture(CapturePayload(surface: 8, kind: .window, slot: nil, windowKey: "com.apple.Notes|4321", maxW: 1280, fps: 5))),
        ("capture_main", .capture(CapturePayload(surface: 9, kind: .main, slot: nil, windowKey: nil, maxW: 1920, fps: 5))),
        ("stop", .stop(SurfacePayload(surface: 7))),
        ("ready", .ready(SurfacePayload(surface: 7))),
        ("zoom_in", .zoomIn(ZoomInPayload(surface: 7, mcWindowFrame: WindowRect(x: 0, y: 25, w: 1440, h: 875)))),
        ("zoom_out", .zoomOut),
        ("request_permissions", .requestPermissions),
        ("pointer_down", .pointerDown(PointerPayload(slot: 1, x: 960, y: 540, button: .left))),
        ("pointer_move", .pointerMove(PointerPayload(slot: 2, x: 10, y: 20, button: nil))),
        ("pointer_up", .pointerUp(PointerPayload(slot: 2, x: 10, y: 20, button: .left))),
        ("scroll", .scroll(ScrollPayload(slot: 1, x: 100, y: 200, dx: 0, dy: -120))),
        ("click", .click(PointerPayload(slot: 1, x: 300, y: 400, button: .right))),
        ("exit_typing", .exitTyping),
        ("pointer_down_main", .pointerDown(PointerPayload(slot: 0, x: 960, y: 540, button: .left, w: 1920, h: 1080))),
        ("click_main", .click(PointerPayload(slot: 0, x: 100, y: 50, button: .left, w: 1920, h: 1080))),
        ("scroll_main", .scroll(ScrollPayload(slot: 0, x: 100, y: 200, dx: 0, dy: -48, w: 1920, h: 1080))),
        ("list_apps", .listApps),
        ("app_icons", .appIcons(AppIconsPayload(bundles: ["com.apple.Notes", "com.google.Chrome"], size: 64))),
        ("open_app", .openApp(OpenAppPayload(slot: 2, bundle: "com.apple.Notes", url: nil, bottom: 96))),
        ("open_app_url", .openApp(OpenAppPayload(slot: 1, bundle: nil, url: "https://www.youtube.com"))),
        ("welcome", .welcome(WelcomePayload(version: 1, screenRecording: true, accessibility: false))),
        ("windows", .windows([
            WindowInfo(windowKey: "com.apple.Notes|4321", app: "Notes", title: "Groceries — List", w: 800, h: 600),
            WindowInfo(windowKey: "com.apple.Safari|99", app: "Safari", title: "", w: 1200, h: 900),
        ])),
        ("display", .display(DisplayPayload(slot: 1, displayId: 5, w: 1920, h: 1080))),
        ("frame", .frame(FrameImage(surface: 7, generation: 3, width: 2, height: 2, rgba: Data([
            255, 0, 0, 255, 0, 255, 0, 255,
            0, 0, 255, 255, 255, 255, 255, 128,
        ])))),
        ("patch", .patch(PatchImage(surface: 7, generation: 3, width: 4, height: 2,
                                    rects: [PixelRect(x: 0, y: 0, w: 1, h: 1), PixelRect(x: 2, y: 1, w: 2, h: 1)],
                                    rgba: Data([10, 20, 30, 255, 40, 50, 60, 255, 70, 80, 90, 255])))),
        ("ended", .ended(EndedPayload(surface: 8, reason: "window closed"))),
        ("exit_zoom", .exitZoom),
        ("error", .error(ErrorPayload(code: "bad_slot", message: "slot must be 1...4"))),
        ("typing_exit", .typingExit(ReasonPayload(reason: "esc"))),
        ("drag_delta", .dragDelta(DeltaPayload(dx: 12, dy: -3))),
        ("drag_ended", .dragEnded(ReasonPayload(reason: "released"))),
        ("apps", .apps(AppsPayload(apps: [AppInfo(bundle: "com.google.Chrome", name: "Google Chrome"),
                                          AppInfo(bundle: "com.apple.Notes", name: "Notes")]))),
        ("icons", .icons(IconsPayload(icons: [AppIcon(bundle: "com.apple.Notes", png: "iVBORw0KGgo=")]))),
        ("app_result", .appResult(AppResultPayload(target: "com.apple.Notes", ok: true, message: "Notes is on screen 2"))),
        ("secure_input", .secureInput(SecureInputPayload(on: true))),
    ]

    static func decodeAll(_ bytes: Data) throws -> [Message] {
        var reader = FrameReader(maxLength: OfficeProtocol.maxFrameLength)
        reader.push(bytes)
        var out: [Message] = []
        while let (type, payload) = try reader.next() { out.append(try Message.decode(type: type, payload: payload)) }
        return out
    }

    static func runAll(fixtures: URL?) {
        run("every message type has an example") {
            let covered = Set(examples.map { $0.message.type })
            expectEqual(covered, Set(MessageType.allCases), "message types covered")
        }

        run("encode/decode round trip for every message") {
            for (name, message) in examples {
                let bytes = try message.encode()
                let decoded = try decodeAll(bytes)
                expectEqual(decoded, [message], name)
                // Envelope: u32 BE length of (type + payload), then the type byte.
                let length = bytes.prefix(4).reduce(0) { ($0 << 8) | Int($1) }
                expectEqual(length, bytes.count - 4, "\(name) length field")
                expectEqual(bytes[4], message.type.rawValue, "\(name) type byte")
            }
        }

        run("byte-at-a-time stream decodes every message in order") {
            var all = Data()
            for (_, message) in examples { all.append(try message.encode()) }
            var reader = FrameReader(maxLength: OfficeProtocol.maxFrameLength)
            var decoded: [Message] = []
            for byte in all {
                reader.push(Data([byte]))
                while let (type, payload) = try reader.next() { decoded.append(try Message.decode(type: type, payload: payload)) }
            }
            expectEqual(decoded, examples.map { $0.message })
        }

        run("JSON payloads are compact with sorted keys") {
            let bytes = try Message.capture(CapturePayload(surface: 8, kind: .window, slot: nil, windowKey: "a/b|1", maxW: 1280, fps: 5)).encode()
            let json = String(decoding: bytes.dropFirst(5), as: UTF8.self)
            expectEqual(json, #"{"fps":5,"kind":"window","maxW":1280,"surface":8,"windowKey":"a/b|1"}"#)
            let empty = String(decoding: try Message.zoomOut.encode().dropFirst(5), as: UTF8.self)
            expectEqual(empty, "{}")
        }

        run("frame over 64 MiB is rejected") {
            expectEqual(OfficeProtocol.maxFrameLength, 64 * 1024 * 1024)
            var reader = FrameReader(maxLength: OfficeProtocol.maxFrameLength)
            let tooBig = UInt32(OfficeProtocol.maxFrameLength + 1)
            reader.push(Data([UInt8(tooBig >> 24), UInt8((tooBig >> 16) & 0xff), UInt8((tooBig >> 8) & 0xff), UInt8(tooBig & 0xff), MessageType.frame.rawValue]))
            expectThrows("reader rejects length 64 MiB + 1 before reading the body") { _ = try reader.next() }
            // Exactly 64 MiB is allowed by the header check (it waits for the body).
            var exact = FrameReader(maxLength: OfficeProtocol.maxFrameLength)
            let limit = UInt32(OfficeProtocol.maxFrameLength)
            exact.push(Data([UInt8(limit >> 24), UInt8((limit >> 16) & 0xff), UInt8((limit >> 8) & 0xff), UInt8(limit & 0xff)]))
            let waiting = try exact.next()
            expect(waiting == nil, "64 MiB header waits for its body")
            // The encoder refuses to produce one: 4096 × 4096 RGBA + 20-byte header + type > 64 MiB.
            let huge = FrameImage(surface: 1, generation: 1, width: 4096, height: 4096, rgba: Data(count: 4096 * 4096 * 4))
            expectThrows("encoder rejects a frame over 64 MiB") { _ = try Message.frame(huge).encode() }
        }

        run("control reader limit, empty frame and unknown type are rejected") {
            var small = FrameReader(maxLength: OfficeProtocol.maxControlLength)
            small.push(Data([0, 1, 0, 1, 1]))
            expectThrows("65537-byte control frame") { _ = try small.next() }
            var empty = FrameReader(maxLength: OfficeProtocol.maxFrameLength)
            empty.push(Data([0, 0, 0, 0]))
            expectThrows("zero length") { _ = try empty.next() }
            var unknown = FrameReader(maxLength: OfficeProtocol.maxFrameLength)
            unknown.push(Data([0, 0, 0, 3, 99, 0x7b, 0x7d]))
            expectThrows("type 99") { _ = try unknown.next() }
        }

        run("malformed payloads are rejected") {
            expectThrows("hello without token") { _ = try Message.decode(type: .hello, payload: Data(#"{"version":1,"mcPid":1}"#.utf8)) }
            expectThrows("display capture without slot") {
                _ = try Message.decode(type: .capture, payload: Data(#"{"surface":1,"kind":"display","maxW":1,"fps":1}"#.utf8))
            }
            expectThrows("window capture without windowKey") {
                _ = try Message.decode(type: .capture, payload: Data(#"{"surface":1,"kind":"window","maxW":1,"fps":1}"#.utf8))
            }
            // Mirror ("main"): the main display minus Minecraft; needs neither slot nor windowKey.
            let main = try Message.decode(type: .capture, payload: Data(#"{"surface":1,"kind":"main","maxW":1920,"fps":5}"#.utf8))
            expectEqual(main, .capture(CapturePayload(surface: 1, kind: .main, slot: nil, windowKey: nil, maxW: 1920, fps: 5)), "main capture")
            expectThrows("unknown capture kind") {
                _ = try Message.decode(type: .capture, payload: Data(#"{"surface":1,"kind":"mirror","slot":1,"maxW":1,"fps":1}"#.utf8))
            }
            // FRAME whose pixel bytes don't match width × height × 4.
            var frame = Data()
            for v: (UInt64, Int) in [(1, 4), (1, 8), (2, 4), (2, 4)] { OfficeProtocol.appendBE(&frame, v.0, bytes: v.1) }
            frame.append(Data(count: 15))
            expectThrows("short frame pixels") { _ = try Message.decode(type: .frame, payload: frame) }
            // PATCH with a rectangle outside the frame.
            var patch = Data()
            for v: (UInt64, Int) in [(1, 4), (1, 8), (4, 4), (2, 4), (1, 4), (3, 4), (0, 4), (2, 4), (1, 4)] { OfficeProtocol.appendBE(&patch, v.0, bytes: v.1) }
            patch.append(Data(count: 8))
            expectThrows("patch rect out of bounds") { _ = try Message.decode(type: .patch, payload: patch) }
            // Unknown JSON keys are ignored (forward compatible).
            let ok = try Message.decode(type: .stop, payload: Data(#"{"surface":3,"extra":true}"#.utf8))
            expectEqual(ok, .stop(SurfacePayload(surface: 3)))
        }

        run("windowKey parses bundle id and CGWindowID") {
            expectEqual(WindowKey.parse("com.apple.Notes|4321"), WindowKey(bundleId: "com.apple.Notes", windowID: 4321))
            expectEqual(WindowKey.parse("|17"), WindowKey(bundleId: "", windowID: 17))
            expectEqual(WindowKey.parse("odd|name|9"), WindowKey(bundleId: "odd|name", windowID: 9))
            expect(WindowKey.parse("com.apple.Notes") == nil, "missing id")
            expect(WindowKey.parse("com.apple.Notes|x") == nil, "non-numeric id")
            expectEqual(WindowKey(bundleId: "com.apple.Notes", windowID: 4321).description, "com.apple.Notes|4321")
        }

        run("handshake accepts only the right token and version") {
            let good = Message.hello(HelloPayload(token: token, version: 1, mcPid: 9))
            expectEqual(Handshake.check(good, token: token), .accept(HelloPayload(token: token, version: 1, mcPid: 9)))
            expectEqual(Handshake.check(.hello(HelloPayload(token: "nope", version: 1, mcPid: 9)), token: token), .badToken)
            expectEqual(Handshake.check(.hello(HelloPayload(token: "", version: 1, mcPid: 9)), token: ""), .badToken)
            expectEqual(Handshake.check(.hello(HelloPayload(token: token, version: 2, mcPid: 9)), token: token), .badVersion)
            expectEqual(Handshake.check(.listWindows, token: token), .notHello)
            expectEqual(Handshake.check(nil, token: token), .notHello)
        }

        run("socket: bad token closes, good token gets WELCOME") { try socketTests() }
        run("socket: a second client is refused while one is connected") { try secondClientTest() }
        run("socket: a closed client's fd is not reused under a pending write") { try fdReuseTest() }

        if let fixtures {
            run("golden fixtures written") {
                try FileManager.default.createDirectory(at: fixtures, withIntermediateDirectories: true)
                for (name, message) in examples {
                    let url = fixtures.appendingPathComponent("\(name).bin")
                    try message.encode().write(to: url)
                    expectEqual(try decodeAll(try Data(contentsOf: url)), [message], "re-read \(name).bin")
                }
            }
        }
    }

    // MARK: Socket integration (real HelperServer on a temporary directory)

    static func connect(_ path: String) -> Int32 {
        let fd = socket(AF_UNIX, SOCK_STREAM, 0)
        var address = sockaddr_un()
        address.sun_family = sa_family_t(AF_UNIX)
        withUnsafeMutableBytes(of: &address.sun_path) { raw in raw.copyBytes(from: path.utf8); raw[path.utf8.count] = 0 }
        let ok = withUnsafePointer(to: &address) { $0.withMemoryRebound(to: sockaddr.self, capacity: 1) { Darwin.connect(fd, $0, socklen_t(MemoryLayout<sockaddr_un>.size)) } }
        var timeout = timeval(tv_sec: 5, tv_usec: 0)
        setsockopt(fd, SOL_SOCKET, SO_RCVTIMEO, &timeout, socklen_t(MemoryLayout<timeval>.size))
        var on: Int32 = 1
        setsockopt(fd, SOL_SOCKET, SO_NOSIGPIPE, &on, socklen_t(MemoryLayout<Int32>.size))
        return ok == 0 ? fd : -1
    }

    static func send(_ fd: Int32, _ message: Message) throws {
        let bytes = try message.encode()
        _ = bytes.withUnsafeBytes { Darwin.write(fd, $0.baseAddress!, bytes.count) }
    }

    /// Reads one message, or nil on EOF (connection closed by the helper).
    static func receive(_ fd: Int32) throws -> Message? {
        var reader = FrameReader(maxLength: OfficeProtocol.maxFrameLength)
        var buffer = [UInt8](repeating: 0, count: 4096)
        while true {
            if let (type, payload) = try reader.next() { return try Message.decode(type: type, payload: payload) }
            let n = read(fd, &buffer, buffer.count)
            if n == 0 { return nil }
            if n < 0 { throw POSIXError(.ETIMEDOUT) }
            reader.push(Data(buffer[0..<n]))
        }
    }

    static func socketTests() throws {
        let directory = URL(fileURLWithPath: NSTemporaryDirectory()).appendingPathComponent("oh-\(getpid())", isDirectory: true)
        let server = HelperServer(directory: directory, callbackQueue: DispatchQueue(label: "test-callbacks"))
        server.welcome = { WelcomePayload(version: OfficeProtocol.version, screenRecording: false, accessibility: false) }
        let hellos = DispatchSemaphore(value: 0)
        var helloPid: Int32 = 0
        server.onConnect = { hello, _ in helloPid = hello.mcPid; hellos.signal() }
        try server.start()
        defer { server.stop(); try? FileManager.default.removeItem(at: directory) }

        let attributes = try FileManager.default.attributesOfItem(atPath: directory.path)
        expectEqual((attributes[.posixPermissions] as? NSNumber)?.intValue, 0o700, "directory mode")
        let tokenAttributes = try FileManager.default.attributesOfItem(atPath: server.tokenURL.path)
        expectEqual((tokenAttributes[.posixPermissions] as? NSNumber)?.intValue, 0o600, "token mode")
        let written = try String(contentsOf: server.tokenURL, encoding: .utf8)
        expectEqual(written.count, 64, "token is 32 random bytes in hex")

        let bad = connect(server.socketURL.path)
        expect(bad >= 0, "connect for bad token")
        try send(bad, .hello(HelloPayload(token: String(repeating: "0", count: 64), version: 1, mcPid: 1)))
        let afterBad = try receive(bad)
        expect(afterBad == nil, "bad token: connection closed without WELCOME")
        close(bad)

        let wrongVersion = connect(server.socketURL.path)
        try send(wrongVersion, .hello(HelloPayload(token: written, version: 99, mcPid: 1)))
        if case .error(let e)? = try receive(wrongVersion) { expectEqual(e.code, "version", "version error code") }
        else { expect(false, "wrong version: ERROR expected") }
        let afterVersion = try receive(wrongVersion)
        expect(afterVersion == nil, "wrong version: then closed")
        close(wrongVersion)

        let notHello = connect(server.socketURL.path)
        try send(notHello, .listWindows)
        let afterNotHello = try receive(notHello)
        expect(afterNotHello == nil, "first message not HELLO: closed")
        close(notHello)

        let good = connect(server.socketURL.path)
        try send(good, .hello(HelloPayload(token: written, version: 1, mcPid: 4242)))
        expectEqual(try receive(good), .welcome(WelcomePayload(version: 1, screenRecording: false, accessibility: false)), "WELCOME")
        expect(hellos.wait(timeout: .now() + 5) == .success, "onConnect called")
        expectEqual(helloPid, 4242, "mcPid passed on")
        expectEqual(server.lastWelcome, WelcomePayload(version: 1, screenRecording: false, accessibility: false), "handshake WELCOME remembered")
        // A permission granted while connected: the helper sends WELCOME again with the new values.
        let granted = WelcomePayload(version: 1, screenRecording: true, accessibility: true)
        server.sendWelcome(granted)
        expectEqual(try receive(good), .welcome(granted), "WELCOME re-sent after a permission change")
        expectEqual(server.lastWelcome, granted, "re-sent WELCOME remembered")
        close(good)
    }

    static func makeServer(_ tag: String) throws -> (HelperServer, URL, String) {
        let directory = URL(fileURLWithPath: NSTemporaryDirectory()).appendingPathComponent("oh-\(tag)-\(getpid())", isDirectory: true)
        let server = HelperServer(directory: directory, callbackQueue: DispatchQueue(label: "test-\(tag)"))
        try server.start()
        return (server, directory, try String(contentsOf: server.tokenURL, encoding: .utf8))
    }

    /// A second authenticated HELLO must not take over: the session's captures, zoom and displays belong to the
    /// first connection, and a new client must never be handed that session's (possibly private) frames.
    static func secondClientTest() throws {
        let (server, directory, token) = try makeServer("second")
        var connects = 0
        let lock = NSLock()
        server.onConnect = { _, _ in lock.lock(); connects += 1; lock.unlock() }
        defer { server.stop(); try? FileManager.default.removeItem(at: directory) }

        let first = connect(server.socketURL.path)
        try send(first, .hello(HelloPayload(token: token, version: 1, mcPid: 1)))
        if case .welcome? = try receive(first) {} else { expect(false, "first client gets WELCOME") }

        let second = connect(server.socketURL.path)
        try send(second, .hello(HelloPayload(token: token, version: 1, mcPid: 2)))
        let reply = try receive(second)
        if case .error(let e)? = reply { expectEqual(e.code, "busy", "second client error code") }
        else { expect(false, "second client: ERROR busy expected, got \(String(describing: reply))") }
        let afterBusy = try receive(second)
        expect(afterBusy == nil, "second client closed")
        close(second)

        // The first connection is untouched and still receives messages.
        server.send(.exitZoom)
        let stillFirst = try receive(first)
        expectEqual(stillFirst, .exitZoom, "first client still connected")
        lock.lock(); expectEqual(connects, 1, "onConnect only for the first client"); lock.unlock()
        close(first)
    }

    /// The writer reads the client fd, then the client disconnects before the bytes go out. The reader must not
    /// close that fd while the write is pending, or the number could be reused (by a new socket or a log file)
    /// and receive frame pixels. The hook opens files after the disconnect: none may get the server's fd number.
    static func fdReuseTest() throws {
        let (server, directory, token) = try makeServer("fd")
        let disconnected = DispatchSemaphore(value: 0)
        server.onDisconnect = { disconnected.signal() }
        defer { server.stop(); try? FileManager.default.removeItem(at: directory) }

        let client = connect(server.socketURL.path)
        try send(client, .hello(HelloPayload(token: token, version: 1, mcPid: 1)))
        if case .welcome? = try receive(client) {} else { expect(false, "WELCOME") }

        let hookDone = DispatchSemaphore(value: 0)
        let armed = NSLock()
        var once = true
        var sawDisconnect = false
        var reused = true
        server.beforeWriteForTesting = { fd in
            armed.lock(); let go = once; once = false; armed.unlock()
            guard go else { return }
            close(client) // the mod goes away while this write is pending
            sawDisconnect = disconnected.wait(timeout: .now() + 3) == .success
            var opened: [Int32] = []
            for _ in 0..<64 { let f = open("/dev/null", O_RDONLY); if f >= 0 { opened.append(f) } }
            reused = opened.contains(fd)
            for f in opened { close(f) }
            hookDone.signal()
        }
        server.send(.exitZoom)
        expect(hookDone.wait(timeout: .now() + 5) == .success, "write hook ran")
        expect(sawDisconnect, "reader saw the disconnect while the write was pending")
        expect(!reused, "server fd stayed open until the pending write finished (not reusable)")
    }
}
