#!/bin/bash
# Compiles the pure helper sources (no AppKit / ScreenCaptureKit) with the tests into one executable and runs it.
# Only Command Line Tools are needed (no Xcode, no XCTest). Non-zero exit on any failure.
# The tests also (re)write the golden protocol fixtures in Tests/fixtures/*.bin for the Java side.
set -euo pipefail
here="$(cd "$(dirname "$0")" && pwd)"
out="$here/build/tests"
mkdir -p "$out"
swiftc -swift-version 5 -target arm64-apple-macos14.0 \
  "$here/Sources/OfficeHelper/Protocol.swift" \
  "$here/Sources/OfficeHelper/DisplaySpec.swift" \
  "$here/Sources/OfficeHelper/HotkeyLogic.swift" \
  "$here/Sources/OfficeHelper/Pointer.swift" \
  "$here/Sources/OfficeHelper/Server.swift" \
  "$here"/Tests/OfficeHelperTests/*.swift \
  -o "$out/office-helper-tests"
"$out/office-helper-tests" "$here/Tests/fixtures"
