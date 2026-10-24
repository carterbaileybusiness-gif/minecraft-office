#!/bin/bash
# Runs WindowProbe: the mod's MacWindow (borderless fill, floating, click-through) on a real SDL3 window, no Minecraft.
# Needs the mod built first (./gradlew build: build/classes/java/client) and JDK 25 (JAVA_HOME). Opens a window that
# briefly covers the main display (about 3 s), then closes it. Usage: helper-mac/Tools/windowprobe/run.sh
set -euo pipefail
here="$(cd "$(dirname "$0")" && pwd)"
repo="$(cd "$here/../../.." && pwd)"
out="$repo/helper-mac/build/tools/windowprobe"
mkdir -p "$out"
java_home="${JAVA_HOME:?set JAVA_HOME to a JDK 25}"
m2="$HOME/.gradle/caches/modules-2/files-2.1"
jar() { find "$m2/$1" -name "$2" | grep -v sources | head -1; }
cp="$repo/build/classes/java/client"
for j in "$(jar org.lwjgl/lwjgl lwjgl-3.4.3.jar)" "$(jar org.lwjgl/lwjgl lwjgl-3.4.3-natives-macos-arm64.jar)" \
         "$(jar org.lwjgl/lwjgl-sdl lwjgl-sdl-3.4.3.jar)" "$(jar org.lwjgl/lwjgl-sdl lwjgl-sdl-3.4.3-natives-macos-arm64.jar)" \
         "$(jar net.java.dev.jna/jna jna-5.17.0.jar)" "$(jar org.slf4j/slf4j-api slf4j-api-2.0.17.jar)" \
         "$(jar org.jspecify/jspecify jspecify-1.0.0.jar)"; do
  [ -n "$j" ] || { echo "a jar is missing from the Gradle cache (build the mod first)" >&2; exit 2; }
  cp="$cp:$j"
done
swiftc -O -target arm64-apple-macos14.0 "$here/windowinfo.swift" -o "$out/windowinfo"
"$java_home/bin/javac" -d "$out" -cp "$cp" "$here/WindowProbe.java"
"$java_home/bin/java" -XstartOnFirstThread -cp "$out:$cp" WindowProbe "$out/windowinfo"
