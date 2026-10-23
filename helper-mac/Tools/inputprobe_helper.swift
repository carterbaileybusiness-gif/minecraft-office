// inputprobe --helper <OfficeHelper.app>: the same checks end to end through the REAL signed helper and its socket
// protocol, with the probe standing in for both Minecraft (frontmost, holds the "physical" button, gets typing back)
// and the mod (HELLO with the probe's pid as mcPid, CREATE_DISPLAY, WINDOWS_TO_DISPLAY, CLICK, POINTER_DOWN/MOVE with
// points mapped from DRAG_DELTA the way the mod maps the turned head, SCROLL, WINDOWS_BACK). Refuses to run while
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

        var ids: [Int: CGDirectDisplayID] = [:]
        for slot in [1, 2] {
            client.send(.createDisplay(CreateDisplayPayload(slot: slot, w: 1920, h: 1080)))
            if case .display(let d)? = await waitFor(5, { if case .display(let d) = $0 { return d.slot == slot }; return false }) { ids[slot] = d.displayId }
        }
        guard let idA = ids[1], let idB = ids[2] else { check("E0 helper created displays 1 and 2", false); return }
        await sleep(1.0)
        let boundsA = CGDisplayBounds(idA), boundsB = CGDisplayBounds(idB)
        check("E0 helper created displays 1 and 2", true, "1: \(boundsA), 2: \(boundsB)")
        /// A global point → (slot, pixel) the way the mod sends it (display pixels of a 1920×1080 display).
        func px(_ p: CGPoint) -> PointerPayload? {
            for (slot, id) in ids {
                let b = CGDisplayBounds(id)
                if b.contains(p) {
                    return PointerPayload(slot: slot, x: Int((p.x - b.minX) * 1920 / b.width), y: Int((p.y - b.minY) * 1080 / b.height), button: nil)
                }
            }
            return nil
        }
        func with(_ p: PointerPayload, _ b: PointerButton?) -> PointerPayload { PointerPayload(slot: p.slot, x: p.x, y: p.y, button: b) }

        guard let opened = await openScratch() else { return }
        let (te, win) = opened
        await sleep(0.5)
        let original = axFrame(win) ?? .zero
        let mainBounds = CGDisplayBounds(CGMainDisplayID())
        if !mainBounds.contains(CGPoint(x: original.midX, y: original.midY)) {
            // TextEdit restored the window onto an old display: start from the main display like a real join would
            axSetFrame(win, CGRect(x: 100, y: 100, width: 700, height: 500))
            await sleep(0.4)
        }
        let before = axFrame(win) ?? .zero
        await activateProbe()
        client.send(.windowsToDisplay(WindowsToDisplayPayload(slot: 1, apps: ["com.apple.TextEdit"])))
        await sleep(0.8)
        let moved = axFrame(win) ?? .zero
        check("E1 WINDOWS_TO_DISPLAY moves the window from the main display onto display 1, scaled",
              boundsA.contains(CGPoint(x: moved.midX, y: moved.midY)) && moved.width > before.width,
              "\(before) → \(moved)")
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
        check("E2 CLICK: the app comes forward with the keyboard (typing mode)", NSWorkspace.shared.frontmostApplication?.processIdentifier == te.processIdentifier,
              "frontmost \(NSWorkspace.shared.frontmostApplication?.localizedName ?? "?")")
        let textBefore = (axAttr(text, kAXValueAttribute) as? String) ?? ""
        for code: CGKeyCode in [0, 11] { key(code); await sleep(0.05) } // a b
        key(53)
        let exit = await waitFor(2) { if case .typingExit = $0 { return true }; return false }
        await sleep(0.4)
        let textAfter = (axAttr(text, kAXValueAttribute) as? String) ?? ""
        check("E2 keys typed into the app", textAfter.hasPrefix("ab") && textAfter.count == textBefore.count + 2, "starts \"\(textAfter.prefix(6))\"")
        check("E2 Esc → TYPING_EXIT esc, Minecraft frontmost again", exit == .typingExit(ReasonPayload(reason: "esc")) && probeFrontmost,
              "\(exit.map { "\($0)" } ?? "no TYPING_EXIT"), frontmost \(NSWorkspace.shared.frontmostApplication?.localizedName ?? "?")")
        if textAfter.hasPrefix("ab") { AXUIElementSetAttributeValue(text, kAXValueAttribute as CFString, String(textAfter.dropFirst(2)) as CFString) }

        // A click, then clicking back into "Minecraft" ends typing too.
        client.send(.click(with(textPx, .left)))
        await sleep(0.5)
        await activateProbe()
        let back = await waitFor(2) { if case .typingExit = $0 { return true }; return false }
        check("E3 Minecraft activated while typing → TYPING_EXIT minecraft", back == .typingExit(ReasonPayload(reason: "minecraft")),
              back.map { "\($0)" } ?? "none")

        /// A head-turn drag through the helper: the probe holds the button, the helper takes the mouse, the probe maps
        /// each DRAG_DELTA to the next point and sends POINTER_MOVE.
        func headDrag(from start: CGPoint, to end: CGPoint, steps: Int) async -> (ended: Message?, front: Bool, deltas: Int, probeDrags: Int) {
            relativeMode(true)
            await activateProbe()
            inbox.removeAll()
            counts = [:]
            physical(.leftMouseDown)
            await sleep(0.1)
            guard let startPx = px(start) else { return (nil, false, 0, 0) }
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
                if let p = px(CGPoint(x: start.x + acc.x, y: start.y + acc.y)) { client.send(.pointerMove(p)) } // off-display: keep the last point
            }
            await sleep(0.05)
            physical(.leftMouseUp)
            let ended = await waitFor(2) { if case .dragEnded = $0 { return true }; return false }
            await sleep(0.4)
            return (ended, probeFrontmost, deltas, counts[.leftMouseDragged] ?? 0)
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

        // Scroll where the window is now.
        if let sa = axFind(win, role: kAXScrollAreaRole), let saFrame = axFrame(sa), let p = px(CGPoint(x: saFrame.midX, y: saFrame.midY)) {
            await activateProbe()
            let s0 = axScrollValue(sa) ?? 0
            client.send(.scroll(ScrollPayload(slot: p.slot, x: p.x, y: p.y, dx: 0, dy: -600)))
            await sleep(0.5)
            let s1 = axScrollValue(sa) ?? 0
            check("E6 SCROLL scrolls the app under the point, Minecraft stays frontmost", s1 > s0 + 0.001 && probeFrontmost,
                  String(format: "%.3f → %.3f, frontmost %@", s0, s1, NSWorkspace.shared.frontmostApplication?.localizedName ?? "?"))
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

        client.send(.windowsBack)
        await sleep(0.8)
        let restored = axFrame(win) ?? .zero
        check("E8 WINDOWS_BACK puts the window back exactly where it was (even after it was dragged elsewhere)",
              abs(restored.minX - before.minX) < 2 && abs(restored.minY - before.minY) < 2 && abs(restored.width - before.width) < 2,
              "\(restored) vs \(before)")

        // Join again, then disconnect without WINDOWS_BACK: the helper restores on its way out.
        client.send(.windowsToDisplay(WindowsToDisplayPayload(slot: 2, apps: ["com.apple.TextEdit"])))
        await sleep(0.6)
        let onTwo = axFrame(win) ?? .zero
        client.close()
        for _ in 0..<50 where helperRunning { await sleep(0.1) }
        await sleep(0.5)
        let afterQuit = axFrame(win) ?? .zero
        check("E9 disconnect: helper restores windows, removes its displays and quits",
              boundsB.contains(CGPoint(x: onTwo.midX, y: onTwo.midY)) && abs(afterQuit.minX - before.minX) < 2 && abs(afterQuit.minY - before.minY) < 2
                && !helperRunning && !VirtualDisplays.isOnline(idA) && !VirtualDisplays.isOnline(idB),
              "on display 2 \(onTwo.origin), after quit \(afterQuit.origin), helper running \(helperRunning)")
        relativeMode(false)
    }

    var helperRunning: Bool { !NSRunningApplication.runningApplications(withBundleIdentifier: "local.carter.office-helper").isEmpty }
}
