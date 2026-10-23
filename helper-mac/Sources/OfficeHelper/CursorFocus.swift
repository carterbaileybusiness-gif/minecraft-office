import AppKit
import CoreGraphics

/// Display mode zoom: the cursor moves to the centre of the hologram's virtual display and is kept inside its
/// bounds (checked 120 times a second) until released. Warping needs no permission.
@MainActor final class CursorFocus {
    private var timer: DispatchSourceTimer?
    private(set) var displayID: CGDirectDisplayID?
    private var savedLocation: CGPoint?
    var log: (String) -> Void = { _ in }

    var isActive: Bool { displayID != nil }

    static var location: CGPoint { CGEvent(source: nil)?.location ?? .zero }

    /// `clamp` false only warps: without Accessibility the exit hotkey can't fire, so the pointer must stay free.
    func focus(on id: CGDirectDisplayID, clamp: Bool = true) {
        // Re-focusing keeps the location from before the first zoom.
        let saved = displayID == nil ? Self.location : savedLocation
        release(restore: false)
        savedLocation = saved
        displayID = id
        let bounds = CGDisplayBounds(id)
        warp(to: CursorClamp.center(of: bounds))
        guard clamp else { log("cursor moved to display \(id) (not clamped)"); return }
        let timer = DispatchSource.makeTimerSource(queue: .main)
        timer.schedule(deadline: .now(), repeating: .nanoseconds(1_000_000_000 / 120), leeway: .milliseconds(1))
        timer.setEventHandler { [weak self] in
            MainActor.assumeIsolated {
                guard let self, let id = self.displayID else { return }
                let bounds = CGDisplayBounds(id)
                guard !bounds.isEmpty else { self.release(restore: true); return } // display went away
                if let inside = CursorClamp.clamp(Self.location, to: bounds) { self.warp(to: inside) }
            }
        }
        timer.resume()
        self.timer = timer
        log("cursor focused on display \(id) \(bounds)")
    }

    /// Stops clamping; `restore` puts the cursor back where it was before the zoom.
    func release(restore: Bool) {
        timer?.cancel(); timer = nil
        guard displayID != nil else { return }
        displayID = nil
        if restore, let saved = savedLocation { warp(to: saved) }
        savedLocation = nil
        log("cursor released")
    }

    private func warp(to point: CGPoint) {
        CGWarpMouseCursorPosition(point)
        // Warping suppresses mouse movement briefly; re-associate so the next move is not swallowed.
        CGAssociateMouseAndMouseCursorPosition(1)
    }
}
