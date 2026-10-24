#!/usr/bin/env python3
"""Checks that every mod in pack/ has its required dependencies present, and lists library mods nothing needs.
Downloads each pack/mods/*.pw.toml jar into ~/Library/Caches/minecraft-office-tools/pack-jars/ (verifying the listed
hash) and reads fabric.mod.json (id, provides, depends, nested jars).
A "library" is a mod with the Mod Menu `library` badge or with no entrypoints, mixins, data or assets; one that no other mod in
the pack depends on is reported as unused.
Usage: check-pack-deps.py [pack-dir]   (Python 3.11+). Exit 1 when a required id is missing.
"""
import hashlib
import json
import os
import sys
import tomllib
import urllib.request
import zipfile
from io import BytesIO

CACHE = os.path.expanduser("~/Library/Caches/minecraft-office-tools/pack-jars")
# MixinExtras is bundled with Fabric Loader
ALWAYS = {"minecraft", "java", "fabricloader", "fabric-api", "fabric", "mixinextras"}

# Libraries whose requirement is declared only in Modrinth metadata, not in the dependent jar's fabric.mod.json
# (verified against the Modrinth API): Mod Menu -> Text Placeholder API, Cristel Lib -> Cloth Config API.
KEEP = {"placeholder-api", "cloth-config"}


def always_present(mod_id):
    return mod_id in ALWAYS or mod_id.startswith("fabric-")


def fetch(meta):
    os.makedirs(CACHE, exist_ok=True)
    path = os.path.join(CACHE, meta["filename"])
    fmt, want = meta["download"]["hash-format"], meta["download"]["hash"]

    def ok():
        if not os.path.isfile(path):
            return False
        h = hashlib.new(fmt)
        with open(path, "rb") as handle:
            h.update(handle.read())
        return h.hexdigest() == want

    if not ok():
        request = urllib.request.Request(meta["download"]["url"], headers={"User-Agent": "minecraft-office-pack-check"})
        with urllib.request.urlopen(request, timeout=120) as response, open(path, "wb") as out:
            out.write(response.read())
        if not ok():
            sys.exit(f"hash mismatch for {meta['filename']}")
    return path


def collect(zf, into):
    """Appends {id, provides, depends} of this jar and of its nested jars; returns the top-level fabric.mod.json."""
    try:
        fmj = json.loads(zf.read("fabric.mod.json").decode("utf-8"), strict=False)
    except KeyError:
        return None
    into.append({"id": fmj["id"], "provides": set(fmj.get("provides", [])), "depends": set(fmj.get("depends", {}))})
    for nested in fmj.get("jars", []):
        try:
            collect(zipfile.ZipFile(BytesIO(zf.read(nested["file"]))), into)
        except KeyError:
            continue
    return fmj


def is_library(fmj, zf):
    badges = fmj.get("custom", {}).get("modmenu", {}).get("badges", [])
    has_content = any(n.startswith(("data/", "assets/")) for n in zf.namelist())
    return "library" in badges or (not fmj.get("entrypoints") and not fmj.get("mixins") and not has_content)


def main():
    root = sys.argv[1] if len(sys.argv) > 1 else os.path.join(os.path.dirname(__file__), "..", "pack")
    mods_dir = os.path.join(root, "mods")
    mods = {}  # slug -> {"entries": [...], "fmj": top-level fabric.mod.json}
    for name in sorted(os.listdir(mods_dir)):
        if not name.endswith(".pw.toml"):
            continue
        with open(os.path.join(mods_dir, name), "rb") as handle:
            meta = tomllib.load(handle)
        entries = []
        with zipfile.ZipFile(fetch(meta)) as zf:
            fmj = collect(zf, entries)
        mods[name[: -len(".pw.toml")]] = {"entries": entries, "fmj": fmj, "library": fmj is not None and is_library(fmj, zf)}

    present = set(ALWAYS)
    owner = {}
    for slug, mod in mods.items():
        for e in mod["entries"]:
            present.add(e["id"])
            present |= e["provides"]
            for i in {e["id"]} | e["provides"]:
                owner.setdefault(i, slug + " (nested)" if e is not mod["entries"][0] else slug)
        if mod["entries"]:
            for i in {mod["entries"][0]["id"]} | mod["entries"][0]["provides"]:
                owner[i] = slug

    missing = {}
    needed_by = {}  # id -> slugs whose top-level jar depends on it (nested jars are self-contained)
    for slug, mod in mods.items():
        if not mod["entries"]:
            continue
        for dep in mod["entries"][0]["depends"]:
            if always_present(dep):
                continue
            needed_by.setdefault(dep, set()).add(slug)
            if dep not in present:
                missing.setdefault(dep, set()).add(slug)

    print("depended on by:")
    for dep, who in sorted(needed_by.items()):
        print(f"  {dep} ({owner.get(dep, 'NOT IN PACK')}): {', '.join(sorted(who))}")
    if missing:
        print("missing:")
        for dep, who in sorted(missing.items()):
            print(f"  {dep} (needed by {', '.join(sorted(who))})")
    else:
        print("missing: none")

    unused = []
    for slug, mod in mods.items():
        if not mod["entries"] or slug == "fabric-api" or slug in KEEP or not mod["library"]:
            continue
        ids = {mod["entries"][0]["id"]} | mod["entries"][0]["provides"]
        if not any(ids & other["entries"][0]["depends"] for s, other in mods.items() if s != slug and other["entries"]):
            unused.append(slug)
    if unused:
        print("unused library mods (nothing depends on them):")
        for slug in unused:
            print(f"  {slug}")
    else:
        print("unused libraries: none")
    sys.exit(1 if missing else 0)


if __name__ == "__main__":
    main()
