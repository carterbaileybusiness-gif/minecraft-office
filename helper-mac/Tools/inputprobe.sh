#!/bin/bash
# Builds and runs helper-mac/Tools/inputprobe.swift with the helper's own EventInjector / VirtualDisplays code, then
# (if built) runs it again against the real signed helper. Usage: inputprobe.sh [path/to/OfficeHelper.app]
# Needs Accessibility for the app that runs it (Terminal etc.); opens and closes TextEdit, creates and removes two
# virtual displays. Prints PASS/FAIL per check; "[informational]" checks record which routes do NOT keep Minecraft's
# input (they are expected to come out either way). Exit status: 1 when a required check failed.
set -euo pipefail
here="$(cd "$(dirname "$0")/.." && pwd)"
out="$here/build/tools"
mkdir -p "$out"
swiftc -swift-version 5 -O -parse-as-library -target arm64-apple-macos14.0 \
  -import-objc-header "$here/Sources/OfficeHelper/CGVirtualDisplay.h" \
  -framework AppKit -framework ApplicationServices -framework CoreGraphics \
  "$here/Sources/OfficeHelper/DisplaySpec.swift" \
  "$here/Sources/OfficeHelper/VirtualDisplays.swift" \
  "$here/Sources/OfficeHelper/Pointer.swift" \
  "$here/Sources/OfficeHelper/HotkeyLogic.swift" \
  "$here/Sources/OfficeHelper/EventInjector.swift" \
  "$here/Sources/OfficeHelper/Protocol.swift" \
  "$here/Tools/inputprobe.swift" \
  "$here/Tools/inputprobe_helper.swift" \
  -o "$out/inputprobe"
app="${1:-$here/build/OfficeHelper.app}"
if pgrep -x OfficeHelper >/dev/null; then
  echo "OfficeHelper is running (a game in progress?): not probing; it takes over the mouse and focus for ~45 s" >&2
  exit 2
fi
status=0
echo "== delivery routes (the probe posts events itself)"
"$out/inputprobe" || status=1
if [ -d "$app" ]; then
  echo "== end to end through the signed helper: $app"
  "$out/inputprobe" --helper "$app" || status=1
else
  echo "(no helper at $app: build it with helper-mac/build.sh for the end-to-end part)"
fi
exit $status
