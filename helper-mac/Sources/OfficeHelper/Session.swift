import AppKit
import ScreenCaptureKit

/// Routes the mod's messages. Everything runs on the main actor; frames go straight to the server's writer queue.
@MainActor final class Session {
    let server: HelperServer
    let displays = VirtualDisplays()
    let placement = Placement()
    let cursor = CursorFocus()
    let hotkey = ExitHotkey()
    /// In-world use: the crosshair as the mouse (drags, clicks into typing mode, scrolling).
    let input = InWorldInput()
    /// The player's windows, moved onto a hologram's display while they're in a world.
    let windowMover = WindowMover()
    private var captures: [Int: SurfaceCapture] = [:]
    private var minecraftPid: pid_t?
    private var zoomedSurface: Int?
    private var nextGeneration: UInt64 = 0
    private var pollTimer: Timer?
    private var permissionTimer: Timer?
    /// How often Screen Recording / Accessibility are re-checked, so a grant reaches the mod without a restart.
    static let permissionPollSeconds: TimeInterval = 2
    private var askedScreenRecording = false
    private var askedAccessibility = false
    /// Called when the mod disconnects: the helper quits and its displays vanish with it.
    var onFinished: (() -> Void)?

    init(server: HelperServer) {
        self.server = server
        displays.log = Log.write; placement.log = Log.write; cursor.log = Log.write
        input.log = Log.write; windowMover.log = Log.write
        input.send = { [weak self] message in self?.server.send(message) }
        input.minecraftPid = { [weak self] in self?.minecraftPid }
        input.activateMinecraft = { [weak self] in self?.activateMinecraftApp() }
        input.display = { [weak self] slot in
            guard let self, let live = self.displays.displays[slot] else { return nil }
            let bounds = CGDisplayBounds(live.displayID)
            return bounds.isEmpty ? nil : (bounds, live.spec.width, live.spec.height)
        }
        displays.onTerminated = { [weak self] slot in self?.displayGone(slot: slot) }
        hotkey.onExit = { [weak self] in
            Log.write("exit hotkey")
            self?.endZoom(notifyMod: true)
        }
    }

    // MARK: Connection

    func connected(_ hello: HelloPayload, peerPid: pid_t) {
        minecraftPid = hello.mcPid > 0 ? hello.mcPid : peerPid
        Log.write("mod connected: mcPid \(hello.mcPid), peer pid \(peerPid)")
        pollTimer?.invalidate()
        pollTimer = Timer.scheduledTimer(withTimeInterval: 1, repeats: true) { [weak self] _ in
            MainActor.assumeIsolated { self?.poll() }
        }
        // Without this macOS never lists OfficeHelper under Accessibility: AXIsProcessTrusted() alone doesn't add it.
        if !AXIsProcessTrusted() { askAccessibilityOnce() }
        permissionTimer?.invalidate()
        permissionTimer = Timer.scheduledTimer(withTimeInterval: Self.permissionPollSeconds, repeats: true) { [weak self] _ in
            MainActor.assumeIsolated { self?.sendWelcomeIfPermissionsChanged() }
        }
    }

    func disconnected() {
        Log.write("mod disconnected")
        shutdown()
        onFinished?()
    }

    /// Restores everything this helper changed and removes its displays.
    func shutdown() {
        pollTimer?.invalidate(); pollTimer = nil
        permissionTimer?.invalidate(); permissionTimer = nil
        endZoom(notifyMod: false, activateMinecraft: false)
        input.shutdown()
        windowMover.restore() // before the displays go: every window back exactly where it was
        for capture in captures.values { capture.stop() }
        captures.removeAll()
        displays.removeAll()
    }

    nonisolated static var welcome: WelcomePayload {
        WelcomePayload(version: OfficeProtocol.version, screenRecording: CGPreflightScreenCaptureAccess(), accessibility: AXIsProcessTrusted())
    }

    // MARK: Messages

