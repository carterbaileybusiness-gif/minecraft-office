// cursorprobe <OfficeHelper.app> <devclient.py>: is the system cursor really hidden in work mode (spec 5.10 round 2)?
// A stand-in for Minecraft (a regular app whose borderless floating window covers the main display, click-through)
// over a TextEdit window; the REAL signed helper gets WORK_BEGIN on the Mirror (slot 0) from devclient.py. The cursor
// is looked for with `screencapture -C` around it while working (expected: only the stand-in's colour) and after
// EXIT_TYPING (expected: a cursor again). Prints PASS/FAIL; exit 1 on a failure. Run: helper-mac/Tools/cursorprobe.sh.
// (inputprobe's W1/W4 check the same inside its longer run.) PROBE_KEEP_SHOTS=<dir> keeps the screenshots.
import AppKit

let app = NSApplication.shared
app.setActivationPolicy(.regular)
func pump(_ s: Double) { RunLoop.current.run(until: Date().addingTimeInterval(s)) }

/// More than a few pixels that aren't the stand-in's colour in a 48-pt square at the cursor = a cursor is drawn.
func cursorDrawn(at p: CGPoint) -> Bool {
    let file = FileManager.default.temporaryDirectory.appendingPathComponent("office-cursorprobe-\(getpid()).png")
    let t = Process()
    t.executableURL = URL(fileURLWithPath: "/usr/sbin/screencapture")
    t.arguments = ["-x", "-C", "-R", "\(Int(p.x) - 8),\(Int(p.y) - 8),48,48", file.path]
    try? t.run(); t.waitUntilExit()
    defer {
        if let keep = ProcessInfo.processInfo.environment["PROBE_KEEP_SHOTS"] {
            try? FileManager.default.copyItem(at: file, to: URL(fileURLWithPath: keep).appendingPathComponent("cursorprobe-\(Int(Date().timeIntervalSince1970 * 1000)).png"))
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
    if ProcessInfo.processInfo.environment["PROBE_KEEP_SHOTS"] != nil { print("     \(odd) non-stand-in pixels") }
    return odd > 20
}

let helper = CommandLine.arguments[1], devclient = CommandLine.arguments[2]
if !NSRunningApplication.runningApplications(withBundleIdentifier: "local.carter.office-helper").isEmpty {
    print("OfficeHelper is running (a game in progress?): not probing"); exit(2)
}
let file = FileManager.default.temporaryDirectory.appendingPathComponent("office-cursorprobe.txt")
try? String(repeating: "hello hidden cursor\n", count: 50).write(to: file, atomically: true, encoding: .utf8)
let launchedTextEdit = NSRunningApplication.runningApplications(withBundleIdentifier: "com.apple.TextEdit").isEmpty
let open = Process(); open.executableURL = URL(fileURLWithPath: "/usr/bin/open"); open.arguments = ["-a", "TextEdit", file.path]
try? open.run(); open.waitUntilExit()
pump(2)
guard let te = NSRunningApplication.runningApplications(withBundleIdentifier: "com.apple.TextEdit").first,
      let rows = CGWindowListCopyWindowInfo([.optionOnScreenOnly], kCGNullWindowID) as? [[String: Any]],
      let w = rows.first(where: { ($0[kCGWindowOwnerPID as String] as? NSNumber)?.int32Value == te.processIdentifier
          && ($0[kCGWindowLayer as String] as? NSNumber)?.intValue == 0 }),
      let b = CGRect(dictionaryRepresentation: w[kCGWindowBounds as String] as! CFDictionary) else { print("FAIL TextEdit window"); exit(1) }
let p = CGPoint(x: b.minX + 120, y: b.minY + 120)
let main = CGDisplayBounds(CGMainDisplayID())
let win = NSWindow(contentRect: NSScreen.screens[0].frame, styleMask: [.borderless], backing: .buffered, defer: false)
win.level = .floating
win.backgroundColor = NSColor(calibratedRed: 0.32, green: 0.55, blue: 0.29, alpha: 1)
win.orderFrontRegardless()
NSApp.activate(ignoringOtherApps: true)
win.makeKeyAndOrderFront(nil)
pump(0.5)
win.ignoresMouseEvents = true // the mod: work mode = floating + click-through
let h = Process(); h.executableURL = URL(fileURLWithPath: "/usr/bin/open"); h.arguments = ["-g", helper]; try? h.run(); h.waitUntilExit()
pump(2)
let x = Int(p.x * 1920 / main.width), y = Int(p.y * 1080 / main.height)
let d = Process(); d.executableURL = URL(fileURLWithPath: "/usr/bin/python3")
d.arguments = ["-I", devclient, "work", "0", "\(x)", "\(y)", "sleep", "4", "exittyping"]
d.standardOutput = FileHandle.nullDevice
try? d.run()
pump(2)
let during = CGEvent(source: nil)?.location ?? p
let hidden = !cursorDrawn(at: during)
var failed = 0
print("\(hidden ? "PASS" : "FAIL") work mode on the Mirror: the system cursor is hidden (cursor at \(during), frontmost \(NSWorkspace.shared.frontmostApplication?.localizedName ?? "?"))")
if !hidden { failed += 1 }
d.waitUntilExit()
pump(0.6)
let shown = cursorDrawn(at: CGEvent(source: nil)?.location ?? p)
print("\(shown ? "PASS" : "FAIL") after EXIT_TYPING the cursor is drawn again")
if !shown { failed += 1 }
win.orderOut(nil)
if launchedTextEdit { te.terminate() }
try? FileManager.default.removeItem(at: file)
exit(failed == 0 ? 0 : 1)
