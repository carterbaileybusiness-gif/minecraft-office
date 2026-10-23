import Foundation

enum HotkeyLogicTests {
    static let esc: UInt16 = 53

    static func runAll() {
        run("zoom style follows what the surface shows") {
            let mirror = ZoomStyle(kind: .main), display = ZoomStyle(kind: .display), window = ZoomStyle(kind: .window)
            expect(mirror.hidesMinecraft && !mirror.clampsCursor && !mirror.placesWindow, "Mirror: Minecraft steps aside, no clamp")
            expect(mirror.exitsWhenMinecraftActivates, "Mirror: clicking back into Minecraft exits")
            expect(display.clampsCursor && !display.hidesMinecraft && !display.exitsWhenMinecraftActivates, "Extend: cursor clamp only")
            expect(window.placesWindow && !window.hidesMinecraft && !window.clampsCursor, "Window: placement only")
        }

        run("Mirror never captures without Minecraft excluded") {
            expectEqual(MirrorExclusion.decide(found: true, attempt: 0), .capture)
            expectEqual(MirrorExclusion.decide(found: false, attempt: 0), .retry(after: MirrorExclusion.delays[0]))
            let last = MirrorExclusion.delays.count
            expectEqual(MirrorExclusion.decide(found: false, attempt: last), .fail, "gives up instead of showing Minecraft in itself")
            expectEqual(MirrorExclusion.decide(found: true, attempt: last), .capture, "found on the last try")
            let total = MirrorExclusion.delays.reduce(0, +)
            expect(total >= 4 && total <= 6, "about 5 s in all, got \(total)")
            expect(zip(MirrorExclusion.delays, MirrorExclusion.delays.dropFirst()).allSatisfy { $0 <= $1 }, "backs off")
        }

        run("Minecraft is never left hidden") {
            var guardState = MinecraftHideGuard()
            expect(!guardState.shouldUnhide, "nothing hidden yet")
            guardState.hid()
            expect(guardState.isHidden, "hidden while zoomed into Mirror")
            expect(guardState.shouldUnhide, "every way out (exit, disconnect, error, quit) must unhide")
            guardState.unhid()
            expect(!guardState.shouldUnhide, "unhidden once")
            guardState.hid(); guardState.hid()
            guardState.unhid()
            expect(!guardState.isHidden, "a second hide is still one unhide")
        }

        run("double Esc within 350 ms exits") {
            var d = ExitChordDetector()
            expect(!d.keyDown(keyCode: esc, modifiers: [], time: 10.0, isRepeat: false), "first Esc alone does nothing")
            expect(d.keyDown(keyCode: esc, modifiers: [], time: 10.35, isRepeat: false), "second Esc at 350 ms exits")
            // A third press starts over rather than chaining.
            expect(!d.keyDown(keyCode: esc, modifiers: [], time: 10.5, isRepeat: false), "detector resets after firing")
        }

        run("slow, repeated, or interrupted Esc does not exit") {
            var d = ExitChordDetector()
            _ = d.keyDown(keyCode: esc, modifiers: [], time: 1.0, isRepeat: false)
            expect(!d.keyDown(keyCode: esc, modifiers: [], time: 1.351, isRepeat: false), "351 ms is too slow")
            expect(d.keyDown(keyCode: esc, modifiers: [], time: 1.6, isRepeat: false), "but that press starts a new pair")

            var r = ExitChordDetector()
            _ = r.keyDown(keyCode: esc, modifiers: [], time: 2.0, isRepeat: false)
            expect(!r.keyDown(keyCode: esc, modifiers: [], time: 2.05, isRepeat: true), "auto-repeat is not a second tap")

            var i = ExitChordDetector()
            _ = i.keyDown(keyCode: esc, modifiers: [], time: 3.0, isRepeat: false)
            _ = i.keyDown(keyCode: 0, modifiers: [], time: 3.1, isRepeat: false) // "a"
            expect(!i.keyDown(keyCode: esc, modifiers: [], time: 3.2, isRepeat: false), "another key between breaks the pair")

            var m = ExitChordDetector()
            _ = m.keyDown(keyCode: esc, modifiers: [.shift], time: 4.0, isRepeat: false)
            expect(!m.keyDown(keyCode: esc, modifiers: [], time: 4.1, isRepeat: false), "modified Esc is not a tap")
        }

        run("Ctrl+Alt+Cmd+Esc always exits") {
            var d = ExitChordDetector()
            expect(d.keyDown(keyCode: esc, modifiers: [.control, .option, .command], time: 5, isRepeat: false), "fallback chord")
            expect(d.keyDown(keyCode: esc, modifiers: [.control, .option, .command, .shift], time: 9, isRepeat: false), "extra shift ok")
            expect(!d.keyDown(keyCode: esc, modifiers: [.control, .command], time: 12, isRepeat: false), "missing option")
        }

        run("cursor clamp keeps the pointer inside the display") {
            let bounds = CGRect(x: 1440, y: 0, width: 1920, height: 1080)
            expect(CursorClamp.clamp(CGPoint(x: 2000, y: 500), to: bounds) == nil, "inside: no warp")
            expectEqual(CursorClamp.clamp(CGPoint(x: 100, y: 500), to: bounds), CGPoint(x: 1440, y: 500))
            expectEqual(CursorClamp.clamp(CGPoint(x: 4000, y: -5), to: bounds), CGPoint(x: 3359, y: 0))
            expectEqual(CursorClamp.clamp(CGPoint(x: 3360, y: 1080), to: bounds), CGPoint(x: 3359, y: 1079))
            expectEqual(CursorClamp.center(of: bounds), CGPoint(x: 2400, y: 540))
        }

        run("clamp releases when the exit hotkey can no longer fire") {
            // Secure Event Input (a password field) hides keys from the global monitor: release at once.
            var secure = ClampGuard()
            expect(secure.shouldRelease(secureInput: true, accessibilityTrusted: { true }), "secure input releases")

            // Accessibility revoked: noticed on the first tick...
            var revokedAtStart = ClampGuard()
            expect(revokedAtStart.shouldRelease(secureInput: false, accessibilityTrusted: { false }), "untrusted releases")

            // ...and within one check interval (~100 ms at 120 Hz) when revoked mid-zoom, without asking every tick.
            var g = ClampGuard()
            var asks = 0
            var trusted = true
            var releasedAt: Int?
            for tick in 0..<60 {
                if tick == 20 { trusted = false }
                if g.shouldRelease(secureInput: false, accessibilityTrusted: { asks += 1; return trusted }) { releasedAt = tick; break }
            }
            expect(releasedAt != nil, "revocation noticed")
            expect((releasedAt ?? 99) - 20 < ClampGuard.permissionCheckInterval, "noticed within one interval, got tick \(releasedAt ?? -1)")
            expect(asks <= 20 / ClampGuard.permissionCheckInterval + 2, "permission not polled every tick (\(asks) asks)")

            // Trusted and no secure input: keeps holding.
            var ok = ClampGuard()
            var held = true
            for _ in 0..<240 { if ok.shouldRelease(secureInput: false, accessibilityTrusted: { true }) { held = false } }
            expect(held, "holds while the hotkey works")
        }
    }
}
