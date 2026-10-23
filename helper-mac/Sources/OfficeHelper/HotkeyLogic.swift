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

enum CursorClamp {
    /// The point moved inside `bounds` (max edges exclusive), or nil when it is already inside.
    static func clamp(_ point: CGPoint, to bounds: CGRect) -> CGPoint? {
        let x = min(max(point.x, bounds.minX), bounds.maxX - 1)
        let y = min(max(point.y, bounds.minY), bounds.maxY - 1)
        return x == point.x && y == point.y ? nil : CGPoint(x: x, y: y)
    }

    static func center(of bounds: CGRect) -> CGPoint { CGPoint(x: bounds.midX, y: bounds.midY) }
}
