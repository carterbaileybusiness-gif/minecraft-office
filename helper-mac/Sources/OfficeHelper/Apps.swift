import Foundation

// Pure rules for the mini Dock (spec 5.10; Foundation only, so check.sh tests them): which requests are valid, the
// installed-app list, which window of an app goes onto the hologram's display, and where it lands there.

enum AppRules {
    /// A plausible bundle id (letters, digits, dots, dashes; what Home Base's bridge accepted too).
    static func isBundleId(_ s: String) -> Bool {
        s.range(of: "^[A-Za-z0-9.-]{3,200}$", options: .regularExpression) != nil
    }

    /// Why an OPEN_APP is refused (nil = fine): slot 1...4, exactly one of bundle / url, the url https with a host.
    static func problem(_ p: OpenAppPayload) -> String? {
        guard (1...DisplaySpec.maxSlots).contains(p.slot) else { return "OPEN_APP slot must be 1...\(DisplaySpec.maxSlots)" }
        switch (p.bundle, p.url) {
        case (let b?, nil):
            return isBundleId(b) ? nil : "OPEN_APP bundle is not a bundle id"
        case (nil, let u?):
            guard u.count <= 2048, let url = URL(string: u), url.scheme?.lowercased() == "https", let host = url.host, !host.isEmpty else {
                return "OPEN_APP url must be https"
            }
            return nil
        default:
            return "OPEN_APP needs exactly one of bundle and url"
        }
    }
}

/// The installed apps the "All apps" search lists (the Applications folders, scanned like Home Base's bridge did).
enum AppCatalog {
    /// One `.app` found on disk.
    struct Found: Equatable { var bundle: String?; var name: String }

    /// Drops apps without a bundle id and the helper itself, keeps the first of each bundle id (folders are scanned in
    /// priority order), sorts by name the way Finder does.
    static func list(_ found: [Found], excluding: Set<String>) -> [AppInfo] {
        var seen = Set<String>()
        var out: [AppInfo] = []
        for f in found {
            guard let bundle = f.bundle, AppRules.isBundleId(bundle), !excluding.contains(bundle), seen.insert(bundle).inserted else { continue }
            let name = f.name.hasSuffix(".app") ? String(f.name.dropLast(4)) : f.name
            out.append(AppInfo(bundle: bundle, name: name.isEmpty ? bundle : name))
        }
        return out.sorted { $0.name.localizedStandardCompare($1.name) == .orderedAscending }
    }
}

/// Which of an app's windows goes onto the hologram's display after a Dock click. Pure; `Key` is a window identity.
enum AppWindowPick {
    /// `before`: the app's standard windows when the click came in; `now`: its standard windows front to back.
    /// A window that wasn't there before (the app just opened one) wins; otherwise, once the app has had `grace`
    /// seconds to open one (or didn't need to: it was already showing windows and we didn't ask for a new one), its
    /// frontmost window. Nil = keep waiting.
    static func choose<Key: Hashable>(before: Set<Key>, now: [Key], askedForNew: Bool, elapsed: Double, grace: Double = 1.5) -> Key? {
        if let fresh = now.first(where: { !before.contains($0) }) { return fresh }
        guard let front = now.first else { return nil }
        return !askedForNew || elapsed >= grace ? front : nil
    }
}

/// Where the window lands: it fills the display's visible area (below the menu bar), like a zoomed window. Pure.
enum AppWindowFit {
    static func frame(visible: CGRect) -> CGRect { visible.integral }
}
