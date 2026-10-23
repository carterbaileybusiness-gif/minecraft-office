import AppKit
import ApplicationServices

/// Joining a world: the player's app windows move from the main display onto their first hologram's display, keeping
/// the layout ({@link WindowMovePlan}); leaving (WINDOWS_BACK, the mod disconnecting, the helper quitting) puts each one
/// back exactly where it was ({@link WindowLedger}, in memory only). Never moved: Minecraft, the helper, minimized or
/// full-screen windows, panels and sheets (only standard windows), the desktop and menu bar items (not app windows). If
/// a display disappears without WINDOWS_BACK (crash), macOS moves its windows back to a remaining display by itself.
/// Needs Accessibility.
@MainActor final class WindowMover {
    /// AXUIElement keyed by CFEqual / CFHash.
    struct Element: Hashable {
        let ref: AXUIElement
        let pid: pid_t
        static func == (a: Element, b: Element) -> Bool { CFEqual(a.ref, b.ref) }
        func hash(into hasher: inout Hasher) { hasher.combine(CFHash(ref)) }
    }

    var log: (String) -> Void = { _ in }
    private var ledger = WindowLedger<Element>()

    var movedCount: Int { ledger.count }

    /// Moves the eligible windows from the main display onto `display`; returns how many moved.
    @discardableResult
    func moveWindows(to display: CGDirectDisplayID, excluding pids: Set<pid_t>, onlyApps: Set<String>?) -> Int {
        guard AXIsProcessTrusted() else { log("windows: Accessibility missing; not moving"); return 0 }
        let main = CGMainDisplayID()
        guard display != main, let source = Self.visibleFrame(main), let destination = Self.visibleFrame(display) else {
            log("windows: no target display \(display)"); return 0
        }
        let mainBounds = CGDisplayBounds(main)
        var picked: [(Element, CGRect)] = []
        for app in NSWorkspace.shared.runningApplications where app.activationPolicy == .regular && !pids.contains(app.processIdentifier) {
            if let onlyApps, !onlyApps.contains(app.bundleIdentifier ?? "") { continue }
            for window in Self.windows(of: app.processIdentifier) {
                guard Self.isMovable(window), let frame = Self.frame(window),
                      frame.width > 40, frame.height > 40, mainBounds.contains(CGPoint(x: frame.midX, y: frame.midY)) else { continue }
                picked.append((Element(ref: window, pid: app.processIdentifier), frame))
            }
        }
        let planned = WindowMovePlan.plan(picked.map { $0.1 }, from: source, to: destination)
        for ((element, original), target) in zip(picked, planned) {
            ledger.record(element, original: original)
            Placement.setFrame(element.ref, target)
        }
        log("windows: moved \(picked.count) onto display \(display) (\(Int(destination.width))x\(Int(destination.height)))")
        return picked.count
    }

    /// Puts every moved window that still exists back where it was.
    func restore() {
        guard ledger.count > 0 else { return }
        let existing = Set(ledger.keys.filter { Self.frame($0.ref) != nil })
        let back = ledger.restore(existing: existing)
        for (element, frame) in back { Placement.setFrame(element.ref, frame) }
        log("windows: restored \(back.count)")
    }

    static func windows(of pid: pid_t) -> [AXUIElement] {
        var raw: CFTypeRef?
        guard AXUIElementCopyAttributeValue(AXUIElementCreateApplication(pid), kAXWindowsAttribute as CFString, &raw) == .success else { return [] }
        return raw as? [AXUIElement] ?? []
    }

    static func isMovable(_ window: AXUIElement) -> Bool {
        var subrole: CFTypeRef?, minimized: CFTypeRef?, fullScreen: CFTypeRef?, settable: DarwinBoolean = false
        AXUIElementCopyAttributeValue(window, kAXSubroleAttribute as CFString, &subrole)
        guard (subrole as? String) == (kAXStandardWindowSubrole as String) else { return false }
        AXUIElementCopyAttributeValue(window, kAXMinimizedAttribute as CFString, &minimized)
        if (minimized as? Bool) == true { return false }
        AXUIElementCopyAttributeValue(window, "AXFullScreen" as CFString, &fullScreen)
        if (fullScreen as? Bool) == true { return false }
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
