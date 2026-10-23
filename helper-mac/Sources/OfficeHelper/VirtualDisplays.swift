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
    /// The window server ended a display on its own (slot).
    var onTerminated: ((Int) -> Void)?
    var log: (String) -> Void = { _ in }

    func displayID(slot: Int) -> CGDirectDisplayID? { displays[slot]?.displayID }

    func slot(of displayID: CGDirectDisplayID) -> Int? { displays.first { $0.value.displayID == displayID }?.key }

    /// Creates (or keeps, when identical) the display for this slot and returns its id.
    func create(_ spec: DisplaySpec) throws -> CGDirectDisplayID {
        if let existing = displays[spec.slot] {
            if existing.spec == spec { return existing.displayID }
            remove(slot: spec.slot)
        }
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
    func remove(slot: Int) {
        guard let live = displays.removeValue(forKey: slot) else { return }
        log("removed \(live.spec.name) id \(live.displayID)")
    }

    func removeAll() { for slot in Array(displays.keys) { remove(slot: slot) } }

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
