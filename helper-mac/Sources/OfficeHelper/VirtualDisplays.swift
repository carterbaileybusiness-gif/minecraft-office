import Foundation
import CoreGraphics

/// "Office Display N" virtual displays (private CGVirtualDisplay API, see CGVirtualDisplay.h).
/// The descriptor advertises a HiDPI-capable 2× ceiling, but only the 1× mode is applied: displays created with a
/// HiDPI mode applied were the ones that failed to go away on release. Releasing the CGVirtualDisplay object removes
/// the display; the window server also removes it when this process exits for any reason.
@MainActor final class VirtualDisplays {
    struct Live {
        let spec: DisplaySpec
        let display: CGVirtualDisplay
        var displayID: CGDirectDisplayID { display.displayID }
    }

    enum Failure: Error, CustomStringConvertible {
        case createFailed, applyFailed
        var description: String {
            switch self {
            case .createFailed: return "CGVirtualDisplay could not be created"
            case .applyFailed: return "CGVirtualDisplay rejected its 1x mode"
            }
        }
    }

    private(set) var displays: [Int: Live] = [:]
    /// Where the other displays (real monitors) were just before the last create (MonitorHomes): macOS shoves a monitor
    /// aside to fit a new display in, and the arrange after that create puts it back.
    private var homes = MonitorHomes()
    /// The window server ended a display on its own (slot).
    var onTerminated: ((Int) -> Void)?
    var log: (String) -> Void = { _ in }

    func displayID(slot: Int) -> CGDirectDisplayID? { displays[slot]?.displayID }

    func slot(of displayID: CGDirectDisplayID) -> Int? { displays.first { $0.value.displayID == displayID }?.key }

    /// Creates (or keeps, when identical) the display for this slot and returns its id.
    func create(_ spec: DisplaySpec) throws -> CGDirectDisplayID {
        if let existing = displays[spec.slot] {
            if existing.spec == spec { return existing.displayID }
            remove(slot: spec.slot, keepMonitors: false) // the create below records and restores them
        }
        homes.beforeCreate(Dictionary(uniqueKeysWithValues: otherIDs().map { ($0, CGDisplayBounds($0).origin) }),
                           at: Date().timeIntervalSinceReferenceDate)
        defer { if displays.isEmpty { homes.noneLeft() } } // a failed first create leaves nothing to put back
        let descriptor = CGVirtualDisplayDescriptor()
        descriptor.queue = DispatchQueue.main
        descriptor.name = spec.name
        descriptor.maxPixelsWide = UInt32(spec.maxPixelsWide)
        descriptor.maxPixelsHigh = UInt32(spec.maxPixelsHigh)
        descriptor.sizeInMillimeters = spec.sizeInMillimeters
        descriptor.serialNum = spec.serial
        descriptor.productID = spec.productID
        descriptor.vendorID = spec.vendorID
        // sRGB primaries and D65 white point.
        descriptor.redPrimary = CGPoint(x: 0.64, y: 0.33)
        descriptor.greenPrimary = CGPoint(x: 0.30, y: 0.60)
        descriptor.bluePrimary = CGPoint(x: 0.15, y: 0.06)
        descriptor.whitePoint = CGPoint(x: 0.3127, y: 0.3290)
        let slot = spec.slot
        descriptor.terminationHandler = { [weak self] _, _ in
            MainActor.assumeIsolated {
                guard let self, self.displays[slot] != nil else { return }
                self.log("display slot \(slot) terminated by the window server")
                self.displays[slot] = nil
                if self.displays.isEmpty { self.homes.noneLeft() }
                self.onTerminated?(slot)
            }
        }
        guard let display = CGVirtualDisplay(descriptor: descriptor) else { throw Failure.createFailed }
        let settings = CGVirtualDisplaySettings()
        settings.hiDPI = 0
        settings.modes = [CGVirtualDisplayMode(width: UInt32(spec.width), height: UInt32(spec.height), refreshRate: 60)]
        guard display.apply(settings) else { throw Failure.applyFailed }
        displays[slot] = Live(spec: spec, display: display)
        log("created \(spec.name) id \(display.displayID) \(spec.width)x\(spec.height) serial \(String(spec.serial, radix: 16))")
        return display.displayID
    }

    /// Releasing the last reference removes the display.
    func remove(slot: Int, keepMonitors: Bool = true) {
        guard displays[slot] != nil else { return }
        let before = Dictionary(uniqueKeysWithValues: otherIDs().map { ($0, CGDisplayBounds($0).origin) })
        guard let live = displays.removeValue(forKey: slot) else { return }
        log("removed \(live.spec.name) id \(live.displayID)")
        if displays.isEmpty { homes.noneLeft() }
        if keepMonitors && !before.isEmpty { keepMonitorsPut(before) }
    }

