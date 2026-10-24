import Foundation

// Pure in-world pointer rules (no AppKit): display pixel → global point, the helper's drag state, typing mode.

enum PointerButton: String, Codable, Equatable { case left, right, middle }

enum PointerMapping {
    /// Pixel (x, y) of a w×h virtual display → the global point (points, top-left origin) at that pixel's centre, inside
    /// `bounds` (CGDisplayBounds). Out-of-range pixels are clamped onto the display.
    static func globalPoint(x: Int, y: Int, width: Int, height: Int, bounds: CGRect) -> CGPoint {
        let w = max(width, 1), h = max(height, 1)
        let cx = min(max(x, 0), w - 1), cy = min(max(y, 0), h - 1)
        return CGPoint(x: bounds.minX + (Double(cx) + 0.5) * bounds.width / Double(w),
                       y: bounds.minY + (Double(cy) + 0.5) * bounds.height / Double(h))
    }
}

/// Physical mouse motion for DRAG_DELTA: whole points go out, the fraction stays for the next motion, so a slow
/// trackpad drag (a fraction of a point per event) still turns the player's head. Pure.
struct DeltaAccumulator: Equatable {
    private(set) var dx: Double = 0
    private(set) var dy: Double = 0

    mutating func add(dx: Double, dy: Double) {
        self.dx += dx
        self.dy += dy
    }

    /// The whole points so far (toward zero), or nil when there are none yet.
    mutating func take() -> (Int, Int)? {
        let x = Int(dx.rounded(.towardZero)), y = Int(dy.rounded(.towardZero))
        guard x != 0 || y != 0 else { return nil }
        dx -= Double(x)
        dy -= Double(y)
        return (x, y)
    }
}

/// A drag the helper drives for the mod (route "H" in Tools/inputprobe.swift, the one that works): the player holds the
/// physical button in Minecraft and turns their head. The helper brings the app under the press point forward (so the
/// press is not swallowed as an activation click), posts a real down there, takes the physical mouse with an event tap
/// (its motion goes back to the mod as DRAG_DELTA to turn the head, its release ends the drag), and posts real drags
/// at every point the mod maps from the crosshair. The window server then does the rest natively: text selection,
/// window drags across displays, drag and drop. Pure: the caller performs the effects.
struct DragMachine {
    enum Phase: Equatable { case idle, activating, dragging }
    enum Effect: Equatable {
        case startTap, stopTap
        case activate(CGPoint)
        case postDown(CGPoint, PointerButton), postDrag(CGPoint, PointerButton), postUp(CGPoint, PointerButton)
        case sendEnded(String)
        case activateMinecraft
    }

    private(set) var phase: Phase = .idle
    private(set) var button: PointerButton = .left
    private(set) var downPoint: CGPoint = .zero
    private(set) var lastPoint: CGPoint = .zero
    /// The last point a drag event was posted at (no duplicates).
    private var postedPoint: CGPoint?

    var isActive: Bool { phase != .idle }

    /// POINTER_DOWN. A drag still running is cancelled first (its up posted, Minecraft not re-activated in between).
    mutating func down(at point: CGPoint, button: PointerButton) -> [Effect] {
        var effects = phase == .idle ? [] : end("cancelled", reactivate: false)
        phase = .activating
        self.button = button
        downPoint = point
        lastPoint = point
        postedPoint = nil
        effects += [.startTap, .activate(point)]
        return effects
    }

    /// The app under the press point is frontmost (or waiting for it timed out): press there, catch up on the motion.
    mutating func activated() -> [Effect] {
        guard phase == .activating else { return [] }
        phase = .dragging
        return pressAndCatchUp()
    }

    /// POINTER_MOVE: the crosshair is over this point now.
    mutating func move(to point: CGPoint) -> [Effect] {
        switch phase {
        case .idle: return []
        case .activating: lastPoint = point; return []
        case .dragging:
            lastPoint = point
            guard postedPoint != point else { return [] }
            postedPoint = point
            return [.postDrag(point, button)]
        }
    }

    /// The physical button came up (seen by the tap, or found up by the safety poll).
    mutating func physicalUp() -> [Effect] { end("released", reactivate: true) }

