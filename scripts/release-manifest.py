#!/usr/bin/env python3
"""Maintains manifest.json, the release's table of contents with SHA-256 checksums.

    release-manifest.py MANIFEST set KEY FILE URL --version 0.1.0

Creates MANIFEST when it does not exist, then sets/replaces entry KEY with {name, sha256, size, url} for FILE.
Keys in use: mod, instance, mrpack (workflow / release.sh) and helper (scripts/release-helper.sh). The mod reads
`helper` to download and verify OfficeHelper. Standard library only.
"""
import argparse
import hashlib
import json
import os
import sys

SCHEMA = 1
KEYS = ("mod", "instance", "mrpack", "helper")


def sha256_of(path):
    digest = hashlib.sha256()
    with open(path, "rb") as handle:
        for block in iter(lambda: handle.read(1 << 20), b""):
            digest.update(block)
    return digest.hexdigest()


def main():
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("manifest")
    parser.add_argument("action", choices=["set"])
    parser.add_argument("key", choices=KEYS)
    parser.add_argument("file")
    parser.add_argument("url")
    parser.add_argument("--version", required=True)
    args = parser.parse_args()

    data = {}
    if os.path.exists(args.manifest):
        with open(args.manifest, encoding="utf-8") as handle:
            data = json.load(handle)
        if data.get("version") != args.version:
            sys.exit(f"manifest is for version {data.get('version')}, not {args.version}")
    data.update({"schema": SCHEMA, "version": args.version, "tag": f"v{args.version}"})
    data[args.key] = {
        "name": os.path.basename(args.file),
        "sha256": sha256_of(args.file),
        "size": os.path.getsize(args.file),
        "url": args.url,
    }
    ordered = {k: data[k] for k in ("schema", "version", "tag", *KEYS) if k in data}
    with open(args.manifest, "w", encoding="utf-8") as handle:
        json.dump(ordered, handle, indent=2)
        handle.write("\n")
    print(f"manifest {args.manifest}: {args.key} = {ordered[args.key]['name']} {ordered[args.key]['sha256']}")


if __name__ == "__main__":
    main()
