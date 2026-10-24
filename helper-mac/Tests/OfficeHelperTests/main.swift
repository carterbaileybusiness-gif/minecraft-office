import Foundation

// Minimal test runner: only Command Line Tools are installed (no XCTest), so check.sh compiles the pure
// helper sources with these files into one executable. Exit status is non-zero when any check fails.

var failures = 0
var checks = 0
var currentTest = ""

func expect(_ condition: @autoclosure () -> Bool, _ message: String, file: String = #fileID, line: Int = #line) {
    checks += 1
    if !condition() {
        failures += 1
        print("  FAIL \(currentTest): \(message) (\(file):\(line))")
    }
}

func expectEqual<T: Equatable>(_ a: T, _ b: T, _ message: String = "", file: String = #fileID, line: Int = #line) {
    expect(a == b, "\(message) expected \(b), got \(a)", file: file, line: line)
}

func expectThrows(_ message: String, file: String = #fileID, line: Int = #line, _ body: () throws -> Void) {
    do { try body(); expect(false, "\(message): did not throw", file: file, line: line) }
    catch { expect(true, message) }
}

func run(_ name: String, _ body: () throws -> Void) {
    currentTest = name
    let before = failures
    do { try body() } catch { failures += 1; print("  FAIL \(name): threw \(error)") }
    print("\(failures == before ? "PASS" : "FAIL") \(name)")
}

let fixtureDirectory: URL? = CommandLine.arguments.count > 1 ? URL(fileURLWithPath: CommandLine.arguments[1], isDirectory: true) : nil

ProtocolTests.runAll(fixtures: fixtureDirectory)
DisplaySpecTests.runAll()
HotkeyLogicTests.runAll()
PointerTests.runAll()
AppsTests.runAll()
ArrangementTests.runAll()

print("\(checks) checks, \(failures) failures")
exit(failures == 0 ? 0 : 1)
