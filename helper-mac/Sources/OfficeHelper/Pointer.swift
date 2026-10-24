import Foundation

// Pure in-world pointer rules (no AppKit): display pixel → global point, the helper's drag state, typing mode.

enum PointerButton: String, Codable, Equatable { case left, right, middle }

enum PointerMapping {
    /// Pixel (x, y) of a w×h virtual display → the global point (points, top-left origin) at that pixel's centre, inside
    /// `bounds` (CGDisplayBounds). Out-of-range pixels are clamped onto the display.
    static func globalPoint(x: Int, y: Int, width: Int, height: Int, bounds: CGRect) -> CGPoint {
        let w = max(width, 1), h = max(height, 1)
        let cx = min(max(x, 0), w - 1), cy = min(max(y, 0), h - 1)
        return CGPoint(x: bounds.minX + (Double(cx) + 0.5) * bounds.width / Double(w),
                       y: bounds.minY + (Double(cy) + 0.5) * bounds.height / Double(h))
    }
}

/// Physical mouse motion for DRAG_DELTA: whole points go out, the fraction stays for the next motion, so a slow
/// trackpad drag (a fraction of a point per event) still turns the player's head. Pure.
struct DeltaAccumulator: Equatable {
    private(set) var dx: Double = 0
    private(set) var dy: Double = 0

    mutating func add(dx: Double, dy: Double) {
        self.dx += dx
        self.dy += dy
    }

    /// The whole points so far (toward zero), or nil when there are none yet.
    mutating func take() -> (Int, Int)? {
        let x = Int(dx.rounded(.towardZero)), y = Int(dy.rounded(.towardZero))
        guard x != 0 || y != 0 else { return nil }
        dx -= Double(x)
        dy -= Double(y)
        return (x, y)
    }
}

/// A drag the helper drives for the mod (route "H" in Tools/inputprobe.swift, the one that works): the player holds the
/// physical button in Minecraft and turns their head. The helper brings the app under the press point forward (so the
/// press is not swallowed as an activation click), posts a real down there, takes the physical mouse with an event tap
/// (its motion goes back to the mod as DRAG_DELTA to turn the head, its release ends the drag), and posts real drags
/// at every point the mod maps from the crosshair. The window server then does the rest natively: text selection,
/// window drags across displays, drag and drop. Pure: the caller performs the effects.
struct DragMachine {
    enum Phase: Equatable { case idle, activating, dragging }
    enum Effect: Equatable {
        case startTap, stopTap
        case activate(CGPoint)
        case postDown(CGPoint, PointerButton), postDrag(CGPoint, PointerButton), postUp(CGPoint, PointerButton)
        case sendEnded(String)
        case activateMinecraft
    }

    private(set) var phase: Phase = .idle
    private(set) var button: PointerButton = .left
    private(set) var downPoint: CGPoint = .zero
    private(set) var lastPoint: CGPoint = .zero
    /// The last point a drag event was posted at (no duplicates).
    private var postedPoint: CGPoint?

    var isActive: Bool { phase != .idle }

    /// POINTER_DOWN. A drag still running is cancelled first (its up posted, Minecraft not re-activated in between).
    mutating func down(at point: CGPoint, button: PointerButton) -> [Effect] {
        var effects = phase == .idle ? [] : end("cancelled", reactivate: false)
        phase = .activating
        self.button = button
        downPoint = point
        lastPoint = point
        postedPoint = nil
        effects += [.startTap, .activate(point)]
        return effects
    }

    /// The app under the press point is frontmost (or waiting for it timed out): press there, catch up on the motion.
    mutating func activated() -> [Effect] {
        guard phase == .activating else { return [] }
        phase = .dragging
        return pressAndCatchUp()
    }

    /// POINTER_MOVE: the crosshair is over this point now.
    mutating func move(to point: CGPoint) -> [Effect] {
        switch phase {
        case .idle: return []
        case .activating: lastPoint = point; return []
        case .dragging:
            lastPoint = point
            guard postedPoint != point else { return [] }
            postedPoint = point
            return [.postDrag(point, button)]
        }
    }

    /// The physical button came up (seen by the tap, or found up by the safety poll).
    mutating func physicalUp() -> [Effect] { end("released", reactivate: true) }

    /// POINTER_UP from the mod, the mod disconnecting, the helper quitting.
    mutating func cancel() -> [Effect] { end("cancelled", reactivate: true) }

    private mutating func pressAndCatchUp() -> [Effect] {
        var effects: [Effect] = [.postDown(downPoint, button)]
        postedPoint = downPoint
        if lastPoint != downPoint {
            effects.append(.postDrag(lastPoint, button))
            postedPoint = lastPoint
        }
        return effects
    }

