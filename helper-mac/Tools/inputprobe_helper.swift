// inputprobe --helper <OfficeHelper.app>: the same checks end to end through the REAL signed helper and its socket
// protocol, with the probe standing in for both Minecraft (frontmost, holds the "physical" button, gets typing back;
// for the Mirror part a borderless floating window over the whole main display that is click-through while used)
// and the mod (HELLO with the probe's pid as mcPid, CREATE_DISPLAY, LIST_APPS / APP_ICONS / OPEN_APP, CLICK,
// POINTER_DOWN/MOVE with points mapped from DRAG_DELTA the way the mod maps the turned head, SCROLL; slot 0 = the
// Mirror, with the mod's click-through toggling played by the probe). Refuses to run while
// another OfficeHelper is running (Carter's game).

import AppKit
import ApplicationServices

/// Minimal socket client for the helper protocol (Protocol.swift).
final class HelperSocket: @unchecked Sendable {
    let fd: Int32
    var onMessage: (@MainActor (Message) -> Void)?

    init?(path: String) {
        fd = socket(AF_UNIX, SOCK_STREAM, 0)
        guard fd >= 0 else { return nil }
        var addr = sockaddr_un()
        addr.sun_family = sa_family_t(AF_UNIX)
        let bytes = Array(path.utf8)
        guard bytes.count < MemoryLayout.size(ofValue: addr.sun_path) else { Darwin.close(fd); return nil }
        withUnsafeMutableBytes(of: &addr.sun_path) { raw in
            for (i, b) in bytes.enumerated() { raw[i] = b }
            raw[bytes.count] = 0
        }
        let ok = withUnsafePointer(to: &addr) {
            $0.withMemoryRebound(to: sockaddr.self, capacity: 1) { connect(fd, $0, socklen_t(MemoryLayout<sockaddr_un>.size)) }
        }
        guard ok == 0 else { Darwin.close(fd); return nil }
    }

    func send(_ message: Message) {
        guard let data = try? message.encode() else { return }
        data.withUnsafeBytes { raw in
            var off = 0
            while off < raw.count {
                let n = write(fd, raw.baseAddress! + off, raw.count - off)
                if n <= 0 { return }
                off += n
            }
        }
    }

    func startReading() {
        let thread = Thread { [self] in
            var reader = FrameReader(maxLength: OfficeProtocol.maxFrameLength)
            var buf = [UInt8](repeating: 0, count: 1 << 16)
            while true {
                let n = read(fd, &buf, buf.count)
                if n <= 0 { return }
                reader.push(Data(buf[0..<n]))
                while let (type, payload) = (try? reader.next()) ?? nil {
                    guard let message = try? Message.decode(type: type, payload: payload) else { continue }
                    DispatchQueue.main.async { MainActor.assumeIsolated { self.onMessage?(message) } }
                }
            }
        }
        thread.start()
    }

    func close() { Darwin.close(fd) }
}