    func handle(_ message: Message) {
        switch message {
        case .listWindows:
            askScreenRecordingOnce()
            var excluded: Set<pid_t> = [ProcessInfo.processInfo.processIdentifier]
            if let minecraftPid { excluded.insert(minecraftPid) }
            Task { self.server.send(.windows(await WindowCatalog.list(excluding: excluded))) }
        case .createDisplay(let p): createDisplay(p)
        case .removeDisplay(let p): removeDisplay(slot: p.slot)
        case .capture(let p): capture(p)
        case .stop(let p): stopCapture(surface: p.surface)
        case .zoomIn(let p): zoomIn(p)
        case .zoomOut: endZoom(notifyMod: false)
        case .requestPermissions: requestPermissions()
        case .pointerDown, .pointerMove, .pointerUp, .click, .scroll, .exitTyping:
            if !AXIsProcessTrusted() { askAccessibilityOnce() }
            input.handle(message)
        case .windowsToDisplay(let p): moveWindows(p)
        case .windowsBack: windowMover.restore()
        case .hello, .ready:
            break // HELLO is handled by the server's handshake; READY by its frame flow
        case .welcome, .windows, .display, .frame, .patch, .ended, .exitZoom, .error, .typingExit, .dragDelta, .dragEnded:
            sendError("bad_request", "\(message.type) is a helper → mod message")
        }
    }

    private func moveWindows(_ p: WindowsToDisplayPayload) {
        guard let id = displays.displayID(slot: p.slot) else { sendError("not_found", "no display for slot \(p.slot)"); return }
        var excluded: Set<pid_t> = [getpid()]
        if let minecraftPid { excluded.insert(minecraftPid) }
        windowMover.moveWindows(to: id, excluding: excluded, onlyApps: p.apps.map(Set.init))
    }

    private func sendError(_ code: String, _ message: String) {
        Log.write("error \(code): \(message)")
        server.send(.error(ErrorPayload(code: code, message: message)))
    }

    // MARK: Displays

    private func createDisplay(_ p: CreateDisplayPayload) {
        let spec: DisplaySpec
        do { spec = try DisplaySpec(slot: p.slot, width: p.w, height: p.h) }
        catch let e as DisplaySpecError { sendError(e.code, e.message); return }
        catch { sendError("bad_request", "\(error)"); return }
        let id: CGDirectDisplayID
        do {
            if let old = displays.displays[p.slot], old.spec != spec { endCaptures(onSlot: p.slot, reason: "display removed") }
            id = try displays.create(spec)
        } catch {
            sendError("display_failed", "\(spec.name): \(error)")
            return
        }
        Task {
            guard await VirtualDisplays.waitOnline(id) else {
                self.sendError("display_failed", "\(spec.name) did not come online")
                return
            }
            guard self.displays.displayID(slot: spec.slot) == id else { return } // removed meanwhile
            self.server.send(.display(DisplayPayload(slot: spec.slot, displayId: id, w: spec.width, h: spec.height)))
        }
    }

    private func removeDisplay(slot: Int) {
        guard (1...DisplaySpec.maxSlots).contains(slot) else { sendError("bad_slot", "slot must be 1...\(DisplaySpec.maxSlots), got \(slot)"); return }
        endCaptures(onSlot: slot, reason: "display removed")
        displays.remove(slot: slot)
    }

    private func displayGone(slot: Int) { endCaptures(onSlot: slot, reason: "display removed") }

    private func endCaptures(onSlot slot: Int, reason: String) {
        guard let id = displays.displayID(slot: slot) else { return }
        for (surface, capture) in captures where capture.source == .display(id) { end(surface: surface, reason: reason) }
    }

    // MARK: Capture

    private func capture(_ p: CapturePayload) {
        stopCapture(surface: p.surface)
        guard CGPreflightScreenCaptureAccess() else {
            askScreenRecordingOnce()
            server.send(.ended(EndedPayload(surface: p.surface, reason: "no permission")))
            return
        }
        let source: SurfaceCapture.Source
        switch p.kind {
        case .display:
            guard let slot = p.slot, let id = displays.displayID(slot: slot) else {
                server.send(.ended(EndedPayload(surface: p.surface, reason: "no display")))
                return
            }
            source = .display(id)
        case .window:
            guard let key = p.windowKey.flatMap(WindowKey.parse), WindowCatalog.window(key.windowID) != nil else {
                server.send(.ended(EndedPayload(surface: p.surface, reason: "window closed")))
                return
            }
            source = .window(key)
        case .main:
            source = .main(excluding: minecraftPid)
        }
        let capture = SurfaceCapture(surface: p.surface, source: source, maxWidth: p.maxW, fps: p.fps)
        captures[p.surface] = capture
        server.markReady(surface: p.surface) // the first frame of a CAPTURE needs no READY; restarts still do
        start(capture)
    }

