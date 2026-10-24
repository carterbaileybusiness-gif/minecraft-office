import Foundation

// Pure zoom-exit and cursor rules (no AppKit), shared by ExitHotkey and CursorFocus.

struct KeyModifiers: OptionSet, Equatable {
    let rawValue: Int
    static let shift = KeyModifiers(rawValue: 1 << 0)
    static let control = KeyModifiers(rawValue: 1 << 1)
    static let option = KeyModifiers(rawValue: 1 << 2)
    static let command = KeyModifiers(rawValue: 1 << 3)
}

/// Double-tap Esc (two plain presses within 350 ms) or Ctrl+Alt+Cmd+Esc.
struct ExitChordDetector {
    static let escape: UInt16 = 53
    static let window: TimeInterval = 0.350
    private var lastEscape: TimeInterval?

    /// Feed every key-down; returns true when the zoom should exit.
    mutating func keyDown(keyCode: UInt16, modifiers: KeyModifiers, time: TimeInterval, isRepeat: Bool) -> Bool {
        guard keyCode == Self.escape else { lastEscape = nil; return false }
        if modifiers.isSuperset(of: [.control, .option, .command]) { lastEscape = nil; return true }
        if isRepeat { return false }
        guard modifiers.isEmpty else { lastEscape = nil; return false }
        // Small tolerance so exactly 350 ms counts despite floating-point timestamps.
        if let last = lastEscape, time - last <= Self.window + 0.0005 { lastEscape = nil; return true }
        lastEscape = time
        return false
    }
}

/// What zooming into a surface does, by what it shows. Extend: the cursor moves onto the virtual display and is
/// clamped there. Window: the window is placed over Minecraft. Mirror: nothing (it is used in-world instead, spec 5.10). Zoom
/// itself is retired on a Mac; the messages stay for protocol compatibility.
enum ZoomStyle: Equatable {
    case mirror, display, window

    init(kind: CaptureKind) {
        switch kind {
        case .main: self = .mirror
        case .display: self = .display
        case .window: self = .window
        }
    }

    var clampsCursor: Bool { self == .display }
    var placesWindow: Bool { self == .window }
}

/// Mirror captures are built only once Minecraft's app is in ScreenCaptureKit's shareable content, so it can be
/// excluded: retried with backoff (about 5 s in all), then the capture fails rather than show Minecraft inside itself.
enum MirrorExclusion {
    enum Decision: Equatable { case capture, retry(after: TimeInterval), fail }
    static let delays: [TimeInterval] = [0.25, 0.5, 1, 1.5, 2]

    /// `attempt` counts from 0; `found` = Minecraft's app is in the shareable content.
    static func decide(found: Bool, attempt: Int) -> Decision {
        if found { return .capture }
        return attempt < delays.count ? .retry(after: delays[attempt]) : .fail
    }
}

enum CursorClamp {
    /// The point moved inside `bounds` (max edges exclusive), or nil when it is already inside.
    static func clamp(_ point: CGPoint, to bounds: CGRect) -> CGPoint? {
        let x = min(max(point.x, bounds.minX), bounds.maxX - 1)
        let y = min(max(point.y, bounds.minY), bounds.maxY - 1)
        return x == point.x && y == point.y ? nil : CGPoint(x: x, y: y)
    }

    static func center(of bounds: CGRect) -> CGPoint { CGPoint(x: bounds.midX, y: bounds.midY) }
}

/// Decides, each 120 Hz clamp tick, whether the cursor clamp must let go for good because the exit hotkey can no
/// longer fire: Secure Event Input (a password field) hides keys from the global monitor, and without Accessibility
/// the monitor sees nothing. Accessibility is re-checked every `permissionCheckInterval` ticks (about 100 ms).
struct ClampGuard {
    static let permissionCheckInterval = 12
    private var ticks = 0

    mutating func shouldRelease(secureInput: Bool, accessibilityTrusted: () -> Bool) -> Bool {
        if secureInput { return true }
        defer { ticks += 1 }
        return ticks % Self.permissionCheckInterval == 0 && !accessibilityTrusted()
    }
}
