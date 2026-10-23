# Releasing Minecraft Office

How a new version reaches the five friends. Everything runs from **Carter's Mac** except the CI build, so the helper
signing key never leaves the machine.

## The pieces

| What | Where it lives | Made by |
|---|---|---|
| Mod jar `office-<v>.jar` | GitHub Release `v<v>` | `release.yml` (CI, on tag) |
| Prism instance `Minecraft-Office.zip` | GitHub Release | `release.yml` via `scripts/make-friend-instance.sh` |
| `manifest.json` (SHA-256 of every asset) | GitHub Release | `release.yml`, then updated by the two scripts below |
| Modrinth pack `Minecraft-Office.mrpack` | GitHub Release | `scripts/release.sh` (needs packwiz) |
| Signed helper `OfficeHelper-<v>.zip` | GitHub Release | `scripts/release-helper.sh` (Carter's Mac) |
| The packwiz pack (`pack/`) | GitHub Pages: `https://carterbaileybusiness-gif.github.io/minecraft-office/pack/pack.toml` | `pages.yml`, on every push touching `pack/` |

Friends import `Minecraft-Office.zip` once. After that every **Play** runs `packwiz-installer-bootstrap` against the
Pages URL, which pulls mods listed in `pack/` (our own mod included, via `pack/mods/office.pw.toml` pointing at the
release asset). So **a release reaches friends when the pack commit is pushed**, not when the tag is.

`manifest.json` format (schema 1): `{schema, version, tag, mod, instance, mrpack, helper}`, each entry
`{name, sha256, size, url}`. The mod's helper installer reads `helper` from
`https://github.com/carterbaileybusiness-gif/minecraft-office/releases/download/v<version>/manifest.json`
and verifies the SHA-256 before unzipping. Entries are written by `scripts/release-manifest.py`.

## Cutting a release

From a clean, pushed `main` (or `feat/v1` until it is merged), with `gh` logged in and packwiz installed:

```
scripts/release.sh 0.1.1
scripts/release-helper.sh 0.1.1      # after release.sh finishes; signs on this Mac
```

`release.sh` does, in order:

1. bumps `version` in `gradle.properties` and `pack/pack.toml`, commits `release: v0.1.1`, tags `v0.1.1`, pushes both;
2. waits for the `release` workflow (build + tests, unsigned helper build as a CI check, creates the GitHub Release
   with the jar, `Minecraft-Office.zip` and `manifest.json`);
3. downloads the jar, checks its SHA-256 against the manifest, writes `pack/mods/office.pw.toml` (release URL +
   sha512), runs `packwiz refresh`, builds `Minecraft-Office.mrpack`, uploads it and the updated manifest;
4. commits `pack: minecraft office 0.1.1` and pushes. The `pages` workflow then publishes the pack.

`release-helper.sh` builds the helper, signs it (`helper-mac/sign.sh`, persistent local identity), zips it as
`OfficeHelper-<v>.zip`, adds it to `manifest.json` and uploads both with `gh release upload --clobber`.

**Why no commits from CI:** the workflow only publishes assets. The pack hash can only be written once the jar is
public, and doing it locally keeps CI read-only on the repo (no write token, no bot commits racing yours).

### If something fails: just re-run it

`scripts/release.sh 0.1.1` is safe to run again with the same version. If a run dies midway (network, a rejected
push, packwiz or upload error, Ctrl-C), run the exact same command. It detects what is already done and skips it:
version bump already committed, tag already created locally or pushed, release already published (it does not wait
for the workflow again), pack already committed (no empty commit). Half-written files in `pack/` from the interrupted
run are simply regenerated. It refuses, with a message, if the branch is behind origin, if an unrelated unpushed
commit would be published, or if the tag on origin belongs to a different commit.

`scripts/release-helper.sh` refuses until `release.sh` has fully finished (the pack commit for that version is on
origin and `manifest.json` has the `mrpack` entry), so the two can never overwrite each other's manifest. It is also
safe to re-run (it uploads with `--clobber`).

The one case that is not a plain re-run: the `release` **workflow itself fails** (build or test error). Fix the
problem on the branch, then remove the tag and any half-made release before re-running:
`git push --delete origin v0.1.1 && git tag -d v0.1.1 && gh release delete v0.1.1` (skip the last if no release
exists), then `scripts/release.sh 0.1.1`.

Pack looks broken after Pages deploys: `python3 scripts/check-pack.py pack` (also run in `pages.yml`) says what is
stale; fix with `cd pack && packwiz refresh` and push.

## One-time setup (controller / Carter)

1. **GitHub Pages:** repo Settings, Pages, **Source: GitHub Actions**. (The workflow cannot enable this itself.)
2. **Pages branch rule:** the `github-pages` environment allows only the default branch (`main`) by default. While
   work is on `feat/v1`, add it under Settings, Environments, github-pages, Deployment branches, or merge to `main`
   first. Otherwise the `pages` run fails with "not allowed to deploy".
3. `gh auth login` on the Mac, and packwiz at `~/Library/Caches/minecraft-office-tools/bin/packwiz` (or on `PATH`).
4. Check `https://carterbaileybusiness-gif.github.io/minecraft-office/pack/pack.toml` loads after the first deploy.

## Rebuilding the friend files by hand

`scripts/make-friend-instance.sh` writes `build/dist/Minecraft-Office.zip` (Prism instance: Minecraft 26.3, Fabric
Loader from `gradle.properties`, 4 GB, pre-launch command running the pinned and checksum-verified
packwiz-installer-bootstrap v0.0.3) and, if packwiz is available, `build/dist/Minecraft-Office.mrpack`.
Set `OFFICE_PACK_URL` to bake in a different `pack.toml` URL (for example a `packwiz serve` address when testing).
