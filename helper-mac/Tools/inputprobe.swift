// inputprobe: which way of posting input lets the player drag on a hologram while Minecraft keeps its mouse?
//
// Run: bash helper-mac/Tools/inputprobe.sh   (needs Accessibility for the terminal that runs it; no Minecraft needed)
// `inputprobe` alone compares the delivery routes; `inputprobe --helper <OfficeHelper.app>` (inputprobe_helper.swift)
// then runs the chosen route end to end through the real signed helper.
//
// The probe itself is a regular app with a window on the main display: it stands in for Minecraft (it is frontmost,
// its mouse is disassociated from the cursor like SDL's relative mode, and it "holds" the physical button by receiving
// a HID mouse-down and HID drags with deltas). It creates two Office virtual displays (the helper's own
// VirtualDisplays code), opens a scratch document in TextEdit (`open -a TextEdit`), moves that window onto display A
// with Accessibility, then tries each delivery route of the helper's EventInjector and checks the result through
// Accessibility (caret, selection, scroll position, window position, typed text, frontmost app). It closes TextEdit,
// removes its displays and puts the cursor back when it is done. Prints one PASS/FAIL line per check and a summary.

import AppKit
import ApplicationServices

// MARK: Results

@MainActor var results: [(String, Bool, String)] = []
@MainActor func check(_ name: String, _ ok: Bool, _ detail: String = "") {
    results.append((name, ok, detail))
    print("\(ok ? "PASS" : "FAIL") \(name)\(detail.isEmpty ? "" : " — \(detail)")")
    fflush(stdout)
}
@MainActor func note(_ s: String) { print("     \(s)"); fflush(stdout) }

func sleep(_ seconds: Double) async { try? await Task.sleep(nanoseconds: UInt64(seconds * 1_000_000_000)) }

// MARK: Accessibility helpers

func axAttr(_ e: AXUIElement, _ name: String) -> CFTypeRef? {
    var v: CFTypeRef?
    return AXUIElementCopyAttributeValue(e, name as CFString, &v) == .success ? v : nil
}
func axPoint(_ e: AXUIElement, _ name: String) -> CGPoint? {
    guard let v = axAttr(e, name) else { return nil }
    var p = CGPoint.zero
    return AXValueGetValue(v as! AXValue, .cgPoint, &p) ? p : nil
}
func axSize(_ e: AXUIElement) -> CGSize? {
    guard let v = axAttr(e, kAXSizeAttribute) else { return nil }
    var s = CGSize.zero
    return AXValueGetValue(v as! AXValue, .cgSize, &s) ? s : nil
}
func axFrame(_ e: AXUIElement) -> CGRect? {
    guard let p = axPoint(e, kAXPositionAttribute), let s = axSize(e) else { return nil }
    return CGRect(origin: p, size: s)
}
func axSetFrame(_ e: AXUIElement, _ r: CGRect) {
    var o = r.origin, s = r.size
    if let v = AXValueCreate(.cgSize, &s) { AXUIElementSetAttributeValue(e, kAXSizeAttribute as CFString, v) }
    if let v = AXValueCreate(.cgPoint, &o) { AXUIElementSetAttributeValue(e, kAXPositionAttribute as CFString, v) }
    if let v = AXValueCreate(.cgSize, &s) { AXUIElementSetAttributeValue(e, kAXSizeAttribute as CFString, v) }
}
func axSetPosition(_ e: AXUIElement, _ p: CGPoint) {
    var o = p
    if let v = AXValueCreate(.cgPoint, &o) { AXUIElementSetAttributeValue(e, kAXPositionAttribute as CFString, v) }
}
func axRange(_ e: AXUIElement) -> CFRange? {
    guard let v = axAttr(e, kAXSelectedTextRangeAttribute) else { return nil }
    var r = CFRange()
    return AXValueGetValue(v as! AXValue, .cfRange, &r) ? r : nil
}
func axSetRange(_ e: AXUIElement, _ r: CFRange) {
    var r = r
    if let v = AXValueCreate(.cfRange, &r) { AXUIElementSetAttributeValue(e, kAXSelectedTextRangeAttribute as CFString, v) }
}
func axChildren(_ e: AXUIElement) -> [AXUIElement] { (axAttr(e, kAXChildrenAttribute) as? [AXUIElement]) ?? [] }
func axRole(_ e: AXUIElement) -> String { (axAttr(e, kAXRoleAttribute) as? String) ?? "" }
func axFind(_ e: AXUIElement, role: String, depth: Int = 0) -> AXUIElement? {
    if axRole(e) == role { return e }
    guard depth < 8 else { return nil }
    for c in axChildren(e) { if let f = axFind(c, role: role, depth: depth + 1) { return f } }
    return nil
}
func axScrollValue(_ scrollArea: AXUIElement) -> Double? {
    guard let bar = axAttr(scrollArea, kAXVerticalScrollBarAttribute) else { return nil }
    return (axAttr(bar as! AXUIElement, kAXValueAttribute) as? NSNumber)?.doubleValue
}

