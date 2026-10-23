import Foundation

enum DisplaySpecTests {
    static func runAll() {
        run("slots 1...4 get unique names and serials") {
            let specs = try (1...4).map { try DisplaySpec(slot: $0, shape: .wide16x9) }
            expectEqual(specs.map(\.name), ["Office Display 1", "Office Display 2", "Office Display 3", "Office Display 4"])
            expectEqual(Set(specs.map(\.serial)).count, 4, "unique serials")
            expect(specs.allSatisfy { $0.serial != 0 }, "serials non-zero")
            expect(specs.allSatisfy { $0.vendorID != 0 }, "vendor id non-zero (macOS 14 requirement)")
            // Same slot → same identity across launches, so macOS restores window positions.
            expectEqual(try DisplaySpec(slot: 2, shape: .tall9x16).serial, specs[1].serial, "serial depends only on the slot")
        }

        run("slot 0 and slot 5 are rejected") {
            expectThrows("slot 5") { _ = try DisplaySpec(slot: 5, shape: .wide16x9) }
            expectThrows("slot 0") { _ = try DisplaySpec(slot: 0, shape: .wide16x9) }
            expectThrows("slot -1") { _ = try DisplaySpec(slot: -1, width: 1920, height: 1080) }
            expectEqual(DisplaySpec.maxSlots, 4)
        }

        run("size comes from the shape table") {
            expectEqual(DisplayShape.wide16x9.width, 1920); expectEqual(DisplayShape.wide16x9.height, 1080)
            expectEqual(DisplayShape.ultra21x9.width, 2560); expectEqual(DisplayShape.ultra21x9.height, 1080)
            expectEqual(DisplayShape.tall9x16.width, 1080); expectEqual(DisplayShape.tall9x16.height, 1920)
            let ultra = try DisplaySpec(slot: 3, shape: .ultra21x9)
            expectEqual(ultra.width, 2560); expectEqual(ultra.height, 1080)
            expectEqual(try DisplaySpec(slot: 1, width: 1080, height: 1920).shape, .tall9x16)
            expectEqual(DisplayShape(width: 2560, height: 1080), .ultra21x9)
            expectThrows("size not in the shape table") { _ = try DisplaySpec(slot: 1, width: 1366, height: 768) }
        }

        run("physical size and HiDPI ceiling") {
            let spec = try DisplaySpec(slot: 1, shape: .wide16x9)
            // About 110 pixels per inch at 1×.
            expect(abs(spec.sizeInMillimeters.width - 1920 * 25.4 / 110) < 0.01, "width mm")
            expect(abs(spec.sizeInMillimeters.height - 1080 * 25.4 / 110) < 0.01, "height mm")
            expectEqual(spec.maxPixelsWide, 3840, "advertises 2× ceiling")
            expectEqual(spec.maxPixelsHigh, 2160)
        }

        run("display error codes") {
            do { _ = try DisplaySpec(slot: 9, shape: .wide16x9) } catch let e as DisplaySpecError { expectEqual(e.code, "bad_slot") }
            do { _ = try DisplaySpec(slot: 1, width: 1, height: 1) } catch let e as DisplaySpecError { expectEqual(e.code, "bad_size") }
        }
    }
}