extension Probe {
    func runHelper(app: String) async {
        if !NSRunningApplication.runningApplications(withBundleIdentifier: "local.carter.office-helper").isEmpty {
            check("no other OfficeHelper is running (Carter's game?) — not touching it", false)
            return
        }
        await activateProbe()
        let dir = FileManager.default.homeDirectoryForCurrentUser.appendingPathComponent("Library/Application Support/Minecraft Office")
        let sockPath = dir.appendingPathComponent("helper.sock").path
        let tokenURL = dir.appendingPathComponent("helper.token")
        let launchedAt = Date()
        let open = Process()
        open.executableURL = URL(fileURLWithPath: "/usr/bin/open")
        open.arguments = ["-g", app]
        try? open.run(); open.waitUntilExit()
        var client: HelperSocket?
        for _ in 0..<100 {
            await sleep(0.1)
            let fresh = ((try? FileManager.default.attributesOfItem(atPath: tokenURL.path))?[.modificationDate] as? Date).map { $0 >= launchedAt.addingTimeInterval(-1) } ?? false
            if fresh, FileManager.default.fileExists(atPath: sockPath), let c = HelperSocket(path: sockPath) { client = c; break }
        }
        guard let client, let token = try? String(contentsOf: tokenURL, encoding: .utf8).trimmingCharacters(in: .whitespacesAndNewlines) else {
            check("helper launched and its socket accepts us", false); return
        }
        var inbox: [Message] = []
        client.onMessage = { inbox.append($0) }
        client.startReading()
        client.send(.hello(HelloPayload(token: token, version: OfficeProtocol.version, mcPid: getpid())))
        func waitFor(_ timeout: Double, _ match: (Message) -> Bool) async -> Message? {
            let end = Date().addingTimeInterval(timeout)
            while Date() < end {
                if let i = inbox.firstIndex(where: match) { return inbox.remove(at: i) }
                await sleep(0.01)
            }
            return nil
        }
        defer { client.close() }
        guard case .welcome(let w)? = await waitFor(5, { if case .welcome = $0 { return true }; return false }) else {
            check("WELCOME from the helper", false); return
        }
        check("E0 helper WELCOME: Accessibility granted to the signed helper", w.accessibility, "screen recording \(w.screenRecording)")
        helperPid = NSRunningApplication.runningApplications(withBundleIdentifier: "local.carter.office-helper").first?.processIdentifier

        // PROBE_MONITOR=1: a "real monitor" (a probe-owned virtual display) at the main display's right edge before the
        // Office displays exist — they must go to the left (W6), and the cursor on it is shown (W5).
        if ProcessInfo.processInfo.environment["PROBE_MONITOR"] != nil {
            let main = CGDisplayBounds(CGMainDisplayID())
            var placed = CGRect.zero
            if let id = makeProbeMonitor() {
                _ = await VirtualDisplays.waitOnline(id)
                placeDisplay(id, at: CGPoint(x: main.maxX, y: main.minY))
                await sleep(1.0)
                placed = CGDisplayBounds(id)
                if placed.minX == main.maxX { probeMonitorID = id }
            }
            check("W6 setup: a probe monitor sits at the main display's right edge", probeMonitorID != nil, "main \(main), monitor \(placed)")
        }
        var ids: [Int: CGDirectDisplayID] = [:]
        var sides: [Int: String] = [:]
        for slot in [1, 2] {
            client.send(.createDisplay(CreateDisplayPayload(slot: slot, w: 1920, h: 1080)))
            if case .display(let d)? = await waitFor(5, { if case .display(let d) = $0 { return d.slot == slot }; return false }) {
                ids[slot] = d.displayId
                sides[slot] = d.side
            }
        }
        guard let idA = ids[1], let idB = ids[2] else { check("E0 helper created displays 1 and 2", false); return }
        await sleep(1.0)
        let boundsA = CGDisplayBounds(idA), boundsB = CGDisplayBounds(idB)
        let mainBounds = CGDisplayBounds(CGMainDisplayID())
        check("E0 helper created displays 1 and 2", true, "1: \(boundsA), 2: \(boundsB), main \(mainBounds)")
        if probeMonitorID != nil {
            check("W6 with a monitor at the main display's right edge the Office displays go to its left, in slot order, and DISPLAY says \"left\"",
                  boundsA.maxX == mainBounds.minX && boundsB.maxX == boundsA.minX && boundsA.minY == mainBounds.minY
                    && sides[1] == "left" && sides[2] == "left",
                  "main \(mainBounds), 1: \(boundsA), 2: \(boundsB), sides \(sides)")
        } else {
            check("E0 DISPLAY says which side of the main display the display is on (right here)", sides[1] == "right" && sides[2] == "right",
                  "sides \(sides)")
        }
        /// The hologram's picture size the mod sends for a Mirror (slot 0): its 16:9 shape's pixels.
        let pictureW = 1920, pictureH = 1080
        /// A global point → (slot, pixel) the way the mod sends it: display pixels of a 1920×1080 Office display, or — with
        /// `mirror` — slot 0 + a pixel of the Mirror hologram's 1920×1080 picture of the main display.
        func px(_ p: CGPoint, mirror: Bool = false) -> PointerPayload? {
            for (slot, id) in ids {
                let b = CGDisplayBounds(id)
                if b.contains(p) {
                    return PointerPayload(slot: slot, x: Int((p.x - b.minX) * 1920 / b.width), y: Int((p.y - b.minY) * 1080 / b.height), button: nil)
                }
            }
            if mirror, mainBounds.contains(p) {
                return PointerPayload(slot: 0, x: Int((p.x - mainBounds.minX) * Double(pictureW) / mainBounds.width),
                                      y: Int((p.y - mainBounds.minY) * Double(pictureH) / mainBounds.height), button: nil, w: pictureW, h: pictureH)
            }
            return nil
        }
        func with(_ p: PointerPayload, _ b: PointerButton?) -> PointerPayload { PointerPayload(slot: p.slot, x: p.x, y: p.y, button: b, w: p.w, h: p.h) }

        // E11: the Dock's app list and icons.
        client.send(.listApps)
        if case .apps(let list)? = await waitFor(10, { if case .apps = $0 { return true }; return false }) {
            let bundles = Set(list.apps.map(\.bundle))
            check("E11 LIST_APPS lists the installed apps (TextEdit, Finder, Safari among them)",
                  bundles.isSuperset(of: ["com.apple.TextEdit", "com.apple.finder", "com.apple.Safari"]) && !bundles.contains("local.carter.office-helper"),
                  "\(list.apps.count) apps, e.g. \(list.apps.prefix(4).map(\.name))")
        } else { check("E11 LIST_APPS answered", false) }
        client.send(.appIcons(AppIconsPayload(bundles: ["com.apple.TextEdit", "com.example.not-installed"], size: 64)))
        if case .icons(let icons)? = await waitFor(5, { if case .icons = $0 { return true }; return false }) {
            let png = icons.icons.first.flatMap { Data(base64Encoded: $0.png) }
            let rep = png.flatMap { NSBitmapImageRep(data: $0) }
            check("E11 APP_ICONS: a 64×64 PNG for TextEdit, nothing for an app that isn't installed",
                  icons.icons.map(\.bundle) == ["com.apple.TextEdit"] && rep?.pixelsWide == 64 && rep?.pixelsHigh == 64,
                  "\(icons.icons.map(\.bundle)), \(png?.count ?? 0) bytes, \(rep?.pixelsWide ?? 0)×\(rep?.pixelsHigh ?? 0)")
        } else { check("E11 APP_ICONS answered", false) }
        client.send(.openApp(OpenAppPayload(slot: 1, bundle: "com.example.not-installed", url: nil)))
        let missing = await waitFor(3) { if case .appResult = $0 { return true }; return false }
        check("E11 OPEN_APP of an app that isn't installed says so", missing == .appResult(AppResultPayload(target: "com.example.not-installed",
              ok: false, message: "That app isn't installed")), missing.map { "\($0)" } ?? "no APP_RESULT")

        guard let opened = await openScratch() else { return }
        let (te, win) = opened
        await sleep(0.5)
        let homeOnMain = CGRect(x: mainBounds.minX + 120, y: mainBounds.minY + 90, width: 760, height: 520)
        axSetFrame(win, homeOnMain) // where a player's window would be: on the laptop screen
        await sleep(0.4)
        let before = axFrame(win) ?? .zero
        if ProcessInfo.processInfo.environment["PROBE_ONLY_WORK"] != nil { // just the work-mode checks (W0-W4)
            await runWork(client: client, inbox: { inbox }, clearInbox: { inbox.removeAll() }, waitFor: waitFor, px: px, with: with,
                          te: te, win: win, homeOnMain: homeOnMain, idA: idA, mainBounds: mainBounds)
            if let mid = probeMonitorID {
                // W7: the user moves the monitor while the Office displays are up (below the laptop screen); removing
                // the displays must leave it there — no "restore" to where it was when they were created.
                placeDisplay(mid, at: CGPoint(x: mainBounds.minX, y: mainBounds.maxY))
                await sleep(1.0)
                let placed = CGDisplayBounds(mid).origin
                // display 2 goes: the helper re-arranges (display 1 stays) and must not put the monitor "back"
                client.send(.removeDisplay(SlotPayload(slot: 2)))
                await sleep(1.5)
                let afterOne = CGDisplayBounds(mid).origin
                check("W7 a monitor moved while the Office displays are up stays where the user put it when one is removed",
                      afterOne == placed && placed.y == mainBounds.maxY,
                      "moved to \(placed), after removing display 2: \(afterOne)")
                client.send(.removeDisplay(SlotPayload(slot: 1)))
                await sleep(1.5)
                let afterAll = CGDisplayBounds(mid).origin
                check("W7 …and when the last one is removed (macOS's remembered layout would carry it back; the helper keeps it put)",
                      afterAll == placed, "after removing display 1 too: \(afterAll)")

                // W8: the mod sends every CREATE_DISPLAY at once on each (re)connect. With the monitor at the laptop
                // screen's right edge, three back-to-back creates: each new display may shove it; it must end where the
                // user had it, the Office displays to the left in slot order.
                await sleep(3.5) // the post-remove window is over
                placeDisplay(mid, at: CGPoint(x: mainBounds.maxX, y: mainBounds.minY))
                await sleep(1.0)
                let userPlace = CGDisplayBounds(mid).origin
                inbox.removeAll()
                // as the mod sends them; a small gap (PROBE_CREATE_GAP seconds, default none) lets a display come online
                // between two creates, which is when a shove can slip between a create and its arrange
                let gap = Double(ProcessInfo.processInfo.environment["PROBE_CREATE_GAP"] ?? "") ?? 0
                for slot in 1...3 {
                    client.send(.createDisplay(CreateDisplayPayload(slot: slot, w: 1920, h: 1080)))
                    if gap > 0 { await sleep(gap) }
                }
                var shown: [Int: CGDirectDisplayID] = [:]
                for _ in 0..<80 where shown.count < 3 {
                    await sleep(0.1)
                    for m in inbox { if case .display(let d) = m { shown[d.slot] = d.displayId } }
                }
                await sleep(1.5)
                let monitorNow = CGDisplayBounds(mid).origin
                let b = (1...3).map { shown[$0].map { CGDisplayBounds($0) } ?? .null }
                check("W8 three back-to-back creates: the monitor ends where the user had it, displays 1-3 left of the laptop screen in slot order",
                      userPlace == CGPoint(x: mainBounds.maxX, y: mainBounds.minY) && monitorNow == userPlace && shown.count == 3
                        && b[0].maxX == mainBounds.minX && b[1].maxX == b[0].minX && b[2].maxX == b[1].minX,
                      "monitor \(userPlace) → \(monitorNow); 1: \(b[0]), 2: \(b[1]), 3: \(b[2])")
                for slot in [3, 2, 1] {
                    client.send(.removeDisplay(SlotPayload(slot: slot)))
                    await sleep(0.8)
                }
                await sleep(1.5)
                check("W8 …and it stays there when they are removed again", CGDisplayBounds(mid).origin == userPlace,
                      "monitor now \(CGDisplayBounds(mid).origin)")
            }
            return
        }

        // E1: the Dock's OPEN_APP brings TextEdit's front window onto display 1 (nothing moves by itself any more).
        await activateProbe()
        inbox.removeAll()
        client.send(.openApp(OpenAppPayload(slot: 1, bundle: "com.apple.TextEdit", url: nil)))
        let result = await waitFor(5) { if case .appResult = $0 { return true }; return false }
        await sleep(0.4)
        let moved = axFrame(win) ?? .zero
        check("E1 OPEN_APP (Dock click) moves TextEdit's front window onto display 1, filling it; Minecraft keeps the front",
              boundsA.contains(CGPoint(x: moved.midX, y: moved.midY)) && moved.width > 1500 && probeFrontmost
                && { if case .appResult(let r)? = result { return r.ok }; return false }(),
              "\(before) → \(moved), \(result.map { "\($0)" } ?? "no APP_RESULT"), frontmost \(NSWorkspace.shared.frontmostApplication?.localizedName ?? "?")")
        guard let scrollArea = axFind(win, role: kAXScrollAreaRole), let text = axFind(scrollArea, role: kAXTextAreaRole),
              let scrollFrame = axFrame(scrollArea) else { check("found TextEdit's text area", false); return }
        let textPoint = CGPoint(x: scrollFrame.minX + 60, y: scrollFrame.minY + 40)
        guard let textPx = px(textPoint) else { check("text point is on an Office display", false); return }

        // Typing mode: CLICK, keys, Esc.
        relativeMode(true)
        axSetRange(text, CFRange(location: 0, length: 0))
        await activateProbe()
        inbox.removeAll()
        client.send(.click(with(textPx, .left)))
        await sleep(0.6)
        let e2caret = axRange(text)?.location ?? 0
        check("E2 CLICK: the app comes forward with the keyboard (typing mode), the caret where the click was",
              NSWorkspace.shared.frontmostApplication?.processIdentifier == te.processIdentifier && e2caret > 0,
              "frontmost \(NSWorkspace.shared.frontmostApplication?.localizedName ?? "?"), caret \(e2caret)")
        let textBefore = (axAttr(text, kAXValueAttribute) as? String) ?? ""
        for code: CGKeyCode in [0, 11] { key(code); await sleep(0.05) } // a b
        key(53)
        let exit = await waitFor(2) { if case .typingExit = $0 { return true }; return false }
        await sleep(0.4)
        let textAfter = (axAttr(text, kAXValueAttribute) as? String) ?? ""
        let chars = Array(textAfter)
        let typedHere = textAfter.count == textBefore.count + 2 && e2caret + 2 <= chars.count && String(chars[e2caret..<(e2caret + 2)]) == "ab"
        check("E2 keys typed into the app at the caret", typedHere, "at \(e2caret): \"\(e2caret + 2 <= chars.count ? String(chars[e2caret..<(e2caret + 2)]) : "?")\"")
        check("E2 Esc → TYPING_EXIT esc, Minecraft frontmost again", exit == .typingExit(ReasonPayload(reason: "esc")) && probeFrontmost,
              "\(exit.map { "\($0)" } ?? "no TYPING_EXIT"), frontmost \(NSWorkspace.shared.frontmostApplication?.localizedName ?? "?")")
        if typedHere { AXUIElementSetAttributeValue(text, kAXValueAttribute as CFString, textBefore as CFString) }

        // A click, then clicking back into "Minecraft" ends typing too.
        client.send(.click(with(textPx, .left)))
        await sleep(0.5)
        await activateProbe()
        let back = await waitFor(2) { if case .typingExit = $0 { return true }; return false }
        check("E3 Minecraft activated while typing → TYPING_EXIT minecraft", back == .typingExit(ReasonPayload(reason: "minecraft")),
              back.map { "\($0)" } ?? "none")

        /// A head-turn drag through the helper: the probe holds the button, the helper takes the mouse, the probe maps
        /// each DRAG_DELTA to the next point and sends POINTER_MOVE. `pressed` runs once the probe has its button down
        /// (the mod turning on click-through for a Mirror drag), `ended` after DRAG_ENDED (the mod restoring it).
        func headDrag(from start: CGPoint, to end: CGPoint, steps: Int, mirror: Bool = false,
                      pressed: () -> Void = {}, ended: () -> Void = {}) async -> (ended: Message?, front: Bool, deltas: Int, probeDrags: Int) {
            relativeMode(true)
            await activateProbe()
            inbox.removeAll()
            counts = [:]
            physical(.leftMouseDown)
            await sleep(0.1)
            guard let startPx = px(start, mirror: mirror) else { return (nil, false, 0, 0) }
            pressed()
            client.send(.pointerDown(with(startPx, .left)))
            await sleep(0.05)
            let sx = Int64(((end.x - start.x) / Double(steps)).rounded()), sy = Int64(((end.y - start.y) / Double(steps)).rounded())
            var acc = CGPoint.zero
            var deltas = 0
            for _ in 1...steps {
                physical(.leftMouseDragged, dx: sx, dy: sy)
                await sleep(0.02)
                while let i = inbox.firstIndex(where: { if case .dragDelta = $0 { return true }; return false }) {
                    if case .dragDelta(let d) = inbox.remove(at: i) { acc.x += Double(d.dx); acc.y += Double(d.dy); deltas += 1 }
                }
                if let p = px(CGPoint(x: start.x + acc.x, y: start.y + acc.y), mirror: mirror) { client.send(.pointerMove(p)) } // off-display: keep the last point
            }
            await sleep(0.05)
            physical(.leftMouseUp)
            let done = await waitFor(2) { if case .dragEnded = $0 { return true }; return false }
            ended()
            await sleep(0.4)
            return (done, probeFrontmost, deltas, counts[.leftMouseDragged] ?? 0)
        }

        axSetRange(text, CFRange(location: 0, length: 0))
        let d1 = await headDrag(from: textPoint, to: CGPoint(x: textPoint.x + 360, y: textPoint.y + 80), steps: 20)
        let sel = axRange(text)
        check("E4 head-turn drag selects text in the app", (sel?.length ?? 0) > 20, "selection \(sel.map { "\($0.location)+\($0.length)" } ?? "?")")
        check("E4 helper owned the physical mouse, sent DRAG_DELTA, ended on release, Minecraft frontmost again",
              d1.ended == .dragEnded(ReasonPayload(reason: "released")) && d1.front && d1.deltas > 5 && d1.probeDrags == 0,
              "ended \(d1.ended.map { "\($0)" } ?? "none"), deltas \(d1.deltas), Minecraft saw \(d1.probeDrags) drags, frontmost \(d1.front)")

        let frame = axFrame(win) ?? .zero
        let title = CGPoint(x: frame.minX + frame.width * 0.75, y: frame.minY + 12)
        let d2 = await headDrag(from: title, to: CGPoint(x: boundsB.minX + 700, y: boundsB.minY + 100), steps: 40)
        let after = axFrame(win) ?? .zero
        let grab = CGPoint(x: after.minX + (title.x - frame.minX), y: after.minY + (title.y - frame.minY))
        check("E5 head-turn drag carries the window from hologram 1's display onto hologram 2's",
              boundsB.contains(grab) && d2.ended == .dragEnded(ReasonPayload(reason: "released")) && d2.front,
              "window \(frame.origin) → \(after.origin), ended \(d2.ended.map { "\($0)" } ?? "none")")

        // Scroll on display 2 (the window wholly on it: one that straddles two displays is drawn on one of them only).
        axSetFrame(win, CGRect(x: boundsB.minX + 100, y: boundsB.minY + 100, width: 900, height: 600))
        await sleep(0.4)
        if let sa = axFind(win, role: kAXScrollAreaRole), let saFrame = axFrame(sa), let p = px(CGPoint(x: saFrame.midX, y: saFrame.midY)) {
            await activateProbe()
            let s0 = axScrollValue(sa) ?? 0
            client.send(.scroll(ScrollPayload(slot: p.slot, x: p.x, y: p.y, dx: 0, dy: -48))) // the mod's one wheel notch
            await sleep(0.5)
            let s1 = axScrollValue(sa) ?? 0
            // 400 lines ≈ 5250 pt of text: one notch (48 px) is about 1 %; lines instead of pixels would be ~50 %+
            check("E6 SCROLL (one notch = 48 px) scrolls the app a little, Minecraft stays frontmost",
                  s1 > s0 + 0.001 && s1 - s0 < 0.10 && probeFrontmost,
                  String(format: "%.4f → %.4f, frontmost %@", s0, s1, NSWorkspace.shared.frontmostApplication?.localizedName ?? "?"))
        } else {
            check("E6 SCROLL target found", false)
        }

        // Released before the helper could take the mouse: still one complete gesture, and it ends.
        await activateProbe()
        inbox.removeAll()
        physical(.leftMouseDown)
        await sleep(0.05)
        physical(.leftMouseUp)
        if let p = px(textPoint) { client.send(.pointerDown(with(p, .left))) }
        let quick = await waitFor(2) { if case .dragEnded = $0 { return true }; return false }
        await sleep(0.3)
        check("E7 a press already released when POINTER_DOWN arrives still ends (DRAG_ENDED), Minecraft frontmost",
              quick == .dragEnded(ReasonPayload(reason: "released")) && probeFrontmost, quick.map { "\($0)" } ?? "none")

        // A slow trackpad drag: 10 moves of 0.4 pt each (integer deltas 0) still turn the head by about 4 pt.
        await activateProbe()
        inbox.removeAll()
        physical(.leftMouseDown)
        await sleep(0.1)
        if let p = px(textPoint) { client.send(.pointerDown(with(p, .left))) }
        await sleep(0.1)
        for _ in 0..<10 { physicalFine(.leftMouseDragged, dx: 0.4, dy: 0); await sleep(0.02) }
        await sleep(0.1)
        physical(.leftMouseUp)
        _ = await waitFor(2) { if case .dragEnded = $0 { return true }; return false }
        let fine = inbox.reduce(0) { sum, m in if case .dragDelta(let d) = m { return sum + d.dx }; return sum }
        // Events posted by a process lose the fraction (macOS rounds a posted 0.4 to 0; real trackpad events keep it), so
        // this can only be informational here; DeltaAccumulator's unit test covers the summing.
        check("E10 [informational] posted fractional motion reaches the mod", fine >= 3 && fine <= 4, "Σdx \(fine) of 4.0")
        await sleep(0.3)

        await runMirror(client: client, inbox: { inbox }, clearInbox: { inbox.removeAll() }, waitFor: waitFor, headDrag: headDrag,
                        px: px, with: with, te: te, win: win, homeOnMain: homeOnMain, boundsB: boundsB, mainBounds: mainBounds)
        await runWork(client: client, inbox: { inbox }, clearInbox: { inbox.removeAll() }, waitFor: waitFor, px: px, with: with,
                      te: te, win: win, homeOnMain: homeOnMain, idA: idA, mainBounds: mainBounds)

        // Leaving: the helper removes its displays and quits; no window was moved on join, so none is moved back (a
        // window on a removed display goes to the main display by macOS itself).
        axSetFrame(win, CGRect(x: boundsB.minX + 100, y: boundsB.minY + 100, width: 700, height: 500))
        await sleep(0.4)
        client.close()
        for _ in 0..<50 where helperRunning { await sleep(0.1) }
        await sleep(0.8)
        let afterQuit = axFrame(win) ?? .zero
        check("E9 disconnect: helper removes its displays and quits; the window on a removed display lands on the main display",
              !helperRunning && !VirtualDisplays.isOnline(idA) && !VirtualDisplays.isOnline(idB)
                && mainBounds.insetBy(dx: -2, dy: -2).contains(CGPoint(x: afterQuit.midX, y: afterQuit.midY)),
              "helper running \(helperRunning), window now \(afterQuit)")
        relativeMode(false)
    }

