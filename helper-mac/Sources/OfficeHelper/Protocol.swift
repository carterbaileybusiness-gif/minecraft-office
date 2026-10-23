import Foundation

// Pure protocol code (Foundation only) so check.sh can compile it without AppKit / ScreenCaptureKit.
//
// Envelope (all integers big-endian): u32 length of (type + payload) | u8 type | payload.
// Control payloads are compact UTF-8 JSON with sorted keys; FRAME and PATCH are binary (Home Base layouts):
//   FRAME: u32 surface, u64 generation, u32 width, u32 height, width × height × 4 RGBA bytes (rows top to bottom).
//   PATCH: u32 surface, u64 generation, u32 width, u32 height, u32 count, count × (u32 x, y, w, h),
//          then each rectangle's RGBA rows in order.

enum OfficeProtocol {
    static let version = 1
    /// Largest accepted value of the u32 length field (type byte + payload), either direction.
    static let maxFrameLength = 64 * 1024 * 1024
    /// The helper reads only JSON from the mod, so its inbound limit is much smaller.
    static let maxControlLength = 64 * 1024
    /// PATCH messages carry at most this many rectangles (more are merged into one).
    static let maxPatchRects = 32

    static func appendBE(_ data: inout Data, _ value: UInt64, bytes: Int) {
        for shift in stride(from: (bytes - 1) * 8, through: 0, by: -8) { data.append(UInt8((value >> UInt64(shift)) & 0xff)) }
    }

    static func readBE(_ data: Data, at offset: Int, bytes: Int) -> UInt64? {
        guard offset >= 0, offset + bytes <= data.count else { return nil }
        let start = data.startIndex + offset
        return data[start..<(start + bytes)].reduce(0) { ($0 << 8) | UInt64($1) }
    }

    /// Builds the envelope. Throws when the length would exceed `maxFrameLength`.
    static func envelope(_ type: MessageType, _ parts: [Data]) throws -> Data {
        let length = 1 + parts.reduce(0) { $0 + $1.count }
        guard length <= maxFrameLength else { throw ProtocolError.frameTooLarge(length) }
        var out = Data(capacity: 4 + length)
        appendBE(&out, UInt64(length), bytes: 4)
        out.append(type.rawValue)
        for part in parts { out.append(part) }
        return out
    }

    /// Header for an envelope whose payload is written separately (avoids copying large frames).
    static func header(_ type: MessageType, payloadLength: Int) throws -> Data {
        let length = 1 + payloadLength
        guard length <= maxFrameLength else { throw ProtocolError.frameTooLarge(length) }
        var out = Data(capacity: 5)
        appendBE(&out, UInt64(length), bytes: 4)
        out.append(type.rawValue)
        return out
    }
}

enum MessageType: UInt8, CaseIterable {
    // Mod → helper
    case hello = 1, listWindows = 2, createDisplay = 3, removeDisplay = 4, capture = 5, stop = 6, ready = 7, zoomIn = 8, zoomOut = 9
    /// Ask macOS for the missing permissions now (native prompts); the helper answers with a fresh WELCOME.
    case requestPermissions = 10
    // Helper → mod
    case welcome = 64, windows = 65, display = 66, frame = 67, patch = 68, ended = 69, exitZoom = 70, error = 71
}

enum ProtocolError: Error, Equatable {
    case frameTooLarge(Int)
    case emptyFrame
    case unknownType(UInt8)
    case badPayload(String)
}

/// Streaming envelope decoder: push bytes as they arrive, then pull complete messages.
struct FrameReader {
    let maxLength: Int
    private var buffer = Data()
    init(maxLength: Int) { self.maxLength = maxLength }

    mutating func push(_ data: Data) { buffer.append(data) }

    /// The next complete (type, payload), nil when more bytes are needed. A bad length or type throws at once,
    /// before the body is read, so an oversized frame never gets buffered.
    mutating func next() throws -> (MessageType, Data)? {
        guard buffer.count >= 4, let raw = OfficeProtocol.readBE(buffer, at: 0, bytes: 4) else { return nil }
        let length = Int(raw)
        try Self.validate(length: length, max: maxLength)
        guard buffer.count >= 5 else { return nil }
        let typeByte = buffer[buffer.startIndex + 4]
        guard let type = MessageType(rawValue: typeByte) else { throw ProtocolError.unknownType(typeByte) }
        guard buffer.count >= 4 + length else { return nil }
        let start = buffer.startIndex
        let payload = Data(buffer[(start + 5)..<(start + 4 + length)])
        buffer = Data(buffer[(start + 4 + length)...])
        return (type, payload)
    }

