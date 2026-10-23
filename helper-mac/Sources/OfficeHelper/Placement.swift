import AppKit
import ApplicationServices

/// Window mode zoom: puts the real window exactly over Minecraft's window and brings its app to the front, so it is
/// used natively (from Home Base's HomeBaseSession.place()). The original frame is remembered and restored on
/// ZOOM_OUT / exit. Needs Accessibility.
@MainActor final class Placement {
    struct Placed {
        let windowID: CGWindowID
        let pid: pid_t
        let element: AXUIElement
        let original: CGRect
    }

    private(set) var placed: Placed?
    var log: (String) -> Void = { _ in }

    /// `frame` is in global points, top-left origin. Returns false when the window or permission is missing.
    @discardableResult
    func place(windowID: CGWindowID, at frame: CGRect, minecraftPid: pid_t?) -> Bool {
        if let placed, placed.windowID != windowID { restore() }
        guard AXIsProcessTrusted(), let current = WindowCatalog.window(windowID),
              let element = Self.axWindow(pid: current.pid, frame: current.frame, title: current.title) else {
            log("place \(windowID): window or Accessibility missing")
            return false
        }
        if placed == nil { placed = Placed(windowID: windowID, pid: current.pid, element: element, original: current.frame) }
        var minimized: CFTypeRef?
        AXUIElementCopyAttributeValue(element, kAXMinimizedAttribute as CFString, &minimized)
        if (minimized as? Bool) == true { AXUIElementSetAttributeValue(element, kAXMinimizedAttribute as CFString, kCFBooleanFalse) }
        Self.setFrame(element, frame)
        AXUIElementSetAttributeValue(element, kAXMainAttribute as CFString, kCFBooleanTrue)
        AXUIElementPerformAction(element, kAXRaiseAction as CFString)
        if let app = NSRunningApplication(processIdentifier: current.pid) {
            let minecraft = minecraftPid.flatMap { NSRunningApplication(processIdentifier: $0) }
            let activated = minecraft.map { app.activate(from: $0, options: []) } ?? false
            if !activated { app.activate(options: [.activateAllWindows]) }
        }
        log("placed window \(windowID) at \(frame) (was \(placed?.original ?? .zero))")
        return true
    }

    /// Puts the placed window back where it was. Safe to call when nothing is placed or the window is gone.
    func restore() {
        guard let placed else { return }
        self.placed = nil
        guard WindowCatalog.window(placed.windowID) != nil else { log("restore \(placed.windowID): window gone"); return }
        Self.setFrame(placed.element, placed.original)
        log("restored window \(placed.windowID) to \(placed.original)")
    }

    static func setFrame(_ element: AXUIElement, _ frame: CGRect) {
        var origin = frame.origin, size = frame.size
        // Size, position, size: a window moving to a smaller display may otherwise keep a clipped size.
        if let value = AXValueCreate(.cgSize, &size) { AXUIElementSetAttributeValue(element, kAXSizeAttribute as CFString, value) }
        if let value = AXValueCreate(.cgPoint, &origin) { AXUIElementSetAttributeValue(element, kAXPositionAttribute as CFString, value) }
        if let value = AXValueCreate(.cgSize, &size) { AXUIElementSetAttributeValue(element, kAXSizeAttribute as CFString, value) }
    }

    /// The Accessibility window with this frame; tabs share a frame, so a title breaks the tie.
    static func axWindow(pid: pid_t, frame: CGRect, title: String?) -> AXUIElement? {
        let app = AXUIElementCreateApplication(pid)
        var raw: CFTypeRef?
        guard AXUIElementCopyAttributeValue(app, kAXWindowsAttribute as CFString, &raw) == .success, let windows = raw as? [AXUIElement] else { return nil }
        let matches = windows.filter { window in
            var p: CFTypeRef?, s: CFTypeRef?
            var position = CGPoint.zero, size = CGSize.zero
            guard AXUIElementCopyAttributeValue(window, kAXPositionAttribute as CFString, &p) == .success,
                  AXUIElementCopyAttributeValue(window, kAXSizeAttribute as CFString, &s) == .success,
                  let p, let s else { return false }
            AXValueGetValue(p as! AXValue, .cgPoint, &position)
            AXValueGetValue(s as! AXValue, .cgSize, &size)
            return abs(position.x - frame.minX) < 2 && abs(position.y - frame.minY) < 2 && abs(size.width - frame.width) < 2 && abs(size.height - frame.height) < 2
        }
        if matches.count <= 1 || title == nil { return matches.first }
        return matches.first { window in
            var raw: CFTypeRef?
            AXUIElementCopyAttributeValue(window, kAXTitleAttribute as CFString, &raw)
            return (raw as? String) == title
        } ?? matches.first
    }
}
