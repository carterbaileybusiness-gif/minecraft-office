import Foundation
import CoreVideo
import Accelerate
import Security

/// Local-only channel to the Minecraft mod (ported from Home Base's HomeBaseServer). A Unix-domain socket in an
/// owner-only directory, peer-uid checked and token authenticated. One client at a time; at most one frame in
/// flight per surface (the next goes out after the mod's READY). No AppKit, so check.sh can test it.
final class HelperServer {
    static var defaultDirectory: URL {
        FileManager.default.homeDirectoryForCurrentUser
            .appendingPathComponent("Library/Application Support/Minecraft Office", isDirectory: true)
    }

    let directory: URL
    var socketURL: URL { directory.appendingPathComponent("helper.sock") }
    var tokenURL: URL { directory.appendingPathComponent("helper.token") }

    /// Called on `callbackQueue`.
    var onConnect: ((HelloPayload, pid_t) -> Void)?
    var onMessage: ((Message) -> Void)?
    var onDisconnect: (() -> Void)?
    /// Builds the WELCOME reply (called on the accept thread).
    var welcome: () -> WelcomePayload = { WelcomePayload(version: OfficeProtocol.version, screenRecording: false, accessibility: false) }
    /// The WELCOME the current client last got (handshake or `sendWelcome`), so permission changes can be detected.
    var lastWelcome: WelcomePayload? { lock.lock(); defer { lock.unlock() }; return sentWelcome }
    /// Test hook: runs on the writer queue after a write has picked its fd, before any byte is written.
    var beforeWriteForTesting: ((Int32) -> Void)?
    /// Diagnostics only (never frame contents).
    var log: (String) -> Void = { _ in }

    private let callbackQueue: DispatchQueue
    private let lock = NSLock()
    private var listener: Int32 = -1
    private var client: Int32 = -1
    private var clientSession: UInt64 = 0
    private var sentWelcome: WelcomePayload?
    private var wants = Set<Int>()
    /// Newest undelivered frame per surface and the regions changed since the last one sent.
    private var pending: [Int: (buffer: CVPixelBuffer, generation: UInt64, dirty: [CGRect]?)] = [:]
    private var sentGeneration: [Int: UInt64] = [:]
    private(set) var token = ""
    private let writer = DispatchQueue(label: "local.carter.office-helper.writer", qos: .userInitiated)
    private(set) var framesSent = 0
    private(set) var patchesSent = 0

    init(directory: URL = HelperServer.defaultDirectory, callbackQueue: DispatchQueue = .main) {
        self.directory = directory
        self.callbackQueue = callbackQueue
    }

    var isConnected: Bool { lock.lock(); defer { lock.unlock() }; return client >= 0 }

    func start() throws {
        let fm = FileManager.default
        try fm.createDirectory(at: directory, withIntermediateDirectories: true, attributes: [.posixPermissions: 0o700])
        try fm.setAttributes([.posixPermissions: 0o700], ofItemAtPath: directory.path)
        var bytes = [UInt8](repeating: 0, count: 32)
        guard SecRandomCopyBytes(kSecRandomDefault, bytes.count, &bytes) == errSecSuccess else { throw POSIXError(.EIO) }
        token = bytes.map { String(format: "%02x", $0) }.joined()
        try? fm.removeItem(at: tokenURL)
        guard fm.createFile(atPath: tokenURL.path, contents: Data(token.utf8), attributes: [.posixPermissions: 0o600]) else { throw POSIXError(.EIO) }

        let path = socketURL.path
        unlink(path)
        let fd = socket(AF_UNIX, SOCK_STREAM, 0)
        guard fd >= 0 else { throw POSIXError(.EIO) }
        var address = sockaddr_un()
        address.sun_family = sa_family_t(AF_UNIX)
        let capacity = MemoryLayout.size(ofValue: address.sun_path)
        guard path.utf8.count < capacity else { close(fd); throw POSIXError(.ENAMETOOLONG) }
        withUnsafeMutableBytes(of: &address.sun_path) { raw in
            raw.copyBytes(from: path.utf8); raw[path.utf8.count] = 0
        }
        let size = socklen_t(MemoryLayout<sockaddr_un>.size)
        let bound = withUnsafePointer(to: &address) { $0.withMemoryRebound(to: sockaddr.self, capacity: 1) { bind(fd, $0, size) } }
        guard bound == 0, chmod(path, 0o600) == 0, listen(fd, 4) == 0 else { close(fd); throw POSIXError(.EADDRINUSE) }
        lock.lock(); listener = fd; lock.unlock()
        let thread = Thread { [weak self] in self?.acceptLoop(fd) }
        thread.name = "OfficeHelper accept"
        thread.start()
    }