    // MARK: Mirror (spec 5.10): an interactive view of the main display, with Minecraft on top of it

    /// The probe's window plays Minecraft's on the laptop screen: borderless, covering the whole main display, above
    /// normal windows (floating level, as the mod sets with SDL_SetWindowAlwaysOnTop) — TextEdit sits underneath it.
    /// During a Mirror interaction it is click-through (`ignoresMouseEvents`, as the mod sets on Minecraft's NSWindow),
    /// so the helper's events reach TextEdit; it is restored after.
    func standIn(_ on: Bool) {
        if on {
            let screen = NSScreen.screens.first?.frame ?? .zero
            window.styleMask = [.borderless]
            window.level = .floating
            window.backgroundColor = NSColor(calibratedRed: 0.32, green: 0.55, blue: 0.29, alpha: 1) // grass: "Minecraft"
            window.setFrame(screen, display: true)
            window.ignoresMouseEvents = false
            window.orderFrontRegardless()
        } else {
            window.ignoresMouseEvents = false
            window.level = .normal
            window.styleMask = [.titled]
            let main = CGDisplayBounds(CGMainDisplayID())
            window.setFrame(NSRect(x: 80, y: main.height - 80 - 360, width: 520, height: 360), display: true)
        }
    }

    func clickThrough(_ on: Bool) { window.ignoresMouseEvents = on }

