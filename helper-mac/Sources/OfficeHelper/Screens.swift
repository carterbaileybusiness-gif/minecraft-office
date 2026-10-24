import AppKit
import CoreGraphics

/// The real screens for the display map (DISPLAYS): every active display, its CGDisplay UUID (the opaque id the mod
/// keeps), bounds and a short name. Names never leave this computer except to the mod on it.
enum ScreenCatalog {
    /// The UUID macOS keys display arrangement on (stable across reconnects and reboots).
    static func uuid(_ id: CGDirectDisplayID) -> String? {
        guard let uuid = CGDisplayCreateUUIDFromDisplayID(id)?.takeRetainedValue() else { return nil }
        return CFUUIDCreateString(nil, uuid) as String?
    }

    /// The active display with this UUID, nil when it is not connected (or asleep, or a mirrored copy).
    static func displayID(uuid: String) -> CGDirectDisplayID? {
        guard let parsed = CFUUIDCreateFromString(nil, uuid as CFString) else { return nil }
        let id = CGDisplayGetDisplayIDFromUUID(parsed)
        return id != 0 && activeIDs().contains(id) ? id : nil
    }

    /// Active displays, mirrored copies left out (they share their source's bounds).
    static func activeIDs() -> [CGDirectDisplayID] {
        var count: UInt32 = 0
        guard CGGetActiveDisplayList(0, nil, &count) == .success, count > 0 else { return [] }
        var ids = [CGDirectDisplayID](repeating: 0, count: Int(count))
        guard CGGetActiveDisplayList(count, &ids, &count) == .success else { return [] }
        return ids.prefix(Int(count)).filter { CGDisplayMirrorsDisplay($0) == kCGNullDirectDisplay }
    }

    /// Every screen: the main display first, then by origin x, then y. `ours`: the Office displays (slot → id), which
    /// carry their slot and no name.
    @MainActor static func list(ours: [Int: CGDirectDisplayID]) -> [ScreenInfo] {
        let slots = Dictionary(ours.map { ($0.value, $0.key) }, uniquingKeysWith: { a, _ in a })
        var names: [CGDirectDisplayID: String] = [:]
        for screen in NSScreen.screens {
            if let number = screen.deviceDescription[NSDeviceDescriptionKey("NSScreenNumber")] as? NSNumber {
                names[number.uint32Value] = screen.localizedName
            }
        }
        let main = CGMainDisplayID()
        let infos = activeIDs().compactMap { id -> (CGRect, ScreenInfo)? in
            guard let uuid = uuid(id) else { return nil }
            let b = CGDisplayBounds(id)
            guard b.width >= 1, b.height >= 1 else { return nil }
            let slot = slots[id] ?? 0
            let name = slot == 0 ? MonitorNames.short(names[id] ?? "", builtIn: CGDisplayIsBuiltin(id) != 0) : nil
            return (b, ScreenInfo(id: uuid, slot: slot, main: id == main, name: name,
                                  x: Int(b.minX), y: Int(b.minY), w: Int(b.width), h: Int(b.height)))
        }
        let sorted = infos.sorted { a, b in
            if a.1.main != b.1.main { return a.1.main }
            return (a.0.minX, a.0.minY) < (b.0.minX, b.0.minY)
        }
        return Array(sorted.map(\.1).prefix(ScreensLimits.maxDisplays))
    }

    private nonisolated(unsafe) static var reconfigured: (() -> Void)?
    private nonisolated(unsafe) static var registered = false

    /// Calls `handler` on the main queue after each display reconfiguration (a monitor plugged in or out, moved,
    /// an Office display created, moved or removed). One handler; a new call replaces it.
    @MainActor static func onReconfigure(_ handler: @escaping () -> Void) {
        reconfigured = handler
        guard !registered else { return }
        registered = true
        CGDisplayRegisterReconfigurationCallback({ _, flags, _ in
            guard !flags.contains(.beginConfigurationFlag) else { return }
            DispatchQueue.main.async { MainActor.assumeIsolated { ScreenCatalog.reconfigured?() } }
        }, nil)
    }
}