    // MARK: Accept and read

    private func acceptLoop(_ fd: Int32) {
        while true {
            let connection = accept(fd, nil, nil)
            if connection < 0 { if errno == EINTR { continue }; return }
            var uid: uid_t = 0, gid: gid_t = 0
            var pid: pid_t = 0
            var pidSize = socklen_t(MemoryLayout<pid_t>.size)
            guard getpeereid(connection, &uid, &gid) == 0, uid == getuid(),
                  getsockopt(connection, SOL_LOCAL, LOCAL_PEERPID, &pid, &pidSize) == 0 else {
                log("rejected peer: different user"); close(connection); continue
            }
            var on: Int32 = 1
            setsockopt(connection, SOL_SOCKET, SO_NOSIGPIPE, &on, socklen_t(MemoryLayout<Int32>.size))
            var timeout = timeval(tv_sec: 3, tv_usec: 0)
            setsockopt(connection, SOL_SOCKET, SO_RCVTIMEO, &timeout, socklen_t(MemoryLayout<timeval>.size))
            var reader = FrameReader(maxLength: OfficeProtocol.maxControlLength)
            let first = (try? Self.readMessage(connection, &reader)) ?? nil
            let result = Handshake.check(first.flatMap { try? Message.decode(type: $0.0, payload: $0.1) }, token: token)
            guard case .accept(let hello) = result else {
                log("rejected peer pid \(pid): \(result)")
                if result == .badVersion, let bytes = try? Message.error(ErrorPayload(code: "version",
                        message: "helper speaks protocol \(OfficeProtocol.version)")).encode() {
                    _ = Self.writeAll(connection, [bytes])
                }
                close(connection); continue
            }
            timeout = timeval(tv_sec: 0, tv_usec: 0)
            setsockopt(connection, SOL_SOCKET, SO_RCVTIMEO, &timeout, socklen_t(MemoryLayout<timeval>.size))

            // One mod per helper. A second client never takes over: the session's captures, zoom and displays
            // belong to the first connection, and its (possibly private) frames must not reach anyone else.
            lock.lock()
            let busy = client >= 0
            if !busy {
                client = connection; clientSession &+= 1; wants.removeAll(); pending.removeAll(); sentGeneration.removeAll()
            }
            let session = clientSession
            lock.unlock()
            if busy {
                log("refused second client pid \(pid): a mod is already connected")
                if let bytes = try? Message.error(ErrorPayload(code: "busy", message: "another Minecraft is connected")).encode() {
                    _ = Self.writeAll(connection, [bytes])
                }
                close(connection)
                continue
            }
            sendWelcome(welcome())
            callbackQueue.async { self.onConnect?(hello, pid) }
            let readerThread = Thread { [weak self] in self?.readLoop(connection, session: session, reader: reader) }
            readerThread.name = "OfficeHelper reader"
            readerThread.start()
        }
    }

    /// Blocking read of one message; nil on EOF / timeout; throws on a protocol violation.
    private static func readMessage(_ fd: Int32, _ reader: inout FrameReader) throws -> (MessageType, Data)? {
        var buffer = [UInt8](repeating: 0, count: 16 * 1024)
        while true {
            if let message = try reader.next() { return message }
            let n = read(fd, &buffer, buffer.count)
            if n > 0 { reader.push(Data(buffer[0..<n])) }
            else if n < 0 && errno == EINTR { continue }
            else { return nil }
        }
    }

