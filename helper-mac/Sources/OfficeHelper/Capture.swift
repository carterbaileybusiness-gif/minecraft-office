import AppKit
import ScreenCaptureKit
import CoreMedia
import CoreVideo

/// Each stream gets its own receiver (from Home Base's CaptureEngine). Only one latest frame waits for the main
/// queue; dirty rectangles of coalesced frames accumulate.
private final class CaptureReceiver: NSObject, SCStreamOutput, SCStreamDelegate {
    private let lock = NSLock()
    private var latest: CVPixelBuffer?
    /// Changed regions (output pixels) since the last delivered frame; nil means the whole frame.
    private var dirty: [CGRect]? = nil
    private var dirtyKnown = false
    private var scheduled = false
    private var alive = true
    let frame: @MainActor (CVPixelBuffer, [CGRect]?) -> Void
    let failure: @MainActor (String) -> Void
    init(frame: @escaping @MainActor (CVPixelBuffer, [CGRect]?) -> Void, failure: @escaping @MainActor (String) -> Void) {
        self.frame = frame; self.failure = failure
    }
    func invalidate() { lock.lock(); alive = false; latest = nil; lock.unlock() }
    func stream(_ stream: SCStream, didOutputSampleBuffer sampleBuffer: CMSampleBuffer, of outputType: SCStreamOutputType) {
        guard outputType == .screen, sampleBuffer.isValid,
              let attachments = CMSampleBufferGetSampleAttachmentsArray(sampleBuffer, createIfNecessary: false) as? [[SCStreamFrameInfo: Any]],
              let raw = attachments.first?[.status] as? Int, raw == SCFrameStatus.complete.rawValue,
              let buffer = sampleBuffer.imageBuffer else { return }
        let rects = (attachments.first?[.dirtyRects] as? [NSDictionary])?.compactMap { CGRect(dictionaryRepresentation: $0) }
        lock.lock()
        guard alive else { lock.unlock(); return }
        if latest == nil { dirty = rects; dirtyKnown = true }
        else if let rects, dirty != nil { dirty! += rects }
        else { dirty = nil }
        latest = buffer
        let enqueue = !scheduled
        scheduled = true
        lock.unlock()
        guard enqueue else { return }
        DispatchQueue.main.async { [weak self] in
            guard let self else { return }
            self.lock.lock()
            let buffer = self.alive ? self.latest : nil
            let dirty = self.dirtyKnown ? self.dirty : nil
            self.latest = nil; self.scheduled = false; self.dirty = nil; self.dirtyKnown = false
            self.lock.unlock()
            if let buffer { MainActor.assumeIsolated { self.frame(buffer, dirty) } }
        }
    }
    func stream(_ stream: SCStream, didStopWithError error: Error) {
        lock.lock(); let report = alive; lock.unlock()
        if report { DispatchQueue.main.async { [weak self] in MainActor.assumeIsolated { self?.failure(error.localizedDescription) } } }
    }
    func streamDidBecomeInactive(_ stream: SCStream) {
        lock.lock(); let report = alive; lock.unlock()
        if report { DispatchQueue.main.async { [weak self] in MainActor.assumeIsolated { self?.failure("stream inactive") } } }
    }
}

enum CaptureError: Error, CustomStringConvertible {
    case noDisplay(CGDirectDisplayID), wrongDisplay(expected: CGDirectDisplayID, got: CGDirectDisplayID), noWindow(String)
    case minecraftNotListed(pid_t)
    var description: String {
        switch self {
        case .minecraftNotListed(let pid): return "Minecraft (pid \(pid)) can't be excluded from the mirror"
        case .noDisplay(let id): return "display \(id) is not capturable"
        case .wrongDisplay(let e, let g): return "matched display \(g), expected \(e)"
        case .noWindow(let key): return "window \(key) not found"
        }
    }
}

/// One captured surface: a virtual display (cursor shown), one app window, or the main display minus Minecraft (Mirror).
@MainActor final class SurfaceCapture {
    /// `main(excluding:)`: the main display with every window of that app (Minecraft, HELLO's mcPid) left out.
    enum Source: Equatable { case display(CGDirectDisplayID), window(WindowKey), main(excluding: pid_t?) }

    let surface: Int
    let source: Source
    let maxWidth: Int
    private(set) var requestedFPS: Int
    private(set) var generation: UInt64 = 0
    /// Window captures restart when the window's size changes (e.g. placed over Minecraft while zoomed).
    private(set) var capturedWindowSize: CGSize?
    var onFrame: ((CVPixelBuffer, [CGRect]?, UInt64) -> Void)?
    var onFailure: ((String) -> Void)?

    private var stream: SCStream?
    private var receiver: CaptureReceiver?
    private var configuration: SCStreamConfiguration?
    private var generations = CaptureGeneration()
    private let frameQueue = DispatchQueue(label: "local.carter.office-helper.frames", qos: .userInitiated)
    static let zoomedFPS = 30