    private mutating func end(_ reason: String, reactivate: Bool) -> [Effect] {
        var effects: [Effect]
        switch phase {
        case .idle: return []
        case .activating:
            // Released before the app came forward: the gesture still happens (down, drag, up). A cancel never pressed.
            effects = reason == "released" ? pressAndCatchUp() + [.postUp(lastPoint, button)] : []
        case .dragging:
            effects = [.postUp(lastPoint, button)]
        }
        phase = .idle
        postedPoint = nil
        effects += [.stopTap, .sendEnded(reason)]
        if reactivate { effects.append(.activateMinecraft) }
        return effects
    }
}

/// Typing mode in the helper: after a CLICK the app has the keyboard; a plain Esc (seen by a session event tap, so it is
/// swallowed and never reaches the app) or Minecraft becoming active again ends it. Esc's repeats and its release are
/// swallowed too, or Minecraft would get them and open its pause menu. Pure.
struct TypingMachine {
    enum Effect: Equatable { case armTap, disarmTap, activateMinecraft, sendExit(String) }
    static let escape: UInt16 = 53

    private(set) var active = false
    /// The Esc that ended typing is still held: swallow its repeats and its release.
    private(set) var swallowingEscape = false
    /// The app being typed into (the one the click brought forward; nil = the first app activated after the click).
    private(set) var target: pid_t?
    /// Work mode (spec 5.10 round 2): the player uses the Mac with the real mouse, so other apps coming forward is
    /// normal; only Esc and Minecraft coming back end it.
    private(set) var work = false

    mutating func enter(target: pid_t? = nil, work: Bool = false) -> [Effect] {
        guard !active else { return [] }
        active = true
        self.target = target
        self.work = work
        return swallowingEscape ? [] : [.armTap]
    }

    /// Some app became the active one while typing. Minecraft: back to the game. The app typed into (or, when it wasn't
    /// known, the first one): nothing. Any other app (the player Cmd-Tabbed or clicked elsewhere, or a password field's
    /// secure input hid Esc and they switched away): typing is over, without activating Minecraft — the player chose
    /// that app — so the mod drops Minecraft's floating / click-through ("elsewhere").
    mutating func appActivated(pid: pid_t, isMinecraft: Bool) -> [Effect] {
        guard active else { return [] }
        if isMinecraft { return minecraftActivated() }
        if work { return [] } // working on the Mac: clicking into another app is just using it
        guard let target else { self.target = pid; return [] }
        if pid == target { return [] }
        active = false
        self.target = nil
        return swallowingEscape ? [.sendExit("elsewhere")] : [.disarmTap, .sendExit("elsewhere")]
    }

    /// Every key event the tap sees. `swallow` = drop it.
    mutating func key(code: UInt16, down: Bool, modifiers: KeyModifiers) -> (swallow: Bool, effects: [Effect]) {
        guard code == Self.escape else { return (false, []) }
        if swallowingEscape {
            if !down {
                swallowingEscape = false
                return (true, active ? [] : [.disarmTap])
            }
            return (true, [])
        }
        guard active, down, modifiers.isEmpty else { return (false, []) }
        active = false
        swallowingEscape = true
        return (true, [.activateMinecraft, .sendExit("esc")])
    }

    /// Minecraft is the active app again (the player clicked its window, Cmd-Tab, the Dock).
    mutating func minecraftActivated() -> [Effect] {
        guard active else { return [] }
        active = false
        target = nil
        return swallowingEscape ? [.sendExit("minecraft")] : [.disarmTap, .sendExit("minecraft")]
    }

    /// EXIT_TYPING from the mod (it noticed first, or left the world): no message back.
    mutating func exit() -> [Effect] {
        guard active else { return [] }
        active = false
        target = nil
        return swallowingEscape ? [] : [.disarmTap]
    }
}

/// Where a pointer message lands (spec 5.10): slot 1-4 = that virtual display, (x, y) one of its pixels; slot 0 = the
/// main display (a Mirror hologram), (x, y) a pixel of a w × h picture of it, mapped by proportion onto its bounds in
/// points (so a Retina display, whose points are half its pixels, and any capture size both work). Pure.
enum PointerTarget {
    static let mainSlot = 0

    enum Failure: Error, Equatable { case noDisplay, noPictureSize }

