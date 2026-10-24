import Foundation

/// The mini Dock's pure rules: valid requests, the installed-app list, which window moves, where it lands.
enum AppsTests {
    static func runAll() {
        run("OPEN_APP: slot 1-4, exactly one of bundle and url, https only") {
            expect(AppRules.problem(OpenAppPayload(slot: 1, bundle: "com.apple.Notes", url: nil)) == nil, "an app")
            expect(AppRules.problem(OpenAppPayload(slot: 4, bundle: nil, url: "https://www.youtube.com")) == nil, "a web page")
            expect(AppRules.problem(OpenAppPayload(slot: 0, bundle: "com.apple.Notes", url: nil)) != nil, "slot 0 is the Mirror: no Dock")
            expect(AppRules.problem(OpenAppPayload(slot: 5, bundle: "com.apple.Notes", url: nil)) != nil, "slot 5")
            expect(AppRules.problem(OpenAppPayload(slot: 1, bundle: nil, url: nil)) != nil, "neither")
            expect(AppRules.problem(OpenAppPayload(slot: 1, bundle: "com.apple.Notes", url: "https://x.y")) != nil, "both")
            for bad in ["http://www.youtube.com", "file:///etc/passwd", "javascript:alert(1)", "https://", "youtube.com"] {
                expect(AppRules.problem(OpenAppPayload(slot: 1, bundle: nil, url: bad)) != nil, "refused: \(bad)")
            }
            for bad in ["", "a", "com.apple.Notes; rm -rf", "../../x", String(repeating: "a", count: 201)] {
                expect(AppRules.problem(OpenAppPayload(slot: 1, bundle: bad, url: nil)) != nil, "refused bundle: \(bad.prefix(20))")
            }
            expectThrows("decode refuses a bad OPEN_APP") {
                _ = try Message.decode(type: .openApp, payload: Data(#"{"slot":1,"url":"http://example.com"}"#.utf8))
            }
            expectThrows("decode refuses oversize icons") {
                _ = try Message.decode(type: .appIcons, payload: Data(#"{"bundles":["com.apple.Notes"],"size":4096}"#.utf8))
            }
            expectThrows("decode refuses a bad bundle in APP_ICONS") {
                _ = try Message.decode(type: .appIcons, payload: Data(#"{"bundles":["/bin/sh"],"size":64}"#.utf8))
            }
            let many = (0..<65).map { "\"com.x.a\($0)\"" }.joined(separator: ",")
            expectThrows("at most 64 icons a request") {
                _ = try Message.decode(type: .appIcons, payload: Data("{\"bundles\":[\(many)],\"size\":64}".utf8))
            }
        }

        run("installed apps: one per bundle id, the helper and nameless bundles left out, sorted like Finder") {
            let found = [
                AppCatalog.Found(bundle: "com.apple.finder", name: "Finder"),
                AppCatalog.Found(bundle: "com.google.Chrome", name: "Google Chrome.app"),
                AppCatalog.Found(bundle: nil, name: "Broken.app"),
                AppCatalog.Found(bundle: "local.carter.office-helper", name: "OfficeHelper.app"),
                AppCatalog.Found(bundle: "com.apple.Notes", name: "Notes"),
                AppCatalog.Found(bundle: "com.google.Chrome", name: "Chrome copy"),
                AppCatalog.Found(bundle: "com.app.ten", name: "App 10"),
                AppCatalog.Found(bundle: "com.app.two", name: "App 2"),
            ]
            expectEqual(AppCatalog.list(found, excluding: ["local.carter.office-helper"]), [
                AppInfo(bundle: "com.app.two", name: "App 2"),
                AppInfo(bundle: "com.app.ten", name: "App 10"),
                AppInfo(bundle: "com.apple.finder", name: "Finder"),
                AppInfo(bundle: "com.google.Chrome", name: "Google Chrome"),
                AppInfo(bundle: "com.apple.Notes", name: "Notes"),
            ])
        }

        run("which window moves: a new one wins; else the frontmost (after a grace when a new one was asked for)") {
            expectEqual(AppWindowPick.choose(before: [1, 2], now: [1, 2], askedForNew: false, elapsed: 0), 1,
                        "running with windows: its frontmost moves at once")
            expectEqual(AppWindowPick.choose(before: [1, 2], now: [3, 1, 2], askedForNew: false, elapsed: 0), 3, "a new window wins")
            expectEqual(AppWindowPick.choose(before: [], now: [], askedForNew: true, elapsed: 10), nil as Int?, "no window yet: wait")
            expectEqual(AppWindowPick.choose(before: [], now: [7], askedForNew: true, elapsed: 0.1), 7, "the launched app's first window")
            expectEqual(AppWindowPick.choose(before: [5], now: [5], askedForNew: true, elapsed: 0.5), nil as Int?,
                        "a web page asked for: give the browser a moment to open its window")
            expectEqual(AppWindowPick.choose(before: [5], now: [5], askedForNew: true, elapsed: 2), 5,
                        "it opened a tab in its front window: that window moves")
        }

        run("the window fills the display's visible area") {
            let visible = CGRect(x: 1440, y: 25, width: 1920, height: 1055)
            expectEqual(AppWindowFit.frame(visible: visible), visible)
            expectEqual(AppWindowFit.frame(visible: CGRect(x: 0.5, y: 24.6, width: 100, height: 50)), CGRect(x: 0, y: 24, width: 101, height: 51))
        }
    }
}
