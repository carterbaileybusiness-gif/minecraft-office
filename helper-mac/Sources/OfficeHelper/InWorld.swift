import AppKit
import CoreGraphics

/// In-world use for the mod: the player's crosshair is the mouse on their holograms (spec 5.9).
///
/// - A press + head turn is a drag the helper drives ({@link DragMachine}): the app under the press point comes forward,
///   a real down is posted there, a session event tap takes the physical mouse (its motion → DRAG_DELTA so the mod
///   turns the head; its release → the drag's end, DRAG_ENDED), and every POINTER_MOVE is posted as a real drag.
///   Minecraft is activated again when it ends. (Posting to the app's pid instead keeps Minecraft in front, but AppKit
///   ignores such clicks, drags and scrolls: see Tools/inputprobe.swift.)
/// - CLICK is a real click there (the app comes forward with the keyboard: typing mode, {@link TypingMachine}); a
///   session key tap swallows the Esc that ends it, and Minecraft becoming active ends it too (TYPING_EXIT).
/// - SCROLL is a real scroll event at the point; scrolling never activates an app, so Minecraft keeps its input.
/// Everything needs Accessibility (posting events, event taps).
@MainActor final class InWorldInput {
    var send: (Message) -> Void = { _ in }
    var log: (String) -> Void = { _ in }
    var minecraftPid: () -> pid_t? = { nil }
    /// Bounds (global points) and pixel size of a slot's virtual display.
    var display: (Int) -> (bounds: CGRect, width: Int, height: Int)? = { _ in nil }
    var activateMinecraft: () -> Void = {}

    private var drag = DragMachine()
    private var typing = TypingMachine()
    private var mouseTap: CFMachPort?
    private var keyTap: CFMachPort?
    private var buttonPoll: Timer?
    private var activationWait = 0
    /// Physical motion not sent yet (points; fractions kept, so a slow trackpad drag still turns the head).
    private var pending = DeltaAccumulator()
    private var flushScheduled = false
    private var activationObserver: NSObjectProtocol?

    /// The one instance the C tap callbacks reach (they can't capture context).
    nonisolated(unsafe) static weak var current: InWorldInput?

    init() { Self.current = self }

    var isDragging: Bool { drag.isActive }
    var isTyping: Bool { typing.active }

    // MARK: Messages

    func handle(_ message: Message) {
        switch message {
        case .pointerDown(let p):
            guard let point = point(p), let button = p.button else { return }
            if typing.active { perform(typing.exit()) }
            perform(drag.down(at: point, button: button))
        case .pointerMove(let p):
            if drag.isActive, let point = point(p, quiet: true) { perform(drag.move(to: point)) }
        case .pointerUp:
            perform(drag.cancel())
        case .click(let p):
            guard let point = point(p), let button = p.button else { return }
            if drag.isActive { perform(drag.cancel()) }
            click(at: point, button: button)
        case .scroll(let p):
            guard let point = point(PointerPayload(slot: p.slot, x: p.x, y: p.y, button: nil)) else { return }
            EventInjector.scrollHID(at: point, dx: Int32(clamping: p.dx), dy: Int32(clamping: p.dy))
        case .exitTyping:
            perform(typing.exit())
        default:
            break
        }
    }

    /// The mod disconnected / the helper quits: never leave a button pressed, a tap running or Esc swallowed.
    func shutdown() {
        if drag.isActive { perform(drag.cancel().filter { $0 != .activateMinecraft }) }
        perform(typing.exit())
        disarmKeys()
        stopObservingMinecraft()
        if let mouseTap { CGEvent.tapEnable(tap: mouseTap, enable: false) }
    }

    private func point(_ p: PointerPayload, quiet: Bool = false) -> CGPoint? {
        guard AXIsProcessTrusted() else {
            if !quiet { send(.error(ErrorPayload(code: "no_accessibility", message: "in-world use needs Accessibility"))) }
            return nil
        }
        guard let d = display(p.slot) else {
            if !quiet { send(.error(ErrorPayload(code: "not_found", message: "no display for slot \(p.slot)"))) }
            return nil
        }
        return PointerMapping.globalPoint(x: p.x, y: p.y, width: d.width, height: d.height, bounds: d.bounds)
    }

    // MARK: Click → typing mode

    private func click(at point: CGPoint, button: PointerButton) {
        EventInjector.hidClick(at: point, button: button)
        let effects = typing.enter()
        perform(effects)
        observeMinecraft()
        log("click \(button) at \(Int(point.x)),\(Int(point.y)): typing")
    }

    private func perform(_ effects: [TypingMachine.Effect]) {
        for effect in effects {
            switch effect {
            case .armTap: armKeys()
            case .disarmTap: disarmKeys()
            case .activateMinecraft: activateMinecraft()
            case .sendExit(let reason):
                log("typing ended: \(reason)")
                send(.typingExit(ReasonPayload(reason: reason)))
            }
        }
        if !typing.active { stopObservingMinecraft() }
    }

