import AppKit
import ApplicationServices

/// The mini Dock's side of the helper (spec 5.10): the installed apps, their icons (NSWorkspace → PNG), and opening an
/// app or a web page onto a hologram's display. Windows only ever move because the player clicked a Dock icon; nothing
/// moves on join or leave. Opening never takes the keyboard from Minecraft: the app is opened without activation, its
/// window is moved with Accessibility (needed) and raised, and if the app activates itself (Electron apps do) while
/// Minecraft was in front, Minecraft is activated again. Reuses Home Base's bridge patterns (Mac UI Makeover,
/// Prototypes/MinecraftBridge/HomeBaseSession.swift: `installedApps`, `open` with its "reopen" for apps that launch
/// without a window, Finder opening the home folder).
@MainActor final class AppLauncher {
    var log: (String) -> Void = { _ in }
    var send: (Message) -> Void = { _ in }
    /// A slot's virtual display and its height in pixels.
    var display: (Int) -> (id: CGDirectDisplayID, pixelHeight: Int)? = { _ in nil }
    var minecraftPid: () -> pid_t? = { nil }
    var activateMinecraft: () -> Void = {}

    /// The newest request per target; an older one still waiting for its window gives up.
    private var requests: [String: Int] = [:]
    private var nextRequest = 0
    /// How long an app gets to show a window (Electron apps can be slow on first launch).
    static let windowWaitSeconds: Double = 40

    init() {
        // On the system-wide element this sets the default for every Accessibility call this process makes.
        AXUIElementSetMessagingTimeout(AXUIElementCreateSystemWide(), AXWindows.axTimeout)
    }

    // MARK: Apps and icons

    /// The Applications folders (as Home Base's bridge scanned them), plus Finder, which lives in CoreServices.
    nonisolated static func installedApps() -> [AppInfo] {
        let fm = FileManager.default
        let folders = ["/Applications", "/Applications/Utilities", "/System/Applications", "/System/Applications/Utilities",
                       fm.homeDirectoryForCurrentUser.appendingPathComponent("Applications").path]
        var found: [AppCatalog.Found] = [AppCatalog.Found(bundle: "com.apple.finder", name: "Finder")]
        for folder in folders {
            guard let names = try? fm.contentsOfDirectory(atPath: folder) else { continue }
            for name in names where name.hasSuffix(".app") {
                let url = URL(fileURLWithPath: folder).appendingPathComponent(name)
                found.append(AppCatalog.Found(bundle: Bundle(url: url)?.bundleIdentifier, name: fm.displayName(atPath: url.path)))
            }
        }
        return AppCatalog.list(found, excluding: [Bundle.main.bundleIdentifier ?? "local.carter.office-helper"])
    }

    /// PNG icons, `size` pixels square; apps that aren't installed are left out.
    static func icons(_ bundles: [String], size: Int) -> [AppIcon] {
        bundles.compactMap { bundle in
            guard let url = NSWorkspace.shared.urlForApplication(withBundleIdentifier: bundle),
                  let png = png(NSWorkspace.shared.icon(forFile: url.path), size: size) else { return nil }
            return AppIcon(bundle: bundle, png: png.base64EncodedString())
        }
    }

    static func png(_ image: NSImage, size: Int) -> Data? {
        guard let rep = NSBitmapImageRep(bitmapDataPlanes: nil, pixelsWide: size, pixelsHigh: size, bitsPerSample: 8, samplesPerPixel: 4,
                                         hasAlpha: true, isPlanar: false, colorSpaceName: .deviceRGB, bytesPerRow: 0, bitsPerPixel: 0) else { return nil }
        rep.size = NSSize(width: size, height: size)
        NSGraphicsContext.saveGraphicsState()
        defer { NSGraphicsContext.restoreGraphicsState() }
        guard let context = NSGraphicsContext(bitmapImageRep: rep) else { return nil }
        NSGraphicsContext.current = context
        context.imageInterpolation = .high
        image.draw(in: NSRect(x: 0, y: 0, width: size, height: size), from: .zero, operation: .copy, fraction: 1)
        context.flushGraphics()
        return rep.representation(using: .png, properties: [:])
    }

    // MARK: Opening onto a display