    /// Front-to-back index of a window in the on-screen list (lower = nearer the front), and its layer.
    func stacking(_ number: Int) -> (index: Int, layer: Int)? {
        let rows = CGWindowListCopyWindowInfo([.optionOnScreenOnly], kCGNullWindowID) as? [[String: Any]] ?? []
        for (i, row) in rows.enumerated() where (row[kCGWindowNumber as String] as? NSNumber)?.intValue == number {
            return (i, (row[kCGWindowLayer as String] as? NSNumber)?.intValue ?? -1)
        }
        return nil
    }

    func textEditWindowNumber(_ te: NSRunningApplication) -> Int? {
        let rows = CGWindowListCopyWindowInfo([.optionOnScreenOnly], kCGNullWindowID) as? [[String: Any]] ?? []
        return rows.first { ($0[kCGWindowOwnerPID as String] as? NSNumber)?.int32Value == te.processIdentifier
            && ($0[kCGWindowLayer as String] as? NSNumber)?.intValue == 0
            && (($0[kCGWindowName as String] as? String)?.contains("inputprobe") ?? true) }
            .flatMap { ($0[kCGWindowNumber as String] as? NSNumber)?.intValue }
    }

    func runMirror(client: HelperSocket, inbox: () -> [Message], clearInbox: () -> Void, waitFor: (Double, (Message) -> Bool) async -> Message?,
                   headDrag: (CGPoint, CGPoint, Int, Bool, () -> Void, () -> Void) async -> (ended: Message?, front: Bool, deltas: Int, probeDrags: Int),
                   px: (CGPoint, Bool) -> PointerPayload?, with: (PointerPayload, PointerButton?) -> PointerPayload,
                   te: NSRunningApplication, win: AXUIElement, homeOnMain: CGRect, boundsB: CGRect, mainBounds: CGRect) async {
        axSetFrame(win, homeOnMain) // back on the laptop screen, under "Minecraft"
        await sleep(0.4)
        standIn(true)
        await activateProbe()
        await sleep(0.5)
        guard let scrollArea = axFind(win, role: kAXScrollAreaRole), let text = axFind(scrollArea, role: kAXTextAreaRole),
              let scrollFrame = axFrame(scrollArea), let teNumber = textEditWindowNumber(te) else {
            check("M0 TextEdit's text area under the stand-in", false); standIn(false); return
        }
        let textPoint = CGPoint(x: scrollFrame.minX + 60, y: scrollFrame.minY + 40)
        let probeNumber = window.windowNumber
        func onTop() -> (ok: Bool, detail: String) {
            guard let p = stacking(probeNumber), let t = stacking(teNumber) else { return (false, "window list") }
            return (p.index < t.index && p.layer == Int(CGWindowLevelForKey(.floatingWindow)), "stand-in #\(p.index) layer \(p.layer), TextEdit #\(t.index) layer \(t.layer)")
        }
        let top0 = onTop()
        check("M0 the stand-in covers the main display above TextEdit (floating level)",
              top0.ok && window.frame.width >= mainBounds.width - 1 && EventInjector.window(at: textPoint, excluding: [getpid()])?.pid == te.processIdentifier,
              "\(top0.detail); the helper finds TextEdit under the point")
        guard let textPx = px(textPoint, true) else { check("M0 text point maps to slot 0", false); standIn(false); return }
        note("Mirror text point \(textPoint) → slot 0 pixel (\(textPx.x), \(textPx.y)) of \(textPx.w ?? 0)×\(textPx.h ?? 0)")

        // M1 control: without click-through the helper's click lands on "Minecraft" itself.
        relativeMode(true)
        axSetRange(text, CFRange(location: 0, length: 0))
        await activateProbe()
        clearInbox()
        counts = [:]
        client.send(.click(with(textPx, .left)))
        await sleep(0.6)
        let probeGotIt = (counts[.leftMouseDown] ?? 0) > 0
        check("M1 control: NOT click-through → the click hits Minecraft's window, TextEdit isn't reached",
              probeGotIt && NSWorkspace.shared.frontmostApplication?.processIdentifier != te.processIdentifier && (axRange(text)?.location ?? 0) == 0,
              "stand-in got \(counts[.leftMouseDown] ?? 0) down(s), frontmost \(NSWorkspace.shared.frontmostApplication?.localizedName ?? "?")")
        client.send(.exitTyping)
        await sleep(0.2)

        // M2: click-through → the click reaches TextEdit under Minecraft, typing works, Esc returns; Minecraft stays on top.
        await activateProbe()
        clearInbox()
        counts = [:]
        clickThrough(true) // the mod: CLICK on a Mirror → Minecraft click-through until typing ends
        client.send(.click(with(textPx, .left)))
        await sleep(0.7)
        let caret = axRange(text)
        let teFront = NSWorkspace.shared.frontmostApplication?.processIdentifier == te.processIdentifier
        check("M2 click-through CLICK on the Mirror reaches TextEdit underneath (caret moved, TextEdit has the keyboard)",
              teFront && (caret?.location ?? 0) > 0 && (counts[.leftMouseDown] ?? 0) == 0,
              "caret \(caret.map { "\($0.location)" } ?? "?"), frontmost \(NSWorkspace.shared.frontmostApplication?.localizedName ?? "?"), stand-in downs \(counts[.leftMouseDown] ?? 0)")
        let top2 = onTop()
        check("M2 Minecraft's window stays above TextEdit while TextEdit is active (floating)", top2.ok, top2.detail)
        let textBefore = (axAttr(text, kAXValueAttribute) as? String) ?? ""
        for code: CGKeyCode in [0, 11] { key(code); await sleep(0.05) } // a b
        key(53)
        let exit = await waitFor(2) { if case .typingExit = $0 { return true }; return false }
        clickThrough(false) // the mod: typing ended → restore
        await sleep(0.4)
        let textAfter = (axAttr(text, kAXValueAttribute) as? String) ?? ""
        let loc = caret?.location ?? 0
        let typedAtCaret = textAfter.count == textBefore.count + 2
            && String(Array(textAfter)[min(loc, textAfter.count)..<min(loc + 2, textAfter.count)]) == "ab"
        check("M2 keys typed into TextEdit at the click point; Esc → TYPING_EXIT esc, Minecraft frontmost, no longer click-through",
              typedAtCaret && exit == .typingExit(ReasonPayload(reason: "esc")) && probeFrontmost && !window.ignoresMouseEvents,
              "typed at \(loc): \(typedAtCaret), \(exit.map { "\($0)" } ?? "no TYPING_EXIT"), frontmost \(NSWorkspace.shared.frontmostApplication?.localizedName ?? "?")")
        if typedAtCaret { AXUIElementSetAttributeValue(text, kAXValueAttribute as CFString, textBefore as CFString) }

        // M3: a head-turn drag on the Mirror selects text.
        axSetRange(text, CFRange(location: 0, length: 0))
        let m3 = await headDrag(textPoint, CGPoint(x: textPoint.x + 300, y: textPoint.y + 60), 20, true,
                                { self.clickThrough(true) }, { self.clickThrough(false) })
        let sel = axRange(text)
        check("M3 head-turn drag on the Mirror selects text in TextEdit under Minecraft; ends on release, Minecraft frontmost",
              (sel?.length ?? 0) > 20 && m3.ended == .dragEnded(ReasonPayload(reason: "released")) && m3.front && m3.probeDrags == 0,
              "selection \(sel.map { "\($0.location)+\($0.length)" } ?? "?"), ended \(m3.ended.map { "\($0)" } ?? "none"), Minecraft saw \(m3.probeDrags) drags")

        // M4: drag TextEdit's title bar from the Mirror (main display) onto hologram 2's display.
        let frame = axFrame(win) ?? .zero
        let title = CGPoint(x: frame.minX + frame.width * 0.6, y: frame.minY + 12)
        let m4 = await headDrag(title, CGPoint(x: boundsB.minX + 600, y: boundsB.minY + 120), 40, true,
                                { self.clickThrough(true) }, { self.clickThrough(false) })
        let after = axFrame(win) ?? .zero
        let grab = CGPoint(x: after.minX + (title.x - frame.minX), y: after.minY + (title.y - frame.minY))
        check("M4 dragging the title bar from the Mirror carries TextEdit's window onto an Extend display (hologram 2)",
              boundsB.contains(grab) && m4.ended == .dragEnded(ReasonPayload(reason: "released")) && m4.front,
              "window \(frame.origin) → \(after.origin) (display 2 \(boundsB)), ended \(m4.ended.map { "\($0)" } ?? "none")")
        let top4 = onTop()
        note("after M4: \(top4.detail)")

        // M5: scroll on the Mirror: a click-through moment around the SCROLL.
        axSetFrame(win, homeOnMain)
        await sleep(0.5)
        await activateProbe()
        if let sa = axFind(win, role: kAXScrollAreaRole), let saFrame = axFrame(sa), let p = px(CGPoint(x: saFrame.midX, y: saFrame.midY), true) {
            let s0 = axScrollValue(sa) ?? 0
            client.send(.scroll(ScrollPayload(slot: 0, x: p.x, y: p.y, dx: 0, dy: -48, w: p.w, h: p.h))) // NOT click-through
            await sleep(0.5)
            let s1 = axScrollValue(sa) ?? 0
            check("M5 [informational] SCROLL on the Mirror without click-through doesn't reach TextEdit", s1 <= s0 + 0.0001,
                  String(format: "%.4f → %.4f", s0, s1))
            clickThrough(true) // the mod: a scroll on a Mirror → click-through for a moment
            client.send(.scroll(ScrollPayload(slot: 0, x: p.x, y: p.y, dx: 0, dy: -48, w: p.w, h: p.h)))
            await sleep(0.4) // the mod keeps a Mirror scroll click-through 400 ms
            clickThrough(false)
            await sleep(0.3)
            let s2 = axScrollValue(sa) ?? 0
            check("M5 SCROLL on the Mirror with a click-through moment scrolls TextEdit one notch; Minecraft stays frontmost",
                  s2 > s1 + 0.001 && s2 - s1 < 0.10 && probeFrontmost && !window.ignoresMouseEvents,
                  String(format: "%.4f → %.4f, frontmost %@", s1, s2, NSWorkspace.shared.frontmostApplication?.localizedName ?? "?"))
        } else {
            check("M5 SCROLL target found", false)
        }
        let top5 = onTop()
        check("M6 afterwards: Minecraft on top, frontmost, not click-through", top5.ok && probeFrontmost && !window.ignoresMouseEvents, top5.detail)
        standIn(false)
        await activateProbe()
    }