    private func observeMinecraft() {
        guard activationObserver == nil else { return }
        activationObserver = NSWorkspace.shared.notificationCenter.addObserver(
            forName: NSWorkspace.didActivateApplicationNotification, object: nil, queue: .main) { [weak self] note in
            let app = note.userInfo?[NSWorkspace.applicationUserInfoKey] as? NSRunningApplication
            MainActor.assumeIsolated {
                guard let self, let pid = self.minecraftPid(), app?.processIdentifier == pid else { return }
                self.perform(self.typing.minecraftActivated())
            }
        }
    }

    private func stopObservingMinecraft() {
        if let activationObserver { NSWorkspace.shared.notificationCenter.removeObserver(activationObserver) }
        activationObserver = nil
    }

    private func armKeys() {
        if keyTap == nil {
            let mask = (1 << CGEventType.keyDown.rawValue) | (1 << CGEventType.keyUp.rawValue)
            keyTap = CGEvent.tapCreate(tap: .cgSessionEventTap, place: .headInsertEventTap, options: .defaultTap,
                                       eventsOfInterest: CGEventMask(mask), callback: inWorldKeyTap, userInfo: nil)
            guard let keyTap else {
                log("typing: Esc tap not created (Accessibility?)")
                send(.error(ErrorPayload(code: "no_accessibility", message: "Esc to return needs Accessibility")))
                return
            }
            CFRunLoopAddSource(CFRunLoopGetMain(), CFMachPortCreateRunLoopSource(nil, keyTap, 0), .commonModes)
        }
        if let keyTap { CGEvent.tapEnable(tap: keyTap, enable: true) }
    }

    private func disarmKeys() {
        if let keyTap { CGEvent.tapEnable(tap: keyTap, enable: false) }
    }

    /// Key tap (main run loop). Returns true to swallow.
    fileprivate func key(_ event: CGEvent, type: CGEventType) -> Bool {
        if type == .tapDisabledByTimeout || type == .tapDisabledByUserInput {
            if let keyTap, typing.active || typing.swallowingEscape { CGEvent.tapEnable(tap: keyTap, enable: true) }
            return false
        }
        guard type == .keyDown || type == .keyUp else { return false }
        let code = UInt16(truncatingIfNeeded: event.getIntegerValueField(.keyboardEventKeycode))
        let result = typing.key(code: code, down: type == .keyDown, modifiers: Self.modifiers(event.flags))
        perform(result.effects)
        return result.swallow
    }

    static func modifiers(_ flags: CGEventFlags) -> KeyModifiers {
        var m: KeyModifiers = []
        if flags.contains(.maskShift) { m.insert(.shift) }
        if flags.contains(.maskControl) { m.insert(.control) }
        if flags.contains(.maskAlternate) { m.insert(.option) }
        if flags.contains(.maskCommand) { m.insert(.command) }
        return m
    }

    // MARK: Drag

    private func perform(_ effects: [DragMachine.Effect]) {
        for effect in effects {
            switch effect {
            case .startTap: startMouseTap()
            case .stopTap: stopMouseTap()
            case .activate(let point): activateApp(under: point)
            case .postDown(let p, let b):
                EventInjector.postHID(.mouseMoved, at: p, button: .left, clickState: 0)
                EventInjector.postHID(EventInjector.downType(b), at: p, button: b)
            case .postDrag(let p, let b): EventInjector.postHID(EventInjector.draggedType(b), at: p, button: b)
            case .postUp(let p, let b): EventInjector.postHID(EventInjector.upType(b), at: p, button: b)
            case .sendEnded(let reason):
                flushDeltas()
                log("drag ended: \(reason)")
                send(.dragEnded(ReasonPayload(reason: reason)))
            case .activateMinecraft:
                // after the up has been handled (a window drop, a text selection), give Minecraft its mouse back
                DispatchQueue.main.asyncAfter(deadline: .now() + 0.05) { [weak self] in
                    MainActor.assumeIsolated { if self?.drag.isActive == false { self?.activateMinecraft() } }
                }
            }
        }
    }

