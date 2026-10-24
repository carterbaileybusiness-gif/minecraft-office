#!/bin/bash
# One-click test launcher for "Minecraft Office" on an 8 GB M1 Mac.
# Sets up (or updates) a Prism Launcher instance with the packwiz client mods + our mod jar, then launches it
# while holding the shared heavy lock so agents don't run builds/servers at the same time.
# Env: PLAY_TEST_DRY_RUN=1  -> do all setup, stop before launching the game.
set -u

REPO="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
PRISM_APP="/Applications/Prism Launcher.app"
PRISM_BIN="$PRISM_APP/Contents/MacOS/prismlauncher"
PRISM_DIR="$HOME/Library/Application Support/PrismLauncher"
ID="minecraft-office-test"
INST="$PRISM_DIR/instances/$ID"
BOOTSTRAP="$HOME/Library/Caches/minecraft-office-tools/packwiz-installer-bootstrap.jar"
LOCK="$HOME/Library/Caches/minecraft-office-heavy.lock"
MC_VERSION="26.3"
FABRIC_VERSION="0.20.1"
DRY="${PLAY_TEST_DRY_RUN:-0}"
HAVE_LOCK=0
MINIMISED=0
LAUNCH_MARK=""

say()  { printf '\n==> %s\n' "$*"; }
info() { printf '    %s\n' "$*"; }
die()  { printf '\nERROR: %s\n' "$*" >&2; if [ -t 0 ]; then printf 'Press Return to close.'; read -r _; fi; exit 1; }

release_lock() {
  if [ "$HAVE_LOCK" = 1 ]; then rm -rf "$LOCK"; HAVE_LOCK=0; fi
}
cleanup() {
  # The Terminal window minimised itself while the game ran: put it back on every way out (quit, error, Ctrl-C).
  if [ "$MINIMISED" = 1 ]; then printf '\033[1t'; MINIMISED=0; fi
  [ -n "$LAUNCH_MARK" ] && rm -f "$LAUNCH_MARK"
  release_lock
}
trap cleanup EXIT
trap 'echo; echo "Interrupted - releasing lock."; exit 130' INT TERM HUP

printf 'Minecraft Office - test launcher\n'
[ "$DRY" = 1 ] && printf '(dry run: setup only, the game will not be launched)\n'

# ---- 1. checks -------------------------------------------------------------
say "Checking tools"
[ -x "$PRISM_BIN" ] || die "Prism Launcher not found at $PRISM_APP"
[ -f "$BOOTSTRAP" ] || die "packwiz bootstrap missing: $BOOTSTRAP"
[ -f "$REPO/pack/pack.toml" ] || die "pack/pack.toml not found in $REPO"
JAVA="$(ls -d "$HOME"/.gradle/jdks/*25*/*/Contents/Home/bin/java "$HOME"/.gradle/jdks/*25*/Contents/Home/bin/java 2>/dev/null | head -1)"
[ -n "$JAVA" ] && [ -x "$JAVA" ] || die "No JDK 25 under ~/.gradle/jdks (run ./gradlew build once to provision it)."
info "Java 25: $JAVA"
if pgrep -f "Prism Launcher.app/Contents/MacOS/prismlauncher" >/dev/null 2>&1; then
  die "Prism Launcher is running. Quit it first (Prism Launcher > Quit), then run this again."
fi
FREE_KB=$(df -k "$HOME" | awk 'NR==2 {print $4}')
[ "$FREE_KB" -ge 4194304 ] || die "Under 4 GiB free disk - free some space first."

# ---- 2. Prism instance -----------------------------------------------------
say "Setting up Prism instance '$ID'"
mkdir -p "$INST"
if [ -d "$INST/.minecraft" ]; then GAME="$INST/.minecraft"; else GAME="$INST/minecraft"; fi
mkdir -p "$GAME/mods"

# Skip Prism's first-run wizard (only if Prism has never been run on this Mac).
if [ ! -f "$PRISM_DIR/prismlauncher.cfg" ]; then
  cat > "$PRISM_DIR/prismlauncher.cfg" <<CFG
[General]
ApplicationTheme=system
IgnoreJavaWizard=true
JavaPath=$JAVA
Language=en_US
CFG
  info "wrote minimal prismlauncher.cfg (skips the setup wizard; you'll still sign in once)"
fi

# No GitHub release of the helper exists yet: when a locally built (helper-mac/build.sh) OfficeHelper.app is present,
# point the mod at it with the dev override -Doffice.helperPath so it never tries to download one.
HELPER_APP="$REPO/helper-mac/build/OfficeHelper.app"
HELPER_ARG=""
if [ -d "$HELPER_APP" ]; then
  # Prism splits JvmArgs on spaces and ignores quotes, and the repo path has a space: link the app from a space-free path.
  HELPER_LINK="$HOME/.minecraft-office/OfficeHelper.app"
  mkdir -p "$HOME/.minecraft-office"
  ln -sfn "$HELPER_APP" "$HELPER_LINK"
  HELPER_ARG="-Doffice.helperPath=$HELPER_LINK"
  info "using local helper: $HELPER_APP (via $HELPER_LINK)"
