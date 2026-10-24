import Foundation

/// The display map's pure rules: touching (shared vectors V1–V6 with the mod's snapping), remembered spots, SET_ORIGINS
/// checks, the arrangement file, monitor names.
enum ArrangementTests {
    static let main = CGRect(x: 0, y: 0, width: 1440, height: 900)
    static let fhd = CGSize(width: 1920, height: 1080)
    static func rect(_ x: Double, _ y: Double, _ w: Double = 1920, _ h: Double = 1080) -> CGRect { CGRect(x: x, y: y, width: w, height: h) }

    static func runAll() {
        run("touching: shared vectors V1-V6") {
            expect(ScreenRules.valid(rect(1440, 0), among: [main]), "V1 right of main: valid")
            expect(!ScreenRules.valid(rect(1440, 900), among: [main]), "V2 corner only: invalid")
            expect(!ScreenRules.valid(rect(1000, 0), among: [main]), "V3 overlap: invalid")
            expect(!ScreenRules.valid(rect(1500, 0), among: [main]), "V4 detached: invalid")
            expect(ScreenRules.valid(rect(-1920, -180), among: [main]), "V5 left of and above: valid")
            expect(ScreenRules.valid(rect(0, -1080), among: [main]), "V6 above: valid")
            expectEqual(ScreenRules.sharedEdge(rect(1440, 0), main), 900, "V1 shares the main display's whole right edge")
            expectEqual(ScreenRules.sharedEdge(rect(1440, 900), main), 0, "corner")
            expect(ScreenRules.overlaps(rect(1000, 0), main), "V3 overlaps")
            expect(!ScreenRules.overlaps(rect(1440, 0), main), "edge contact is not overlap")
        }

        run("arrangement: remembered spots are used while valid, else today's automatic placement") {
            let one = [(slot: 1, size: fhd)]
            expectEqual(DisplayArrangement.origins(main: main, others: [], ours: one, saved: [1: CGPoint(x: 1440, y: 0)]),
                        [1: CGPoint(x: 1440, y: 0)], "V1 saved: used")
            expectEqual(DisplayArrangement.origins(main: main, others: [], ours: one, saved: [1: CGPoint(x: 0, y: -1080)]),
                        [1: CGPoint(x: 0, y: -1080)], "V6 saved (above): used instead of the right edge")
            expectEqual(DisplayArrangement.origins(main: main, others: [], ours: one, saved: [1: CGPoint(x: 1500, y: 0)]),
                        [1: CGPoint(x: 1440, y: 0)], "V4 saved (detached): automatic")
            let two = [(slot: 2, size: fhd), (slot: 1, size: fhd)]
            expectEqual(DisplayArrangement.origins(main: main, others: [], ours: two,
                                                   saved: [1: CGPoint(x: 0, y: -1080), 2: CGPoint(x: 1920, y: -1080)]),
                        [1: CGPoint(x: 0, y: -1080), 2: CGPoint(x: 1920, y: -1080)], "slot 2 touching saved slot 1: both used")
            expectEqual(DisplayArrangement.origins(main: main, others: [], ours: two,
                                                   saved: [2: CGPoint(x: 0, y: -1080), 1: CGPoint(x: 1920, y: -1080)]),
                        [2: CGPoint(x: 0, y: -1080), 1: CGPoint(x: 1920, y: -1080)], "slot 1 touching only slot 2: still both (repeat)")
            let monitor = rect(1440, -180)
            expectEqual(DisplayArrangement.origins(main: main, others: [monitor], ours: one, saved: [1: CGPoint(x: 1440, y: 0)]),
                        [1: CGPoint(x: -1920, y: 0)], "saved spot overlapping a monitor: automatic")
            expectEqual(DisplayArrangement.origins(main: main, others: [], ours: two, saved: [2: CGPoint(x: 0, y: -1080)]),
                        [1: CGPoint(x: 1440, y: 0), 2: CGPoint(x: 0, y: -1080)], "the rest go automatically around a saved one")
            let monitorAbove = rect(0, -1080)
            expectEqual(DisplayArrangement.origins(main: main, others: [monitorAbove], ours: one, saved: [1: CGPoint(x: 1920, y: -1080)]),
                        [1: CGPoint(x: 1920, y: -1080)], "next to a monitor counts as touching")
            // the existing expectations without saved spots (also in PointerTests)
            expectEqual(DisplayArrangement.origins(main: main, others: [], ours: two), [1: CGPoint(x: 1440, y: 0), 2: CGPoint(x: 3360, y: 0)])
        }

        run("SET_ORIGINS: one new layout, touching and not overlapping") {
            let live: [Int: CGRect] = [1: rect(0, -1080)]
            func request(_ origins: [(Int, Int, Int)]) -> SetOriginsPayload {
                SetOriginsPayload(origins: origins.map { SlotOrigin(slot: $0.0, x: $0.1, y: $0.2) })
            }
            expectEqual(OriginRequest.check(request([(1, 1440, 0)]), live: live, fixed: [main]), .success([1: CGPoint(x: 1440, y: 0)]), "V1")
            if case .failure(let why) = OriginRequest.check(request([(1, 1440, 900)]), live: live, fixed: [main]) {
                expect(why.message.contains("must touch a screen"), "V2: \(why)")
            } else { expect(false, "V2 rejected") }
            if case .failure(let why) = OriginRequest.check(request([(1, 1000, 0)]), live: live, fixed: [main]) {
                expect(why.message.contains("overlaps"), "V3: \(why)")
            } else { expect(false, "V3 rejected") }
            let row: [Int: CGRect] = [1: rect(1440, 0), 2: rect(3360, 0)]
            expectEqual(OriginRequest.check(request([(1, 3360, 0), (2, 1440, 0)]), live: row, fixed: [main]),
                        .success([1: CGPoint(x: 3360, y: 0), 2: CGPoint(x: 1440, y: 0)]), "1 and 2 swapped in one request")
            if case .failure(let why) = OriginRequest.check(request([(1, 3360, 0)]), live: row, fixed: [main]) {
                expect(why.message.contains("overlaps"), "onto display 2: \(why)")
            } else { expect(false, "moving onto display 2 rejected") }
            if case .failure(let why) = OriginRequest.check(request([(1, 0, -1080)]), live: row, fixed: [main]) {
                expect(why.message.contains("display 2 must touch a screen"), "display 2 would be left floating: \(why)")
            } else { expect(false, "leaving display 2 detached rejected") }
            if case .failure(let why) = OriginRequest.check(request([(3, 1440, 0)]), live: live, fixed: [main]) {
                expect(why.message.contains("not on"), "a display that doesn't exist: \(why)")
            } else { expect(false, "unknown slot rejected") }
            expectEqual(OriginRequest.check(request([(1, 1920, -1080)]), live: live, fixed: [main, rect(0, -1080)]),
                        .success([1: CGPoint(x: 1920, y: -1080)]), "touching a monitor only")
        }

        run("arrangement file: round trip, per main display, garbage is empty") {
            var saved = SavedArrangement()
            saved.record(forMain: "MAIN-A", [1: CGPoint(x: 1440, y: 0)])
            saved.record(forMain: "MAIN-B", [1: CGPoint(x: 0, y: -1080), 2: CGPoint(x: 1920, y: -1080)])
            saved.record(forMain: "MAIN-A", [2: CGPoint(x: 3360, y: 0)])
            expectEqual(saved.origins(forMain: "MAIN-A"), [1: CGPoint(x: 1440, y: 0), 2: CGPoint(x: 3360, y: 0)], "MAIN-A keeps slot 1")
            expectEqual(saved.origins(forMain: "MAIN-B"), [1: CGPoint(x: 0, y: -1080), 2: CGPoint(x: 1920, y: -1080)], "separate layouts")
            expectEqual(saved.origins(forMain: "OTHER"), [:], "unknown main display")

            let directory = URL(fileURLWithPath: NSTemporaryDirectory()).appendingPathComponent("oh-arr-\(getpid())", isDirectory: true)
            try FileManager.default.createDirectory(at: directory, withIntermediateDirectories: true)
            defer { try? FileManager.default.removeItem(at: directory) }
            let store = ArrangementStore(url: directory.appendingPathComponent("display-arrangement.json"))
            expectEqual(store.load(), SavedArrangement(), "missing file: empty")
            var one = SavedArrangement()
            one.record(forMain: "M", [1: CGPoint(x: 1440, y: 0)])
            try store.save(one)
            expectEqual(String(decoding: try Data(contentsOf: store.url), as: UTF8.self), #"{"layouts":{"M":{"1":{"x":1440,"y":0}}},"schema":1}"#)
            expectEqual(store.load(), one, "round trip")
            try store.save(saved)
            expectEqual(store.load(), saved, "round trip, two main displays")
            try Data("not json {".utf8).write(to: store.url)
            expectEqual(store.load(), SavedArrangement(), "garbage: empty")
            try Data(#"{"layouts":{"M":{"1":{"x":1,"y":2}}},"schema":2}"#.utf8).write(to: store.url)
            expectEqual(store.load(), SavedArrangement(), "another schema: empty")
            try Data(#"{"layouts":{"M":{"9":{"x":1,"y":2},"one":{"x":1,"y":2}}},"schema":1}"#.utf8).write(to: store.url)
            expectEqual(store.load().origins(forMain: "M"), [:], "slot keys outside 1...4 are ignored")
        }

        run("identify: a centred square, 28 % of the shorter side") {
            expectEqual(IdentifyLayout.square(in: main), CGRect(x: 594, y: 324, width: 252, height: 252), "the MacBook")
            expectEqual(IdentifyLayout.square(in: rect(-1920, -180)), CGRect(x: -1111, y: 209, width: 302, height: 302),
                        "a monitor left of / above the main display")
            expectEqual(IdentifyLayout.square(in: rect(0, 0, 1080, 1920)), CGRect(x: 389, y: 809, width: 302, height: 302), "portrait")
        }

        run("monitor names: short, and never empty") {
            expectEqual(MonitorNames.short("Built-in Retina Display", builtIn: true), "MacBook")
            expectEqual(MonitorNames.short("LG HDR 4K", builtIn: false), "LG")
            expectEqual(MonitorNames.short("DELL U2720Q", builtIn: false), "DELL")
            expectEqual(MonitorNames.short("", builtIn: false), "Screen")
            expectEqual(MonitorNames.short("   ", builtIn: false), "Screen")
            expectEqual(MonitorNames.short("Supercalifragilistic", builtIn: false), "Supercalifra")
            expectEqual(MonitorNames.short("Supercalifragilistic", builtIn: false).count, 12)
        }
    }
}