    /// POINTER_UP from the mod, the mod disconnecting, the helper quitting.
    mutating func cancel() -> [Effect] { end("cancelled", reactivate: true) }

    private mutating func pressAndCatchUp() -> [Effect] {
        var effects: [Effect] = [.postDown(downPoint, button)]
        postedPoint = downPoint
        if lastPoint != downPoint {
            effects.append(.postDrag(lastPoint, button))
            postedPoint = lastPoint
        }
        return effects
    }

    private mutating func end(_ reason: String, reactivate: Bool) -> [Effect] {
        var effects: [Effect]
        switch phase {
        case .idle: return []
        case .activating:
            // Released before the app came forward: the gesture still happens (down, drag, up). A cancel never pressed.
            effects = reason == "released" ? pressAndCatchUp() + [.postUp(lastPoint, button)] : []
        case .dragging:
            effects = [.postUp(lastPoint, button)]
        }
        phase = .idle
        postedPoint = nil
        effects += [.stopTap, .sendEnded(reason)]
        if reactivate { effects.append(.activateMinecraft) }
        return effects
    }
}

/// Typing mode in the helper: after a CLICK the app has the keyboard; a plain Esc (seen by a session event tap, so it is
/// swallowed and never reaches the app) or Minecraft becoming active again ends it. Esc's repeats and its release are
/// swallowed too, or Minecraft would get them and open its pause menu. Pure.
struct TypingMachine {
    enum Effect: Equatable { case armTap, disarmTap, activateMinecraft, sendExit(String) }
    static let escape: UInt16 = 53

    private(set) var active = false
    /// The Esc that ended typing is still held: swallow its repeats and its release.
    private(set) var swallowingEscape = false

    mutating func enter() -> [Effect] {
        guard !active else { return [] }
        active = true
        return swallowingEscape ? [] : [.armTap]
    }

    /// Every key event the tap sees. `swallow` = drop it.
    mutating func key(code: UInt16, down: Bool, modifiers: KeyModifiers) -> (swallow: Bool, effects: [Effect]) {
        guard code == Self.escape else { return (false, []) }
        if swallowingEscape {
            if !down {
                swallowingEscape = false
                return (true, active ? [] : [.disarmTap])
            }
            return (true, [])
        }
        guard active, down, modifiers.isEmpty else { return (false, []) }
        active = false
        swallowingEscape = true
        return (true, [.activateMinecraft, .sendExit("esc")])
    }

    /// Minecraft is the active app again (the player clicked its window, Cmd-Tab, the Dock).
    mutating func minecraftActivated() -> [Effect] {
        guard active else { return [] }
        active = false
        return swallowingEscape ? [.sendExit("minecraft")] : [.disarmTap, .sendExit("minecraft")]
    }

    /// EXIT_TYPING from the mod (it noticed first, or left the world): no message back.
    mutating func exit() -> [Effect] {
        guard active else { return [] }
        active = false
        return swallowingEscape ? [] : [.disarmTap]
    }
}

/// Where a pointer message lands (spec 5.10): slot 1-4 = that virtual display, (x, y) one of its pixels; slot 0 = the
/// main display (a Mirror hologram), (x, y) a pixel of a w × h picture of it, mapped by proportion onto its bounds in
/// points (so a Retina display, whose points are half its pixels, and any capture size both work). Pure.
enum PointerTarget {
    static let mainSlot = 0

    enum Failure: Error, Equatable { case noDisplay, noPictureSize }

    static func globalPoint(slot: Int, x: Int, y: Int, w: Int?, h: Int?, main: CGRect,
                            display: (Int) -> (bounds: CGRect, width: Int, height: Int)?) -> Result<CGPoint, Failure> {
        if slot == mainSlot {
            guard let w, let h, w > 0, h > 0 else { return .failure(.noPictureSize) }
            guard !main.isEmpty else { return .failure(.noDisplay) }
            return .success(PointerMapping.globalPoint(x: x, y: y, width: w, height: h, bounds: main))
        }
        guard let d = display(slot) else { return .failure(.noDisplay) }
        return .success(PointerMapping.globalPoint(x: x, y: y, width: d.width, height: d.height, bounds: d.bounds))
    }
}
