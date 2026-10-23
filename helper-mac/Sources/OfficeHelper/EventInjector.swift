import AppKit
import CoreGraphics

/// Posts real input events into other apps at global coordinates (points, top-left origin). Needs Accessibility.
///
/// Two delivery routes, chosen by what the event must not disturb (evidence: helper-mac/Tools/inputprobe.swift):
/// - `toPid`: the event goes straight into the target app's event queue, bypassing the window server's routing. The app
///   under the point handles it, but nothing is activated and the cursor does not move, so Minecraft stays frontmost
///   and keeps its relative mouse input (and the physical button it is holding). Used for in-world drags and scrolls.
/// - `hid`: a normal system-wide event, exactly as if the mouse did it: the cursor moves there and a click activates
///   the app it lands on. Used for a click that enters typing mode.
enum EventInjector {
    /// A window under a point: who gets an event posted there.
    struct Target: Equatable {
        let windowID: CGWindowID
        let pid: pid_t
        let frame: CGRect
    }

    /// CGEventField raw values the window server fills for hardware events; AppKit uses them to find the window.
    static let windowUnderPointerField = CGEventField(rawValue: 91)!
    static let windowUnderPointerThatCanHandleField = CGEventField(rawValue: 92)!

    /// A private source, so posted events never change the combined button state Minecraft sees.
    static let privateSource = CGEventSource(stateID: .privateState)
    static let hidSource = CGEventSource(stateID: .hidSystemState)
    /// `eventSourceUserData` of every event the helper posts, so its own event taps let them through.
    static let injectedMark: Int64 = 0x4F46_4943 // "OFIC"

    static func isInjected(_ event: CGEvent) -> Bool { event.getIntegerValueField(.eventSourceUserData) == injectedMark }

    /// The front-most normal (layer 0) window containing `point`, skipping `excluding` (the helper, Minecraft).
    static func window(at point: CGPoint, excluding: Set<pid_t>) -> Target? {
        let rows = CGWindowListCopyWindowInfo([.optionOnScreenOnly, .excludeDesktopElements], kCGNullWindowID) as? [[String: Any]] ?? []
        for row in rows { // front to back
            guard (row[kCGWindowLayer as String] as? NSNumber)?.intValue == 0,
                  let pid = (row[kCGWindowOwnerPID as String] as? NSNumber)?.int32Value, !excluding.contains(pid),
                  let id = (row[kCGWindowNumber as String] as? NSNumber)?.uint32Value,
                  let bounds = frame(row), bounds.contains(point) else { continue }
            if let alpha = (row[kCGWindowAlpha as String] as? NSNumber)?.doubleValue, alpha <= 0.01 { continue }
            return Target(windowID: id, pid: pid, frame: bounds)
        }
        return nil
    }

    static func frame(_ row: [String: Any]) -> CGRect? {
        (row[kCGWindowBounds as String] as? [String: Any]).flatMap { CGRect(dictionaryRepresentation: $0 as CFDictionary) }
    }

    static func cgButton(_ button: PointerButton) -> CGMouseButton {
        switch button { case .left: return .left; case .right: return .right; case .middle: return .center }
    }

    static func downType(_ button: PointerButton) -> CGEventType {
        switch button { case .left: return .leftMouseDown; case .right: return .rightMouseDown; case .middle: return .otherMouseDown }
    }

    static func upType(_ button: PointerButton) -> CGEventType {
        switch button { case .left: return .leftMouseUp; case .right: return .rightMouseUp; case .middle: return .otherMouseUp }
    }

    static func draggedType(_ button: PointerButton) -> CGEventType {
        switch button { case .left: return .leftMouseDragged; case .right: return .rightMouseDragged; case .middle: return .otherMouseDragged }
    }

    private static var eventNumber: Int64 = 0x4F00

    /// A fresh mouse event number: down, its drags and its up share one, like a real click.
    static func nextEventNumber() -> Int64 { eventNumber += 1; return eventNumber }

    /// Mouse event straight into `target`'s process (no activation, no cursor move).
    @discardableResult
    static func postToPid(_ type: CGEventType, at point: CGPoint, button: PointerButton, target: Target,
                          clickState: Int64 = 1, eventNumber: Int64) -> Bool {
        guard let event = CGEvent(mouseEventSource: privateSource, mouseType: type, mouseCursorPosition: point,
                                  mouseButton: cgButton(button)) else { return false }
        event.setIntegerValueField(.mouseEventClickState, value: clickState)
        event.setIntegerValueField(.mouseEventNumber, value: eventNumber)
        event.setIntegerValueField(windowUnderPointerField, value: Int64(target.windowID))
        event.setIntegerValueField(windowUnderPointerThatCanHandleField, value: Int64(target.windowID))
        event.postToPid(target.pid)
        return true
    }

    /// A system-wide mouse event (moves the cursor; a down activates the app it lands on).
    @discardableResult
    static func postHID(_ type: CGEventType, at point: CGPoint, button: PointerButton, clickState: Int64 = 1) -> Bool {
        guard let event = CGEvent(mouseEventSource: hidSource, mouseType: type, mouseCursorPosition: point,
                                  mouseButton: cgButton(button)) else { return false }
        event.setIntegerValueField(.mouseEventClickState, value: clickState)
        event.setIntegerValueField(.eventSourceUserData, value: injectedMark)
        event.post(tap: .cghidEventTap)
        return true
    }

    /// System-wide scroll-wheel lines at `point` (scrolls the window there; scrolling never activates an app).
    @discardableResult
    static func scrollHID(at point: CGPoint, dx: Int32, dy: Int32) -> Bool {
        guard let event = CGEvent(scrollWheelEvent2Source: hidSource, units: .line, wheelCount: 2,
                                  wheel1: dy, wheel2: dx, wheel3: 0) else { return false }
        event.location = point
        event.setIntegerValueField(.eventSourceUserData, value: injectedMark)
        event.post(tap: .cghidEventTap)
        return true
    }

    /// A real click at `point` (down, up), as the physical mouse would do it.
    static func hidClick(at point: CGPoint, button: PointerButton) {
        postHID(.mouseMoved, at: point, button: .left, clickState: 0)
        postHID(downType(button), at: point, button: button)
        postHID(upType(button), at: point, button: button)
    }

    /// Scroll-wheel lines at `point`, delivered to the window there without activating it (or the cursor moving).
    @discardableResult
    static func scroll(at point: CGPoint, dx: Int32, dy: Int32, target: Target) -> Bool {
        guard let event = CGEvent(scrollWheelEvent2Source: privateSource, units: .line, wheelCount: 2,
                                  wheel1: dy, wheel2: dx, wheel3: 0) else { return false }
        event.location = point
        event.setIntegerValueField(windowUnderPointerField, value: Int64(target.windowID))
        event.setIntegerValueField(windowUnderPointerThatCanHandleField, value: Int64(target.windowID))
        event.postToPid(target.pid)
        return true
    }
}