    static func validate(length: Int, max: Int) throws {
        if length < 1 { throw ProtocolError.emptyFrame }
        if length > max { throw ProtocolError.frameTooLarge(length) }
    }
}

// MARK: Payloads

struct HelloPayload: Codable, Equatable { var token: String; var version: Int; var mcPid: Int32 }
struct CreateDisplayPayload: Codable, Equatable { var slot: Int; var w: Int; var h: Int }
struct SlotPayload: Codable, Equatable { var slot: Int }
struct SurfacePayload: Codable, Equatable { var surface: Int }

enum CaptureKind: String, Codable, Equatable { case display, window }

struct CapturePayload: Codable, Equatable {
    var surface: Int
    var kind: CaptureKind
    /// Required when kind == display.
    var slot: Int?
    /// Required when kind == window: "<bundleId>|<CGWindowID>".
    var windowKey: String?
    /// Largest captured width in pixels (height follows the aspect ratio).
    var maxW: Int
    var fps: Int
}

/// Global display coordinates in points, top-left origin (Quartz / CGWindow space).
struct WindowRect: Codable, Equatable { var x: Int; var y: Int; var w: Int; var h: Int }
struct ZoomInPayload: Codable, Equatable { var surface: Int; var mcWindowFrame: WindowRect }

struct WelcomePayload: Codable, Equatable { var version: Int; var screenRecording: Bool; var accessibility: Bool }
struct WindowInfo: Codable, Equatable { var windowKey: String; var app: String; var title: String; var w: Int; var h: Int }
struct DisplayPayload: Codable, Equatable { var slot: Int; var displayId: UInt32; var w: Int; var h: Int }
struct EndedPayload: Codable, Equatable { var surface: Int; var reason: String }
struct ErrorPayload: Codable, Equatable { var code: String; var message: String }

struct PixelRect: Equatable { var x: UInt32; var y: UInt32; var w: UInt32; var h: UInt32 }

struct FrameImage: Equatable {
    var surface: UInt32
    var generation: UInt64
    var width: UInt32
    var height: UInt32
    var rgba: Data
}

struct PatchImage: Equatable {
    var surface: UInt32
    var generation: UInt64
    var width: UInt32
    var height: UInt32
    var rects: [PixelRect]
    var rgba: Data
}

/// `windowKey` = "<bundleId>|<CGWindowID>". The id follows the last "|"; the bundle id may be empty.
struct WindowKey: Equatable, CustomStringConvertible {
    var bundleId: String
    var windowID: UInt32
    var description: String { "\(bundleId)|\(windowID)" }
    static func parse(_ key: String) -> WindowKey? {
        guard let bar = key.lastIndex(of: "|"), let id = UInt32(key[key.index(after: bar)...]) else { return nil }
        return WindowKey(bundleId: String(key[..<bar]), windowID: id)
    }
}

// MARK: Messages

enum Message: Equatable {
    case hello(HelloPayload)
    case listWindows
    case createDisplay(CreateDisplayPayload)
    case removeDisplay(SlotPayload)
    case capture(CapturePayload)
    case stop(SurfacePayload)
    case ready(SurfacePayload)
    case zoomIn(ZoomInPayload)
    case zoomOut
    case requestPermissions
    case welcome(WelcomePayload)
    case windows([WindowInfo])
    case display(DisplayPayload)
    case frame(FrameImage)
    case patch(PatchImage)
    case ended(EndedPayload)
    case exitZoom
    case error(ErrorPayload)

    var type: MessageType {
        switch self {
        case .hello: return .hello
        case .listWindows: return .listWindows
        case .createDisplay: return .createDisplay
        case .removeDisplay: return .removeDisplay
        case .capture: return .capture
        case .stop: return .stop
        case .ready: return .ready
        case .zoomIn: return .zoomIn
        case .zoomOut: return .zoomOut
        case .requestPermissions: return .requestPermissions
        case .welcome: return .welcome
        case .windows: return .windows
        case .display: return .display
        case .frame: return .frame
        case .patch: return .patch
        case .ended: return .ended
        case .exitZoom: return .exitZoom
        case .error: return .error
        }
    }

