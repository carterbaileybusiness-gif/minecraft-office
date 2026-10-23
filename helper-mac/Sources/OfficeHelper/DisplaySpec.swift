import Foundation

// Pure virtual display identity and size rules (no AppKit / CoreGraphics private API).

/// Hologram shape → virtual display resolution (plan Global Constraints).
enum DisplayShape: CaseIterable, Equatable {
    case wide16x9, ultra21x9, tall9x16

    var width: Int {
        switch self { case .wide16x9: return 1920; case .ultra21x9: return 2560; case .tall9x16: return 1080 }
    }
    var height: Int {
        switch self { case .wide16x9: return 1080; case .ultra21x9: return 1080; case .tall9x16: return 1920 }
    }

    init?(width: Int, height: Int) {
        guard let match = Self.allCases.first(where: { $0.width == width && $0.height == height }) else { return nil }
        self = match
    }
}

struct DisplaySpecError: Error, Equatable {
    let code: String
    let message: String
    static func badSlot(_ slot: Int) -> DisplaySpecError {
        DisplaySpecError(code: "bad_slot", message: "slot must be 1...\(DisplaySpec.maxSlots), got \(slot)")
    }
    static func badSize(_ w: Int, _ h: Int) -> DisplaySpecError {
        DisplaySpecError(code: "bad_size", message: "\(w)x\(h) is not a hologram shape (1920x1080, 2560x1080, 1080x1920)")
    }
}

/// Everything needed to create "Office Display N". Identity (name, vendor, product, serial) depends only on the slot
/// so a recreated display is the same display to macOS and it restores window positions.
struct DisplaySpec: Equatable {
    static let maxSlots = 4
    /// macOS 14 needs a non-zero vendor id and distinct serials per virtual display.
    static let vendorID: UInt32 = 0x4D4F // "MO"
    static let productID: UInt32 = 0x0001
    static let pixelsPerInch = 110.0

    let slot: Int
    let shape: DisplayShape

    init(slot: Int, shape: DisplayShape) throws {
        guard (1...Self.maxSlots).contains(slot) else { throw DisplaySpecError.badSlot(slot) }
        self.slot = slot
        self.shape = shape
    }

    init(slot: Int, width: Int, height: Int) throws {
        guard (1...Self.maxSlots).contains(slot) else { throw DisplaySpecError.badSlot(slot) }
        guard let shape = DisplayShape(width: width, height: height) else { throw DisplaySpecError.badSize(width, height) }
        try self.init(slot: slot, shape: shape)
    }

    var name: String { "Office Display \(slot)" }
    var vendorID: UInt32 { Self.vendorID }
    var productID: UInt32 { Self.productID }
    /// "MO" in the high bytes, the slot in the low byte: 0x4D4F0001...0x4D4F0004.
    var serial: UInt32 { 0x4D4F_0000 | UInt32(slot) }
    var width: Int { shape.width }
    var height: Int { shape.height }
    /// The descriptor advertises a 2× ceiling (HiDPI capable); only the 1× mode is applied.
    var maxPixelsWide: Int { width * 2 }
    var maxPixelsHigh: Int { height * 2 }
    var sizeInMillimeters: CGSize {
        CGSize(width: Double(width) * 25.4 / Self.pixelsPerInch, height: Double(height) * 25.4 / Self.pixelsPerInch)
    }
}