    init(surface: Int, source: Source, maxWidth: Int, fps: Int) {
        self.surface = surface; self.source = source
        self.maxWidth = max(64, min(4096, maxWidth))
        self.requestedFPS = max(1, min(60, fps))
    }

    func start(generation: UInt64, zoomed: Bool) async throws {
        let ticket = generations.begin()
        detachStream()
        self.generation = generation
        var content = try await SCShareableContent.excludingDesktopWindows(false, onScreenWindowsOnly: false)
        guard generations.accepts(ticket) else { return }
        if case .main(let excludedPid) = source {
            // Never mirror without Minecraft excluded: wait (backoff, ~5 s) for its app to be listed, else fail.
            var attempt = 0
            while true {
                let found = excludedPid.map { pid in content.applications.contains { $0.processID == pid } } ?? false
                switch MirrorExclusion.decide(found: found, attempt: attempt) {
                case .capture: break
                case .fail: throw CaptureError.minecraftNotListed(excludedPid ?? -1)
                case .retry(let delay):
                    Log.write("mirror: Minecraft (pid \(excludedPid ?? -1)) not in shareable content yet; retry in \(delay) s")
                    try await Task.sleep(nanoseconds: UInt64(delay * 1_000_000_000))
                    guard generations.accepts(ticket) else { return }
                    content = try await SCShareableContent.excludingDesktopWindows(false, onScreenWindowsOnly: false)
                    guard generations.accepts(ticket) else { return }
                    attempt += 1
                    continue
                }
                break
            }
        }
        let configuration = SCStreamConfiguration()
        configuration.minimumFrameInterval = CMTime(value: 1, timescale: CMTimeScale(zoomed ? Self.zoomedFPS : requestedFPS))
        configuration.queueDepth = 3
        configuration.capturesAudio = false
        configuration.pixelFormat = kCVPixelFormatType_32BGRA
        let filter: SCContentFilter
        switch source {
        case .display(let id):
            // Match strictly by displayID and assert it: never fall back to another (e.g. the main) display.
            guard let display = content.displays.first(where: { $0.displayID == id }) else { throw CaptureError.noDisplay(id) }
            guard display.displayID == id, display.frame.equalTo(CGDisplayBounds(id)) else {
                throw CaptureError.wrongDisplay(expected: id, got: display.displayID)
            }
            let pixelsWide = max(1, CGDisplayPixelsWide(id)), pixelsHigh = max(1, CGDisplayPixelsHigh(id))
            let width = min(pixelsWide, maxWidth)
            configuration.width = width
            configuration.height = max(1, Int((Double(pixelsHigh) * Double(width) / Double(pixelsWide)).rounded()))
            configuration.showsCursor = true
            filter = SCContentFilter(display: display, excludingWindows: [])
            capturedWindowSize = nil
        case .main(let excludedPid):
            // Mirror: "it should just be my screen", minus Minecraft so there is no Minecraft-inside-Minecraft.
            let id = CGMainDisplayID()
            guard let display = content.displays.first(where: { $0.displayID == id }) else { throw CaptureError.noDisplay(id) }
            let pixelsWide = max(1, CGDisplayPixelsWide(id)), pixelsHigh = max(1, CGDisplayPixelsHigh(id))
            let width = min(pixelsWide, maxWidth)
            configuration.width = width
            configuration.height = max(1, Int((Double(pixelsHigh) * Double(width) / Double(pixelsWide)).rounded()))
            configuration.showsCursor = true
            let minecraft = content.applications.filter { excludedPid != nil && $0.processID == excludedPid! }
            guard !minecraft.isEmpty else { throw CaptureError.minecraftNotListed(excludedPid ?? -1) } // checked above
            filter = SCContentFilter(display: display, excludingApplications: minecraft, exceptingWindows: [])
            capturedWindowSize = nil
        case .window(let key):
            guard let window = content.windows.first(where: { $0.windowID == key.windowID }),
                  key.bundleId.isEmpty || window.owningApplication?.bundleIdentifier == key.bundleId else {
                throw CaptureError.noWindow(key.description)
            }
            // Cap at about 8 megapixels; never upscale past 2× (Retina).
            let budget = sqrt(8_000_000 / max(1, window.frame.width * window.frame.height))
            let scale = max(0.1, min(2, Double(maxWidth) / window.frame.width, budget))
            configuration.width = max(1, Int(window.frame.width * scale))
            configuration.height = max(1, Int(window.frame.height * scale))
            configuration.showsCursor = false
            configuration.ignoreShadowsSingleWindow = true
            filter = SCContentFilter(desktopIndependentWindow: window)
            capturedWindowSize = window.frame.size
        }
        let output = CaptureReceiver(frame: { [weak self] buffer, dirty in
            guard let self, self.generations.accepts(ticket) else { return }
            self.onFrame?(buffer, dirty, generation)
        }, failure: { [weak self] message in
            guard let self, self.generations.accepts(ticket) else { return }
            self.onFailure?(message)
        })
        let next = SCStream(filter: filter, configuration: configuration, delegate: output)
        try next.addStreamOutput(output, type: .screen, sampleHandlerQueue: frameQueue)
        stream = next; receiver = output; self.configuration = configuration
        do { try await next.startCapture() }
        catch {
            if generations.accepts(ticket) { generations.cancel(); stream = nil; receiver = nil }
            output.invalidate()
            throw error
        }
        if !generations.accepts(ticket) { output.invalidate(); try? await next.stopCapture() }
    }