// MARK: Keyboard + Esc tap

let hid = CGEventSource(stateID: .hidSystemState)
func key(_ code: CGKeyCode) {
    CGEvent(keyboardEventSource: hid, virtualKey: code, keyDown: true)?.post(tap: .cghidEventTap)
    CGEvent(keyboardEventSource: hid, virtualKey: code, keyDown: false)?.post(tap: .cghidEventTap)
}

nonisolated(unsafe) var swallowed: [Int64: Int] = [:]
nonisolated(unsafe) var swallowCodes: Set<Int64> = []
nonisolated(unsafe) var escTap: CFMachPort?

func startTap() -> Bool {
    let mask = (1 << CGEventType.keyDown.rawValue) | (1 << CGEventType.keyUp.rawValue)
    guard let tap = CGEvent.tapCreate(tap: .cgSessionEventTap, place: .headInsertEventTap, options: .defaultTap,
                                      eventsOfInterest: CGEventMask(mask), callback: { _, type, event, _ in
        if type == .tapDisabledByTimeout || type == .tapDisabledByUserInput {
            if let escTap { CGEvent.tapEnable(tap: escTap, enable: true) }
            return Unmanaged.passUnretained(event)
        }
        let code = event.getIntegerValueField(.keyboardEventKeycode)
        if swallowCodes.contains(code) {
            if type == .keyDown { swallowed[code, default: 0] += 1 }
            return nil
        }
        return Unmanaged.passUnretained(event)
    }, userInfo: nil) else { return false }
    escTap = tap
    let source = CFMachPortCreateRunLoopSource(nil, tap, 0)
    CFRunLoopAddSource(CFRunLoopGetMain(), source, .commonModes)
    CGEvent.tapEnable(tap: tap, enable: true)
    return true
}

// MARK: Drag tap (route H): while the helper drives a drag it owns the physical mouse

nonisolated(unsafe) var dragTap: CFMachPort?
nonisolated(unsafe) var dragDX: Int64 = 0
nonisolated(unsafe) var dragDY: Int64 = 0
nonisolated(unsafe) var dragSwallowed = 0
nonisolated(unsafe) var dragUpSeen = false

func startDragTap() -> Bool {
    dragDX = 0; dragDY = 0; dragSwallowed = 0; dragUpSeen = false
    if let dragTap { CGEvent.tapEnable(tap: dragTap, enable: true); return true }
    let types: [CGEventType] = [.mouseMoved, .leftMouseDragged, .leftMouseUp, .leftMouseDown]
    let mask = types.reduce(CGEventMask(0)) { $0 | (1 << $1.rawValue) }
    guard let tap = CGEvent.tapCreate(tap: .cgSessionEventTap, place: .headInsertEventTap, options: .defaultTap,
                                      eventsOfInterest: mask, callback: { _, type, event, _ in
        if type == .tapDisabledByTimeout || type == .tapDisabledByUserInput {
            if let dragTap { CGEvent.tapEnable(tap: dragTap, enable: true) }
            return Unmanaged.passUnretained(event)
        }
        if EventInjector.isInjected(event) { return Unmanaged.passUnretained(event) }
        dragDX += event.getIntegerValueField(.mouseEventDeltaX)
        dragDY += event.getIntegerValueField(.mouseEventDeltaY)
        if type == .leftMouseUp { dragUpSeen = true }
        dragSwallowed += 1
        return nil
    }, userInfo: nil) else { return false }
    dragTap = tap
    CFRunLoopAddSource(CFRunLoopGetMain(), CFMachPortCreateRunLoopSource(nil, tap, 0), .commonModes)
    CGEvent.tapEnable(tap: tap, enable: true)
    return true
}

func stopDragTap() { if let dragTap { CGEvent.tapEnable(tap: dragTap, enable: false) } }

// MARK: Probe

@MainActor final class Probe: NSObject, NSApplicationDelegate {
    var window: NSWindow!
    let displays = VirtualDisplays()
    var counts: [NSEvent.EventType: Int] = [:]
    var deltaSum = 0.0
    var textEdit: NSRunningApplication?
    var launchedTextEdit = false
    var scratch: URL?
    var savedCursor = CGPoint.zero
    var helperPid: pid_t?