    /// For a few seconds after a remove, puts back a monitor macOS moves (its remembered layout for the remaining
    /// displays): monitors stay where they were just before the remove — where the user last had them.
    private func keepMonitorsPut(_ before: [CGDirectDisplayID: CGPoint]) {
        Task { @MainActor [weak self] in
            for _ in 0..<12 {
                try? await Task.sleep(nanoseconds: 250_000_000)
                guard let self else { return }
                let now = Dictionary(uniqueKeysWithValues: self.otherIDs().map { ($0, CGDisplayBounds($0).origin) })
                let back = MonitorHomes.movedSince(before, now: now)
                guard !back.isEmpty else { continue }
                var config: CGDisplayConfigRef?
                guard CGBeginDisplayConfiguration(&config) == .success, let config else { return }
                for b in back { CGConfigureDisplayOrigin(config, b.id, Int32(b.to.x), Int32(b.to.y)) }
                let result = CGCompleteDisplayConfiguration(config, .forSession)
                self.log("kept \(back.count) monitor(s) where they were before the remove: \(result == .success ? "ok" : "error \(result.rawValue)")")
                return
            }
        }
    }

    /// Online displays that are neither the main display nor ours.
    private func otherIDs() -> [CGDirectDisplayID] {
        let ourIDs = Set(displays.values.map(\.displayID))
        var count: UInt32 = 0
        CGGetOnlineDisplayList(0, nil, &count)
        var ids = [CGDirectDisplayID](repeating: 0, count: Int(count))
        CGGetOnlineDisplayList(count, &ids, &count)
        return ids.prefix(Int(count)).filter { $0 != CGMainDisplayID() && !ourIDs.contains($0) }
    }


    /// Everything goes (the mod left; the helper quits right after): macOS's own layout applies from then on.
    func removeAll() { for slot in Array(displays.keys) { remove(slot: slot, keepMonitors: false) } }

    /// Puts the Office displays next to the main display (DisplayArrangement) for this login session, when they aren't
    /// there already. Other displays are only put back where they were just before the create this arrange follows
    /// (macOS shoves a monitor at the main display's right edge aside for a new display; that edge stays the monitor's).
    /// After a remove nothing is put back to an old place: a monitor the user moved meanwhile stays where they put it
    /// (`remove` even undoes macOS moving it to its remembered layout).
    func arrange() {
        let main = CGDisplayBounds(CGMainDisplayID())
        // each monitor where it was just before the last create (macOS may have shoved it aside to fit ours in)
        let others = homes.forArrange(Dictionary(uniqueKeysWithValues: otherIDs().map { ($0, CGDisplayBounds($0)) }))
        let ours = displays.map { (slot: $0.key, size: CGSize(width: $0.value.spec.width, height: $0.value.spec.height)) }
        let wanted = DisplayArrangement.origins(main: main, others: others.rects, ours: ours)
        let moves = wanted.filter { slot, origin in
            guard let id = displays[slot]?.displayID else { return false }
            return CGDisplayBounds(id).origin != origin
        }
        let restores = others.restores
        guard !moves.isEmpty || !restores.isEmpty else { return }
        var config: CGDisplayConfigRef?
        guard CGBeginDisplayConfiguration(&config) == .success, let config else { return }
        for (slot, origin) in moves {
            if let id = displays[slot]?.displayID { CGConfigureDisplayOrigin(config, id, Int32(origin.x), Int32(origin.y)) }
        }
        for r in restores { CGConfigureDisplayOrigin(config, r.id, Int32(r.to.x), Int32(r.to.y)) }
        let result = CGCompleteDisplayConfiguration(config, .forSession)
        log("arranged \(moves.keys.sorted()) next to the main display\(restores.isEmpty ? "" : ", \(restores.count) monitor(s) back where they were"): "
            + (result == .success ? "ok" : "error \(result.rawValue)"))
    }

    static func isOnline(_ id: CGDirectDisplayID) -> Bool {
        var count: UInt32 = 0
        guard CGGetOnlineDisplayList(0, nil, &count) == .success, count > 0 else { return false }
        var ids = [CGDirectDisplayID](repeating: 0, count: Int(count))
        guard CGGetOnlineDisplayList(count, &ids, &count) == .success else { return false }
        return ids.prefix(Int(count)).contains(id)
    }

    /// Polls until the display is online (normally within a few hundred milliseconds).
    static func waitOnline(_ id: CGDirectDisplayID, timeout: TimeInterval = 3) async -> Bool {
        let deadline = Date().addingTimeInterval(timeout)
        while Date() < deadline {
            if isOnline(id) { return true }
            try? await Task.sleep(nanoseconds: 50_000_000)
        }
        return isOnline(id)
    }
}