    private func start(_ capture: SurfaceCapture) {
        let surface = capture.surface
        nextGeneration &+= 1
        let generation = nextGeneration
        capture.onFrame = { [weak self] buffer, dirty, generation in
            self?.server.offer(buffer, surface: surface, generation: generation, dirty: dirty)
        }
        capture.onFailure = { [weak self] message in self?.captureFailed(surface: surface, message: message) }
        Task {
            do {
                try await capture.start(generation: generation, zoomed: self.zoomedSurface == surface)
                Log.write("capture surface \(surface) \(capture.source) generation \(generation)")
            } catch {
                guard self.captures[surface] === capture else { return }
                Log.write("capture surface \(surface) failed: \(error)")
                if case .window(let key) = capture.source, WindowCatalog.window(key.windowID) == nil {
                    self.end(surface: surface, reason: "window closed")
                } else {
                    self.end(surface: surface, reason: "capture failed: \(error)")
                }
            }
        }
    }

    private func captureFailed(surface: Int, message: String) {
        guard let capture = captures[surface] else { return }
        if case .window(let key) = capture.source, WindowCatalog.window(key.windowID) == nil {
            end(surface: surface, reason: "window closed")
        } else if case .display(let id) = capture.source, !VirtualDisplays.isOnline(id) {
            end(surface: surface, reason: "display removed")
        } else {
            end(surface: surface, reason: "capture failed: \(message)")
        }
    }

    /// Stops a surface, tells the mod why, and exits the zoom if it was the zoomed one.
    private func end(surface: Int, reason: String) {
        guard captures[surface] != nil else { return }
        let wasZoomed = zoomedSurface == surface
        stopCapture(surface: surface)
        Log.write("surface \(surface) ended: \(reason)")
        server.send(.ended(EndedPayload(surface: surface, reason: reason)))
        if wasZoomed { endZoom(notifyMod: true) }
    }

    private func stopCapture(surface: Int) {
        guard let capture = captures.removeValue(forKey: surface) else { return }
        capture.stop()
        server.forget(surface: surface)
        if zoomedSurface == surface { endZoom(notifyMod: false) }
    }

    /// Once a second: closed windows end their surface; resized windows restart capture at the new size.
    private func poll() {
        for (surface, capture) in captures {
            switch capture.source {
            case .window(let key):
                guard let window = WindowCatalog.window(key.windowID) else { end(surface: surface, reason: "window closed"); continue }
                if let size = capture.capturedWindowSize,
                   abs(size.width - window.frame.width) > 1 || abs(size.height - window.frame.height) > 1 {
                    start(capture)
                }
            case .display(let id):
                if !VirtualDisplays.isOnline(id) { end(surface: surface, reason: "display removed") }
            case .main:
                break
            }
        }
    }

    // MARK: Zoom

    private func zoomIn(_ p: ZoomInPayload) {
        guard let capture = captures[p.surface] else { sendError("not_found", "surface \(p.surface) is not captured"); return }
        if let current = zoomedSurface, current != p.surface { endZoom(notifyMod: false, activateMinecraft: false) }
        zoomedSurface = p.surface
        capture.setZoomed(true)
        if !AXIsProcessTrusted() {
            askAccessibilityOnce()
            sendError("no_accessibility", "exit hotkey and window placement need Accessibility")
        }
        hotkey.arm()
        switch capture.source {
        case .display(let id):
            cursor.focus(on: id, clamp: AXIsProcessTrusted())
        case .window(let key):
            let f = p.mcWindowFrame
            placement.place(windowID: key.windowID, at: CGRect(x: f.x, y: f.y, width: f.w, height: f.h), minecraftPid: minecraftPid)
        case .main:
            break // Mirror is view-only on a Mac (spec 5.9): nothing to work on
        }
        Log.write("zoom in surface \(p.surface)")
    }

