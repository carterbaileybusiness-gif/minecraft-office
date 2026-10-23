import AppKit

/// While zoomed in: double-tap Esc (≤ 350 ms) or Ctrl+Alt+Cmd+Esc, pressed in any app, exits the zoom.
/// A global key-down monitor sees other apps' keys only when Accessibility is granted.
@MainActor final class ExitHotkey {
    var onExit: (() -> Void)?
    private var monitor: Any?
    private var detector = ExitChordDetector()

    var isArmed: Bool { monitor != nil }

    func arm() {
        guard monitor == nil else { return }
        detector = ExitChordDetector()
        monitor = NSEvent.addGlobalMonitorForEvents(matching: .keyDown) { [weak self] event in
            MainActor.assumeIsolated { self?.handle(event) }
        }
    }

    func disarm() {
        if let monitor { NSEvent.removeMonitor(monitor) }
        monitor = nil
    }

    private func handle(_ event: NSEvent) {
        let flags = event.modifierFlags.intersection(.deviceIndependentFlagsMask)
        var modifiers: KeyModifiers = []
        if flags.contains(.shift) { modifiers.insert(.shift) }
        if flags.contains(.control) { modifiers.insert(.control) }
        if flags.contains(.option) { modifiers.insert(.option) }
        if flags.contains(.command) { modifiers.insert(.command) }
        if detector.keyDown(keyCode: event.keyCode, modifiers: modifiers, time: event.timestamp, isRepeat: event.isARepeat) {
            onExit?()
        }
    }
}
