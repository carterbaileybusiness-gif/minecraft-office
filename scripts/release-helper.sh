#!/bin/bash
# Builds the Mac helper, signs it with the persistent local identity (helper-mac/sign.sh), zips it as
# OfficeHelper-<version>.zip and attaches it to the GitHub Release, with its SHA-256 in manifest.json (key "helper",
# which the mod reads to download and verify the helper). Signing only ever happens on Carter's Mac.
#   scripts/release-helper.sh [version]      (default: version in gradle.properties; the release v<version> must exist)
set -euo pipefail

REPO="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$REPO"
SLUG="carterbaileybusiness-gif/minecraft-office"

die() { echo "release-helper.sh: $*" >&2; exit 1; }
say() { printf '\n==> %s\n' "$*"; }

[ "$(uname -s)" = "Darwin" ] || die "run this on the Mac that holds the signing identity"
command -v gh >/dev/null || die "gh (GitHub CLI) not found"
gh auth status >/dev/null 2>&1 || die "gh is not logged in (run: gh auth login)"

version="${1:-$(sed -n 's/^version=//p' gradle.properties)}"
[[ "$version" =~ ^[0-9]+\.[0-9]+\.[0-9]+$ ]] || die "usage: scripts/release-helper.sh [version]"
tag="v$version"
gh release view "$tag" --repo "$SLUG" >/dev/null 2>&1 || die "release $tag does not exist yet (run scripts/release.sh $version first)"

# Do not race scripts/release.sh: it re-uploads manifest.json, which would drop a helper entry written meanwhile.
# release.sh is finished when the manifest has the mrpack entry AND the pack commit pointing at this version is on origin.
git fetch --quiet origin
pack_pushed=0
for b in main feat/v1; do
  if git show "origin/$b:pack/mods/office.pw.toml" 2>/dev/null | grep -q "download/$tag/office-$version.jar"; then pack_pushed=1; fi
done
[ "$pack_pushed" = 1 ] || die "scripts/release.sh $version has not finished (pack/mods/office.pw.toml for $tag is not on origin yet); re-run it first"
work="$REPO/build/release-helper-$version"
rm -rf "$work"; mkdir -p "$work"
gh release download "$tag" --repo "$SLUG" --dir "$work" --pattern manifest.json
python3 -c 'import json,sys; sys.exit(0 if "mrpack" in json.load(open(sys.argv[1])) else 1)' "$work/manifest.json" \
  || die "manifest.json of $tag has no mrpack entry yet; scripts/release.sh $version has not finished"

if git rev-parse -q --verify "refs/tags/$tag" >/dev/null 2>&1; then
  if ! git diff --quiet "$tag" -- helper-mac; then
    echo "WARNING: helper-mac/ differs from tag $tag; you are releasing the helper from your working tree." >&2
  fi
fi

say "Building and signing OfficeHelper.app"
# CI / OFFICE_HELPER_NO_SIGN would make build.sh skip signing; this script must always sign.
env -u CI -u OFFICE_HELPER_NO_SIGN bash helper-mac/build.sh
app="$REPO/helper-mac/build/OfficeHelper.app"
codesign --verify --strict "$app"
if codesign -dv "$app" 2>&1 | grep -q 'Signature=adhoc'; then
  die "the app has only an ad-hoc signature; sign.sh did not run"
fi

say "Zipping"
dist="$REPO/build/dist"
mkdir -p "$dist"
zip_path="$dist/OfficeHelper-$version.zip"
rm -f "$zip_path"
ditto -c -k --keepParent "$app" "$zip_path"
echo "$zip_path ($(shasum -a 256 "$zip_path" | cut -d' ' -f1))"

say "Updating manifest.json and uploading"
gh release download "$tag" --repo "$SLUG" --dir "$work" --pattern manifest.json --clobber   # fresh copy, build took a while
python3 scripts/release-manifest.py "$work/manifest.json" set helper "$zip_path" \
  "https://github.com/$SLUG/releases/download/$tag/OfficeHelper-$version.zip" --version "$version"
gh release upload "$tag" --repo "$SLUG" --clobber "$zip_path" "$work/manifest.json"

echo
echo "Helper for $tag uploaded: https://github.com/$SLUG/releases/tag/$tag"