    private func readLoop(_ fd: Int32, session: UInt64, reader initial: FrameReader) {
        var reader = initial
        while true {
            let next: (MessageType, Data)?
            do { next = try Self.readMessage(fd, &reader) } catch { log("protocol error: \(error)"); break }
            guard let (type, payload) = next else { break }
            let message: Message
            do { message = try Message.decode(type: type, payload: payload) } catch {
                log("bad payload for \(type): \(error)")
                send(.error(ErrorPayload(code: "bad_request", message: "could not decode \(type)")))
                continue
            }
            if case .ready(let p) = message { markReady(surface: p.surface, session: session); continue }
            callbackQueue.async { [weak self] in
                guard let self else { return }
                self.lock.lock(); let current = self.clientSession == session; self.lock.unlock()
                if current { self.onMessage?(message) }
            }
        }
        lock.lock()
        let current = clientSession == session && client == fd
        if current { client = -1; wants.removeAll(); pending.removeAll() }
        lock.unlock()
        // Close on the serial writer queue: a write that already picked this fd finishes first, so the number can't
        // be reused (by a new socket or a log file) and receive frame bytes. Later writes see client == -1.
        writer.async { close(fd) }
        if current { callbackQueue.async { [weak self] in self?.onDisconnect?() } }
    }

    // MARK: Sending

    func send(_ message: Message) {
        guard let bytes = try? message.encode() else { log("could not encode \(message.type)"); return }
        writer.async { [weak self] in _ = self?.write([bytes]) }
    }

    /// Sends WELCOME (the handshake reply, or again later when a permission changed) and remembers it.
    func sendWelcome(_ payload: WelcomePayload) {
        lock.lock(); sentWelcome = payload; lock.unlock()
        send(.welcome(payload))
    }

    /// The mod may receive one frame for this surface (READY, or a fresh CAPTURE).
    func markReady(surface: Int) {
        lock.lock(); let session = clientSession; lock.unlock()
        markReady(surface: surface, session: session)
    }

    private func markReady(surface: Int, session: UInt64) {
        lock.lock()
        var due: (buffer: CVPixelBuffer, generation: UInt64, dirty: [CGRect]?)?
        if clientSession == session { if let p = pending.removeValue(forKey: surface) { due = p } else { wants.insert(surface) } }
        lock.unlock()
        if let due { transmit(surface: surface, due.buffer, generation: due.generation, dirty: due.dirty) }
    }

    /// Queues the newest frame; it goes out as soon as the mod is ready for this surface. Changed regions of frames
    /// skipped meanwhile are merged so only what changed is sent (a full FRAME when unknown or after a new generation).
    func offer(_ buffer: CVPixelBuffer, surface: Int, generation: UInt64, dirty: [CGRect]?) {
        lock.lock()
        guard client >= 0 else { lock.unlock(); return }
        var merged = dirty
        if let previous = pending[surface] {
            merged = previous.generation == generation && previous.dirty != nil && dirty != nil ? previous.dirty! + dirty! : nil
        }
        if sentGeneration[surface] != generation { merged = nil }
        let go = wants.contains(surface)
        if go { wants.remove(surface); pending[surface] = nil } else { pending[surface] = (buffer, generation, merged) }
        lock.unlock()
        if go { transmit(surface: surface, buffer, generation: generation, dirty: merged) }
    }

    func forget(surface: Int) {
        lock.lock(); pending[surface] = nil; wants.remove(surface); sentGeneration[surface] = nil; lock.unlock()
    }