    // MARK: Work mode (spec 5.10, round 2): the real mouse drives the real cursor; Esc ends it

    /// A mouse move / drag as the real mouse makes it: a HID event at the new cursor position (acceleration already
    /// applied, as the window server does for a real mouse), unmarked, with its deltas.
    func hand(_ type: CGEventType, to p: CGPoint, from q: CGPoint) {
        guard let e = CGEvent(mouseEventSource: hid, mouseType: type, mouseCursorPosition: p, mouseButton: .left) else { return }
        e.setIntegerValueField(.mouseEventDeltaX, value: Int64((p.x - q.x).rounded()))
        e.setIntegerValueField(.mouseEventDeltaY, value: Int64((p.y - q.y).rounded()))
        e.post(tap: .cghidEventTap)
    }

    var cursorNow: CGPoint { CGEvent(source: nil)?.location ?? .zero }

    func runWork(client: HelperSocket, inbox: () -> [Message], clearInbox: () -> Void, waitFor: (Double, (Message) -> Bool) async -> Message?,
                 px: (CGPoint, Bool) -> PointerPayload?, with: (PointerPayload, PointerButton?) -> PointerPayload,
                 te: NSRunningApplication, win: AXUIElement, homeOnMain: CGRect, idA: CGDirectDisplayID, mainBounds: CGRect) async {
        let boundsA = CGDisplayBounds(idA)
        check("W0 display 1 sits right next to the main display (the arrangement), top edges aligned",
              (boundsA.minX == mainBounds.maxX || boundsA.maxX == mainBounds.minX) && boundsA.minY == mainBounds.minY,
              "main \(mainBounds), display 1 \(boundsA)")
        axSetFrame(win, homeOnMain)
        await sleep(0.4)
        standIn(true)
        relativeMode(false)
        await activateProbe()
        await sleep(0.4)
        guard let scrollArea = axFind(win, role: kAXScrollAreaRole), let text = axFind(scrollArea, role: kAXTextAreaRole),
              let scrollFrame = axFrame(scrollArea) else { check("W found TextEdit's text area", false); standIn(false); return }
        let textPoint = CGPoint(x: scrollFrame.minX + 80, y: scrollFrame.minY + 60)
        guard let textPx = px(textPoint, true) else { check("W text point maps to slot 0", false); standIn(false); return }

        // W1: a click (button already up): the real cursor goes to the point, TextEdit gets the click, the cursor hides.
        axSetRange(text, CFRange(location: 0, length: 0))
        clearInbox()
        clickThrough(true) // the mod: work mode = floating + click-through
        client.send(.workBegin(with(textPx, .left)))
        await sleep(0.8)
        let caret = axRange(text)?.location ?? 0
        let cursor = cursorNow
        var hidden = !cursorDrawn(at: cursor)
        if ProcessInfo.processInfo.environment["PROBE_CURSOR_TIMELINE"] != nil {
            for t in [0.3, 0.6, 1.2] { await sleep(t); note("W1 timeline +\(t) s: cursor drawn \(cursorDrawn(at: cursorNow)) at \(cursorNow)") }
            hidden = !cursorDrawn(at: cursorNow)
        }
        check("W1 WORK_BEGIN on the Mirror: the real cursor is at the clicked point, TextEdit got the click and the keyboard, the system cursor is hidden",
              hypot(cursor.x - textPoint.x, cursor.y - textPoint.y) < 2 && caret > 0
                && NSWorkspace.shared.frontmostApplication?.processIdentifier == te.processIdentifier && hidden,
              "cursor \(cursor) vs \(textPoint), caret \(caret), frontmost \(NSWorkspace.shared.frontmostApplication?.localizedName ?? "?"), hidden \(hidden)")

        // W2: the real mouse moves the real cursor 1:1; CURSOR reports where it is, to the pixel.
        var p = cursorNow
        for _ in 0..<20 {
            let next = CGPoint(x: p.x + 9, y: p.y + 4)
            hand(.mouseMoved, to: next, from: p)
            p = next
            await sleep(0.01)
        }
        await sleep(0.2)
        let real = cursorNow
        let reports = inbox().compactMap { m -> CursorPayload? in if case .cursor(let c) = m { return c }; return nil }
        let last = reports.last
        let reportedAt = last.map { CGPoint(x: mainBounds.minX + Double($0.x) * mainBounds.width / Double($0.w),
                                            y: mainBounds.minY + Double($0.y) * mainBounds.height / Double($0.h)) }
        check("W2 the hand moves the real cursor (180 pt right, 80 down) and CURSOR reports it on the Mirror within 1 pt",
              hypot(real.x - (textPoint.x + 180), real.y - (textPoint.y + 80)) < 2 && last?.slot == 0
                && reportedAt.map { abs($0.x - real.x) <= 1 && abs($0.y - real.y) <= 1 } == true,
              "real \(real), \(reports.count) reports, last \(last.map { "\($0)" } ?? "none")")

        // W9 (round 3): walk while dragging. With the real button held, W goes to Minecraft (WALK [forward]), never to
        // TextEdit; with it up, W types. Releasing the button while W is held stops the walk at once and nothing of
        // that W reaches TextEdit (its repeat, its up).
        func walks() -> [[String]] { inbox().compactMap { m -> [String]? in if case .walk(let w) = m { return w.keys }; return nil } }
        func keyEvent(_ code: CGKeyCode, _ down: Bool) { CGEvent(keyboardEventSource: hid, virtualKey: code, keyDown: down)?.post(tap: .cghidEventTap) }
        let textW0 = (axAttr(text, kAXValueAttribute) as? String) ?? ""
        let w9at = cursorNow
        clearInbox()
        hand(.leftMouseDown, to: w9at, from: w9at)
        await sleep(0.15)
        keyEvent(13, true)
        await sleep(0.15)
        keyEvent(13, true) // a repeat
        await sleep(0.1)
        let walking = walks()
        keyEvent(13, false)
        await sleep(0.15)
        let afterUp = walks()
        hand(.leftMouseUp, to: w9at, from: w9at)
        await sleep(0.2)
        let textW1 = (axAttr(text, kAXValueAttribute) as? String) ?? ""
        check("W9 button held: W walks in Minecraft (WALK [forward], then []) and TextEdit gets no 'w'",
              walking.last == ["forward"] && afterUp.last == [] && textW1 == textW0,
              "walks \(afterUp), TextEdit \(textW1.count - textW0.count) chars more")
        clearInbox()
        key(13)
        await sleep(0.3)
        let textW2 = (axAttr(text, kAXValueAttribute) as? String) ?? ""
        check("W9 button up: W types into TextEdit again, no WALK", textW2.count == textW0.count + 1 && textW2.contains("w") && walks().isEmpty,
              "TextEdit \(textW2.count - textW0.count) chars more, walks \(walks())")
        clearInbox()
        hand(.leftMouseDown, to: w9at, from: w9at)
        await sleep(0.15)
        keyEvent(13, true)
        await sleep(0.15)
        let released = Date()
        hand(.leftMouseUp, to: w9at, from: w9at)
        var stopMs = -1.0
        for _ in 0..<50 {
            if walks().last == [] { stopMs = Date().timeIntervalSince(released) * 1000; break }
            await sleep(0.005)
        }
        keyEvent(13, true) // the key still held after the release: its repeat ...
        await sleep(0.1)
        keyEvent(13, false) // ... and its up
        await sleep(0.3)
        let textW3 = (axAttr(text, kAXValueAttribute) as? String) ?? ""
        check("W9 releasing the button with W held stops the walk at once; that W never reaches TextEdit, nothing stuck",
              walks() == [["forward"], []] && stopMs >= 0 && stopMs < 100 && textW3 == textW2
                && !CGEventSource.keyState(.hidSystemState, key: 13) && !CGEventSource.buttonState(.hidSystemState, button: .left),
              String(format: "walks %@, stopped %.0f ms after the release, TextEdit %d chars more", "\(walks())", stopMs, textW3.count - textW2.count))
        if textW2 != textW0 { AXUIElementSetAttributeValue(text, kAXValueAttribute as CFString, textW0 as CFString) }

        // W10 (round 3): real mouse → CURSOR report latency (the drawn cursor's helper share): 40 moves at random gaps
        var arrivals: [(time: Date, cursor: CursorPayload)] = []
        let keepOnMessage = client.onMessage
        client.onMessage = { m in
            if case .cursor(let c) = m { arrivals.append((Date(), c)) }
            keepOnMessage?(m)
        }
        var moves: [(time: Date, x: Int, y: Int)] = []
        var at = cursorNow
        for i in 0..<40 {
            let next = CGPoint(x: (at.x + Double(i % 2 == 0 ? 7 : -5)).rounded(), y: (at.y + 3).rounded())
            let t = Date()
            hand(.mouseMoved, to: next, from: at)
            moves.append((t, Int(next.x - mainBounds.minX), Int(next.y - mainBounds.minY)))
            at = next
            await sleep(0.008 + Double((i * 7) % 13) / 1000)
        }
        await sleep(0.2)
        client.onMessage = keepOnMessage
        let lat = moves.compactMap { m in
            arrivals.first { $0.time >= m.time && $0.cursor.slot == 0 && $0.cursor.x == m.x && $0.cursor.y == m.y }
                .map { $0.time.timeIntervalSince(m.time) * 1000 }
        }.sorted()
        let p50 = lat.isEmpty ? Double.nan : lat[lat.count / 2]
        let p90 = lat.isEmpty ? Double.nan : lat[min(lat.count - 1, Int(Double(lat.count) * 0.9))]
        let line = String(format: "CURSOR LATENCY real move → CURSOR report: median %.1f ms, p90 %.1f ms (%d/40 seen)", p50, p90, lat.count)
        print(line); fflush(stdout)
        check("W10 every real move is reported (\(lat.count)/40)", lat.count >= 36, line)

        // W5 (PROBE_MONITOR): the cursor on a real monitor — no hologram shows it — is the system cursor, shown; back on
        // the Mirror it is hidden again.
        if let mid = probeMonitorID {
            let mb = CGDisplayBounds(mid)
            showProbeMonitorWindow(mid)
            await sleep(0.3)
            clearInbox()
            let onMonitor = CGPoint(x: mb.midX, y: mb.midY)
            hand(.mouseMoved, to: onMonitor, from: cursorNow)
            await sleep(0.5)
            let there = inbox().compactMap { m -> CursorPayload? in if case .cursor(let c) = m { return c }; return nil }.last
            let shown = cursorDrawn(at: cursorNow)
            check("W5 the cursor onto a real monitor: CURSOR slot -1 and the system cursor is shown there",
                  there?.slot == -1 && shown && mb.contains(cursorNow), "cursor \(cursorNow), last \(there.map { "\($0)" } ?? "none"), drawn \(shown)")
            clearInbox()
            hand(.mouseMoved, to: real, from: cursorNow)
            await sleep(0.5)
            let back = inbox().compactMap { m -> CursorPayload? in if case .cursor(let c) = m { return c }; return nil }.last
            let hiddenAgain = !cursorDrawn(at: cursorNow)
            check("W5 back onto the Mirror: CURSOR slot 0 and the system cursor hidden again",
                  back?.slot == 0 && hiddenAgain, "cursor \(cursorNow), last \(back.map { "\($0)" } ?? "none"), hidden \(hiddenAgain)")
            probeMonitorWindow?.orderOut(nil)
        }

        // W4 (before W3, so W3 starts from a press): Esc ends work mode; the cursor shows again, reports stop.
        key(53)
        let exit = await waitFor(2) { if case .typingExit = $0 { return true }; return false }
        clickThrough(false)
        await sleep(0.4)
        clearInbox()
        hand(.mouseMoved, to: CGPoint(x: real.x - 30, y: real.y), from: real)
        await sleep(0.3)
        let after = inbox().contains { if case .cursor = $0 { return true }; return false }
        check("W4 Esc → TYPING_EXIT esc, Minecraft frontmost, system cursor visible again, no more CURSOR reports",
              exit == .typingExit(ReasonPayload(reason: "esc")) && probeFrontmost && cursorDrawn(at: cursorNow) && !after,
              "\(exit.map { "\($0)" } ?? "none"), frontmost \(NSWorkspace.shared.frontmostApplication?.localizedName ?? "?"), reports after: \(after)")

        // W3: a real press on TextEdit's title bar on the Mirror, a real drag off the main display's right edge onto
        // display 1, a real release: the window is on display 1. The helper posts only the down; the hand does the rest.
        axSetFrame(win, homeOnMain)
        await sleep(0.4)
        await activateProbe()
        let frame = axFrame(win) ?? .zero
        let title = CGPoint(x: frame.minX + frame.width * 0.6, y: frame.minY + 12)
        guard let titlePx = px(title, true) else { check("W3 title maps to slot 0", false); standIn(false); return }
        counts = [:]
        physical(.leftMouseDown) // the player presses in Minecraft (the stand-in gets it, as Minecraft would)
        await sleep(0.05)
        clickThrough(true)
        clearInbox()
        client.send(.workBegin(with(titlePx, .left)))
        await sleep(0.6)
        var q = cursorNow
        let target = CGPoint(x: boundsA.minX + 500, y: boundsA.minY + 200)
        let steps = 40
        for i in 1...steps {
            let next = CGPoint(x: title.x + (target.x - title.x) * Double(i) / Double(steps), y: title.y + (target.y - title.y) * Double(i) / Double(steps))
            hand(.leftMouseDragged, to: next, from: q)
            q = next
            await sleep(0.015)
        }
        await sleep(0.1)
        hand(.leftMouseUp, to: q, from: q)
        await sleep(0.5)
        let moved = axFrame(win) ?? .zero
        let grab = CGPoint(x: moved.minX + (title.x - frame.minX), y: moved.minY + (title.y - frame.minY))
        let onA = inbox().compactMap { m -> CursorPayload? in if case .cursor(let c) = m { return c }; return nil }.last
        check("W3 real press + real drag + real release carry TextEdit's window from the Mirror across the edge onto display 1",
              boundsA.contains(grab) && onA?.slot == 1,
              "window \(frame.origin) → \(moved.origin) (display 1 \(boundsA)), last CURSOR \(onA.map { "\($0)" } ?? "none")")
        let stuck = CGEventSource.buttonState(.hidSystemState, button: .left)
        check("W3 nothing stuck: the button is up", !stuck, "button down \(stuck)")
        key(53)
        _ = await waitFor(2) { if case .typingExit = $0 { return true }; return false }
        clickThrough(false)
        await sleep(0.3)
        axSetFrame(win, homeOnMain)
        standIn(false)
        await activateProbe()
    }

