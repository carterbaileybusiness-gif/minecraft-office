import Foundation
import CoreGraphics

// Pure display-map rules (Foundation + CoreGraphics types only, so check.sh can test them): when a screen touches
// another, whether a SET_ORIGINS may be applied, the remembered arrangement and its file, short monitor names.

/// Touching rules, shared with the mod's snapping (vectors V1–V6 in the plan). Rects are global points, top-left origin.
enum ScreenRules {
    /// The interiors overlap (sharing only an edge or a corner is not overlapping).
    static func overlaps(_ a: CGRect, _ b: CGRect) -> Bool {
        a.minX < b.maxX && b.minX < a.maxX && a.minY < b.maxY && b.minY < a.maxY
    }

    /// How long the edge the two share is (0 when they don't touch, or touch only at a corner).
    static func sharedEdge(_ a: CGRect, _ b: CGRect) -> CGFloat {
        if a.maxX == b.minX || b.maxX == a.minX { return max(0, min(a.maxY, b.maxY) - max(a.minY, b.minY)) }
        if a.maxY == b.minY || b.maxY == a.minY { return max(0, min(a.maxX, b.maxX) - max(a.minX, b.minX)) }
        return 0
    }

    /// `r` overlaps none of `among` and shares at least 1 pt of edge with one of them.
    static func valid(_ r: CGRect, among: [CGRect]) -> Bool {
        !among.contains { overlaps(r, $0) } && among.contains { sharedEdge(r, $0) >= 1 }
    }
}

/// Checks a SET_ORIGINS against the screens as they are (pure). `live`: the Office displays now (slot → bounds);
/// `fixed`: every other screen, the main display first, then the real monitors; none of them ever moves. `mainSlot`:
/// the Office display that is the main display, when the user made one main in System Settings (it is never moved:
/// moving the main display shifts every other screen, real monitors included). The request is checked as one new
/// layout, so displays may trade places. No Office display of the new layout may overlap anything, and the whole layout
/// (monitors and Office displays) must stay one piece joined to the main display by touching edges: never an Office
/// display floating detached, never a monitor left hanging off a moved Office display (macOS would move that monitor
/// to close the gap). Shared vector V7 with the mod's `DisplaySnap.layoutValid`.
enum OriginRequest {
    /// Why a request was refused (sent as `ERROR bad_request`).
    struct Problem: Error, Equatable {
        let message: String
        init(_ message: String) { self.message = message }
    }

    static func check(_ p: SetOriginsPayload, live: [Int: CGRect], fixed: [CGRect], mainSlot: Int? = nil)
        -> Result<[Int: CGPoint], Problem> {
        var moved: [Int: CGPoint] = [:]
        for o in p.origins {
            guard live[o.slot] != nil else { return .failure(Problem("display \(o.slot) is not on")) }
            if o.slot == mainSlot { return .failure(Problem("display \(o.slot) is the main display")) }
            moved[o.slot] = CGPoint(x: o.x, y: o.y)
        }
        var layout = live
        for (slot, origin) in moved { layout[slot]!.origin = origin }
        let slots = layout.keys.sorted()
        for slot in slots {
            let r = layout[slot]!
            if fixed.contains(where: { ScreenRules.overlaps(r, $0) })
                || slots.contains(where: { $0 != slot && ScreenRules.overlaps(r, layout[$0]!) }) {
                return .failure(Problem("display \(slot) overlaps another screen"))
            }
        }
        // one piece: grow from the main display through touching edges, over monitors and Office displays alike
        var joined: [CGRect] = []
        var leftSlots = Set(slots)
        var leftFixed = Set(fixed.indices)
        if let main = mainSlot, let r = layout[main] {
            joined.append(r)
            leftSlots.remove(main)
        } else if let r = fixed.first {
            joined.append(r)
            leftFixed.remove(0)
        }
        var grew = true
        while grew {
            grew = false
            for slot in leftSlots.sorted() where joined.contains(where: { ScreenRules.sharedEdge(layout[slot]!, $0) >= 1 }) {
                joined.append(layout[slot]!)
                leftSlots.remove(slot)
                grew = true
            }
            for i in leftFixed.sorted() where joined.contains(where: { ScreenRules.sharedEdge(fixed[i], $0) >= 1 }) {
                joined.append(fixed[i])
                leftFixed.remove(i)
                grew = true
            }
        }
        if let lost = leftSlots.sorted().first { return .failure(Problem("display \(lost) must touch a screen")) }
        if !leftFixed.isEmpty { return .failure(Problem("a monitor would be cut off")) }
        return .success(moved)
    }
}