    private func transmit(surface: Int, _ buffer: CVPixelBuffer, generation: UInt64, dirty: [CGRect]?) {
        writer.async { [weak self] in
            guard let self else { return }
            let width = CVPixelBufferGetWidth(buffer), height = CVPixelBufferGetHeight(buffer)
            let bounds = CGRect(x: 0, y: 0, width: width, height: height)
            var rects = (dirty ?? []).map { $0.integral.intersection(bounds) }.filter { !$0.isEmpty }
            let area = rects.reduce(0) { $0 + $1.width * $1.height }
            if rects.count > OfficeProtocol.maxPatchRects { rects = [rects.dropFirst().reduce(rects[0]) { $0.union($1) }] }
            var sent = false
            let head = Message.imageHeader(surface: UInt32(surface), generation: generation, width: UInt32(width), height: UInt32(height))
            if dirty != nil, rects.isEmpty {
                sent = true // nothing visible changed
            } else if dirty != nil, area < bounds.width * bounds.height * 0.5, let pixels = Self.rgba(buffer, rects: rects) {
                var header = head
                OfficeProtocol.appendBE(&header, UInt64(rects.count), bytes: 4)
                for r in rects { for v in [r.minX, r.minY, r.width, r.height] { OfficeProtocol.appendBE(&header, UInt64(v), bytes: 4) } }
                if let envelope = try? OfficeProtocol.header(.patch, payloadLength: header.count + pixels.count) {
                    sent = self.write([envelope, header, pixels])
                    if sent { self.patchesSent += 1 }
                }
            } else if let rgba = Self.rgba(buffer, rects: [bounds]) {
                if let envelope = try? OfficeProtocol.header(.frame, payloadLength: head.count + rgba.count) {
                    sent = self.write([envelope, head, rgba])
                    if sent { self.framesSent += 1 }
                } else {
                    self.log("frame \(width)x\(height) exceeds 64 MiB; dropped")
                }
            }
            self.lock.lock()
            if sent { self.sentGeneration[surface] = generation }
            // An empty patch needs no READY from the mod; keep the surface ready for the next frame.
            if sent && dirty != nil && rects.isEmpty { self.wants.insert(surface) }
            self.lock.unlock()
        }
    }

    /// BGRA → RGBA for each rectangle, concatenated row by row.
    static func rgba(_ buffer: CVPixelBuffer, rects: [CGRect]) -> Data? {
        guard CVPixelBufferGetPixelFormatType(buffer) == kCVPixelFormatType_32BGRA,
              CVPixelBufferLockBaseAddress(buffer, .readOnly) == kCVReturnSuccess else { return nil }
        defer { CVPixelBufferUnlockBaseAddress(buffer, .readOnly) }
        let width = CVPixelBufferGetWidth(buffer), height = CVPixelBufferGetHeight(buffer), stride = CVPixelBufferGetBytesPerRow(buffer)
        guard width > 0, height > 0, width <= 8192, height <= 8192, let base = CVPixelBufferGetBaseAddress(buffer) else { return nil }
        let total = rects.reduce(0) { $0 + Int($1.width) * Int($1.height) * 4 }
        var output = Data(count: total)
        let map: [UInt8] = [2, 1, 0, 3] // BGRA → RGBA
        let ok = output.withUnsafeMutableBytes { raw -> Bool in
            var offset = 0
            for r in rects {
                let x = Int(r.minX), y = Int(r.minY), w = Int(r.width), h = Int(r.height)
                var source = vImage_Buffer(data: base + y * stride + x * 4, height: vImagePixelCount(h), width: vImagePixelCount(w), rowBytes: stride)
                var destination = vImage_Buffer(data: raw.baseAddress! + offset, height: vImagePixelCount(h), width: vImagePixelCount(w), rowBytes: w * 4)
                guard vImagePermuteChannels_ARGB8888(&source, &destination, map, vImage_Flags(kvImageNoFlags)) == kvImageNoError else { return false }
                offset += w * h * 4
            }
            return true
        }
        return ok ? output : nil
    }

    @discardableResult
    private func write(_ parts: [Data]) -> Bool {
        lock.lock(); let fd = client; lock.unlock()
        guard fd >= 0 else { return false }
        beforeWriteForTesting?(fd)
        if Self.writeAll(fd, parts) { return true }
        shutdown(fd, SHUT_RDWR)
        return false
    }

    private static func writeAll(_ fd: Int32, _ parts: [Data]) -> Bool {
        for part in parts {
            var offset = 0
            while offset < part.count {
                let n = part.withUnsafeBytes { Darwin.write(fd, $0.baseAddress! + offset, part.count - offset) }
                if n > 0 { offset += n } else if n < 0 && errno == EINTR { continue } else { return false }
            }
        }
        return true
    }

    func stop() {
        lock.lock()
        let fd = client; client = -1; let listen = listener; listener = -1
        // Enqueued under the lock, so it runs before the reader's close (which the reader enqueues after taking
        // the lock): the fd is still this connection's when it is shut down.
        if fd >= 0 { writer.async { shutdown(fd, SHUT_RDWR) } }
        lock.unlock()
        if listen >= 0 { close(listen); unlink(socketURL.path) }
        try? FileManager.default.removeItem(at: tokenURL)
    }
}