    func applicationDidFinishLaunching(_ notification: Notification) {
        savedCursor = CGEvent(source: nil)?.location ?? .zero
        let main = CGDisplayBounds(CGMainDisplayID())
        // NSWindow uses bottom-left coordinates of the main screen.
        let rect = NSRect(x: 80, y: main.height - 80 - 360, width: 520, height: 360)
        window = NSWindow(contentRect: rect, styleMask: [.titled], backing: .buffered, defer: false)
        window.title = "inputprobe (stands in for Minecraft)"
        window.acceptsMouseMovedEvents = true
        window.makeKeyAndOrderFront(nil)
        NSEvent.addLocalMonitorForEvents(matching: [.leftMouseDown, .leftMouseDragged, .leftMouseUp, .mouseMoved, .scrollWheel,
                                                    .rightMouseDown, .keyDown]) { [weak self] e in
            self?.counts[e.type, default: 0] += 1
            if e.type == .leftMouseDragged || e.type == .mouseMoved { self?.deltaSum += abs(e.deltaX) + abs(e.deltaY) }
            return e
        }
        Task { @MainActor in
            if let i = CommandLine.arguments.firstIndex(of: "--latency"), i + 1 < CommandLine.arguments.count {
                await self.runLatency(app: CommandLine.arguments[i + 1])
            } else if let i = CommandLine.arguments.firstIndex(of: "--helper"), i + 1 < CommandLine.arguments.count {
                await self.runHelper(app: CommandLine.arguments[i + 1])
            } else {
                await self.run()
            }
            await self.cleanup()
            let failed = results.filter { !$0.1 }.count
            let required = results.filter { !$0.1 && !$0.0.contains("[informational]") }.count
            print("\n\(results.count - failed) passed, \(failed) failed (\(required) required checks failed)")
            exit(required == 0 ? 0 : 1)
        }
    }

    var probeFrontmost: Bool { NSWorkspace.shared.frontmostApplication?.processIdentifier == getpid() }
    /// A point inside the probe's window (global, top-left) — where "Minecraft" receives the physical button.
    var probePoint: CGPoint {
        let main = CGDisplayBounds(CGMainDisplayID())
        let f = window.frame
        return CGPoint(x: f.midX, y: main.height - f.midY)
    }

    func activateProbe() async {
        NSApp.activate(ignoringOtherApps: true)
        window.makeKeyAndOrderFront(nil)
        for _ in 0..<20 where !probeFrontmost { await sleep(0.05) }
    }

    /// Minecraft's relative mouse mode: cursor hidden and disassociated from the mouse.
    func relativeMode(_ on: Bool) {
        CGAssociateMouseAndMouseCursorPosition(on ? 0 : 1)
    }

    /// "Physical" mouse events for the probe (HID route, deltas set like a real mouse in relative mode).
    /// A trackpad-like move by a fraction of a point (the integer delta fields stay 0).
    func physicalFine(_ type: CGEventType, dx: Double, dy: Double) {
        guard let e = CGEvent(mouseEventSource: hid, mouseType: type, mouseCursorPosition: probePoint, mouseButton: .left) else { return }
        e.setDoubleValueField(.mouseEventDeltaX, value: dx)
        e.setDoubleValueField(.mouseEventDeltaY, value: dy)
        e.post(tap: .cghidEventTap)
    }

    func physical(_ type: CGEventType, dx: Int64 = 0, dy: Int64 = 0) {
        guard let e = CGEvent(mouseEventSource: hid, mouseType: type, mouseCursorPosition: probePoint, mouseButton: .left) else { return }
        e.setIntegerValueField(.mouseEventDeltaX, value: dx)
        e.setIntegerValueField(.mouseEventDeltaY, value: dy)
        e.setDoubleValueField(.mouseEventDeltaX, value: Double(dx))
        e.setDoubleValueField(.mouseEventDeltaY, value: Double(dy))
        e.post(tap: .cghidEventTap)
    }

    /// A long scratch document in TextEdit (`open -a TextEdit`); closed again by cleanup().
    func openScratch() async -> (NSRunningApplication, AXUIElement)? {
        let dir = FileManager.default.temporaryDirectory.appendingPathComponent("office-inputprobe-\(getpid())")
        try? FileManager.default.createDirectory(at: dir, withIntermediateDirectories: true)
        let file = dir.appendingPathComponent("inputprobe.txt")
        scratch = dir
        let line = "The quick brown fox jumps over the lazy dog, line "
        try? (1...400).map { "\(line)\($0)" }.joined(separator: "\n").write(to: file, atomically: true, encoding: .utf8)
        launchedTextEdit = NSRunningApplication.runningApplications(withBundleIdentifier: "com.apple.TextEdit").isEmpty
        let open = Process()
        open.executableURL = URL(fileURLWithPath: "/usr/bin/open")
        open.arguments = ["-a", "TextEdit", file.path]
        try? open.run(); open.waitUntilExit()
        var axWindow: AXUIElement?
        for _ in 0..<80 {
            await sleep(0.1)
            textEdit = NSRunningApplication.runningApplications(withBundleIdentifier: "com.apple.TextEdit").first
            if let pid = textEdit?.processIdentifier,
               let wins = axAttr(AXUIElementCreateApplication(pid), kAXWindowsAttribute) as? [AXUIElement] {
                axWindow = wins.first { (axAttr($0, kAXTitleAttribute) as? String)?.contains("inputprobe") == true }
                if axWindow != nil { break }
            }
        }
        guard let te = textEdit, let win = axWindow else { check("TextEdit document opened", false); return nil }
        check("TextEdit document opened", true, "pid \(te.processIdentifier), launched by probe: \(launchedTextEdit)")
        return (te, win)
    }

