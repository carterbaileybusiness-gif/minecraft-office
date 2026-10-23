#!/bin/bash
# Cuts a release from Carter's Mac:  scripts/release.sh 0.1.1
#   1. bump version in gradle.properties + pack/pack.toml, commit, tag v<version>, push branch + tag
#   2. wait for the "release" workflow (builds the mod jar, publishes the GitHub Release)
#   3. write pack/mods/office.pw.toml (release asset URL + sha512), `packwiz refresh`, build the .mrpack,
#      upload it + the updated manifest.json to the release
#   4. commit + push the pack change; the "pages" workflow then publishes it, and friends get the new mod on Play
# Afterwards run scripts/release-helper.sh to add the signed helper. Details: RELEASING.md.
#
# Safe to re-run: if a run dies midway (network, push failure, packwiz error), run the same command again. Every step
# skips what is already done: bump already committed, tag already created or pushed, release already published, pack
# already committed. The only unrecoverable case is a FAILED release workflow (see RELEASING.md).
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
git fetch --quiet origin

# origin/<branch> must be an ancestor of HEAD (never behind). Unpushed commits from an earlier, interrupted run of this
# script are fine, but only if they touch just the version files and pack/ (nothing else gets published by accident).
git merge-base --is-ancestor "origin/$branch" HEAD || die "$branch is behind or has diverged from origin/$branch (pull first)"
while IFS= read -r changed; do
  case "$changed" in
    gradle.properties|pack/*) ;;
    *) die "unpushed commit touches $changed; push or drop it before releasing" ;;
  esac
done < <(git diff --name-only "origin/$branch" HEAD)

# Where does the tag stand? (annotated tags show up peeled as ^{} in ls-remote)
remote_tag_commit="$(git ls-remote origin "refs/tags/$tag" "refs/tags/$tag^{}" | awk '/\^\{\}$/ {p=$1} !/\^\{\}$/ && NF {r=$1} END {print (p != "" ? p : r)}')"
local_tag_commit="$(git rev-parse -q --verify "refs/tags/$tag^{commit}" || true)"

resume=0
if [ -n "$remote_tag_commit" ]; then
  # Tag already on origin: this is a re-run. HEAD must be the tagged commit or a descendant that only touches pack/.
  resume=1
  [ -n "$local_tag_commit" ] || git fetch --quiet origin "refs/tags/$tag:refs/tags/$tag"
  tag_commit="$(git rev-parse "refs/tags/$tag^{commit}")"
  [ "$tag_commit" = "$remote_tag_commit" ] || die "local tag $tag differs from origin's; delete the local one (git tag -d $tag) and re-run"
  git merge-base --is-ancestor "$tag_commit" HEAD || die "tag $tag on origin is not an ancestor of HEAD; check out the commit it was cut from"
  while IFS= read -r changed; do
    case "$changed" in
      pack/*) ;;
      *) die "HEAD has changes outside pack/ since $tag ($changed); a resumed release must continue from the tagged commit" ;;
    esac
  done < <(git diff --name-only "$tag_commit" HEAD)
  say "Resuming release $tag (tag already on origin)"
  # An interrupted run can leave regenerated pack files uncommitted; step 3 rewrites them, so that is fine here.
  while IFS= read -r dirty; do
    case "${dirty:3}" in
      pack/*) ;;
      *) die "working tree has changes outside pack/ (${dirty:3}); commit or stash them first" ;;
    esac
  done < <(git status --porcelain)
elif [ -n "$local_tag_commit" ] && [ "$local_tag_commit" != "$(git rev-parse HEAD)" ]; then
  die "local tag $tag exists but does not point at HEAD (git tag -d $tag to discard it)"
fi
if [ "$resume" = 0 ] && [ -n "$(git status --porcelain)" ]; then
  die "working tree is not clean; commit or stash first"
fi

# ---- 1. bump, commit, tag, push ----------------------------------------------
if [ "$resume" = 0 ]; then
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
  [ -n "$local_tag_commit" ] || git tag -a "$tag" -m "Minecraft Office $version"
  say "Pushing $branch and $tag"
  git push origin "$branch" "$tag"
fi

# ---- 2. wait for the release workflow -----------------------------------------
if gh release view "$tag" --repo "$SLUG" >/dev/null 2>&1; then
  say "Release $tag already exists, not waiting for the workflow"
else
  say "Waiting for the release workflow"
  run_id=""
  for _ in $(seq 1 30); do
    run_id="$(gh run list --repo "$SLUG" --workflow release.yml --branch "$tag" --limit 1 --json databaseId --jq '.[0].databaseId // empty')"
    [ -n "$run_id" ] && break
    sleep 5
  done
  [ -n "$run_id" ] || die "release workflow did not start; check https://github.com/$SLUG/actions"
  gh run watch "$run_id" --repo "$SLUG" --exit-status \
    || die "release workflow failed: https://github.com/$SLUG/actions/runs/$run_id (fix it, then delete the tag and re-run; see RELEASING.md)"
fi

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
if git diff --cached --quiet; then
  echo "pack is already committed, nothing to commit"
else
  git commit -q -m "pack: minecraft office $version"
fi
git push origin "$branch"

cat <<DONE

Release $tag is out and the pack points at it.
  Release: https://github.com/$SLUG/releases/tag/$tag
Next, from this Mac:  scripts/release-helper.sh $version     (signed helper; Macs cannot auto-download it until then)
DONE