    var helperRunning: Bool { !NSRunningApplication.runningApplications(withBundleIdentifier: "local.carter.office-helper").isEmpty }
}

// MARK: A "real monitor" for W5 / W6: a probe-owned virtual display (not one of the helper's Office displays)

@MainActor var probeMonitor: CGVirtualDisplay?
@MainActor var probeMonitorID: CGDirectDisplayID?
@MainActor var probeMonitorWindow: NSWindow?

@MainActor func makeProbeMonitor() -> CGDirectDisplayID? {
    let d = CGVirtualDisplayDescriptor()
    d.queue = DispatchQueue.main
    d.name = "Probe Monitor"
    d.maxPixelsWide = 1920
    d.maxPixelsHigh = 1080
    d.sizeInMillimeters = CGSize(width: 443, height: 249)
    d.serialNum = 0x5052_0001 // not an Office display's "MO" serial
    d.productID = 0x0002
    d.vendorID = 0x5052
    d.redPrimary = CGPoint(x: 0.64, y: 0.33)
    d.greenPrimary = CGPoint(x: 0.30, y: 0.60)
    d.bluePrimary = CGPoint(x: 0.15, y: 0.06)
    d.whitePoint = CGPoint(x: 0.3127, y: 0.3290)
    d.terminationHandler = { _, _ in }
    guard let display = CGVirtualDisplay(descriptor: d) else { return nil }
    let settings = CGVirtualDisplaySettings()
    settings.hiDPI = 0
    settings.modes = [CGVirtualDisplayMode(width: 1920, height: 1080, refreshRate: 60)]
    guard display.apply(settings) else { return nil }
    probeMonitor = display
    return display.displayID
}