    private func startMouseTap() {
        pending = DeltaAccumulator()
        if mouseTap == nil {
            let types: [CGEventType] = [.mouseMoved, .leftMouseDown, .leftMouseUp, .leftMouseDragged, .rightMouseDown, .rightMouseUp,
                                        .rightMouseDragged, .otherMouseDown, .otherMouseUp, .otherMouseDragged]
            let mask = types.reduce(CGEventMask(0)) { $0 | (1 << $1.rawValue) }
            mouseTap = CGEvent.tapCreate(tap: .cgSessionEventTap, place: .headInsertEventTap, options: .defaultTap,
                                         eventsOfInterest: mask, callback: inWorldMouseTap, userInfo: nil)
            if let mouseTap { CFRunLoopAddSource(CFRunLoopGetMain(), CFMachPortCreateRunLoopSource(nil, mouseTap, 0), .commonModes) }
        }
        guard let mouseTap else {
            log("drag: mouse tap not created (Accessibility?)")
            perform(drag.cancel())
            return
        }
        CGEvent.tapEnable(tap: mouseTap, enable: true)
        let button = EventInjector.cgButton(drag.button)
        // Safety net: the release can't be missed (released before the tap started, or a tap that macOS disabled).
        let releasedAlready = !CGEventSource.buttonState(.hidSystemState, button: button)
        buttonPoll?.invalidate()
        buttonPoll = Timer.scheduledTimer(withTimeInterval: 0.25, repeats: true) { [weak self] _ in
            MainActor.assumeIsolated {
                guard let self, self.drag.isActive else { return }
                if !CGEventSource.buttonState(.hidSystemState, button: button) {
                    self.log("drag: button found up by the poll")
                    self.perform(self.drag.physicalUp())
                }
            }
        }
        if releasedAlready {
            DispatchQueue.main.async { [weak self] in
                MainActor.assumeIsolated { if let self, self.drag.isActive { self.perform(self.drag.physicalUp()) } }
            }
        }
    }

    private func stopMouseTap() {
        if let mouseTap { CGEvent.tapEnable(tap: mouseTap, enable: false) }
        buttonPoll?.invalidate(); buttonPoll = nil
    }

    /// The app under the press comes forward first, so the press reaches its view (macOS swallows a click that only
    /// activates). Waits for it to be frontmost, at most 300 ms.
    private func activateApp(under point: CGPoint) {
        activationWait += 1
        let wait = activationWait
        var excluded: Set<pid_t> = [getpid()]
        if let pid = minecraftPid() { excluded.insert(pid) }
        guard let target = EventInjector.window(at: point, excluding: excluded),
              let app = NSRunningApplication(processIdentifier: target.pid) else {
            perform(drag.activated()) // the desktop: nothing to bring forward
            return
        }
        if NSWorkspace.shared.frontmostApplication?.processIdentifier != target.pid {
            let from = NSWorkspace.shared.frontmostApplication ?? NSRunningApplication.current
            if !app.activate(from: from, options: []) { app.activate(options: []) }
        }
        let start = Date()
        func poll() {
            guard wait == activationWait, drag.phase == .activating else { return }
            if NSWorkspace.shared.frontmostApplication?.processIdentifier == target.pid || Date().timeIntervalSince(start) > 0.3 {
                perform(drag.activated())
                return
            }
            DispatchQueue.main.asyncAfter(deadline: .now() + 0.01) { MainActor.assumeIsolated { poll() } }
        }
        poll()
    }

    /// Mouse tap (main run loop). Returns true to swallow.
    fileprivate func mouse(_ event: CGEvent, type: CGEventType) -> Bool {
        if type == .tapDisabledByTimeout || type == .tapDisabledByUserInput {
            if let mouseTap, drag.isActive { CGEvent.tapEnable(tap: mouseTap, enable: true) }
            return false
        }
        guard drag.isActive, !EventInjector.isInjected(event) else { return false }
        pending.add(dx: Self.delta(event, .mouseEventDeltaX), dy: Self.delta(event, .mouseEventDeltaY))
        scheduleFlush()
        let up: CGEventType = EventInjector.upType(drag.button)
        if type == up { perform(drag.physicalUp()) }
        return true // the physical mouse belongs to the drag: only the helper's own events move the cursor
    }

    /// A mouse delta with its fraction (trackpads move by fractions of a point); the integer field when that is all.
    static func delta(_ event: CGEvent, _ field: CGEventField) -> Double {
        let d = event.getDoubleValueField(field)
        return d != 0 ? d : Double(event.getIntegerValueField(field))
    }

    private func scheduleFlush() {
        guard !flushScheduled else { return }
        flushScheduled = true
        DispatchQueue.main.asyncAfter(deadline: .now() + 0.004) { [weak self] in
            MainActor.assumeIsolated {
                self?.flushScheduled = false
                self?.flushDeltas()
            }
        }
    }

    private func flushDeltas() {
        guard let (dx, dy) = pending.take() else { return }
        send(.dragDelta(DeltaPayload(dx: dx, dy: dy)))
    }
}

private func inWorldMouseTap(proxy: CGEventTapProxy, type: CGEventType, event: CGEvent, refcon: UnsafeMutableRawPointer?) -> Unmanaged<CGEvent>? {
    let swallow = MainActor.assumeIsolated { InWorldInput.current?.mouse(event, type: type) ?? false }
    return swallow ? nil : Unmanaged.passUnretained(event)
}

private func inWorldKeyTap(proxy: CGEventTapProxy, type: CGEventType, event: CGEvent, refcon: UnsafeMutableRawPointer?) -> Unmanaged<CGEvent>? {
    let swallow = MainActor.assumeIsolated { InWorldInput.current?.key(event, type: type) ?? false }
    return swallow ? nil : Unmanaged.passUnretained(event)
}
