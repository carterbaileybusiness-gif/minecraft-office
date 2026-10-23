#!/bin/bash
# Builds the files friends install, into build/dist/:
#   Minecraft-Office.zip    Prism instance (Add Instance -> Import). Auto-updates the pack on every Play through
#                           packwiz-installer-bootstrap, run as Prism's pre-launch command.
#   Minecraft-Office.mrpack Modrinth pack with the same mods (no auto-update). Skipped when packwiz is missing or
#                           with --no-mrpack. Needs pack/mods/office.pw.toml (written by scripts/release.sh) to
#                           include our own mod.
# Env: OFFICE_PACK_URL  override the pack.toml URL baked into the instance (default: the GitHub Pages one)
#      PACKWIZ          path to the packwiz binary (default: PATH, then ~/Library/Caches/minecraft-office-tools/bin)
set -euo pipefail

REPO="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
DIST="$REPO/build/dist"
CACHE="$REPO/build/cache"
PACK_URL="${OFFICE_PACK_URL:-https://carterbaileybusiness-gif.github.io/minecraft-office/pack/pack.toml}"
MC_VERSION="$(sed -n 's/^minecraft_version=//p' "$REPO/gradle.properties")"
FABRIC_VERSION="$(sed -n 's/^loader_version=//p' "$REPO/gradle.properties")"
LWJGL_VERSION="3.4.3"   # what Prism's meta lists for Minecraft 26.3 (same as scripts/play-test.sh)
MEMORY_MB=4096

# Pinned: v0.0.3 is the latest bootstrap release (2020, stable); the checksum guards against a swapped asset.
BOOTSTRAP_URL="https://github.com/packwiz/packwiz-installer-bootstrap/releases/download/v0.0.3/packwiz-installer-bootstrap.jar"
BOOTSTRAP_SHA256="a8fbb24dc604278e97f4688e82d3d91a318b98efc08d5dbfcbcbcab6443d116c"

mrpack=1
for arg in "$@"; do
  case "$arg" in
    --no-mrpack) mrpack=0 ;;
    *) echo "usage: make-friend-instance.sh [--no-mrpack]" >&2; exit 64 ;;
  esac
done
[ -n "$MC_VERSION" ] && [ -n "$FABRIC_VERSION" ] || { echo "cannot read minecraft_version/loader_version from gradle.properties" >&2; exit 1; }

sha256_of() { if command -v sha256sum >/dev/null 2>&1; then sha256sum "$1" | cut -d' ' -f1; else shasum -a 256 "$1" | cut -d' ' -f1; fi; }

# ---- bootstrap jar ----------------------------------------------------------
mkdir -p "$CACHE" "$DIST"
BOOT="$CACHE/packwiz-installer-bootstrap.jar"
if [ ! -f "$BOOT" ] || [ "$(sha256_of "$BOOT")" != "$BOOTSTRAP_SHA256" ]; then
  echo "==> downloading packwiz-installer-bootstrap v0.0.3"
  curl -fsSL --retry 3 -o "$BOOT.part" "$BOOTSTRAP_URL"
  mv "$BOOT.part" "$BOOT"
fi
[ "$(sha256_of "$BOOT")" = "$BOOTSTRAP_SHA256" ] || { echo "bootstrap checksum mismatch, refusing to continue" >&2; rm -f "$BOOT"; exit 1; }

# ---- Prism instance zip -----------------------------------------------------
# Prism imports any zip that has instance.cfg (MultiMC/Prism format); the game folder is .minecraft.
STAGE="$REPO/build/instance-stage"
rm -rf "$STAGE"
mkdir -p "$STAGE/.minecraft"

# instance.cfg is read by Qt QSettings: a value containing quotes must be written as one quoted string with the inner
# quotes escaped. At launch Prism expands $INST_JAVA (its own Java, possibly under a path with spaces) and runs the
# command with the game folder (.minecraft) as the working directory.
cat > "$STAGE/instance.cfg" <<CFG
[General]
InstanceType=OneSix
ConfigVersion=1.3
name=Minecraft Office
iconKey=default
OverrideMemory=true
MaxMemAlloc=$MEMORY_MB
MinMemAlloc=1024
OverrideCommands=true
PreLaunchCommand="\"\$INST_JAVA\" -jar packwiz-installer-bootstrap.jar $PACK_URL"
CFG