    func run() async {
        await activateProbe()
        check("probe is frontmost at the start", probeFrontmost)

        // Two virtual displays, like holograms 1 and 2.
        guard let a = try? displays.create(DisplaySpec(slot: 1, width: 1920, height: 1080)),
              let b = try? displays.create(DisplaySpec(slot: 2, width: 1920, height: 1080)),
              await VirtualDisplays.waitOnline(a), await VirtualDisplays.waitOnline(b) else {
            check("virtual displays created", false); return
        }
        await sleep(1.0)
        let boundsA = CGDisplayBounds(a), boundsB = CGDisplayBounds(b)
        check("virtual displays created", true, "A id \(a) \(boundsA), B id \(b) \(boundsB)")

        guard let opened = await openScratch() else { return }
        let (te, win) = opened

        let home = CGRect(x: boundsA.minX + 300, y: boundsA.minY + 150, width: 900, height: 600)
        axSetFrame(win, home)
        await sleep(0.5)
        check("TextEdit window moved onto display A via AX", axFrame(win).map { boundsA.contains($0.origin) } ?? false, "\(axFrame(win) ?? .zero)")
        guard let scrollArea = axFind(win, role: kAXScrollAreaRole), let text = axFind(scrollArea, role: kAXTextAreaRole),
              let textFrame = axFrame(text), let scrollFrame = axFrame(scrollArea) else { check("found TextEdit's text area", false); return }
        note("text area \(textFrame), visible \(scrollFrame)")

        func reset() async {
            axSetFrame(win, home)
            axSetRange(text, CFRange(location: 0, length: 0))
            await activateProbe()
            await sleep(0.3)
        }
        func target(_ p: CGPoint) -> EventInjector.Target? { EventInjector.window(at: p, excluding: [getpid()]) }
        let textPoint = CGPoint(x: scrollFrame.minX + 60, y: scrollFrame.minY + 40)
        let titlePoint = CGPoint(x: home.minX + home.width * 0.75, y: home.minY + 12)

        // ---- A: post straight to TextEdit's pid while the probe stays frontmost (approach a)
        relativeMode(true)
        await reset()
        let cursorBefore = CGEvent(source: nil)?.location ?? .zero
        guard let t = target(textPoint) else { check("window under the text point is TextEdit", false); return }
        check("window under the text point is TextEdit", t.pid == te.processIdentifier, "window \(t.windowID)")
        var n = EventInjector.nextEventNumber()
        EventInjector.postToPid(.leftMouseDown, at: textPoint, button: .left, target: t, eventNumber: n)
        await sleep(0.05)
        EventInjector.postToPid(.leftMouseUp, at: textPoint, button: .left, target: t, eventNumber: n)
        await sleep(0.4)
        let caret = axRange(text)
        check("A1 [informational] postToPid click moves TextEdit's caret", (caret?.location ?? 0) > 0, "caret \(caret.map { "\($0.location)+\($0.length)" } ?? "?")")
        check("A1 probe (Minecraft) still frontmost after postToPid click", probeFrontmost,
              "frontmost \(NSWorkspace.shared.frontmostApplication?.localizedName ?? "?")")
        let cursorAfter = CGEvent(source: nil)?.location ?? .zero
        check("A1 postToPid leaves the real cursor where it was", hypot(cursorAfter.x - cursorBefore.x, cursorAfter.y - cursorBefore.y) < 1,
              "\(cursorBefore) → \(cursorAfter)")

        // A2: drag-select while the probe holds the "physical" button and receives the motion.
        await reset()
        counts = [:]; deltaSum = 0
        physical(.leftMouseDown)
        await sleep(0.1)
        n = EventInjector.nextEventNumber()
        EventInjector.postToPid(.leftMouseDown, at: textPoint, button: .left, target: t, eventNumber: n)
        for i in 1...20 {
            physical(.leftMouseDragged, dx: 6, dy: 2)
            let p = CGPoint(x: textPoint.x + Double(i) * 18, y: textPoint.y + Double(i) * 4)
            EventInjector.postToPid(.leftMouseDragged, at: p, button: .left, target: t, eventNumber: n)
            await sleep(0.03)
        }
        EventInjector.postToPid(.leftMouseUp, at: CGPoint(x: textPoint.x + 360, y: textPoint.y + 80), button: .left, target: t, eventNumber: n)
        await sleep(0.05)
        physical(.leftMouseUp)
        await sleep(0.4)
        let sel = axRange(text)
        check("A2 [informational] postToPid drag selects text in TextEdit", (sel?.length ?? 0) > 20, "selection \(sel.map { "\($0.location)+\($0.length)" } ?? "?")")
        check("A2 probe kept the physical button + motion during the drag",
              counts[.leftMouseDown] == 1 && (counts[.leftMouseDragged] ?? 0) >= 15 && counts[.leftMouseUp] == 1 && deltaSum > 50,
              "down \(counts[.leftMouseDown] ?? 0), dragged \(counts[.leftMouseDragged] ?? 0), up \(counts[.leftMouseUp] ?? 0), |delta| \(Int(deltaSum))")
        check("A2 probe (Minecraft) still frontmost after the drag", probeFrontmost)

        // A3: title-bar drag posted to the pid, from display A to display B.
        await reset()
        let before3 = axFrame(win) ?? .zero
        n = EventInjector.nextEventNumber()
        if let tt = target(titlePoint) {
            EventInjector.postToPid(.leftMouseDown, at: titlePoint, button: .left, target: tt, eventNumber: n)
            for i in 1...30 {
                let p = CGPoint(x: titlePoint.x + Double(i) * (boundsB.minX + 400 - titlePoint.x) / 30, y: titlePoint.y + Double(i) * 3)
                EventInjector.postToPid(.leftMouseDragged, at: p, button: .left, target: tt, eventNumber: n)
                await sleep(0.02)
            }
            EventInjector.postToPid(.leftMouseUp, at: CGPoint(x: boundsB.minX + 400, y: titlePoint.y + 90), button: .left, target: tt, eventNumber: n)
        }
        await sleep(0.5)
        let after3 = axFrame(win) ?? .zero
        let moved3 = hypot(after3.minX - before3.minX, after3.minY - before3.minY)
        check("A3 [informational] postToPid title-bar drag moves the window", moved3 > 50, "moved \(Int(moved3)) pt to \(after3.origin)")

        // A4: the helper's window drag: title-bar grab moved with Accessibility, across displays, probe frontmost.
        await reset()
        counts = [:]
        physical(.leftMouseDown)
        let start4 = axFrame(win) ?? .zero
        let grab = CGPoint(x: titlePoint.x - start4.minX, y: titlePoint.y - start4.minY)
        for i in 1...30 {
            physical(.leftMouseDragged, dx: 8, dy: 0)
            let p = CGPoint(x: titlePoint.x + Double(i) * (boundsB.minX + 600 - titlePoint.x) / 30, y: titlePoint.y + Double(i) * 2)
            axSetPosition(win, CGPoint(x: p.x - grab.x, y: p.y - grab.y))
            await sleep(0.02)
        }
        physical(.leftMouseUp)
        await sleep(0.4)
        let after4 = axFrame(win) ?? .zero
        check("A4 AX title-bar drag carries the window from display A onto display B", boundsB.contains(CGPoint(x: after4.midX, y: after4.minY + 5)),
              "window now at \(after4.origin)")
        check("A4 probe (Minecraft) frontmost and got the motion during the window drag", probeFrontmost && (counts[.leftMouseDragged] ?? 0) >= 25)

        // A5: scroll posted to the pid.
        await reset()
        if let s0 = axScrollValue(scrollArea) {
            for _ in 0..<5 { EventInjector.scroll(at: textPoint, dx: 0, dy: -5, target: t); await sleep(0.03) }
            await sleep(0.4)
            let s1 = axScrollValue(scrollArea) ?? s0
            check("A5 [informational] postToPid scroll scrolls TextEdit", s1 > s0 + 0.001, String(format: "scroll %.3f → %.3f", s0, s1))
            check("A5 probe (Minecraft) still frontmost after scrolling", probeFrontmost)
            if let bar = axAttr(scrollArea, kAXVerticalScrollBarAttribute) {
                AXUIElementSetAttributeValue(bar as! AXUIElement, kAXValueAttribute as CFString, NSNumber(value: 0))
            }
        } else {
            check("A5 TextEdit scroll bar readable", false)
        }

        // ---- B: HID down (TextEdit activates), re-activate the probe at once, HID drags + up (approach b)
        await reset()
        counts = [:]; deltaSum = 0
        EventInjector.postHID(.leftMouseDown, at: textPoint, button: .left)
        await sleep(0.05)
        let teActivated = NSWorkspace.shared.frontmostApplication?.processIdentifier == te.processIdentifier
        await activateProbe()
        for i in 1...20 {
            EventInjector.postHID(.leftMouseDragged, at: CGPoint(x: textPoint.x + Double(i) * 18, y: textPoint.y + Double(i) * 4), button: .left)
            await sleep(0.03)
        }
        EventInjector.postHID(.leftMouseUp, at: CGPoint(x: textPoint.x + 360, y: textPoint.y + 80), button: .left)
        await sleep(0.4)
        let selB = axRange(text)
        check("B [informational] HID down activated TextEdit", teActivated)
        check("B [informational] HID drag after re-activating the probe still selects", (selB?.length ?? 0) > 20, "selection \(selB.map { "\($0.location)+\($0.length)" } ?? "?")")
        check("B [informational] probe (Minecraft) frontmost at the end", probeFrontmost,
              "frontmost \(NSWorkspace.shared.frontmostApplication?.localizedName ?? "?"); probe saw down \(counts[.leftMouseDown] ?? 0) dragged \(counts[.leftMouseDragged] ?? 0)")

        // ---- C: plain HID title-bar drag across displays (approach c, the baseline)
        await reset()
        relativeMode(false)
        let before5 = axFrame(win) ?? .zero
        EventInjector.postHID(.mouseMoved, at: titlePoint, button: .left, clickState: 0)
        await sleep(0.05)
        EventInjector.postHID(.leftMouseDown, at: titlePoint, button: .left)
        for i in 1...30 {
            let p = CGPoint(x: titlePoint.x + Double(i) * (boundsB.minX + 400 - titlePoint.x) / 30, y: titlePoint.y + Double(i) * 3)
            EventInjector.postHID(.leftMouseDragged, at: p, button: .left)
            await sleep(0.02)
        }
        EventInjector.postHID(.leftMouseUp, at: CGPoint(x: boundsB.minX + 400, y: titlePoint.y + 90), button: .left)
        await sleep(0.5)
        let after5 = axFrame(win) ?? .zero
        check("C [informational] HID title-bar drag moves the window across displays",
              hypot(after5.minX - before5.minX, after5.minY - before5.minY) > 50, "now at \(after5.origin)")
        check("C [informational] …and the probe (Minecraft) lost the front", !probeFrontmost,
              "frontmost \(NSWorkspace.shared.frontmostApplication?.localizedName ?? "?")")

        // ---- V: postToPid with an AppKit-built event (window number + window-local location filled by NSEvent)
        await reset()
        relativeMode(true)
        if let tv = target(textPoint) {
            let f = tv.frame
            func local(_ p: CGPoint) -> NSPoint { NSPoint(x: p.x - f.minX, y: f.height - (p.y - f.minY)) }
            let num = Int(EventInjector.nextEventNumber() & 0x7fff_ffff)
            for type in [NSEvent.EventType.leftMouseDown, .leftMouseUp] {
                NSEvent.mouseEvent(with: type, location: local(textPoint), modifierFlags: [], timestamp: ProcessInfo.processInfo.systemUptime,
                                   windowNumber: Int(tv.windowID), context: nil, eventNumber: num, clickCount: 1, pressure: 1)?
                    .cgEvent?.postToPid(tv.pid)
                await sleep(0.05)
            }
            await sleep(0.4)
            let c = axRange(text)
            check("V [informational] NSEvent-built postToPid click moves the caret", (c?.location ?? 0) > 0,
                  "caret \(c.map { "\($0.location)" } ?? "?"), frontmost \(NSWorkspace.shared.frontmostApplication?.localizedName ?? "?")")
        }

        // ---- S: scrolling through the window server (scroll goes to the window under the point; never activates)
        await reset()
        if let s0 = axScrollValue(scrollArea) {
            for _ in 0..<5 { EventInjector.scrollHID(at: textPoint, dx: 0, dy: -200); await sleep(0.03) }
            await sleep(0.4)
            let s1 = axScrollValue(scrollArea) ?? s0
            check("S1 [informational] HID scroll with only the event location set scrolls TextEdit", s1 > s0 + 0.001, String(format: "%.3f → %.3f", s0, s1))
            if let bar = axAttr(scrollArea, kAXVerticalScrollBarAttribute) {
                AXUIElementSetAttributeValue(bar as! AXUIElement, kAXValueAttribute as CFString, NSNumber(value: 0))
            }
            await sleep(0.3)
            let t0 = axScrollValue(scrollArea) ?? 0
            let saved = CGEvent(source: nil)?.location ?? .zero
            CGWarpMouseCursorPosition(textPoint)
            for _ in 0..<5 { EventInjector.scrollHID(at: textPoint, dx: 0, dy: -200); await sleep(0.03) }
            CGWarpMouseCursorPosition(saved)
            await sleep(0.4)
            let t1 = axScrollValue(scrollArea) ?? t0
            check("S2 cursor warped to the point + HID scroll scrolls TextEdit", t1 > t0 + 0.001, String(format: "%.3f → %.3f", t0, t1))
            check("S2 probe (Minecraft) still frontmost after scrolling", probeFrontmost)
            if let bar = axAttr(scrollArea, kAXVerticalScrollBarAttribute) {
                AXUIElementSetAttributeValue(bar as! AXUIElement, kAXValueAttribute as CFString, NSNumber(value: 0))
            }
        }

        // ---- B2: approach (b) with time for the activation: HID down, re-activate the probe, HID drags + up
        await reset()
        EventInjector.postHID(.mouseMoved, at: textPoint, button: .left, clickState: 0)
        await sleep(0.05)
        EventInjector.postHID(.leftMouseDown, at: textPoint, button: .left)
        await sleep(0.25)
        let b2Activated = NSWorkspace.shared.frontmostApplication?.processIdentifier == te.processIdentifier
        await activateProbe()
        let b2Front = probeFrontmost
        for i in 1...20 {
            EventInjector.postHID(.leftMouseDragged, at: CGPoint(x: textPoint.x + Double(i) * 18, y: textPoint.y + Double(i) * 4), button: .left)
            await sleep(0.03)
        }
        EventInjector.postHID(.leftMouseUp, at: CGPoint(x: textPoint.x + 360, y: textPoint.y + 80), button: .left)
        await sleep(0.4)
        let selB2 = axRange(text)
        check("B2 [informational] HID down → re-activate Minecraft → HID drag still selects and Minecraft stays in front",
              (selB2?.length ?? 0) > 20 && probeFrontmost,
              "TextEdit activated \(b2Activated), probe back in front \(b2Front), selection \(selB2.map { "\($0.location)+\($0.length)" } ?? "?"), frontmost at end \(NSWorkspace.shared.frontmostApplication?.localizedName ?? "?")")

        // ---- H: the helper drives the drag. Minecraft (the probe) holds the physical button; the helper posts a real
        // (HID) down at the hologram point, takes the physical mouse with an event tap (motion → deltas for the mod to
        // turn the head with, the release → the drag's end), and posts real drags at the points the mod maps.
        func hDrag(from start: CGPoint, to end: CGPoint, steps: Int, activateFirst: Bool) async -> (ok: Bool, last: CGPoint, detail: String) {
            await reset()
            relativeMode(true)
            counts = [:]
            physical(.leftMouseDown) // the player presses the button in Minecraft
            await sleep(0.1)
            guard startDragTap() else { return (false, start, "drag tap not created") }
            var activation = ""
            if activateFirst, let under = target(start), let app = NSRunningApplication(processIdentifier: under.pid) {
                // the helper activates the app under the press first, so the down is not a "first mouse" click
                let t0 = Date()
                app.activate(from: NSRunningApplication.current, options: [])
                for _ in 0..<30 where NSWorkspace.shared.frontmostApplication?.processIdentifier != under.pid { await sleep(0.01) }
                activation = String(format: "activated in %.0f ms; ", Date().timeIntervalSince(t0) * 1000)
            }
            EventInjector.postHID(.mouseMoved, at: start, button: .left, clickState: 0)
            EventInjector.postHID(.leftMouseDown, at: start, button: .left)
            await sleep(0.1)
            let stepX = Int64(((end.x - start.x) / Double(steps)).rounded()), stepY = Int64(((end.y - start.y) / Double(steps)).rounded())
            var p = start
            for _ in 1...steps {
                physical(.leftMouseDragged, dx: stepX, dy: stepY) // the player turns their head
                await sleep(0.016)
                // the mod: head turned by the deltas → crosshair → hologram pixel → global point (here: start + deltas)
                p = CGPoint(x: start.x + Double(dragDX), y: start.y + Double(dragDY))
                EventInjector.postHID(.leftMouseDragged, at: p, button: .left)
            }
            physical(.leftMouseUp) // the player lets go
            await sleep(0.05)
            let upSeen = dragUpSeen
            EventInjector.postHID(.leftMouseUp, at: p, button: .left)
            await sleep(0.1)
            stopDragTap()
            let cursor = CGEvent(source: nil)?.location ?? .zero
            await activateProbe()
            let ok = upSeen && dragSwallowed >= steps && probeFrontmost && (counts[.leftMouseDragged] ?? 0) == 0
            return (ok, p, activation + "tap took \(dragSwallowed) physical events (Δ \(dragDX),\(dragDY)), release seen \(upSeen), cursor at \(Int(cursor.x)),\(Int(cursor.y)); probe got \(counts[.leftMouseDragged] ?? 0) drags; Minecraft back in front \(probeFrontmost)")
        }
        let h1 = await hDrag(from: textPoint, to: CGPoint(x: textPoint.x + 360, y: textPoint.y + 80), steps: 20, activateFirst: false)
        let selH = axRange(text)
        check("H1 [informational] helper-driven drag into an INACTIVE text view selects (macOS: first click only activates)",
              (selH?.length ?? 0) > 20, "selection \(selH.map { "\($0.location)+\($0.length)" } ?? "?")")
        check("H1 the tap owns the physical mouse during the drag, Minecraft is back after", h1.ok, h1.detail)
        let h1b = await hDrag(from: textPoint, to: CGPoint(x: textPoint.x + 360, y: textPoint.y + 80), steps: 20, activateFirst: true)
        let selH1b = axRange(text)
        check("H1b helper-driven drag (app activated first) selects text", (selH1b?.length ?? 0) > 20,
              "selection \(selH1b.map { "\($0.location)+\($0.length)" } ?? "?")")
        check("H1b the tap owns the physical mouse during the drag, Minecraft is back after", h1b.ok, h1b.detail)
        let titleNow = CGPoint(x: home.minX + home.width * 0.75, y: home.minY + 12)
        let titleEnd = CGPoint(x: boundsB.minX + 700, y: boundsB.minY + 100)
        let h2 = await hDrag(from: titleNow, to: titleEnd, steps: 40, activateFirst: true)
        let afterH2 = axFrame(win) ?? .zero
        let grabbed = CGPoint(x: afterH2.minX + (titleNow.x - home.minX), y: afterH2.minY + (titleNow.y - home.minY))
        check("H2 helper-driven title-bar drag carries the window from display A onto display B (window server drag)",
              boundsB.contains(grabbed) && boundsB.contains(CGPoint(x: afterH2.midX, y: afterH2.minY + 5)),
              "window at \(afterH2.origin), grab point now \(grabbed)")
        check("H2 the tap owns the physical mouse during the drag, Minecraft is back after", h2.ok, h2.detail)

        // ---- T: typing mode — a real click enters the app; keys go there; the Esc tap swallows Esc.
        await reset()
        relativeMode(true)
        axSetRange(text, CFRange(location: 0, length: 0))
        EventInjector.hidClick(at: textPoint, button: .left)
        await sleep(0.5)
        check("T1 HID click activates TextEdit (typing mode)", NSWorkspace.shared.frontmostApplication?.processIdentifier == te.processIdentifier)
        let cursorT = CGEvent(source: nil)?.location ?? .zero
        check("T1 the real cursor is at the click point (on display A)", hypot(cursorT.x - textPoint.x, cursorT.y - textPoint.y) < 2, "\(cursorT)")
        let tapOK = startTap()
        check("T2 session event tap created (Accessibility)", tapOK)
        swallowCodes = [53, 12] // Esc and, to prove swallowing, the "q" key
        let before = (axAttr(text, kAXValueAttribute) as? String) ?? ""
        for code: CGKeyCode in [0, 12, 11] { key(code); await sleep(0.05) } // a q b
        key(53)
        await sleep(0.5)
        let after = (axAttr(text, kAXValueAttribute) as? String) ?? ""
        let inserted = after.count - before.count
        check("T2 keys reach the app; the tap swallows what it should", after.contains("abThe") && !after.contains("aqb") && inserted == 2,
              "inserted \(inserted) chars, starts \"\(after.prefix(8))\"")
        check("T2 Esc seen and swallowed by the tap", (swallowed[53] ?? 0) == 1, "Esc downs swallowed: \(swallowed[53] ?? 0)")
        check("T2 TextEdit still frontmost after Esc (the app never saw it)", NSWorkspace.shared.frontmostApplication?.processIdentifier == te.processIdentifier)
        swallowCodes = []
        if let escTap { CGEvent.tapEnable(tap: escTap, enable: false) }
        await activateProbe()
        check("T3 re-activating the probe (Minecraft) after Esc", probeFrontmost)
        relativeMode(false)
        // Undo the typing so TextEdit closes without asking (the file is scratch anyway).
        if let value = axAttr(text, kAXValueAttribute) as? String, value.hasPrefix("ab") {
            AXUIElementSetAttributeValue(text, kAXValueAttribute as CFString, String(value.dropFirst(2)) as CFString)
        }
    }

