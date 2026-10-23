import Foundation

/// In-world use: display pixel → global point, the helper-driven drag, typing mode, moving windows on join / leave.
enum PointerTests {
    static func runAll() {
        run("pointer messages need their button; moves don't") {
            let noButton = Data(#"{"slot":1,"x":2,"y":3}"#.utf8)
            for type in [MessageType.pointerDown, .pointerUp, .click] {
                expectThrows("\(type) without button") { _ = try Message.decode(type: type, payload: noButton) }
            }
            expectEqual(try Message.decode(type: .pointerMove, payload: noButton), .pointerMove(PointerPayload(slot: 1, x: 2, y: 3, button: nil)))
            expectThrows("unknown button") { _ = try Message.decode(type: .click, payload: Data(#"{"button":"thumb","slot":1,"x":2,"y":3}"#.utf8)) }
            expectEqual(try Message.decode(type: .windowsToDisplay, payload: Data(#"{"slot":2}"#.utf8)),
                        .windowsToDisplay(WindowsToDisplayPayload(slot: 2, apps: nil)), "apps is optional")
        }

        run("display pixel maps to the global point at its centre") {
            let b = CGRect(x: 1440, y: 0, width: 1920, height: 1080)
            expectEqual(PointerMapping.globalPoint(x: 0, y: 0, width: 1920, height: 1080, bounds: b), CGPoint(x: 1440.5, y: 0.5))
            expectEqual(PointerMapping.globalPoint(x: 1919, y: 1079, width: 1920, height: 1080, bounds: b), CGPoint(x: 3359.5, y: 1079.5))
            expectEqual(PointerMapping.globalPoint(x: 960, y: 540, width: 1920, height: 1080, bounds: b), CGPoint(x: 2400.5, y: 540.5))
            // off the display: clamped onto its edge pixels
            expectEqual(PointerMapping.globalPoint(x: -5, y: 5000, width: 1920, height: 1080, bounds: b), CGPoint(x: 1440.5, y: 1079.5))
            // a display whose points are half its pixels (HiDPI) and one left of / above the main display
            let hi = CGRect(x: -960, y: -540, width: 960, height: 540)
            expectEqual(PointerMapping.globalPoint(x: 1918, y: 0, width: 1920, height: 1080, bounds: hi), CGPoint(x: -0.75, y: -539.75))
        }

        run("drag: down activates the app first, then a real down, drags and up at the mapped points") {
            var d = DragMachine()
            let p0 = CGPoint(x: 100, y: 100), p1 = CGPoint(x: 120, y: 110), p2 = CGPoint(x: 2000, y: 50)
            expectEqual(d.down(at: p0, button: .left), [.startTap, .activate(p0)])
            expectEqual(d.phase, .activating)
            expectEqual(d.move(to: p1), [], "moves while the app comes forward are kept, not posted")
            expectEqual(d.activated(), [.postDown(p0, .left), .postDrag(p1, .left)])
            expectEqual(d.phase, .dragging)
            expectEqual(d.move(to: p2), [.postDrag(p2, .left)], "across displays: just another point")
            expectEqual(d.move(to: p2), [], "same point twice is posted once")
            expectEqual(d.physicalUp(), [.postUp(p2, .left), .stopTap, .sendEnded("released"), .activateMinecraft])
            expectEqual(d.phase, .idle)
            expectEqual(d.physicalUp(), [], "a second release does nothing")
            expectEqual(d.move(to: p1), [], "moves after the end are ignored")
            expectEqual(d.activated(), [], "a late activation after the end is ignored")
        }

        run("drag: released before the app came forward still completes down, drag, up") {
            var d = DragMachine()
            let p0 = CGPoint(x: 5, y: 5), p1 = CGPoint(x: 50, y: 5)
            _ = d.down(at: p0, button: .left)
            _ = d.move(to: p1)
            expectEqual(d.physicalUp(), [.postDown(p0, .left), .postDrag(p1, .left), .postUp(p1, .left), .stopTap,
                                         .sendEnded("released"), .activateMinecraft])
            var e = DragMachine()
            _ = e.down(at: p0, button: .left)
            expectEqual(e.physicalUp(), [.postDown(p0, .left), .postUp(p0, .left), .stopTap, .sendEnded("released"), .activateMinecraft],
                        "no movement: no drag event")
        }

        run("drag: the mod cancels; a new down replaces a running drag") {
            var d = DragMachine()
            expectEqual(d.cancel(), [], "nothing to cancel")
            let p0 = CGPoint(x: 1, y: 1), p1 = CGPoint(x: 9, y: 9)
            _ = d.down(at: p0, button: .left)
            expectEqual(d.cancel(), [.stopTap, .sendEnded("cancelled"), .activateMinecraft], "never pressed in the app: nothing to release")
            _ = d.down(at: p0, button: .left)
            _ = d.activated()
            expectEqual(d.down(at: p1, button: .left), [.postUp(p0, .left), .stopTap, .sendEnded("cancelled"), .startTap, .activate(p1)])
            expectEqual(d.cancel(), [.stopTap, .sendEnded("cancelled"), .activateMinecraft])
        }

        run("typing: Esc returns to Minecraft and is swallowed down, repeats and up") {
            var t = TypingMachine()
            let esc: UInt16 = 53
            expectEqual(t.key(code: esc, down: true, modifiers: []).swallow, false, "Esc outside typing mode is the app's")
            expectEqual(t.enter(), [.armTap])
            expect(t.active, "typing")
            let a = t.key(code: 0, down: true, modifiers: [])
            expect(!a.swallow && a.effects.isEmpty, "other keys go to the app")
            let cmdEsc = t.key(code: esc, down: true, modifiers: [.command])
            expect(!cmdEsc.swallow && cmdEsc.effects.isEmpty, "a modified Esc is the app's")
            let down = t.key(code: esc, down: true, modifiers: [])
            expect(down.swallow, "Esc swallowed")
            expectEqual(down.effects, [.activateMinecraft, .sendExit("esc")])
            expect(!t.active, "typing over")
            let repeatDown = t.key(code: esc, down: true, modifiers: [])
            expect(repeatDown.swallow && repeatDown.effects.isEmpty, "auto-repeat swallowed (it would open Minecraft's pause menu)")
            let up = t.key(code: esc, down: false, modifiers: [])
            expect(up.swallow, "Esc up swallowed")
            expectEqual(up.effects, [.disarmTap])
            expect(!t.key(code: esc, down: true, modifiers: []).swallow, "the next Esc is normal again")
        }

        run("typing: clicking back into Minecraft or the mod ending it") {
            var t = TypingMachine()
            expectEqual(t.minecraftActivated(), [], "not typing")
            _ = t.enter()
            expectEqual(t.minecraftActivated(), [.disarmTap, .sendExit("minecraft")])
            expectEqual(t.minecraftActivated(), [], "once")
            _ = t.enter()
            expectEqual(t.exit(), [.disarmTap], "the mod already knows")
            expect(!t.key(code: 53, down: true, modifiers: []).swallow, "Esc is normal after the mod ended typing")
            expectEqual(t.exit(), [], "idempotent")
            _ = t.enter()
            expectEqual(t.enter(), [], "entering twice keeps one tap")
        }

        run("windows: the main display's layout lands scaled on the hologram's display") {
            let main = CGRect(x: 0, y: 25, width: 1440, height: 875)
            let office = CGRect(x: 1440, y: 25, width: 1920, height: 1055)
            let left = CGRect(x: 0, y: 25, width: 720, height: 875), right = CGRect(x: 720, y: 25, width: 720, height: 875)
            let out = WindowMovePlan.plan([left, right], from: main, to: office)
            let s = min(1920.0 / 1440.0, 1055.0 / 875.0)
            let ox = 1440 + (1920 - 1440 * s) / 2
            expect(close(out[0], CGRect(x: ox, y: 25, width: 720 * s, height: 875 * s)), "left half: \(out[0])")
            expect(close(out[1], CGRect(x: ox + 720 * s, y: 25, width: 720 * s, height: 875 * s)), "right half: \(out[1])")
            expect(out.allSatisfy { office.insetBy(dx: -0.5, dy: -0.5).contains($0) }, "inside the display")
            let small = WindowMovePlan.plan([CGRect(x: 100, y: 125, width: 400, height: 300)], from: main, to: office)[0]
            expect(abs(small.width / small.height - 400.0 / 300.0) < 0.01, "aspect kept")
            expect(close(small.origin, CGPoint(x: ox + 100 * s, y: 25 + 100 * s)), "relative position kept: \(small.origin)")
        }

        run("windows: anything that would hang off the display is pulled inside") {
            let main = CGRect(x: 0, y: 25, width: 1440, height: 875)
            let tall = CGRect(x: 1440, y: 25, width: 1080, height: 1895)
            let out = WindowMovePlan.plan([CGRect(x: -300, y: 0, width: 3000, height: 2000), CGRect(x: 1400, y: 880, width: 500, height: 300)],
                                          from: main, to: tall)
            for r in out { expect(tall.insetBy(dx: -0.5, dy: -0.5).contains(r), "\(r) inside \(tall)") }
            expect(out[1].width > 100 && out[1].height > 60, "kept a usable size")
        }

        run("windows: originals are kept once and only existing windows go back") {
            var ledger = WindowLedger<Int>()
            ledger.record(1, original: CGRect(x: 0, y: 25, width: 500, height: 400))
            ledger.record(2, original: CGRect(x: 600, y: 25, width: 500, height: 400))
            ledger.record(1, original: CGRect(x: 2000, y: 0, width: 10, height: 10)) // moved again: the first original wins
            expectEqual(ledger.count, 2)
            let back = ledger.restore(existing: [1])
            expectEqual(back.count, 1, "window 2 was closed meanwhile")
            expectEqual(back.first?.key, 1)
            expectEqual(back.first?.frame, CGRect(x: 0, y: 25, width: 500, height: 400))
            expectEqual(ledger.count, 0, "nothing left to restore twice")
            expect(ledger.restore(existing: [1, 2]).isEmpty, "idempotent")
        }
    }

    static func close(_ a: CGRect, _ b: CGRect) -> Bool {
        abs(a.minX - b.minX) < 1 && abs(a.minY - b.minY) < 1 && abs(a.width - b.width) < 1 && abs(a.height - b.height) < 1
    }

    static func close(_ a: CGPoint, _ b: CGPoint) -> Bool { abs(a.x - b.x) < 1 && abs(a.y - b.y) < 1 }
}