cat > "$STAGE/mmc-pack.json" <<JSON
{
    "components": [
        { "cachedName": "LWJGL 3", "cachedVersion": "$LWJGL_VERSION", "cachedVolatile": true, "dependencyOnly": true, "uid": "org.lwjgl3", "version": "$LWJGL_VERSION" },
        { "cachedName": "Minecraft", "cachedRequires": [ { "suggests": "$LWJGL_VERSION", "uid": "org.lwjgl3" } ], "cachedVersion": "$MC_VERSION", "important": true, "uid": "net.minecraft", "version": "$MC_VERSION" },
        { "cachedName": "Intermediary Mappings", "cachedRequires": [ { "equals": "$MC_VERSION", "uid": "net.minecraft" } ], "cachedVersion": "$MC_VERSION", "cachedVolatile": true, "dependencyOnly": true, "uid": "net.fabricmc.intermediary", "version": "$MC_VERSION" },
        { "cachedName": "Fabric Loader", "cachedRequires": [ { "uid": "net.fabricmc.intermediary" } ], "cachedVersion": "$FABRIC_VERSION", "uid": "net.fabricmc.fabric-loader", "version": "$FABRIC_VERSION" }
    ],
    "formatVersion": 1
}
JSON

cp "$BOOT" "$STAGE/.minecraft/packwiz-installer-bootstrap.jar"
# Gentle defaults for 8 GB machines; Minecraft fills in everything else on first start.
printf 'renderDistance:10\nsimulationDistance:8\nonboardAccessibility:false\n' > "$STAGE/.minecraft/options.txt"

# Reproducible zip: fixed timestamps, no extra attributes, instance.cfg first.
find "$STAGE" -exec touch -t 202601010000 {} +
rm -f "$DIST/Minecraft-Office.zip"
( cd "$STAGE" && zip -X -q "$DIST/Minecraft-Office.zip" instance.cfg mmc-pack.json \
    && zip -X -q -r "$DIST/Minecraft-Office.zip" .minecraft )
echo "==> $DIST/Minecraft-Office.zip"
unzip -l "$DIST/Minecraft-Office.zip"

# ---- .mrpack -----------------------------------------------------------------
if [ "$mrpack" = 1 ]; then
  PACKWIZ="${PACKWIZ:-$(command -v packwiz || true)}"
  [ -n "$PACKWIZ" ] || PACKWIZ="$HOME/Library/Caches/minecraft-office-tools/bin/packwiz"
  if [ -x "$PACKWIZ" ]; then
    [ -f "$REPO/pack/mods/office.pw.toml" ] || echo "NOTE: pack/mods/office.pw.toml is missing, so the .mrpack has no Minecraft Office mod yet."
    rm -f "$DIST/Minecraft-Office.mrpack"
    ( cd "$REPO/pack" && "$PACKWIZ" modrinth export -o "$DIST/Minecraft-Office.mrpack" )
    # packwiz silently leaves out a mod it cannot download (e.g. a release asset that is not uploaded yet).
    # Capture first: a pipe into `grep -q` can SIGPIPE unzip, which pipefail would turn into a false failure.
    if [ -f "$REPO/pack/mods/office.pw.toml" ]; then
      mrpack_index="$(unzip -p "$DIST/Minecraft-Office.mrpack" modrinth.index.json)"
      mrpack_entries="$(unzip -Z1 "$DIST/Minecraft-Office.mrpack")"
      case "$mrpack_index
$mrpack_entries" in
        *'"mods/office-'*|*'overrides/mods/office-'*) ;;
        *)
          echo "ERROR: the .mrpack has no mods/office-*.jar (is the release asset reachable?)" >&2
          rm -f "$DIST/Minecraft-Office.mrpack"
          exit 1 ;;
      esac
    fi
    echo "==> $DIST/Minecraft-Office.mrpack"
  else
    echo "NOTE: packwiz not found, skipping the .mrpack (scripts/release.sh builds it on Carter's Mac)."
  fi
fi