    func cleanup() async {
        relativeMode(false)
        if let te = textEdit {
            if launchedTextEdit {
                te.terminate()
                for _ in 0..<30 where !te.isTerminated { await sleep(0.1) }
                if !te.isTerminated { te.forceTerminate() }
            } else if let wins = axAttr(AXUIElementCreateApplication(te.processIdentifier), kAXWindowsAttribute) as? [AXUIElement],
                      let mine = wins.first(where: { (axAttr($0, kAXTitleAttribute) as? String)?.contains("inputprobe") == true }),
                      let close = axAttr(mine, kAXCloseButtonAttribute) {
                AXUIElementPerformAction(close as! AXUIElement, kAXPressAction as CFString)
                await sleep(0.5)
            }
            print("     TextEdit \(launchedTextEdit ? (te.isTerminated ? "quit" : "STILL RUNNING") : "document closed")")
        }
        displays.removeAll()
        if let scratch { try? FileManager.default.removeItem(at: scratch) }
        CGWarpMouseCursorPosition(savedCursor)
        CGAssociateMouseAndMouseCursorPosition(1)
        await sleep(0.3)
        var count: UInt32 = 0
        CGGetOnlineDisplayList(0, nil, &count)
        print("     cleanup: displays online now \(count); cursor back at \(savedCursor)")
    }
}

@main struct InputProbeMain {
    @MainActor static func main() {
        let app = NSApplication.shared
        let probe = Probe()
        app.delegate = probe
        app.setActivationPolicy(.regular)
        withExtendedLifetime(probe) { app.run() }
    }
}