    private static let encoder: JSONEncoder = {
        let e = JSONEncoder()
        e.outputFormatting = [.sortedKeys, .withoutEscapingSlashes]
        return e
    }()

    private struct Empty: Codable {}

    static func json<T: Encodable>(_ value: T) throws -> Data { try encoder.encode(value) }

    /// The payload bytes (no envelope).
    func payload() throws -> Data {
        switch self {
        case .hello(let p): return try Self.json(p)
        case .listWindows, .zoomOut, .requestPermissions, .exitZoom: return try Self.json(Empty())
        case .createDisplay(let p): return try Self.json(p)
        case .removeDisplay(let p): return try Self.json(p)
        case .capture(let p): return try Self.json(p)
        case .stop(let p), .ready(let p): return try Self.json(p)
        case .zoomIn(let p): return try Self.json(p)
        case .welcome(let p): return try Self.json(p)
        case .windows(let p): return try Self.json(p)
        case .display(let p): return try Self.json(p)
        case .ended(let p): return try Self.json(p)
        case .error(let p): return try Self.json(p)
        case .frame(let f):
            guard Int(f.width) * Int(f.height) * 4 == f.rgba.count else { throw ProtocolError.badPayload("frame size") }
            var out = Self.imageHeader(surface: f.surface, generation: f.generation, width: f.width, height: f.height)
            try Self.checkSize(out.count + f.rgba.count)
            out.append(f.rgba)
            return out
        case .patch(let p):
            try Self.validate(rects: p.rects, width: p.width, height: p.height, pixelBytes: p.rgba.count)
            var out = Self.imageHeader(surface: p.surface, generation: p.generation, width: p.width, height: p.height)
            OfficeProtocol.appendBE(&out, UInt64(p.rects.count), bytes: 4)
            for r in p.rects { for v in [r.x, r.y, r.w, r.h] { OfficeProtocol.appendBE(&out, UInt64(v), bytes: 4) } }
            try Self.checkSize(out.count + p.rgba.count)
            out.append(p.rgba)
            return out
        }
    }

    /// The full envelope: u32 length | u8 type | payload.
    func encode() throws -> Data { try OfficeProtocol.envelope(type, [try payload()]) }

    static func imageHeader(surface: UInt32, generation: UInt64, width: UInt32, height: UInt32) -> Data {
        var out = Data(capacity: 20)
        OfficeProtocol.appendBE(&out, UInt64(surface), bytes: 4)
        OfficeProtocol.appendBE(&out, generation, bytes: 8)
        OfficeProtocol.appendBE(&out, UInt64(width), bytes: 4)
        OfficeProtocol.appendBE(&out, UInt64(height), bytes: 4)
        return out
    }

    private static func checkSize(_ payload: Int) throws {
        if 1 + payload > OfficeProtocol.maxFrameLength { throw ProtocolError.frameTooLarge(1 + payload) }
    }

    static func validate(rects: [PixelRect], width: UInt32, height: UInt32, pixelBytes: Int) throws {
        guard !rects.isEmpty, rects.count <= OfficeProtocol.maxPatchRects else { throw ProtocolError.badPayload("patch rect count") }
        var total = 0
        for r in rects {
            guard r.w > 0, r.h > 0, UInt64(r.x) + UInt64(r.w) <= UInt64(width), UInt64(r.y) + UInt64(r.h) <= UInt64(height) else {
                throw ProtocolError.badPayload("patch rect out of bounds")
            }
            total += Int(r.w) * Int(r.h) * 4
        }
        guard total == pixelBytes else { throw ProtocolError.badPayload("patch pixel bytes") }
    }

    private static func decodeJSON<T: Decodable>(_ type: T.Type, _ data: Data) throws -> T {
        do { return try JSONDecoder().decode(type, from: data) }
        catch { throw ProtocolError.badPayload("\(T.self): \(error)") }
    }

