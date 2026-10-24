import AppKit
import Carbon // IsSecureEventInputEnabled
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
    /// The main display's bounds (global points): slot 0, a Mirror hologram.
    var mainBounds: () -> CGRect = { .zero }
    var activateMinecraft: () -> Void = {}

    private var drag = DragMachine()
    private var typing = TypingMachine()
    private var mouseTap: CFMachPort?
    private var keyTap: CFMachPort?
    private var buttonPoll: Timer?
    private var activationWait = 0
    private var clickWait = 0
    /// Physical motion not sent yet (points; fractions kept, so a slow trackpad drag still turns the head).
    private var pending = DeltaAccumulator()
    private var flushScheduled = false
    private var activationObserver: NSObjectProtocol?
    private var secure = SecureInputWatch()
    private var secureTimer: Timer?

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
            whenReachable(point, slot: p.slot) { self.perform(self.drag.down(at: point, button: button)) }
        case .pointerMove(let p):
            if drag.isActive, let point = point(p, quiet: true) { perform(drag.move(to: point)) }
        case .pointerUp:
            perform(drag.cancel())
        case .click(let p):
            guard let point = point(p), let button = p.button else { return }
            if drag.isActive { perform(drag.cancel()) }
            whenReachable(point, slot: p.slot) { self.click(at: point, button: button) }
        case .scroll(let p):
            guard let point = point(PointerPayload(slot: p.slot, x: p.x, y: p.y, button: nil, w: p.w, h: p.h)) else { return }
            whenReachable(point, slot: p.slot) {
                if p.slot == PointerTarget.mainSlot {
                    // Mirror: Minecraft is click-through for a moment; a wheel event of the player's in that moment
                    // lands where the cursor is, so put the (hidden) cursor at the same point.
                    CGWarpMouseCursorPosition(point)
                }
                EventInjector.scrollHID(at: point, dx: Int32(clamping: p.dx), dy: Int32(clamping: p.dy))
            }
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
        switch PointerTarget.globalPoint(slot: p.slot, x: p.x, y: p.y, w: p.w, h: p.h, main: mainBounds(), display: display) {
        case .success(let point):
            return point
        case .failure(let failure):
            if !quiet {
                let message = failure == .noPictureSize ? "slot 0 (the main display) needs w and h" : "no display for slot \(p.slot)"
                send(.error(ErrorPayload(code: failure == .noPictureSize ? "bad_request" : "not_found", message: message)))
            }
            return nil
        }
    }

    /// Mirror (slot 0): the mod made Minecraft's window click-through just before sending, but the window server applies
    /// that a moment later (inputprobe M2/M5: a click posted at once still hit Minecraft). Wait until the window that a
    /// click at `point` would hit is no longer Minecraft's (at most 300 ms), then act. Other slots: at once.
    private func whenReachable(_ point: CGPoint, slot: Int, _ action: @escaping () -> Void) {
        guard slot == PointerTarget.mainSlot, let pid = minecraftPid() else { action(); return }
        let start = Date()
        func poll() {
            let hit = EventInjector.hitWindowPid(at: point)
            let waited = Date().timeIntervalSince(start)
            if hit != pid || waited > 0.3 {
                if waited > 0.001 || hit == pid {
                    log("mirror: \(hit == pid ? "Minecraft still takes clicks after 300 ms; posting anyway" : "click-through after \(Int(waited * 1000)) ms")")
                }
                action()
                return
            }
            DispatchQueue.main.asyncAfter(deadline: .now() + 0.005) { MainActor.assumeIsolated { poll() } }
        }
        poll()
    }

    // MARK: Click → typing mode

    /// The app under the point comes forward first (as for a drag), so the click lands in it — the caret goes where you
    /// clicked, a button presses — instead of only activating it (inputprobe M2 / E2: without this, a first click into
    /// an inactive TextEdit left the caret where it was).
    private func click(at point: CGPoint, button: PointerButton) {
        clickWait += 1
        let wait = clickWait
        bringForward(under: point, isCurrent: { [weak self] in self?.clickWait == wait }) { [weak self] target in
            guard let self else { return }
            EventInjector.hidClick(at: point, button: button)
            let effects = self.typing.enter(target: target)
            self.perform(effects)
            self.observeActivations()
            self.watchSecureInput()
            self.log("click \(button) at \(Int(point.x)),\(Int(point.y)): typing")
        }
    }

    /// While typing: report secure input going on / off (it hides Esc from the tap), twice a second.
    private func watchSecureInput() {
        secureTimer?.invalidate()
        secure.reset()
        reportSecureInput()
        secureTimer = Timer.scheduledTimer(withTimeInterval: 0.5, repeats: true) { [weak self] _ in
            MainActor.assumeIsolated { self?.reportSecureInput() }
        }
    }

    private func reportSecureInput() {
        guard typing.active else { secureTimer?.invalidate(); secureTimer = nil; return }
        if let on = secure.sample(IsSecureEventInputEnabled()) {
            log("typing: secure input \(on ? "on" : "off")")
            send(.secureInput(SecureInputPayload(on: on)))
        }
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
        if !typing.active {
            stopObservingMinecraft()
            secureTimer?.invalidate(); secureTimer = nil
        }
    }

    /// Typing ends when Minecraft becomes active again, or when some third app does (TypingMachine.appActivated).
    private func observeActivations() {
        guard activationObserver == nil else { return }
        activationObserver = NSWorkspace.shared.notificationCenter.addObserver(
            forName: NSWorkspace.didActivateApplicationNotification, object: nil, queue: .main) { [weak self] note in
            let app = note.userInfo?[NSWorkspace.applicationUserInfoKey] as? NSRunningApplication
            MainActor.assumeIsolated {
                guard let self, let pid = app?.processIdentifier, pid != getpid() else { return }
                self.perform(self.typing.appActivated(pid: pid, isMinecraft: pid == self.minecraftPid()))
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
        bringForward(under: point, isCurrent: { [weak self] in self?.activationWait == wait && self?.drag.phase == .activating }) { [weak self] _ in
            guard let self else { return }
            self.perform(self.drag.activated())
        }
    }

    /// Activates the app whose window is under `point` (not Minecraft, not the helper), then runs `then` once it is
    /// frontmost (at most 300 ms; at once over the desktop). `isCurrent` false = a newer gesture took over: do nothing.
    private func bringForward(under point: CGPoint, isCurrent: @escaping () -> Bool, then: @escaping (pid_t?) -> Void) {
        var excluded: Set<pid_t> = [getpid()]
        if let pid = minecraftPid() { excluded.insert(pid) }
        guard let target = EventInjector.window(at: point, excluding: excluded),
              let app = NSRunningApplication(processIdentifier: target.pid) else {
            then(nil) // the desktop: nothing to bring forward (Finder will take the click: the first app activated)
            return
        }
        if NSWorkspace.shared.frontmostApplication?.processIdentifier != target.pid {
            let from = NSWorkspace.shared.frontmostApplication ?? NSRunningApplication.current
            if !app.activate(from: from, options: []) { app.activate(options: []) }
        }
        let start = Date()
        func poll() {
            guard isCurrent() else { return }
            if NSWorkspace.shared.frontmostApplication?.processIdentifier == target.pid || Date().timeIntervalSince(start) > 0.3 {
                then(target.pid)
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