    static func globalPoint(slot: Int, x: Int, y: Int, w: Int?, h: Int?, main: CGRect,
                            display: (Int) -> (bounds: CGRect, width: Int, height: Int)?) -> Result<CGPoint, Failure> {
        if slot == mainSlot {
            guard let w, let h, w > 0, h > 0 else { return .failure(.noPictureSize) }
            guard !main.isEmpty else { return .failure(.noDisplay) }
            return .success(PointerMapping.globalPoint(x: x, y: y, width: w, height: h, bounds: main))
        }
        guard let d = display(slot) else { return .failure(.noDisplay) }
        return .success(PointerMapping.globalPoint(x: x, y: y, width: d.width, height: d.height, bounds: d.bounds))
    }
}

/// Secure input (a password field) hides keys from event taps, so the Esc that ends typing never arrives. While typing,
/// the helper samples `IsSecureEventInputEnabled()` and tells the mod each change (SECURE_INPUT), so it can say
/// "press ⌘-Tab to return". Pure: `sample` returns the value to report, or nil when nothing changed.
struct SecureInputWatch: Equatable {
    private(set) var reported = false

    mutating func sample(_ on: Bool) -> Bool? {
        guard on != reported else { return nil }
        reported = on
        return on
    }

    /// Typing ended: the next session starts from "off" (the mod forgets it when typing ends).
    mutating func reset() { reported = false }
}

/// Work mode: where the real cursor is, for the hologram that shows that display (pure). Slot 0 = the main display (the
/// Mirror), in its points; slots 1-4 = virtual displays, in their pixels (what the hologram's picture is made of), so the
/// drawn cursor lands on the pixel the click goes to. Off all of them (another physical display): slot -1.
enum CursorLocator {
    static func locate(_ p: CGPoint, main: CGRect, displays: [(slot: Int, bounds: CGRect, width: Int, height: Int)]) -> CursorPayload {
        if main.contains(p) {
            return CursorPayload(slot: PointerTarget.mainSlot, x: Int((p.x - main.minX).rounded(.down)), y: Int((p.y - main.minY).rounded(.down)),
                                 w: Int(main.width), h: Int(main.height))
        }
        for d in displays where d.bounds.contains(p) {
            let x = Int(((p.x - d.bounds.minX) * Double(d.width) / d.bounds.width).rounded(.down))
            let y = Int(((p.y - d.bounds.minY) * Double(d.height) / d.bounds.height).rounded(.down))
            return CursorPayload(slot: d.slot, x: min(x, d.width - 1), y: min(y, d.height - 1), w: d.width, h: d.height)
        }
        return CursorPayload(slot: -1, x: 0, y: 0, w: 0, h: 0)
    }
}

/// Walk while dragging (round 3, Carter's ruling; pure). In work mode, while the real mouse button is held (a drag), the
/// movement keys — W A S D, Space, Shift — move the player in Minecraft and are NOT delivered to the app; with the button
/// up they go to the app as usual (typing is untouched). A key is decided when it goes down: one taken from the app stays
/// taken until its key-up (its repeats and its up never reach the app, which never saw it go down), and one already
/// down in the app stays the app's. Releasing the button stops all movement at once, even with a key still held.
/// The keys are physical positions (ANSI key codes): AZERTY's Z Q S D, and Minecraft's own key bindings don't matter.
/// Missed events can't leave anything stuck: `resync` sets the state from the hardware (on a re-enabled tap and 30 times
/// a second), Shift is read from the device flag bits, and a password field (secure input) suspends walking.
struct WalkKeys {
    enum Move: String, CaseIterable, Comparable {
        case forward, back, left, right, jump, sneak
        static func < (a: Move, b: Move) -> Bool { a.rawValue < b.rawValue }
    }

    /// The movement key for a virtual key code (ANSI positions: W A S D, Space, left / right Shift), else nil.
    static func move(forKeyCode code: UInt16) -> Move? {
        switch code {
        case 13: return .forward
        case 1: return .back
        case 0: return .left
        case 2: return .right
        case 49: return .jump
        case 56, 60: return .sneak
        default: return nil
        }
    }

    static let shiftCodes: Set<UInt16> = [56, 60]
    /// Device-dependent flag bits of left / right Shift in an event's flags (NX_DEVICELSHIFTKEYMASK / ...RSHIFT...).
    static let leftShiftBit: UInt64 = 0x2
    static let rightShiftBit: UInt64 = 0x4

    private(set) var buttonHeld = false
    /// A password field has the keyboard (secure input): no walking, the keys are the field's.
    private(set) var suspended = false
    /// Keys whose down the app never saw: Minecraft's until their up.
    private(set) var taken: [UInt16: Move] = [:]
    /// Movement keys down in the app.
    private var appDown: Set<UInt16> = []

    /// What Minecraft should be doing now.
    var moves: Set<Move> { buttonHeld && !suspended ? Set(taken.values) : [] }

