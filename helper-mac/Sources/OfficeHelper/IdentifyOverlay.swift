import AppKit

/// IDENTIFY: for a few seconds a big label ("A", "1") on each screen, like Windows' Identify — one borderless,
/// click-through window per screen at `IdentifyLayout.square`. The windows never activate the helper or take the
/// keyboard (Minecraft keeps focus), show on every Space and over full-screen apps, and are not left out of captures
/// (so a Mirror or Extend hologram shows the label under the mod's own).
@MainActor final class IdentifyOverlay {
    private var windows: [NSWindow] = []
    private var shown = 0

    /// `bounds`: a screen's global bounds (points, top-left origin). A new call replaces the windows of the last one.
    func show(_ items: [(bounds: CGRect, label: String)], seconds: Double = 3) {
        hide()
        shown += 1
        let current = shown
        // Cocoa's origin is the bottom-left of the main display (the one at Quartz (0, 0)).
        let mainHeight = CGDisplayBounds(CGMainDisplayID()).height
        for item in items {
            let square = IdentifyLayout.square(in: item.bounds)
            guard square.width >= 1 else { continue }
            let frame = NSRect(x: square.minX, y: mainHeight - square.maxY, width: square.width, height: square.height)
            let window = NSWindow(contentRect: frame, styleMask: [.borderless], backing: .buffered, defer: false)
            window.isReleasedWhenClosed = false
            window.level = .screenSaver
            window.ignoresMouseEvents = true
            window.collectionBehavior = [.canJoinAllSpaces, .fullScreenAuxiliary, .stationary, .ignoresCycle]
            window.isOpaque = false
            window.backgroundColor = .clear
            window.hasShadow = false
            window.contentView = IdentifyLabelView(frame: NSRect(origin: .zero, size: frame.size), label: item.label)
            window.setFrame(frame, display: false) // borderless windows are not constrained to a screen
            window.orderFrontRegardless() // shown without activating the helper
            windows.append(window)
        }
        DispatchQueue.main.asyncAfter(deadline: .now() + seconds) { [weak self] in
            MainActor.assumeIsolated { if self?.shown == current { self?.hide() } }
        }
    }

    func hide() {
        for window in windows { window.orderOut(nil) }
        windows.removeAll()
    }
}

/// A dark rounded square (0xE6101418, corners 12 % of the side) with the label in white bold at 70 % of the side.
private final class IdentifyLabelView: NSView {
    let label: String

    init(frame: NSRect, label: String) {
        self.label = label
        super.init(frame: frame)
    }

    required init?(coder: NSCoder) { nil }

    override func draw(_ dirtyRect: NSRect) {
        let side = min(bounds.width, bounds.height)
        NSColor(srgbRed: 0x10 / 255.0, green: 0x14 / 255.0, blue: 0x18 / 255.0, alpha: 0xE6 / 255.0).setFill()
        NSBezierPath(roundedRect: bounds, xRadius: side * 0.12, yRadius: side * 0.12).fill()
        let text = NSAttributedString(string: label, attributes: [
            .font: NSFont.systemFont(ofSize: side * 0.7, weight: .bold),
            .foregroundColor: NSColor.white,
        ])
        let size = text.size()
        text.draw(at: NSPoint(x: bounds.midX - size.width / 2, y: bounds.midY - size.height / 2))
    }
}
