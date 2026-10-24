// inputprobe --cpu <OfficeHelper.app>: what own screens cost the Mac (round-2 fix round, "load"). The probe plays the
// mod with a Mirror and three Extend screens: it creates Office Displays 1-3, puts a 960x540 window repainting at
// 60 Hz (a video, say) on each of them and on the laptop screen, captures all four, answers every FRAME / PATCH with
// READY like the mod does (without copying pixels), and measures the helper's CPU time and the frames it delivers:
// - before: every screen at 60 fps (round 2 while working),
// - after: the screen the cursor is on at 60, the others at 30 — changed live with RATE, no new stream.
// Prints one CPU line per phase.
import AppKit
import Darwin

/// User + system CPU seconds of a process so far (proc_pid_rusage; nanoseconds after the timebase conversion).
func cpuSeconds(_ pid: pid_t) -> Double? {
    var info = rusage_info_v2()
    let ok = withUnsafeMutablePointer(to: &info) {
        $0.withMemoryRebound(to: rusage_info_t?.self, capacity: 1) { proc_pid_rusage(pid, RUSAGE_INFO_V2, $0) }
    }
    guard ok == 0 else { return nil }
    var tb = mach_timebase_info_data_t()
    mach_timebase_info(&tb)
    let ticks = Double(info.ri_user_time + info.ri_system_time)
    return ticks * Double(tb.numer) / Double(tb.denom) / 1e9
}

extension Probe {
    func runCPU(app: String) async {
        if !NSRunningApplication.runningApplications(withBundleIdentifier: "local.carter.office-helper").isEmpty {
            check("no other OfficeHelper is running (Carter's game?) — not touching it", false); return
        }
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
        defer { client.close() }
        var welcome = false
        var displays: [Int: CGDirectDisplayID] = [:]
        var frames: [Int: Int] = [:]
        var bytes = 0
        client.onMessage = { message in
            switch message {
            case .welcome: welcome = true
            case .display(let d): displays[d.slot] = d.displayId
            case .frame(let f):
                frames[Int(f.surface), default: 0] += 1
                bytes += f.rgba.count
                client.send(.ready(SurfacePayload(surface: Int(f.surface))))
            case .patch(let p):
                frames[Int(p.surface), default: 0] += 1
                bytes += p.rgba.count
                client.send(.ready(SurfacePayload(surface: Int(p.surface))))
            default: break
            }
        }
        client.startReading()
        client.send(.hello(HelloPayload(token: token, version: OfficeProtocol.version, mcPid: getpid())))
        for _ in 0..<50 where !welcome { await sleep(0.1) }
        for slot in 1...3 { client.send(.createDisplay(CreateDisplayPayload(slot: slot, w: 1920, h: 1080))) }
        for _ in 0..<80 where displays.count < 3 { await sleep(0.1) }
        guard displays.count == 3,
              let helperPid = NSRunningApplication.runningApplications(withBundleIdentifier: "local.carter.office-helper").first?.processIdentifier
        else { check("C displays 1-3 created", false); return }
        await sleep(1.5)

        // a 960x540 window repainting at 60 Hz on the laptop screen and on each Office display
        let primaryHeight = CGDisplayBounds(CGMainDisplayID()).height
        var movers: [NSWindow] = []
        for id in [CGMainDisplayID()] + (1...3).compactMap({ displays[$0] }) {
            let b = CGDisplayBounds(id)
            let w = NSWindow(contentRect: NSRect(x: b.minX + 100, y: primaryHeight - (b.minY + 100 + 540), width: 960, height: 540),
                             styleMask: [.borderless], backing: .buffered, defer: false)
            w.isReleasedWhenClosed = false
            w.level = .floating
            w.ignoresMouseEvents = true
            w.orderFrontRegardless()
            movers.append(w)
        }
        var k = 0
        let paint = Timer(timeInterval: 1.0 / 60, repeats: true) { _ in
            MainActor.assumeIsolated {
                k += 1
                for (i, w) in movers.enumerated() {
                    w.backgroundColor = NSColor(srgbRed: Double((k * 5 + i * 40) % 256) / 255, green: Double((k * 3) % 256) / 255,
                                                blue: Double((k * 7 + i * 90) % 256) / 255, alpha: 1)
                    w.display()
                }
            }
        }
        RunLoop.main.add(paint, forMode: .common)
        defer { paint.invalidate(); movers.forEach { $0.orderOut(nil) } }

        // the Mirror (surface 1, the laptop screen) and three Extend screens (surfaces 2-4, displays 1-3), all at 60
        client.send(.capture(CapturePayload(surface: 1, kind: .main, slot: nil, windowKey: nil, maxW: 1920, fps: 60)))
        for slot in 1...3 {
            client.send(.capture(CapturePayload(surface: 1 + slot, kind: .display, slot: slot, windowKey: nil, maxW: 1920, fps: 60)))
        }
        await sleep(3.0)

        func phase(_ name: String, seconds: Double) async -> Double {
            frames = [:]
            bytes = 0
            let h0 = cpuSeconds(helperPid) ?? 0, me0 = cpuSeconds(getpid()) ?? 0
            let start = Date()
            await sleep(seconds)
            let t = Date().timeIntervalSince(start)
            let helper = ((cpuSeconds(helperPid) ?? 0) - h0) / t * 100
            let me = ((cpuSeconds(getpid()) ?? 0) - me0) / t * 100
            let rates = (1...4).map { String(format: "%.0f", Double(frames[$0] ?? 0) / t) }.joined(separator: "/")
            let line = String(format: "CPU %@: helper %.1f %% of a core, probe (mod stand-in + painting) %.1f %%; frames/s Mirror/1/2/3 = %@; %.0f MB/s over the socket",
                              name, helper, me, rates, Double(bytes) / 1e6 / t)
            print(line); fflush(stdout)
            return helper
        }
        let before = await phase("before (Mirror + 3 Extend all at 60 fps)", seconds: 10)
        for surface in 2...4 { client.send(.rate(RatePayload(surface: surface, fps: 30))) } // the cursor is on the Mirror
        await sleep(2.0)
        let after = await phase("after (Mirror 60 where the cursor is, 3 Extend at 30, via RATE)", seconds: 10)
        check("C fewer helper CPU with only the cursor's screen at 60 fps", after < before,
              String(format: "%.1f %% → %.1f %% of a core", before, after))
        for surface in 1...4 { client.send(.stop(SurfacePayload(surface: surface))) }
        await sleep(0.3)
    }
}