    /// A movement key (or Shift) went down / up / repeated. `swallow`: keep it from the app. `moves`: the new set when
    /// it changed (send it), else nil.
    mutating func key(code: UInt16, down: Bool) -> (swallow: Bool, moves: Set<Move>?) {
        guard let move = Self.move(forKeyCode: code) else { return (false, nil) }
        let before = moves
        if down {
            if taken[code] != nil { return (true, nil) } // a repeat of a taken key
            if appDown.contains(code) { return (false, nil) } // the app's (down before the button)
            if buttonHeld && !suspended {
                taken[code] = move
                return (true, moves != before ? moves : nil)
            }
            appDown.insert(code)
            return (false, nil)
        }
        if taken.removeValue(forKey: code) != nil { return (true, moves != before ? moves : nil) }
        appDown.remove(code)
        return (false, nil)
    }

    /// Shift has no key-down / key-up of its own (flagsChanged): whether left (56) or right (60) Shift is down comes from
    /// the event's device flag bits, never from toggling (a missed change can't invert it).
    mutating func modifier(code: UInt16, flags: UInt64) -> (swallow: Bool, moves: Set<Move>?) {
        guard Self.shiftCodes.contains(code) else { return (false, nil) }
        let bit = code == 56 ? Self.leftShiftBit : Self.rightShiftBit
        return key(code: code, down: flags & bit != 0)
    }

    /// Shift is taken (held for Minecraft): events the app still gets must not carry it.
    var shiftTaken: Bool { taken.keys.contains { Self.shiftCodes.contains($0) } }

    /// The physical mouse button went down / up. Returns the new moves when they changed.
    mutating func button(held: Bool) -> Set<Move>? {
        let before = moves
        buttonHeld = held
        return moves != before ? moves : nil
    }

    /// From the hardware (a re-enabled tap, the 30 Hz tick): the button state, and every key not physically down any more
    /// is forgotten (an up that was missed). Returns the new moves when they changed.
    mutating func resync(buttonHeld held: Bool, isKeyDown: (UInt16) -> Bool) -> Set<Move>? {
        let before = moves
        buttonHeld = held
        taken = taken.filter { isKeyDown($0.key) }
        appDown = appDown.filter(isKeyDown)
        return moves != before ? moves : nil
    }

    /// Secure input on (a password field: its keys must reach it) or off. Returns the new moves when they changed.
    mutating func setSuspended(_ on: Bool) -> Set<Move>? {
        let before = moves
        suspended = on
        return moves != before ? moves : nil
    }

    /// Work mode ended: nothing moves, nothing is taken any more. Returns the new (empty) moves when they changed.
    mutating func reset() -> Set<Move>? {
        let before = moves
        taken = [:]
        appDown = []
        buttonHeld = false
        suspended = false
        return before.isEmpty ? nil : []
    }

    static func names(_ moves: Set<Move>) -> [String] { moves.sorted().map(\.rawValue) }
}

/// Sending a stream of state (the cursor, the walk keys) from an event tap: only the newest one per key waits to be
/// written, so a writer backed up by a big frame never makes the tap wait or a queue grow (pure).
struct LatestSlots {
    private var latest: [String: Data] = [:]
    private var queued: Set<String> = []

    /// Stores `data` as the newest for `key`. True = no write is queued for `key` yet: queue one.
    mutating func put(_ key: String, _ data: Data) -> Bool {
        latest[key] = data
        return queued.insert(key).inserted
    }

    /// The queued write runs: the newest data for `key` (older ones were dropped).
    mutating func take(_ key: String) -> Data? {
        queued.remove(key)
        return latest.removeValue(forKey: key)
    }
}

/// Where the other monitors were just before Office displays were created (pure). macOS shoves a monitor aside to fit a
/// new display in; the arranges that follow put it back where it was. The mod sends every CREATE_DISPLAY at once on each
/// (re)connect, so the record stays until the arrange of the last create still pending has run — each new display may
/// shove the monitor again — and is used by no later arrange. So a monitor the user moves while Office displays are up
/// stays where they put it. The record carries its time: older than `pendingLimit` it is ignored (a create whose arrange
/// never came). Any create that ends without its arrange (failed, timed out, nothing to create) clears it unless another
/// create is still pending.
struct MonitorHomes {
    private(set) var homes: [UInt32: CGPoint] = [:]
    private(set) var recordedAt: Double?
    /// Slots created whose arrange is still to come.
    private(set) var pending: Set<Int> = []
    static let pendingLimit = 5.0

    private func fresh(at t: Double) -> Bool { recordedAt.map { t - $0 < Self.pendingLimit } ?? false }

    private mutating func clear() {
        homes = [:]
        recordedAt = nil
        pending = []
    }

