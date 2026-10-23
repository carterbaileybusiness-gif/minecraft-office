#!/usr/bin/env python3
"""Checks that pack/ is internally consistent, the same way packwiz-installer does on a friend's machine:
pack.toml's index hash matches index.toml, and every file index.toml lists exists with the listed hash.
A stale index makes every friend's launch fail, so pages.yml runs this before publishing.
Usage: check-pack.py [pack-dir]   (needs Python 3.11+ for tomllib)
"""
import hashlib
import os
import sys
import tomllib


def digest(path, algorithm):
    h = hashlib.new(algorithm)
    with open(path, "rb") as handle:
        for block in iter(lambda: handle.read(1 << 20), b""):
            h.update(block)
    return h.hexdigest()


def main():
    root = sys.argv[1] if len(sys.argv) > 1 else os.path.join(os.path.dirname(__file__), "..", "pack")
    problems = []
    with open(os.path.join(root, "pack.toml"), "rb") as handle:
        pack = tomllib.load(handle)
    index_path = os.path.join(root, pack["index"]["file"])
    if digest(index_path, pack["index"]["hash-format"]) != pack["index"]["hash"]:
        problems.append("pack.toml index hash does not match index.toml (run: packwiz refresh)")
    with open(index_path, "rb") as handle:
        index = tomllib.load(handle)
    default_format = index.get("hash-format", "sha256")
    count = 0
    for entry in index.get("files", []):
        count += 1
        path = os.path.join(root, entry["file"])
        if not os.path.isfile(path):
            problems.append(f"missing file {entry['file']}")
        elif digest(path, entry.get("hash-format", default_format)) != entry["hash"]:
            problems.append(f"hash mismatch {entry['file']} (run: packwiz refresh)")
        if entry.get("metafile"):
            with open(path, "rb") as handle:
                meta = tomllib.load(handle)
            for key in ("name", "filename", "download"):
                if key not in meta:
                    problems.append(f"{entry['file']}: missing {key}")
    for problem in problems:
        print("pack check:", problem, file=sys.stderr)
    if problems:
        sys.exit(1)
    print(f"pack ok: {count} indexed files, version {pack.get('version')}")


if __name__ == "__main__":
    main()
