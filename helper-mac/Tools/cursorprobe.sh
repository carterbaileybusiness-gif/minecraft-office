#!/bin/bash
# Builds and runs cursorprobe.swift against the signed helper: is the system cursor hidden in work mode, and back after?
# Usage: cursorprobe.sh [path/to/OfficeHelper.app]. Takes the screen for ~10 s; refuses while an OfficeHelper runs.
set -euo pipefail
here="$(cd "$(dirname "$0")/.." && pwd)"
out="$here/build/tools"
mkdir -p "$out"
swiftc -O -target arm64-apple-macos14.0 "$here/Tools/cursorprobe.swift" -o "$out/cursorprobe"
"$out/cursorprobe" "${1:-$here/build/OfficeHelper.app}" "$here/Tools/devclient.py"