    static func decode(type: MessageType, payload: Data) throws -> Message {
        switch type {
        case .hello: return .hello(try decodeJSON(HelloPayload.self, payload))
        case .listWindows: return .listWindows
        case .createDisplay: return .createDisplay(try decodeJSON(CreateDisplayPayload.self, payload))
        case .removeDisplay: return .removeDisplay(try decodeJSON(SlotPayload.self, payload))
        case .capture:
            let p = try decodeJSON(CapturePayload.self, payload)
            switch p.kind {
            case .display: guard p.slot != nil else { throw ProtocolError.badPayload("display capture needs slot") }
            case .window: guard p.windowKey.flatMap(WindowKey.parse) != nil else { throw ProtocolError.badPayload("window capture needs windowKey") }
            }
            return .capture(p)
        case .stop: return .stop(try decodeJSON(SurfacePayload.self, payload))
        case .ready: return .ready(try decodeJSON(SurfacePayload.self, payload))
        case .zoomIn: return .zoomIn(try decodeJSON(ZoomInPayload.self, payload))
        case .zoomOut: return .zoomOut
        case .requestPermissions: return .requestPermissions
        case .welcome: return .welcome(try decodeJSON(WelcomePayload.self, payload))
        case .windows: return .windows(try decodeJSON([WindowInfo].self, payload))
        case .display: return .display(try decodeJSON(DisplayPayload.self, payload))
        case .ended: return .ended(try decodeJSON(EndedPayload.self, payload))
        case .exitZoom: return .exitZoom
        case .error: return .error(try decodeJSON(ErrorPayload.self, payload))
        case .frame:
            guard let s = OfficeProtocol.readBE(payload, at: 0, bytes: 4), let g = OfficeProtocol.readBE(payload, at: 4, bytes: 8),
                  let w = OfficeProtocol.readBE(payload, at: 12, bytes: 4), let h = OfficeProtocol.readBE(payload, at: 16, bytes: 4),
                  Int(w) * Int(h) * 4 == payload.count - 20 else { throw ProtocolError.badPayload("frame size") }
            return .frame(FrameImage(surface: UInt32(s), generation: g, width: UInt32(w), height: UInt32(h),
                                     rgba: Data(payload.dropFirst(20))))
        case .patch:
            guard let s = OfficeProtocol.readBE(payload, at: 0, bytes: 4), let g = OfficeProtocol.readBE(payload, at: 4, bytes: 8),
                  let w = OfficeProtocol.readBE(payload, at: 12, bytes: 4), let h = OfficeProtocol.readBE(payload, at: 16, bytes: 4),
                  let n = OfficeProtocol.readBE(payload, at: 20, bytes: 4), n <= UInt64(OfficeProtocol.maxPatchRects) else {
                throw ProtocolError.badPayload("patch header")
            }
            var rects: [PixelRect] = []
            for i in 0..<Int(n) {
                let base = 24 + i * 16
                guard let x = OfficeProtocol.readBE(payload, at: base, bytes: 4), let y = OfficeProtocol.readBE(payload, at: base + 4, bytes: 4),
                      let rw = OfficeProtocol.readBE(payload, at: base + 8, bytes: 4), let rh = OfficeProtocol.readBE(payload, at: base + 12, bytes: 4) else {
                    throw ProtocolError.badPayload("patch rects")
                }
                rects.append(PixelRect(x: UInt32(x), y: UInt32(y), w: UInt32(rw), h: UInt32(rh)))
            }
            let pixels = Data(payload.dropFirst(24 + Int(n) * 16))
            try validate(rects: rects, width: UInt32(w), height: UInt32(h), pixelBytes: pixels.count)
            return .patch(PatchImage(surface: UInt32(s), generation: g, width: UInt32(w), height: UInt32(h), rects: rects, rgba: pixels))
        }
    }
}

// MARK: Handshake

enum HandshakeResult: Equatable {
    case accept(HelloPayload)
    case badToken
    case badVersion
    case notHello
}

enum Handshake {
    /// The first message must be HELLO with the launch token (compared in constant time) and protocol version 1.
    static func check(_ message: Message?, token: String) -> HandshakeResult {
        guard case .hello(let hello)? = message else { return .notHello }
        guard constantTimeEqual(hello.token, token) else { return .badToken }
        guard hello.version == OfficeProtocol.version else { return .badVersion }
        return .accept(hello)
    }

    static func constantTimeEqual(_ a: String, _ b: String) -> Bool {
        let x = Array(a.utf8), y = Array(b.utf8)
        guard x.count == y.count, !y.isEmpty else { return false }
        return zip(x, y).reduce(0) { $0 | ($1.0 ^ $1.1) } == 0
    }
}
