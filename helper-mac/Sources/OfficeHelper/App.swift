import AppKit

/// OfficeHelper: a background (LSUIElement) app the Minecraft Office mod launches with `open -g`. It serves one
/// mod connection on ~/Library/Application Support/Minecraft Office/helper.sock and quits when that connection
/// closes (its virtual displays vanish with the process).
@MainActor final class HelperDelegate: NSObject, NSApplicationDelegate {
    private var server: HelperServer?
    private var session: Session?
    private var lockFD: Int32 = -1
    private var signalSources: [DispatchSourceSignal] = []
    private var everConnected = false
    /// Quit if no mod connects this long after launch (an orphaned helper never lingers).
    static let idleQuitSeconds: TimeInterval = 120

    func applicationDidFinishLaunching(_ notification: Notification) {
        let directory = HelperServer.defaultDirectory
        do {
            try FileManager.default.createDirectory(at: directory, withIntermediateDirectories: true, attributes: [.posixPermissions: 0o700])
            try FileManager.default.setAttributes([.posixPermissions: 0o700], ofItemAtPath: directory.path)
        } catch { NSLog("OfficeHelper: cannot create support folder: \(error)"); exit(1) }
        Log.rotate()

        // One helper at a time: a second launch exits and leaves the running one alone.
        lockFD = open(directory.appendingPathComponent("helper.lock").path, O_CREAT | O_RDWR, 0o600)
        guard lockFD >= 0, flock(lockFD, LOCK_EX | LOCK_NB) == 0 else {
            Log.write("another helper is running; exiting")
            exit(0)
        }

        let server = HelperServer(directory: directory)
        let session = Session(server: server)
        server.log = { line in DispatchQueue.main.async { Log.write(line) } }
        server.welcome = { Session.welcome }
        server.onConnect = { [weak self] hello, pid in
            MainActor.assumeIsolated { self?.everConnected = true; session.connected(hello, peerPid: pid) }
        }
        server.onMessage = { message in MainActor.assumeIsolated { session.handle(message) } }
        server.onDisconnect = { MainActor.assumeIsolated { session.disconnected() } }
        session.onFinished = { NSApp.terminate(nil) }
        self.server = server
        self.session = session
        do { try server.start() } catch {
            Log.write("server failed to start: \(error)")
            exit(1)
        }
        let version = Bundle.main.object(forInfoDictionaryKey: "CFBundleShortVersionString") as? String ?? "dev"
        Log.write("OfficeHelper \(version) started pid \(getpid()); screen recording \(CGPreflightScreenCaptureAccess()), accessibility \(AXIsProcessTrusted())")

        for sig in [SIGTERM, SIGINT, SIGHUP] {
            signal(sig, SIG_IGN)
            let source = DispatchSource.makeSignalSource(signal: sig, queue: .main)
            source.setEventHandler { Log.write("signal \(sig)"); NSApp.terminate(nil) }
            source.resume()
            signalSources.append(source)
        }
        DispatchQueue.main.asyncAfter(deadline: .now() + Self.idleQuitSeconds) { [weak self] in
            MainActor.assumeIsolated {
                guard let self, !self.everConnected else { return }
                Log.write("no mod connected within \(Int(Self.idleQuitSeconds)) s; quitting")
                NSApp.terminate(nil)
            }
        }
    }

    func applicationWillTerminate(_ notification: Notification) {
        session?.shutdown()
        server?.stop()
        Log.write("OfficeHelper quit")
    }

    func applicationShouldTerminateAfterLastWindowClosed(_ sender: NSApplication) -> Bool { false }
}

@main struct OfficeHelperMain {
    @MainActor static func main() {
        let app = NSApplication.shared
        let delegate = HelperDelegate()
        app.delegate = delegate
        app.setActivationPolicy(.accessory)
        app.run()
    }
}
