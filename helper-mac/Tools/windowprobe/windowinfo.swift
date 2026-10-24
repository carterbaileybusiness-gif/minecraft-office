// windowinfo <pid> <x> <y>: the on-screen windows of <pid> (layer, bounds in global points) and which app's window a
// click at (x, y) (global, top-left) would hit right now — as the window server sees it, so a window that ignores mouse
// events is skipped. Used by WindowProbe.java (helper-mac/Tools/windowprobe/run.sh).
import AppKit

let args = CommandLine.arguments
guard args.count == 4, let pid = Int32(args[1]), let x = Double(args[2]), let y = Double(args[3]) else {
    print("usage: windowinfo <pid> <x> <y>"); exit(2)
}
_ = NSApplication.shared // a window-server connection, which NSWindow.windowNumber(at:) needs
let rows = CGWindowListCopyWindowInfo([.optionOnScreenOnly], kCGNullWindowID) as? [[String: Any]] ?? []
for row in rows where (row[kCGWindowOwnerPID as String] as? NSNumber)?.int32Value == pid {
    let layer = (row[kCGWindowLayer as String] as? NSNumber)?.intValue ?? -1
    let b = (row[kCGWindowBounds as String] as? [String: Any]).flatMap { CGRect(dictionaryRepresentation: $0 as CFDictionary) } ?? .zero
    print("window layer=\(layer) bounds=\(Int(b.minX)),\(Int(b.minY)),\(Int(b.width)),\(Int(b.height))")
}
let primaryHeight = CGDisplayBounds(CGMainDisplayID()).height
let number = NSWindow.windowNumber(at: NSPoint(x: x, y: primaryHeight - y), belowWindowWithWindowNumber: 0)
var hit: Int32 = 0
if number > 0, let info = CGWindowListCopyWindowInfo([.optionIncludingWindow], CGWindowID(number)) as? [[String: Any]] {
    hit = (info.first?[kCGWindowOwnerPID as String] as? NSNumber)?.int32Value ?? 0
}
let main = CGDisplayBounds(CGMainDisplayID())
print("hit=\(hit) main=\(Int(main.minX)),\(Int(main.minY)),\(Int(main.width)),\(Int(main.height))")