/// Where the user put the Office displays, per main display (its CGDisplay UUID; the main display is always at
/// (0, 0), so these origins are relative to it). File: display-arrangement.json in the helper's support folder,
/// `{"layouts":{"<main uuid>":{"1":{"x":1440,"y":0}}},"schema":1}`.
struct SavedOrigin: Codable, Equatable { var x: Int; var y: Int }

struct SavedArrangement: Codable, Equatable {
    static let currentSchema = 1
    var schema = SavedArrangement.currentSchema
    var layouts: [String: [String: SavedOrigin]] = [:]

    func origins(forMain main: String) -> [Int: CGPoint] {
        var out: [Int: CGPoint] = [:]
        for (key, o) in layouts[main] ?? [:] {
            if let slot = Int(key), (1...4).contains(slot) { out[slot] = CGPoint(x: o.x, y: o.y) }
        }
        return out
    }

    /// Remembers these slots' origins for this main display (other slots keep their remembered spot).
    mutating func record(forMain main: String, _ origins: [Int: CGPoint]) {
        var layout = layouts[main] ?? [:]
        for (slot, p) in origins { layout[String(slot)] = SavedOrigin(x: Int(p.x.rounded()), y: Int(p.y.rounded())) }
        layouts[main] = layout
    }
}

struct ArrangementStore {
    let url: URL

    /// The saved arrangement; a missing, unreadable or foreign-schema file is an empty one.
    func load() -> SavedArrangement {
        guard let data = try? Data(contentsOf: url),
              let saved = try? JSONDecoder().decode(SavedArrangement.self, from: data),
              saved.schema == SavedArrangement.currentSchema else { return SavedArrangement() }
        return saved
    }

    func save(_ arrangement: SavedArrangement) throws {
        let encoder = JSONEncoder()
        encoder.outputFormatting = [.sortedKeys]
        try encoder.encode(arrangement).write(to: url, options: .atomic)
        try? FileManager.default.setAttributes([.posixPermissions: 0o600], ofItemAtPath: url.path)
    }
}

/// Monitor names for the map (they stay on this computer): the built-in display is "MacBook", others the first word of
/// their name ("LG HDR 4K" → "LG"), at most 12 characters; no name → "Screen".
enum MonitorNames {
    static let maxLength = 12

    static func short(_ localized: String, builtIn: Bool) -> String {
        if builtIn { return "MacBook" }
        guard let first = localized.split(whereSeparator: { $0.isWhitespace }).first else { return "Screen" }
        return String(first.prefix(maxLength))
    }
}

/// Identify (3 s, like Windows): a dark rounded square with the screen's label, centred on each screen, its side 28 % of
/// the screen's shorter side (rounded down). The mod draws the in-world label at the same 28 % of the hologram's
/// shorter side, so a captured overlay sits under it.
enum IdentifyLayout {
    static let percent = 28

    /// Global points, top-left origin; integer maths, so the result never depends on floating-point rounding.
    static func square(in bounds: CGRect) -> CGRect {
        let w = Int(bounds.width), h = Int(bounds.height)
        let side = min(w, h) * percent / 100
        let x = Int(bounds.minX) + (w - side) / 2, y = Int(bounds.minY) + (h - side) / 2
        return CGRect(x: x, y: y, width: side, height: side)
    }
}
