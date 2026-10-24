import Foundation

/// In-world use: display pixel → global point (Extend displays and the Mirror's main display), the helper-driven drag, typing mode.
enum PointerTests {
    static func runAll() {
        run("pointer messages need their button; moves don't") {
            let noButton = Data(#"{"slot":1,"x":2,"y":3}"#.utf8)
            for type in [MessageType.pointerDown, .pointerUp, .click] {
                expectThrows("\(type) without button") { _ = try Message.decode(type: type, payload: noButton) }
            }
            expectEqual(try Message.decode(type: .pointerMove, payload: noButton), .pointerMove(PointerPayload(slot: 1, x: 2, y: 3, button: nil)))
            expectThrows("unknown button") { _ = try Message.decode(type: .click, payload: Data(#"{"button":"thumb","slot":1,"x":2,"y":3}"#.utf8)) }
            for gone: UInt8 in [17, 18] { // WINDOWS_TO_DISPLAY / WINDOWS_BACK are retired (spec 5.10)
                expect(MessageType(rawValue: gone) == nil, "type \(gone) is no longer a message")
            }
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

        run("drag deltas: fractions of a point add up instead of being dropped") {
            var acc = DeltaAccumulator()
            var sumX = 0, sumY = 0
            for _ in 0..<10 {
                acc.add(dx: 0.4, dy: -0.25)
                if let (x, y) = acc.take() { sumX += x; sumY += y }
            }
            expectEqual(sumX, 4, "ten 0.4 pt moves turn the head by 4 pt")
            expect(abs(acc.dx) < 1e-9, "nothing left over")
            expectEqual(sumY, -2, "y: -2.5 → -2 out")
            expect(abs(acc.dy + 0.5) < 1e-9, "the fraction waits: \(acc.dy)")
            var whole = DeltaAccumulator()
            whole.add(dx: 7, dy: 0)
            expect(whole.take()! == (7, 0), "whole points go straight out")
            expect(whole.take() == nil, "then nothing")
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

        run("Mirror (slot 0): a picture pixel lands on the main display by proportion, Retina points") {
            let main = CGRect(x: 0, y: 0, width: 1440, height: 900) // a MacBook Air: 2880 x 1800 pixels, 1440 x 900 points
            let none: (Int) -> (bounds: CGRect, width: Int, height: Int)? = { _ in nil }
            // the hologram's 1920 x 1080 picture of a 16:10 screen (stretched onto the panel): centre → centre
            expectEqual(PointerTarget.globalPoint(slot: 0, x: 960, y: 540, w: 1920, h: 1080, main: main, display: none),
                        .success(CGPoint(x: 720.375, y: 450.4166666666667)))
            expectEqual(PointerTarget.globalPoint(slot: 0, x: 0, y: 0, w: 1920, h: 1080, main: main, display: none),
                        .success(CGPoint(x: 0.375, y: 0.4166666666666667)), "top-left pixel")
            expectEqual(PointerTarget.globalPoint(slot: 0, x: 1919, y: 1079, w: 1920, h: 1080, main: main, display: none),
                        .success(CGPoint(x: 1439.625, y: 899.5833333333334)), "bottom-right pixel")
            expectEqual(PointerTarget.globalPoint(slot: 0, x: 5000, y: -3, w: 1920, h: 1080, main: main, display: none),
                        .success(CGPoint(x: 1439.625, y: 0.4166666666666667)), "off the picture: clamped onto the screen")
            // the menu-bar strip of the screen is reachable (y 0...24 points): the picture covers the whole display
            if case .success(let p) = PointerTarget.globalPoint(slot: 0, x: 960, y: 20, w: 1920, h: 1080, main: main, display: none) {
                expect(p.y < 25, "a click near the top reaches the menu bar: \(p)")
            } else { expect(false, "slot 0 maps") }
            expectEqual(PointerTarget.globalPoint(slot: 0, x: 1, y: 1, w: nil, h: 1080, main: main, display: none), .failure(.noPictureSize))
            expectEqual(PointerTarget.globalPoint(slot: 0, x: 1, y: 1, w: 0, h: 0, main: main, display: none), .failure(.noPictureSize))
            expectEqual(PointerTarget.globalPoint(slot: 0, x: 1, y: 1, w: 10, h: 10, main: .zero, display: none), .failure(.noDisplay))
        }

        run("Extend slots still map onto their virtual display; an unknown slot fails") {
            let office = CGRect(x: 1440, y: 0, width: 1920, height: 1080)
            let one: (Int) -> (bounds: CGRect, width: Int, height: Int)? = { $0 == 1 ? (office, 1920, 1080) : nil }
            expectEqual(PointerTarget.globalPoint(slot: 1, x: 960, y: 540, w: nil, h: nil, main: .zero, display: one),
                        .success(CGPoint(x: 2400.5, y: 540.5)))
            expectEqual(PointerTarget.globalPoint(slot: 1, x: 960, y: 540, w: 10, h: 10, main: .zero, display: one),
                        .success(CGPoint(x: 2400.5, y: 540.5)), "w/h only matter for slot 0")
            expectEqual(PointerTarget.globalPoint(slot: 2, x: 0, y: 0, w: nil, h: nil, main: .zero, display: one), .failure(.noDisplay))
        }

        run("pointer messages on slot 0 need the picture size") {
            for type in [MessageType.pointerDown, .click] {
                expectThrows("\(type) on slot 0 without w/h") {
                    _ = try Message.decode(type: type, payload: Data(#"{"button":"left","slot":0,"x":2,"y":3}"#.utf8))
                }
            }
            expectThrows("move on slot 0 without w/h") { _ = try Message.decode(type: .pointerMove, payload: Data(#"{"slot":0,"x":2,"y":3}"#.utf8)) }
            expectThrows("scroll on slot 0 without w/h") {
                _ = try Message.decode(type: .scroll, payload: Data(#"{"dx":0,"dy":1,"slot":0,"x":2,"y":3}"#.utf8))
            }
            expectEqual(try Message.decode(type: .pointerMove, payload: Data(#"{"h":1080,"slot":0,"w":1920,"x":2,"y":3}"#.utf8)),
                        .pointerMove(PointerPayload(slot: 0, x: 2, y: 3, button: nil, w: 1920, h: 1080)))
        }
    }

    static func close(_ a: CGRect, _ b: CGRect) -> Bool {
        abs(a.minX - b.minX) < 1 && abs(a.minY - b.minY) < 1 && abs(a.width - b.width) < 1 && abs(a.height - b.height) < 1
    }

    static func close(_ a: CGPoint, _ b: CGPoint) -> Bool { abs(a.x - b.x) < 1 && abs(a.y - b.y) < 1 }
}
