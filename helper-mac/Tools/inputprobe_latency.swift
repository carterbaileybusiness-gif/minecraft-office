// inputprobe --latency <OfficeHelper.app>: how fast an own screen updates through the real helper (spec 5.10 round 2,
// "the picture lags"). The probe plays the mod: it creates Office Display 1, puts its own window on it, captures the
// display at a requested frame rate and answers every FRAME / PATCH with READY like the mod does. It then
// - changes the window's colour every 150 ms and times how long until a frame showing the new colour arrives
//   (capture → helper → socket → decoded, the helper's whole share of capture→texture), and
// - repaints it every frame for 3 s and counts frames per second.
// Run for 5 fps (the mod's old rate for own screens), 30 and 60. Prints one LATENCY line per rate.
import AppKit

extension Probe {
    func runLatency(app: String) async {
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

        // The frame as the mod would hold it, plus arrival times of what changed at the sample pixel.
        var width = 0, height = 0
        var pixels = [UInt8]()
        var frames = 0
        var sampleAt = (x: 0, y: 0)
        var sampleSeen: [(time: Date, rgb: (UInt8, UInt8, UInt8))] = []
        var welcome = false, displayID: CGDirectDisplayID?
        func sample() -> (UInt8, UInt8, UInt8)? {
            guard width > 0, sampleAt.x < width, sampleAt.y < height else { return nil }
            let i = (sampleAt.y * width + sampleAt.x) * 4
            return (pixels[i], pixels[i + 1], pixels[i + 2])
        }
        client.onMessage = { message in
            switch message {
            case .welcome: welcome = true
            case .display(let d): displayID = d.displayId
            case .frame(let f):
                width = Int(f.width); height = Int(f.height); pixels = [UInt8](f.rgba)
                frames += 1
                if let s = sample() { sampleSeen.append((Date(), s)) }
                client.send(.ready(SurfacePayload(surface: Int(f.surface))))
            case .patch(let p):
                if Int(p.width) == width && Int(p.height) == height {
                    var offset = 0
                    let src = [UInt8](p.rgba)
                    for r in p.rects {
                        for row in 0..<Int(r.h) {
                            let dst = ((Int(r.y) + row) * width + Int(r.x)) * 4
                            pixels.replaceSubrange(dst..<(dst + Int(r.w) * 4), with: src[offset..<(offset + Int(r.w) * 4)])
                            offset += Int(r.w) * 4
                        }
                    }
                }
                frames += 1
                if let s = sample() { sampleSeen.append((Date(), s)) }
                client.send(.ready(SurfacePayload(surface: Int(p.surface))))
            default: break
            }
        }
        client.startReading()
        client.send(.hello(HelloPayload(token: token, version: OfficeProtocol.version, mcPid: getpid())))
        for _ in 0..<50 where !welcome { await sleep(0.1) }
        client.send(.createDisplay(CreateDisplayPayload(slot: 1, w: 1920, h: 1080)))
        for _ in 0..<50 where displayID == nil { await sleep(0.1) }
        guard let displayID else { check("L display 1 created", false); return }
        await sleep(1.0)
        let bounds = CGDisplayBounds(displayID)
        let primaryHeight = CGDisplayBounds(CGMainDisplayID()).height
        // the probe's window on display 1 at (200, 200), 600 × 400; its centre is the sample pixel (a 1× display)
        window.styleMask = [.borderless]
        window.level = .normal
        window.setFrame(NSRect(x: bounds.minX + 200, y: primaryHeight - (bounds.minY + 200 + 400), width: 600, height: 400), display: true)
        window.orderFrontRegardless()
        sampleAt = (x: 500, y: 400)
        func paint(_ r: UInt8, _ g: UInt8, _ b: UInt8) {
            window.backgroundColor = NSColor(srgbRed: Double(r) / 255, green: Double(g) / 255, blue: Double(b) / 255, alpha: 1)
            window.display()
        }
        paint(0, 0, 0)
        await sleep(0.5)

        var surface = 100
        for fps in [5, 30, 60] {
            surface += 1
            frames = 0
            sampleSeen.removeAll()
            client.send(.capture(CapturePayload(surface: surface, kind: .display, slot: 1, windowKey: nil, maxW: 1920, fps: fps)))
            await sleep(1.0)
            // latency: a new colour every 150 ms (300 ms at 5 fps), time to the first frame showing it
            var latencies: [Double] = []
            for i in 1...16 {
                let r = UInt8(40 + (i * 13) % 200), g = UInt8(200 - (i * 7) % 150), b = UInt8((i * 29) % 255)
                let changed = Date()
                paint(r, g, b)
                let wait = fps <= 5 ? 0.6 : 0.25
                await sleep(wait)
                if let hit = sampleSeen.first(where: { $0.time >= changed && abs(Int($0.rgb.0) - Int(r)) < 8 && abs(Int($0.rgb.1) - Int(g)) < 8
                                                     && abs(Int($0.rgb.2) - Int(b)) < 8 }) {
                    latencies.append(hit.time.timeIntervalSince(changed) * 1000)
                }
            }
            // frame rate: repaint every 8 ms for 3 s
            frames = 0
            let start = Date()
            var k = 0
            while Date().timeIntervalSince(start) < 3 {
                k += 1
                paint(UInt8(k % 256), UInt8((k * 3) % 256), 128)
                await sleep(0.008)
            }
            let rate = Double(frames) / Date().timeIntervalSince(start)
            client.send(.stop(SurfacePayload(surface: surface)))
            await sleep(0.3)
            let sorted = latencies.sorted()
            let median = sorted.isEmpty ? .nan : sorted[sorted.count / 2]
            let p90 = sorted.isEmpty ? .nan : sorted[min(sorted.count - 1, Int(Double(sorted.count) * 0.9))]
            let line = String(format: "LATENCY capture %2d fps: change → frame median %5.1f ms, p90 %5.1f ms (%d/16 seen); %4.1f frames/s",
                              fps, median, p90, latencies.count, rate)
            print(line); fflush(stdout)
            check("L \(fps) fps measured", latencies.count >= 12, line)
        }
        standIn(false)
    }
}
