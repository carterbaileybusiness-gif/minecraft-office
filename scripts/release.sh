#!/bin/bash
# Cuts a release from Carter's Mac:  scripts/release.sh 0.1.1
#   1. bump version in gradle.properties + pack/pack.toml, commit, tag v<version>, push branch + tag
#   2. wait for the "release" workflow (builds the mod jar, publishes the GitHub Release)
#   3. write pack/mods/office.pw.toml (release asset URL + sha512), `packwiz refresh`, build the .mrpack,
#      upload it + the updated manifest.json to the release
#   4. commit + push the pack change; the "pages" workflow then publishes it, and friends get the new mod on Play
# Afterwards run scripts/release-helper.sh to add the signed helper. Details: RELEASING.md.
# Env: PACKWIZ  path to the packwiz binary (default: PATH, then ~/Library/Caches/minecraft-office-tools/bin/packwiz)
set -euo pipefail

REPO="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$REPO"
SLUG="carterbaileybusiness-gif/minecraft-office"

die() { echo "release.sh: $*" >&2; exit 1; }
say() { printf '\n==> %s\n' "$*"; }

version="${1:-}"
[[ "$version" =~ ^[0-9]+\.[0-9]+\.[0-9]+$ ]] || die "usage: scripts/release.sh <version>   e.g. 0.1.1"
tag="v$version"

PACKWIZ="${PACKWIZ:-$(command -v packwiz || true)}"
[ -n "$PACKWIZ" ] || PACKWIZ="$HOME/Library/Caches/minecraft-office-tools/bin/packwiz"
[ -x "$PACKWIZ" ] || die "packwiz not found (set PACKWIZ=/path/to/packwiz)"
command -v gh >/dev/null || die "gh (GitHub CLI) not found"
gh auth status >/dev/null 2>&1 || die "gh is not logged in (run: gh auth login)"

branch="$(git rev-parse --abbrev-ref HEAD)"
case "$branch" in
  main|feat/v1) ;;
  *) die "release from main or feat/v1 (the pages workflow only publishes those); you are on $branch" ;;
esac
[ -z "$(git status --porcelain)" ] || die "working tree is not clean; commit or stash first"
git fetch --quiet origin
[ "$(git rev-parse HEAD)" = "$(git rev-parse "origin/$branch")" ] || die "$branch is not in sync with origin/$branch (push or pull first)"
if git rev-parse -q --verify "refs/tags/$tag" >/dev/null || git ls-remote --exit-code --tags origin "$tag" >/dev/null 2>&1; then
  die "tag $tag already exists (to redo a failed release: git push --delete origin $tag; git tag -d $tag)"
fi

# ---- 1. bump, commit, tag, push ----------------------------------------------
say "Bumping version to $version"
current="$(sed -n 's/^version=//p' gradle.properties)"
if [ "$current" != "$version" ]; then
  sed -i.bak "s/^version=.*/version=$version/" gradle.properties && rm gradle.properties.bak
  sed -i.bak "s/^version = .*/version = \"$version\"/" pack/pack.toml && rm pack/pack.toml.bak
  git add gradle.properties pack/pack.toml
  git commit -q -m "release: v$version"
else
  echo "gradle.properties is already at $version, not bumping"
fi
git tag -a "$tag" -m "Minecraft Office $version"
say "Pushing $branch and $tag"
git push origin "$branch" "$tag"

# ---- 2. wait for the release workflow -----------------------------------------
say "Waiting for the release workflow"
run_id=""
for _ in $(seq 1 30); do
  run_id="$(gh run list --repo "$SLUG" --workflow release.yml --branch "$tag" --limit 1 --json databaseId --jq '.[0].databaseId // empty')"
  [ -n "$run_id" ] && break
  sleep 5
done
[ -n "$run_id" ] || die "release workflow did not start; check https://github.com/$SLUG/actions"
gh run watch "$run_id" --repo "$SLUG" --exit-status \
  || die "release workflow failed: https://github.com/$SLUG/actions/runs/$run_id (fix, then delete the tag and re-run)"

# ---- 3. pack/mods/office.pw.toml ----------------------------------------------
say "Fetching release assets"
work="$REPO/build/release-$version"
rm -rf "$work"; mkdir -p "$work"
gh release download "$tag" --repo "$SLUG" --dir "$work" --pattern "office-$version.jar" --pattern manifest.json
jar="$work/office-$version.jar"
expected="$(python3 -c 'import json,sys; print(json.load(open(sys.argv[1]))["mod"]["sha256"])' "$work/manifest.json")"
actual="$(shasum -a 256 "$jar" | cut -d' ' -f1)"
[ "$expected" = "$actual" ] || die "downloaded jar sha256 $actual does not match manifest $expected"
sha512="$(shasum -a 512 "$jar" | cut -d' ' -f1)"

cat > pack/mods/office.pw.toml <<TOML
name = "Minecraft Office"
filename = "office-$version.jar"
side = "both"

[download]
url = "https://github.com/$SLUG/releases/download/$tag/office-$version.jar"
hash-format = "sha512"
hash = "$sha512"
TOML
say "packwiz refresh"
( cd pack && "$PACKWIZ" refresh )
python3 scripts/check-pack.py pack

# ---- .mrpack (needs the release asset to exist, hence after the workflow) -------
say "Building the .mrpack"
bash scripts/make-friend-instance.sh
python3 scripts/release-manifest.py "$work/manifest.json" set mrpack build/dist/Minecraft-Office.mrpack \
  "https://github.com/$SLUG/releases/download/$tag/Minecraft-Office.mrpack" --version "$version"
gh release upload "$tag" --repo "$SLUG" --clobber build/dist/Minecraft-Office.mrpack "$work/manifest.json"

# ---- 4. publish the pack ------------------------------------------------------
say "Committing and pushing the pack (pages workflow publishes it)"
git add pack
git commit -q -m "pack: minecraft office $version"
git push origin "$branch"

cat <<DONE

Release $tag is out and the pack points at it.
  Release: https://github.com/$SLUG/releases/tag/$tag
Next, from this Mac:  scripts/release-helper.sh $version     (signed helper; Macs cannot auto-download it until then)
DONE