    func open(_ p: OpenAppPayload) {
        let target = p.bundle ?? p.url ?? "?"
        guard AXIsProcessTrusted() else { finish(target, false, "OfficeHelper needs Accessibility to put windows on your screens"); return }
        guard let (displayID, pixelHeight) = display(p.slot) else { finish(target, false, "That screen isn't on yet"); return }
        let appURL: URL?
        var webPage: URL?
        if let bundle = p.bundle {
            appURL = NSWorkspace.shared.urlForApplication(withBundleIdentifier: bundle)
        } else {
            webPage = p.url.flatMap(URL.init(string:))
            appURL = webPage.flatMap { NSWorkspace.shared.urlForApplication(toOpen: $0) }
        }
        guard let appURL, let bundle = p.bundle ?? Bundle(url: appURL)?.bundleIdentifier else {
            finish(target, false, p.bundle != nil ? "That app isn't installed" : "No web browser to open it with"); return
        }
        let name = FileManager.default.displayName(atPath: appURL.path).replacingOccurrences(of: ".app", with: "")
        nextRequest += 1
        let request = nextRequest
        requests[target] = request
        let minecraftWasFront = minecraftPid().map { NSWorkspace.shared.frontmostApplication?.processIdentifier == $0 } ?? false
        let running = NSRunningApplication.runningApplications(withBundleIdentifier: bundle).first
        let before = Set(running.map { AXWindows.standardWindows(of: $0.processIdentifier) } ?? [])
        let configuration = NSWorkspace.OpenConfiguration()
        configuration.activates = false
        // Ask for a new window only when there is none to use (or for a web page: the browser opens it).
        let askForNew = webPage != nil || before.isEmpty
        log("dock: open \(target) on slot \(p.slot) (\(running == nil ? "launching" : "running"), \(before.count) windows)")
        Task {
            if let webPage {
                _ = try? await NSWorkspace.shared.open([webPage], withApplicationAt: appURL, configuration: configuration)
            } else if askForNew {
                await Self.launch(bundle: bundle, appURL: appURL, configuration: configuration)
            }
            let start = Date()
            var reopened = 0
            while Date().timeIntervalSince(start) < Self.windowWaitSeconds {
                guard self.requests[target] == request else { return } // a newer click for the same icon took over
                let elapsed = Date().timeIntervalSince(start)
                if let app = NSRunningApplication.runningApplications(withBundleIdentifier: bundle).first {
                    if minecraftWasFront, NSWorkspace.shared.frontmostApplication?.processIdentifier == app.processIdentifier {
                        self.activateMinecraft() // the app took the front itself: the player is still in Minecraft
                    }
                    let now = AXWindows.standardWindows(of: app.processIdentifier)
                    if let window = AppWindowPick.choose(before: before, now: now, askedForNew: askForNew, elapsed: elapsed) {
                        self.place(window, on: displayID, bottomPixels: p.bottom, pixelHeight: pixelHeight)
                        self.requests[target] = nil
                        self.finish(target, true, "\(name) is on Office Display \(p.slot)")
                        return
                    }
                    // Some apps (Safari, Mail…) launch without a window; opening again is a "reopen", which shows one.
                    if askForNew, webPage == nil, (elapsed > 3 && reopened == 0) || (elapsed > 12 && reopened == 1) {
                        reopened += 1
                        await Self.launch(bundle: bundle, appURL: appURL, configuration: configuration)
                    }
                }
                try? await Task.sleep(nanoseconds: 250_000_000)
            }
            guard self.requests[target] == request else { return }
            self.requests[target] = nil
            self.finish(target, false, "\(name) opened but showed no window")
        }
    }

    private static func launch(bundle: String, appURL: URL, configuration: NSWorkspace.OpenConfiguration) async {
        if bundle == "com.apple.finder" {
            // Finder always runs; a home folder window is what opening Finder should show (as in Home Base).
            _ = try? await NSWorkspace.shared.open([FileManager.default.homeDirectoryForCurrentUser], withApplicationAt: appURL,
                                                   configuration: configuration)
        } else {
            _ = try? await NSWorkspace.shared.openApplication(at: appURL, configuration: configuration)
        }
    }