@MainActor func placeDisplay(_ id: CGDirectDisplayID, at origin: CGPoint) {
    var config: CGDisplayConfigRef?
    guard CGBeginDisplayConfiguration(&config) == .success, let config else { return }
    CGConfigureDisplayOrigin(config, id, Int32(origin.x), Int32(origin.y))
    _ = CGCompleteDisplayConfiguration(config, .forSession)
}

/// A plain window over the probe monitor, so the cursor check there has a flat background.
@MainActor func showProbeMonitorWindow(_ id: CGDirectDisplayID) {
    guard let screen = NSScreen.screens.first(where: {
        ($0.deviceDescription[NSDeviceDescriptionKey("NSScreenNumber")] as? NSNumber)?.uint32Value == id
    }) else { return }
    let w = probeMonitorWindow ?? NSWindow(contentRect: screen.frame, styleMask: [.borderless], backing: .buffered, defer: false)
    w.isReleasedWhenClosed = false
    w.level = .floating
    w.backgroundColor = NSColor(calibratedWhite: 0.45, alpha: 1)
    w.ignoresMouseEvents = true
    w.setFrame(screen.frame, display: true)
    w.orderFrontRegardless()
    probeMonitorWindow = w
}

/// Is a cursor drawn on screen around `p`? `screencapture -C` (with the cursor) of a 48-pt square there over the plain
/// stand-in window: more than a few pixels unlike the background = a cursor. (CGCursorIsVisible only reports the
/// calling process's own state.) PROBE_KEEP_SHOTS=<dir> keeps the screenshots.
func cursorDrawn(at p: CGPoint) -> Bool {
    let file = FileManager.default.temporaryDirectory.appendingPathComponent("office-probe-cursor-\(getpid()).png")
    let t = Process()
    t.executableURL = URL(fileURLWithPath: "/usr/sbin/screencapture")
    t.arguments = ["-x", "-C", "-R", "\(Int(p.x) - 8),\(Int(p.y) - 8),48,48", file.path]
    try? t.run(); t.waitUntilExit()
    defer {
        if let keep = ProcessInfo.processInfo.environment["PROBE_KEEP_SHOTS"] { // debugging: keep the screenshots
            try? FileManager.default.copyItem(at: file, to: URL(fileURLWithPath: keep).appendingPathComponent("cursor-\(Int(Date().timeIntervalSince1970 * 1000)).png"))
        }
        try? FileManager.default.removeItem(at: file)
    }
    guard let rep = NSBitmapImageRep(data: (try? Data(contentsOf: file)) ?? Data()) else { return true }
    // the background is whatever the bottom-right corner shows (the cursor's tip is near the top-left); a cursor is
    // a few dozen pixels clearly different from it
    var odd = 0
    guard let bg = rep.colorAt(x: rep.pixelsWide - 1, y: rep.pixelsHigh - 1) else { return true }
    for y in 0..<rep.pixelsHigh { for x in 0..<rep.pixelsWide {
        guard let c = rep.colorAt(x: x, y: y) else { continue }
        if abs(c.redComponent - bg.redComponent) + abs(c.greenComponent - bg.greenComponent) + abs(c.blueComponent - bg.blueComponent) > 0.3 { odd += 1 }
    } }
    return odd > 20
}
