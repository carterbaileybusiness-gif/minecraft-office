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
    /// The mini Dock: installed apps, icons, opening an app onto a hologram's display (spec 5.10).
    let launcher = AppLauncher()
    /// The display map's Identify: a big label on every screen for 3 s.
    let identifyOverlay = IdentifyOverlay()
    private var captures: [Int: SurfaceCapture] = [:]
    /// Mirrors of a chosen monitor (CAPTURE main + display), by surface: while that monitor is unplugged the Mirror shows
    /// the main display, and it switches back when the monitor returns (checked once a second).
    private var mirrorRequests: [Int: CapturePayload] = [:]
    private var minecraftPid: pid_t?
    private var zoomedSurface: Int?
    private var nextGeneration: UInt64 = 0
    private var pollTimer: Timer?
    private var permissionTimer: Timer?
    /// How often Screen Recording / Accessibility are re-checked, so a grant reaches the mod without a restart.
    static let permissionPollSeconds: TimeInterval = 2
    private var askedScreenRecording = false
    private var askedAccessibility = false
    /// The mod's HELLO listed "screens": it understands DISPLAYS pushes and CURSOR `display`.
    private var screensMod = false
    /// Where the user put the Office displays (SET_ORIGINS), beside the socket and token.
    private lazy var arrangement = ArrangementStore(url: server.directory.appendingPathComponent("display-arrangement.json"))
    /// Debounces DISPLAYS pushes after display reconfigurations (each change comes as several callbacks).
    private var reconfigureTicket = 0
    static let reconfigureDebounceSeconds = 0.3
    /// Called when the mod disconnects: the helper quits and its displays vanish with it.
    var onFinished: (() -> Void)?

    init(server: HelperServer) {
        self.server = server
        displays.log = Log.write; placement.log = Log.write; cursor.log = Log.write
        input.log = Log.write; launcher.log = Log.write
        input.send = { [weak self] message in self?.server.send(message) }
        input.sendLatest = { [weak self] message in self?.server.sendLatest(message) }
        input.minecraftPid = { [weak self] in self?.minecraftPid }
        input.activateMinecraft = { [weak self] in self?.activateMinecraftApp() }
        input.display = { [weak self] slot in
            guard let self, let live = self.displays.displays[slot] else { return nil }
            let bounds = CGDisplayBounds(live.displayID)
            return bounds.isEmpty ? nil : (bounds, live.spec.width, live.spec.height)
        }
        input.mainBounds = { CGDisplayBounds(CGMainDisplayID()) }
        input.monitorBounds = { uuid in ScreenCatalog.displayID(uuid: uuid).map { CGDisplayBounds($0) } }
        input.cursorMonitors = { [weak self] in
            guard let self, self.screensMod else { return [] } // an older mod knows only slot -1 off its screens
            return self.captures.compactMap { surface, capture in
                guard case .main(_, let monitor?) = capture.source, let id = self.mirrorRequests[surface]?.display else { return nil }
                return (id: id, bounds: CGDisplayBounds(monitor))
            }
        }
        CursorVeil.log = Log.write
        input.cursorDisplays = { [weak self] in
            guard let self else { return [] }
            return self.displays.displays.map { (slot: $0.key, bounds: CGDisplayBounds($0.value.displayID), width: $0.value.spec.width,
                                                  height: $0.value.spec.height) }
        }
        launcher.send = { [weak self] message in self?.server.send(message) }
        launcher.display = { [weak self] slot in
            guard let live = self?.displays.displays[slot] else { return nil }
            return (live.displayID, live.spec.height)
        }
        launcher.minecraftPid = { [weak self] in self?.minecraftPid }
        launcher.activateMinecraft = { [weak self] in self?.activateMinecraftApp() }
        displays.onTerminated = { [weak self] slot in self?.displayGone(slot: slot) }
        displays.saved = { [weak self] in
            guard let self, let main = ScreenCatalog.uuid(CGMainDisplayID()) else { return [:] }
            return self.arrangement.load().origins(forMain: main)
        }
        ScreenCatalog.onReconfigure { [weak self] in self?.screensReconfigured() }
        hotkey.onExit = { [weak self] in
            Log.write("exit hotkey")
            self?.endZoom(notifyMod: true)
        }
    }

    // MARK: Connection

    func connected(_ hello: HelloPayload, peerPid: pid_t) {
        minecraftPid = hello.mcPid > 0 ? hello.mcPid : peerPid
        screensMod = hello.features?.contains(OfficeProtocol.screensFeature) ?? false
        Log.write("mod connected: mcPid \(hello.mcPid), peer pid \(peerPid), features \(hello.features ?? [])")
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

    /// Restores everything this helper changed and removes its displays. (Minecraft's own window — its level and
    /// click-through — belongs to the mod's process; the mod restores it, and it is gone with Minecraft on a crash. The
    /// helper ends what it drives: a drag's button and tap, typing mode's Esc tap.) No window was moved on join, so none
    /// is moved back; windows on a removed display go back to the main display by macOS itself.
    func shutdown() {
        pollTimer?.invalidate(); pollTimer = nil
        permissionTimer?.invalidate(); permissionTimer = nil
        screensMod = false
        identifyOverlay.hide()
        endZoom(notifyMod: false, activateMinecraft: false)
        input.shutdown()
        for capture in captures.values { capture.stop() }
        captures.removeAll()
        displays.removeAll()
    }

    nonisolated static var welcome: WelcomePayload {
        WelcomePayload(version: OfficeProtocol.version, screenRecording: CGPreflightScreenCaptureAccess(), accessibility: AXIsProcessTrusted(),
                       features: [OfficeProtocol.screensFeature])
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
        case .rate(let p): captures[p.surface]?.setRate(p.fps)
        case .zoomIn(let p): zoomIn(p)
        case .zoomOut: endZoom(notifyMod: false)
        case .requestPermissions: requestPermissions()
        case .pointerDown, .pointerMove, .pointerUp, .click, .scroll, .exitTyping, .workBegin:
            if !AXIsProcessTrusted() { askAccessibilityOnce() }
            input.handle(message)
        case .listApps:
            Task.detached {
                let apps = AppLauncher.installedApps()
                await MainActor.run { self.server.send(.apps(AppsPayload(apps: apps))) }
            }
        case .appIcons(let p): server.send(.icons(IconsPayload(icons: AppLauncher.icons(p.bundles, size: p.size))))
        case .openApp(let p):
            if !AXIsProcessTrusted() { askAccessibilityOnce() }
            launcher.open(p)
        case .listDisplays: sendDisplays()
        case .setOrigins(let p): setOrigins(p)
        case .identify(let p): identify(p)
        case .hello, .ready:
            break // HELLO is handled by the server's handshake; READY by its frame flow
        case .welcome, .windows, .display, .frame, .patch, .ended, .exitZoom, .error, .typingExit, .dragDelta, .dragEnded,
             .apps, .icons, .appResult, .secureInput, .cursor, .walk, .displays:
            sendError("bad_request", "\(message.type) is a helper → mod message")
        }
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
        let identical = displays.displays[p.slot]?.spec == spec
        do {
            if let old = displays.displays[p.slot], old.spec != spec { endCaptures(onSlot: p.slot, reason: "display removed") }
            id = try displays.create(spec)
        } catch {
            sendError("display_failed", "\(spec.name): \(error)")
            return
        }
        if identical { displays.nothingCreated() } // nothing new: no shove to undo
        Task {
            guard await VirtualDisplays.waitOnline(id) else {
                if !identical { self.displays.createEnded(slot: spec.slot) }
                self.sendError("display_failed", "\(spec.name) did not come online")
                return
            }
            guard self.displays.displayID(slot: spec.slot) == id else { // removed meanwhile
                if !identical { self.displays.createEnded(slot: spec.slot) }
                return
            }
            self.displays.arrange(after: identical ? nil : spec.slot) // next to the main display, like monitors next to a laptop
            let origin = CGDisplayBounds(id).origin, main = CGDisplayBounds(CGMainDisplayID())
            self.server.send(.display(DisplayPayload(slot: spec.slot, displayId: id, w: spec.width, h: spec.height,
                                                     x: Int(origin.x), y: Int(origin.y),
                                                     side: origin.x < main.minX ? "left" : "right")))
        }
    }

    private func removeDisplay(slot: Int) {
        guard (1...DisplaySpec.maxSlots).contains(slot) else { sendError("bad_slot", "slot must be 1...\(DisplaySpec.maxSlots), got \(slot)"); return }
        endCaptures(onSlot: slot, reason: "display removed")
        displays.remove(slot: slot)
        displays.arrange() // the rest close up next to the main display
    }

    private func displayGone(slot: Int) { endCaptures(onSlot: slot, reason: "display removed") }

    // MARK: Display map ("screens")

    private func sendDisplays() {
        server.send(.displays(DisplaysPayload(displays: ScreenCatalog.list(ours: displays.displays.mapValues(\.displayID)))))
    }

    /// SET_ORIGINS: checked as one new layout (OriginRequest), applied in one configuration, then every live Office
    /// display's origin is remembered for this main display and the mod gets the screens as they are now.
    private func setOrigins(_ p: SetOriginsPayload) {
        let live = displays.displays.mapValues { CGDisplayBounds($0.displayID) }
        let ours = Set(displays.displays.values.map(\.displayID))
        let fixed = ScreenCatalog.activeIDs().filter { !ours.contains($0) }.map { CGDisplayBounds($0) }
        let moves: [Int: CGPoint]
        switch OriginRequest.check(p, live: live, fixed: fixed) {
        case .failure(let problem):
            sendError("bad_request", problem.message)
            sendDisplays()
            return
        case .success(let m): moves = m
        }
        guard displays.apply(origins: moves) else {
            sendError("display_failed", "macOS refused the new arrangement")
            sendDisplays()
            return
        }
        Task {
            try? await Task.sleep(nanoseconds: 150_000_000) // the window server settles the new bounds
            self.rememberArrangement()
            self.sendDisplays()
        }
    }

    /// IDENTIFY: each label on its screen for 3 s; ids that aren't connected are skipped. No reply.
    private func identify(_ p: IdentifyPayload) {
        let items = p.labels.compactMap { l in ScreenCatalog.displayID(uuid: l.display).map { (bounds: CGDisplayBounds($0), label: l.label) } }
        identifyOverlay.show(items)
    }

    private func rememberArrangement() {
        guard let main = ScreenCatalog.uuid(CGMainDisplayID()) else { return }
        var saved = arrangement.load()
        saved.record(forMain: main, displays.displays.mapValues { CGDisplayBounds($0.displayID).origin })
        do { try arrangement.save(saved) } catch { Log.write("arrangement not saved: \(error)") }
    }

    /// A display reconfiguration (plug, unplug, a move in System Settings, ours created / moved / removed): a "screens"
    /// mod gets DISPLAYS once things settle (300 ms after the last change).
    private func screensReconfigured() {
        guard screensMod else { return }
        reconfigureTicket += 1
        let ticket = reconfigureTicket
        DispatchQueue.main.asyncAfter(deadline: .now() + Self.reconfigureDebounceSeconds) { [weak self] in
            MainActor.assumeIsolated {
                guard let self, self.screensMod, self.reconfigureTicket == ticket else { return }
                self.sendDisplays()
            }
        }
    }

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
            let monitor = Self.mirroredMonitor(p)
            if let wanted = p.display, monitor == nil, ScreenCatalog.displayID(uuid: wanted) == nil {
                Log.write("mirror surface \(p.surface): monitor \(wanted) is not connected; mirroring the main display")
            }
            source = .main(excluding: minecraftPid, display: monitor)
            if p.display != nil { mirrorRequests[p.surface] = p }
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
                if self.monitorWentAway(capture) { return } // restarted on the main display
                Log.write("capture surface \(surface) failed: \(error)")
                if case .window(let key) = capture.source, WindowCatalog.window(key.windowID) == nil {
                    self.end(surface: surface, reason: "window closed")
                } else {
                    self.end(surface: surface, reason: "capture failed: \(error)")
                }
            }
        }
    }

    /// The monitor a CAPTURE main + display mirrors: nil = the main display (no display, it is the main one, or it is
    /// not connected now).
    private static func mirroredMonitor(_ p: CapturePayload) -> CGDirectDisplayID? {
        guard let id = p.display.flatMap(ScreenCatalog.displayID(uuid:)), id != CGMainDisplayID() else { return nil }
        return id
    }

    /// A Mirror's chosen monitor is gone (its stream stopped or never started): it carries on with the main display.
    private func monitorWentAway(_ capture: SurfaceCapture) -> Bool {
        guard case .main(_, let monitor?) = capture.source, let request = mirrorRequests[capture.surface],
              Self.mirroredMonitor(request) != monitor else { return false }
        Log.write("mirror surface \(capture.surface): monitor \(monitor) went away; mirroring the main display")
        self.capture(request)
        return true
    }

    private func captureFailed(surface: Int, message: String) {
        guard let capture = captures[surface] else { return }
        if monitorWentAway(capture) { return }
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
        mirrorRequests[surface] = nil
        guard let capture = captures.removeValue(forKey: surface) else { return }
        capture.stop()
        server.forget(surface: surface)
        if zoomedSurface == surface { endZoom(notifyMod: false) }
    }

    /// Once a second: closed windows end their surface; resized windows restart capture at the new size; a Mirror of a
    /// chosen monitor follows it (main display while it is unplugged, back to it when it returns).
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
            case .main(_, let monitor):
                guard let request = mirrorRequests[surface] else { continue }
                let now = Self.mirroredMonitor(request)
                if now != monitor {
                    Log.write("mirror surface \(surface): monitor \(now.map { "\($0) is back" } ?? "\(monitor ?? 0) went away")")
                    self.capture(request)
                }
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
            break // a Mirror is used in-world on a Mac (spec 5.10), never zoomed into
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