else
  info "NOTE: no local helper at $HELPER_APP (build with: scripts/heavy.sh bash helper-mac/build.sh)"
fi

/usr/bin/python3 -I - "$INST/instance.cfg" "$JAVA" "$HELPER_ARG" <<'PY'
import sys, os
path, java, helper_arg = sys.argv[1:4]
jvm_args = "-XX:+UseZGC"
if helper_arg:
    jvm_args += " " + helper_arg  # space-free path (Prism splits JvmArgs on spaces and does not honour quotes)
want = {
  "InstanceType": "OneSix", "ConfigVersion": "1.3", "name": "Minecraft Office (Test)", "iconKey": "default",
  "OverrideMemory": "true", "MaxMemAlloc": "3072", "MinMemAlloc": "1024",
  "OverrideJavaArgs": "true", "JvmArgs": jvm_args,
  "OverrideJavaLocation": "true", "JavaPath": java,
}
lines, seen = [], set()
if os.path.exists(path):
    for l in open(path).read().splitlines():
        k = l.split("=", 1)[0]
        if k in want:
            lines.append(f"{k}={want[k]}"); seen.add(k)
        elif l.strip() != "[General]":
            lines.append(l)
for k, v in want.items():
    if k not in seen: lines.append(f"{k}={v}")
open(path, "w").write("[General]\n" + "\n".join(lines) + "\n")
PY
cat > "$INST/mmc-pack.json" <<JSON
{
    "components": [
        { "cachedName": "LWJGL 3", "cachedVersion": "3.4.3", "cachedVolatile": true, "dependencyOnly": true, "uid": "org.lwjgl3", "version": "3.4.3" },
        { "cachedName": "Minecraft", "cachedRequires": [ { "suggests": "3.4.3", "uid": "org.lwjgl3" } ], "cachedVersion": "$MC_VERSION", "important": true, "uid": "net.minecraft", "version": "$MC_VERSION" },
        { "cachedName": "Intermediary Mappings", "cachedRequires": [ { "equals": "$MC_VERSION", "uid": "net.minecraft" } ], "cachedVersion": "$MC_VERSION", "cachedVolatile": true, "dependencyOnly": true, "uid": "net.fabricmc.intermediary", "version": "$MC_VERSION" },
        { "cachedName": "Fabric Loader", "cachedRequires": [ { "uid": "net.fabricmc.intermediary" } ], "cachedVersion": "$FABRIC_VERSION", "uid": "net.fabricmc.fabric-loader", "version": "$FABRIC_VERSION" }
    ],
    "formatVersion": 1
}
JSON
info "instance.cfg + mmc-pack.json written (Minecraft $MC_VERSION, Fabric Loader $FABRIC_VERSION, 3 GB heap)"

# ---- 3. all pack mods via packwiz (singleplayer needs the server-side world mods) -------------------------------------------
PACK_URL="file://$(printf '%s' "$REPO/pack/pack.toml" | sed 's/ /%20/g')"
say "Installing all pack mods (singleplayer runs the server side too) (first run downloads ~hundreds of MB)"
( cd "$GAME" && "$JAVA" -jar "$BOOTSTRAP" -g -s both "$PACK_URL" ) \
  || die "packwiz install failed (see output above). Check your internet connection."
# Headless mode switches every optional mod ON; turn them off again (Iris, Distant Horizons, LambDynamicLights)
# and remember that choice in packwiz.json so later runs don't re-download them.
/usr/bin/python3 -I - "$GAME" <<'PY'
import sys, json, os
game = sys.argv[1]
p = os.path.join(game, "packwiz.json")
d = json.load(open(p))
for f, e in d.get("cachedFiles", {}).items():
    if e.get("isOptional"):
        e["optionValue"] = False
        loc = e.get("cachedLocation")
        if loc and os.path.exists(os.path.join(game, loc)):
            os.remove(os.path.join(game, loc))
            print("    left optional mod off:", os.path.basename(loc))
json.dump(d, open(p, "w"))
PY

# ---- 4. our own mod --------------------------------------------------------
say "Copying our mod jar"
OURJAR=""
while IFS= read -r f; do
  case "$f" in *-sources.jar|*-dev.jar|*-javadoc.jar) continue;; esac
  OURJAR="$f"; break
done < <(ls -t "$REPO"/build/libs/office-*.jar 2>/dev/null)
if [ -n "$OURJAR" ]; then
  rm -f "$GAME"/mods/office-*.jar
  cp "$OURJAR" "$GAME/mods/"
  info "installed $(basename "$OURJAR")"
else
  info "NOTE: no built mod jar in build/libs/ yet - launching with the modpack only."
  info "      (build one with: scripts/heavy.sh ./gradlew build)"
fi

info "mods folder has $(ls "$GAME/mods"/*.jar 2>/dev/null | wc -l | tr -d ' ') jars in total"

