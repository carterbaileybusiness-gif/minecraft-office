#!/bin/bash
# Builds helper-mac/build/OfficeHelper.app with swiftc (Command Line Tools only; no Xcode, no SwiftPM).
# Signs it with the persistent local identity (sign.sh) unless --no-sign is given, OFFICE_HELPER_NO_SIGN=1, or CI is
# set; unsigned builds get an ad-hoc signature (fine for CI, but macOS permissions won't survive rebuilds).
set -euo pipefail
here="$(cd "$(dirname "$0")" && pwd)"
version="0.1.0"
sign=1
for arg in "$@"; do
  case "$arg" in
    --no-sign) sign=0 ;;
    *) echo "usage: build.sh [--no-sign]" >&2; exit 64 ;;
  esac
done
[ "${OFFICE_HELPER_NO_SIGN:-}" = "1" ] && sign=0
[ -n "${CI:-}" ] && sign=0

app="$here/build/OfficeHelper.app"
rm -rf "$app"
mkdir -p "$app/Contents/MacOS"
swiftc -swift-version 5 -O -target arm64-apple-macos14.0 \
  -import-objc-header "$here/Sources/OfficeHelper/CGVirtualDisplay.h" \
  -framework AppKit -framework ScreenCaptureKit -framework CoreMedia -framework CoreVideo \
  -framework Accelerate -framework ApplicationServices -framework CoreGraphics -framework Carbon \
  "$here"/Sources/OfficeHelper/*.swift \
  -o "$app/Contents/MacOS/OfficeHelper"

cat > "$app/Contents/Info.plist" <<EOF
<?xml version="1.0" encoding="UTF-8"?>
<!DOCTYPE plist PUBLIC "-//Apple//DTD PLIST 1.0//EN" "http://www.apple.com/DTDs/PropertyList-1.0.dtd">
<plist version="1.0">
<dict>
  <key>CFBundleIdentifier</key><string>local.carter.office-helper</string>
  <key>CFBundleName</key><string>OfficeHelper</string>
  <key>CFBundleDisplayName</key><string>Minecraft Office Helper</string>
  <key>CFBundleExecutable</key><string>OfficeHelper</string>
  <key>CFBundlePackageType</key><string>APPL</string>
  <key>CFBundleShortVersionString</key><string>$version</string>
  <key>CFBundleVersion</key><string>$version</string>
  <key>LSMinimumSystemVersion</key><string>14.0</string>
  <key>LSUIElement</key><true/>
  <key>NSHighResolutionCapable</key><true/>
  <key>NSScreenCaptureUsageDescription</key><string>Minecraft Office shows your screens and chosen windows on your holograms in Minecraft.</string>
</dict>
</plist>
EOF
plutil -lint -s "$app/Contents/Info.plist"

if [ "$sign" = "1" ]; then
  bash "$here/sign.sh" "$app"
else
  codesign --force --sign - --timestamp=none "$app"
  echo "Signing skipped (ad-hoc signature)."
fi
codesign --verify --strict "$app"
printf '%s\n' "$app"