    /// Leaves the zoom: hotkey off, cursor released, placed window restored, Minecraft in front again.
    func endZoom(notifyMod: Bool, activateMinecraft: Bool = true) {
        hotkey.disarm()
        let wasZoomed = zoomedSurface != nil || cursor.isActive || placement.placed != nil
        if let surface = zoomedSurface { captures[surface]?.setZoomed(false) }
        zoomedSurface = nil
        cursor.release(restore: true)
        placement.restore()
        if activateMinecraft, wasZoomed { activateMinecraftApp() }
        if notifyMod { server.send(.exitZoom) }
        if wasZoomed { Log.write("zoom out (notify mod: \(notifyMod))") }
    }

    func activateMinecraftApp() {
        guard let pid = minecraftPid, let minecraft = NSRunningApplication(processIdentifier: pid) else { return }
        let front = NSWorkspace.shared.frontmostApplication
        if front?.processIdentifier == pid { return }
        let activated = front.map { minecraft.activate(from: $0, options: []) } ?? false
        if !activated { minecraft.activate(options: [.activateAllWindows]) }
    }

    // MARK: Permissions (native prompts, asked once per launch unless the mod asks again, and only when needed)

    /// The mod's REQUEST_PERMISSIONS (its "Set up screens" link): prompt for whatever is missing, then report.
    private func requestPermissions() {
        if !AXIsProcessTrusted() { askAccessibility() }
        if !CGPreflightScreenCaptureAccess() {
            askedScreenRecording = true
            _ = CGRequestScreenCaptureAccess()
        }
        Log.write("permissions requested: screen recording \(CGPreflightScreenCaptureAccess()), accessibility \(AXIsProcessTrusted())")
        server.sendWelcome(Session.welcome)
    }

    /// Every couple of seconds: a WELCOME with the new values when a permission changed since the last one sent.
    private func sendWelcomeIfPermissionsChanged() {
        let now = Session.welcome
        guard let last = server.lastWelcome, last != now else { return }
        Log.write("permissions changed: screen recording \(now.screenRecording), accessibility \(now.accessibility)")
        server.sendWelcome(now)
    }

    private func askScreenRecordingOnce() {
        guard !askedScreenRecording, !CGPreflightScreenCaptureAccess() else { return }
        askedScreenRecording = true
        _ = CGRequestScreenCaptureAccess()
    }

    private func askAccessibilityOnce() {
        guard !askedAccessibility else { return }
        askAccessibility()
    }

    /// Adds OfficeHelper to System Settings → Accessibility (switched off) and shows macOS's
    /// "would like to control this computer" prompt.
    private func askAccessibility() {
        askedAccessibility = true
        let key = kAXTrustedCheckOptionPrompt.takeUnretainedValue() as String
        let trusted = AXIsProcessTrustedWithOptions([key: true] as CFDictionary)
        Log.write("asked macOS for Accessibility (trusted \(trusted))")
    }
}

/// Diagnostics (never screen contents or keystrokes) in the owner-only support folder.
enum Log {
    static var url: URL { HelperServer.defaultDirectory.appendingPathComponent("helper.log") }

    static func rotate() {
        if let size = (try? FileManager.default.attributesOfItem(atPath: url.path))?[.size] as? NSNumber, size.intValue > 1_000_000 {
            try? FileManager.default.removeItem(at: url)
        }
    }

    static func write(_ line: String) {
        let stamp = ISO8601DateFormatter().string(from: Date())
        guard let data = "\(stamp) \(line)\n".data(using: .utf8) else { return }
        if let handle = try? FileHandle(forWritingTo: url) { handle.seekToEndOfFile(); handle.write(data); try? handle.close() }
        else { FileManager.default.createFile(atPath: url.path, contents: data, attributes: [.posixPermissions: 0o600]) }
    }
}