    func setZoomed(_ zoomed: Bool) { setFrameRate(zoomed ? Self.zoomedFPS : requestedFPS) }

    private func setFrameRate(_ fps: Int) {
        guard let stream, let configuration, configuration.minimumFrameInterval.timescale != CMTimeScale(fps) else { return }
        configuration.minimumFrameInterval = CMTime(value: 1, timescale: CMTimeScale(fps))
        stream.updateConfiguration(configuration) { _ in }
    }

    /// Invalidates synchronously so a late frame from the old stream is never delivered.
    func stop() {
        generations.cancel()
        detachStream()
    }

    private func detachStream() {
        let previous = stream
        stream = nil; receiver?.invalidate(); receiver = nil; configuration = nil
        if let previous { Task { try? await previous.stopCapture() } }
    }
}

/// From Home Base BridgeCore: a newer start or a stop makes callbacks from older streams stale.
struct CaptureGeneration {
    private var generation: UInt64 = 0
    private var active = false
    mutating func begin() -> UInt64 { generation &+= 1; active = true; return generation }
    mutating func cancel() { active = false }
    func accepts(_ candidate: UInt64) -> Bool { active && generation == candidate }
}

enum WindowCatalog {
    /// Normal app windows for LIST_WINDOWS. With Screen Recording, ScreenCaptureKit gives titles and off-screen
    /// windows; without it, on-screen windows from CGWindowList (titles empty).
    static func list(excluding pids: Set<pid_t>) async -> [WindowInfo] {
        if CGPreflightScreenCaptureAccess(),
           let content = try? await SCShareableContent.excludingDesktopWindows(true, onScreenWindowsOnly: false) {
            return content.windows.compactMap { window -> WindowInfo? in
                guard window.windowLayer == 0, window.frame.width > 40, window.frame.height > 40,
                      let app = window.owningApplication, !pids.contains(app.processID),
                      NSRunningApplication(processIdentifier: app.processID)?.activationPolicy == .regular,
                      window.isOnScreen || !(window.title ?? "").isEmpty else { return nil }
                return WindowInfo(windowKey: WindowKey(bundleId: app.bundleIdentifier, windowID: window.windowID).description,
                                  app: app.applicationName, title: window.title ?? "",
                                  w: Int(window.frame.width), h: Int(window.frame.height))
            }.sorted { ($0.app, $0.title) < ($1.app, $1.title) }
        }
        let rows = CGWindowListCopyWindowInfo([.optionOnScreenOnly, .excludeDesktopElements], kCGNullWindowID) as? [[String: Any]] ?? []
        return rows.compactMap { row -> WindowInfo? in
            guard (row[kCGWindowLayer as String] as? NSNumber)?.intValue == 0,
                  let pid = (row[kCGWindowOwnerPID as String] as? NSNumber)?.int32Value, !pids.contains(pid),
                  let app = NSRunningApplication(processIdentifier: pid), app.activationPolicy == .regular,
                  let id = (row[kCGWindowNumber as String] as? NSNumber)?.uint32Value,
                  let bounds = frame(row), bounds.width > 40, bounds.height > 40 else { return nil }
            return WindowInfo(windowKey: WindowKey(bundleId: app.bundleIdentifier ?? "", windowID: id).description,
                              app: app.localizedName ?? (row[kCGWindowOwnerName as String] as? String ?? ""),
                              title: row[kCGWindowName as String] as? String ?? "",
                              w: Int(bounds.width), h: Int(bounds.height))
        }.sorted { ($0.app, $0.title) < ($1.app, $1.title) }
    }

    static func frame(_ row: [String: Any]) -> CGRect? {
        (row[kCGWindowBounds as String] as? [String: Any]).flatMap { CGRect(dictionaryRepresentation: $0 as CFDictionary) }
    }

    /// Current frame (global points, top-left origin) and owner pid of a window, nil when it no longer exists.
    static func window(_ id: CGWindowID) -> (pid: pid_t, frame: CGRect, title: String?)? {
        guard let rows = CGWindowListCopyWindowInfo(.optionIncludingWindow, id) as? [[String: Any]],
              let row = rows.first(where: { ($0[kCGWindowNumber as String] as? NSNumber)?.uint32Value == id }),
              let pid = (row[kCGWindowOwnerPID as String] as? NSNumber)?.int32Value, let bounds = frame(row) else { return nil }
        return (pid, bounds, row[kCGWindowName as String] as? String)
    }
}