# ---- 4b. macOS fullscreen: never the exclusive kind ------------------------
# Sodium switches Minecraft to *exclusive* fullscreen on first launch on any non-Windows system. On a Mac that is a
# display-capturing window with no title bar, no green button and no menu bar: the only way out is the F11 key, which on
# a MacBook is Volume Down (fn+F11 is often "Show Desktop"). Use macOS's normal fullscreen instead; when the switch is
# made, the next start is windowed once, so a game left stuck in exclusive fullscreen comes back as a window.
OPTS="$GAME/options.txt"
if [ -f "$OPTS" ] && grep -q '^exclusiveFullscreen:true' "$OPTS"; then
  /usr/bin/sed -i '' -e 's/^exclusiveFullscreen:true$/exclusiveFullscreen:false/' -e 's/^fullscreen:true$/fullscreen:false/' "$OPTS"
  info "options.txt: exclusive fullscreen off (starts windowed; fn+F11 or Options > Video Settings > Fullscreen gives macOS fullscreen)"
fi
# Laptop + monitors (spec 5.10): Minecraft starts as a window, which the mod turns into a borderless window filling the
# main display (not macOS's separate full-screen Space, where your other windows - the ones the Mirror hologram shows -
# would be on another desktop). -Doffice.borderless=false keeps a normal window.
if [ -f "$OPTS" ] && grep -q '^fullscreen:true' "$OPTS"; then
  /usr/bin/sed -i '' -e 's/^fullscreen:true$/fullscreen:false/' "$OPTS"
  info "options.txt: starts windowed; the mod makes the window fill the screen without a separate Space"
fi
# Sodium only forces exclusive mode while its "has edited fullscreen option" flag is unset. The pack ships that flag
# (config/sodium-options.json, preserve = true), but an instance that already has the file keeps its own copy: set it.
/usr/bin/python3 -I - "$GAME/config/sodium-options.json" <<'PY'
import sys, json, os
p = sys.argv[1]
if os.path.exists(p):
    d = json.load(open(p))
    n = d.setdefault("notifications", {})
    if n.get("has_edited_fullscreen_option") is not True:
        n["has_edited_fullscreen_option"] = True
        json.dump(d, open(p, "w"), indent=2)
        print("    sodium-options.json: Sodium won't switch to exclusive fullscreen")
PY

if [ "$DRY" = 1 ]; then
  say "Dry run done - stopping before launch."
  info "instance: $INST"
  exit 0
fi

# ---- 5. lock + launch ------------------------------------------------------
say "Pausing heavy agent work (taking the build lock)"
waited=0
until mkdir "$LOCK" 2>/dev/null; do
  holder=$(cat "$LOCK/pid" 2>/dev/null)
  if [ -n "$holder" ] && ! kill -0 "$holder" 2>/dev/null; then rm -rf "$LOCK"; continue; fi
  if [ "$waited" -ge 600 ]; then die "Gave up after 10 min waiting for a build to finish."; fi
  [ $((waited % 30)) -eq 0 ] && info "waiting for a build to finish... (${waited}s)"
  sleep 5; waited=$((waited + 5))
done
echo $$ > "$LOCK/pid"; HAVE_LOCK=1
info "lock held"

game_pid() {
  ps -axo pid=,command= | awk -v me="$$" '/minecraft-office-test/ && /java/ && !/awk/ && !/prismlauncher/ && $1 != me {print $1; exit}'
}

# Minecraft's window is up: its log (rotated on start, so newer than the launch) says the SDL window was created.
LAUNCH_MARK="$(mktemp -t minecraft-office-launch)"
window_up() {
  [ "$GAME/logs/latest.log" -nt "$LAUNCH_MARK" ] && grep -q 'Created window using SDL' "$GAME/logs/latest.log" 2>/dev/null
}
# This Terminal window steps aside (minimise; cleanup restores it) with xterm escape codes: no AppleScript, no
# Automation prompt. The hint stays readable for 5 s first.
in_terminal() { [ "${TERM_PROGRAM:-}" = "Apple_Terminal" ] && [ -t 1 ]; }

say "Launching Minecraft Office via Prism (sign in with Microsoft if it asks)"
"$PRISM_BIN" --launch "$ID" >/dev/null 2>&1 &
PRISM_PID=$!
start=$SECONDS; seen=0; told=0; fronted=0
while :; do
  if [ -n "$(game_pid)" ]; then
    if [ "$seen" = 0 ]; then seen=1; info "game is running - agents stay paused until you quit it"; fi
    if [ "$fronted" = 0 ] && window_up; then
      fronted=1
      info ">>> Minecraft's window is open. If keys or mouse look don't reach it, click the Minecraft window once. <<<"
      if in_terminal; then
        info "(this Terminal window minimises itself in 5 s and comes back when the game closes)"
        sleep 5; printf '\033[2t'; MINIMISED=1
      fi
    fi
  else
    [ "$seen" = 1 ] && break
    kill -0 "$PRISM_PID" 2>/dev/null || break
    if [ $((SECONDS - start)) -ge 180 ] && [ "$told" = 0 ]; then told=1; info "still waiting for the game to start (signing in?)..."; fi
  fi
  sleep 3
done
say "Game closed - releasing lock. Agents may resume."
release_lock
sleep 2
exit 0