    /// Onto the display, filling its visible area, unminimized and in front there (without activating its app).
    private func place(_ window: AXWindows.Window, on displayID: CGDirectDisplayID, bottomPixels: Int?, pixelHeight: Int) {
        let bounds = CGDisplayBounds(displayID)
        let frame = AppWindowFit.frame(visible: AXWindows.visibleFrame(displayID) ?? bounds, bounds: bounds, bottomPixels: bottomPixels,
                                       pixelHeight: pixelHeight)
        var minimized: CFTypeRef?
        AXUIElementCopyAttributeValue(window.ref, kAXMinimizedAttribute as CFString, &minimized)
        if (minimized as? Bool) == true { AXUIElementSetAttributeValue(window.ref, kAXMinimizedAttribute as CFString, kCFBooleanFalse) }
        Placement.setFrame(window.ref, frame)
        AXUIElementPerformAction(window.ref, kAXRaiseAction as CFString)
        log("dock: window of pid \(window.pid) → display \(displayID) \(frame)")
    }

    private func finish(_ target: String, _ ok: Bool, _ message: String) {
        log("dock: \(target): \(message)")
        send(.appResult(AppResultPayload(target: target, ok: ok, message: message)))
    }
}

/// Accessibility windows of other apps (from the retired WindowMover; patterns as in Rectangle / Hammerspoon: AX
/// position and size, standard windows only, a short messaging timeout so a hung app can't stall the helper).
enum AXWindows {
    /// An AXUIElement keyed by CFEqual / CFHash.
    struct Window: Hashable {
        let ref: AXUIElement
        let pid: pid_t
        static func == (a: Window, b: Window) -> Bool { CFEqual(a.ref, b.ref) }
        func hash(into hasher: inout Hasher) { hasher.combine(CFHash(ref)) }
    }

    static let axTimeout: Float = 0.5

    /// The app's standard, movable windows, front to back (minimized ones included: placing one unminimizes it).
    static func standardWindows(of pid: pid_t) -> [Window] {
        var raw: CFTypeRef?
        let app = AXUIElementCreateApplication(pid)
        AXUIElementSetMessagingTimeout(app, axTimeout)
        guard AXUIElementCopyAttributeValue(app, kAXWindowsAttribute as CFString, &raw) == .success, let all = raw as? [AXUIElement] else { return [] }
        return all.filter(isMovable).map { Window(ref: $0, pid: pid) }
    }

    static func isMovable(_ window: AXUIElement) -> Bool {
        var subrole: CFTypeRef?, settable: DarwinBoolean = false
        AXUIElementCopyAttributeValue(window, kAXSubroleAttribute as CFString, &subrole)
        guard (subrole as? String) == (kAXStandardWindowSubrole as String) else { return false }
        var ownSpace: CFTypeRef?
        AXUIElementCopyAttributeValue(window, "AXFullScreen" as CFString, &ownSpace) // read-only: never changed
        if (ownSpace as? Bool) == true { return false }
        guard AXUIElementIsAttributeSettable(window, kAXPositionAttribute as CFString, &settable) == .success, settable.boolValue else { return false }
        return true
    }

    static func frame(_ window: AXUIElement) -> CGRect? {
        var p: CFTypeRef?, s: CFTypeRef?
        guard AXUIElementCopyAttributeValue(window, kAXPositionAttribute as CFString, &p) == .success,
              AXUIElementCopyAttributeValue(window, kAXSizeAttribute as CFString, &s) == .success, let p, let s else { return nil }
        var origin = CGPoint.zero, size = CGSize.zero
        AXValueGetValue(p as! AXValue, .cgPoint, &origin)
        AXValueGetValue(s as! AXValue, .cgSize, &size)
        return CGRect(origin: origin, size: size)
    }

    /// The display's area without the menu bar and Dock, global points, top-left origin.
    static func visibleFrame(_ display: CGDirectDisplayID) -> CGRect? {
        let bounds = CGDisplayBounds(display)
        guard !bounds.isEmpty else { return nil }
        guard let primary = NSScreen.screens.first,
              let screen = NSScreen.screens.first(where: { ($0.deviceDescription[NSDeviceDescriptionKey("NSScreenNumber")] as? NSNumber)?.uint32Value == display }) else {
            return CGRect(x: bounds.minX, y: bounds.minY + 25, width: bounds.width, height: bounds.height - 25) // not listed yet
        }
        let v = screen.visibleFrame // bottom-left origin of the primary screen
        return CGRect(x: v.minX, y: primary.frame.height - v.maxY, width: v.width, height: v.height)
    }
}