    /// Just before creating display `slot` at time `t` (seconds): the monitors' positions now. Overwrites the record,
    /// unless an earlier create is still pending (and its record fresh): then the monitor may be shoved already, and the
    /// earlier record stands.
    mutating func beforeCreate(slot: Int, _ now: [UInt32: CGPoint], at t: Double) {
        if pending.isEmpty || !fresh(at: t) {
            homes = now
            recordedAt = t
            pending = []
        }
        pending.insert(slot)
    }

    /// A create of `slot` ended without its arrange: it failed, its display never came online, it was removed meanwhile,
    /// or there was nothing to create (identical spec). Cleared unless another create is still pending.
    mutating func createEnded(slot: Int) {
        pending.remove(slot)
        if pending.isEmpty { clear() }
    }

    /// For arrange at time `t` (after the create of `slot`, or nil after a remove): each monitor's rect to arrange around
    /// (where it was before the creates, else where it is) and the monitors to put back. A stale record is ignored. The
    /// record goes once no create is pending.
    mutating func forArrange(_ now: [UInt32: CGRect], at t: Double, after slot: Int?) -> (rects: [CGRect], restores: [(id: UInt32, to: CGPoint)]) {
        let use = fresh(at: t) ? homes : [:]
        var rects: [CGRect] = []
        var restores: [(id: UInt32, to: CGPoint)] = []
        for id in now.keys.sorted() {
            let r = now[id]!
            if let home = use[id], home != r.origin {
                rects.append(CGRect(origin: home, size: r.size))
                restores.append((id, home))
            } else {
                rects.append(r)
            }
        }
        if let slot { pending.remove(slot) }
        if pending.isEmpty || !fresh(at: t) { clear() }
        return (rects, restores)
    }

    /// A CREATE_DISPLAY for a display that already exists as asked (nothing created, no shove): the record goes unless
    /// another create is pending (a pending create of this same slot keeps its own record too).
    mutating func nothingCreated() {
        if pending.isEmpty { clear() }
    }

    /// No Office display left (removed, ended by the window server): nothing is to be put back — unless a create is
    /// still pending (its arrange still needs the record).
    mutating func noneLeft() {
        if pending.isEmpty { clear() }
    }

    /// After a remove: macOS re-applies the layout it remembers for the remaining set of displays, which can carry a
    /// monitor back to where it was long ago. The monitors (still online) that moved since `before` — the positions
    /// just before the remove — and where they go back to.
    static func movedSince(_ before: [UInt32: CGPoint], now: [UInt32: CGPoint]) -> [(id: UInt32, to: CGPoint)] {
        before.keys.sorted().compactMap { id in
            guard let was = before[id], let is_ = now[id], is_ != was else { return nil }
            return (id, was)
        }
    }
}

/// Where the Office displays sit in the display arrangement (pure): in slot order in a row along the main display's top
/// edge, starting at its right edge — so moving the cursor off the Mirror's right edge carries it onto display 1, like a
/// laptop and its monitors. If another display (a real monitor) already touches the main display's right edge, the row
/// goes to its left instead: next to the main display's left edge, or, when a monitor sits there too, past the
/// leftmost display in that band (so nothing overlaps).
enum DisplayArrangement {
    static func origins(main: CGRect, others: [CGRect], ours: [(slot: Int, size: CGSize)]) -> [Int: CGPoint] {
        let sorted = ours.sorted { $0.slot < $1.slot }
        let totalWidth = sorted.reduce(0) { $0 + $1.size.width }
        let height = sorted.map(\.size.height).max() ?? 0
        let rightBand = CGRect(x: main.maxX, y: main.minY, width: max(totalWidth, 1), height: max(height, 1))
        let rightFree = !others.contains { $0.intersects(rightBand) || ($0.minX == main.maxX && $0.maxY > main.minY && $0.minY < main.maxY) }
        var out: [Int: CGPoint] = [:]
        if rightFree {
            var x = main.maxX
            for d in sorted { out[d.slot] = CGPoint(x: x, y: main.minY); x += d.size.width }
        } else {
            let leftBand = CGRect(x: main.minX - max(totalWidth, 1), y: main.minY, width: max(totalWidth, 1), height: max(height, 1))
            let inTheWay = others.filter { $0.intersects(leftBand) || ($0.maxX == main.minX && $0.maxY > main.minY && $0.minY < main.maxY) }
            var x = min(main.minX, inTheWay.map(\.minX).min() ?? main.minX)
            for d in sorted { x -= d.size.width; out[d.slot] = CGPoint(x: x, y: main.minY) }
        }
        return out
    }
}
