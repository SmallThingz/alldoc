#!/usr/bin/env python3
"""Assemble the tiny offline catalog; generators stay out of the application."""
import argparse
from collections import Counter
import hashlib
import json
from pathlib import Path
import re

ROOT = Path(__file__).resolve().parent.parent
ASSETS = ROOT / "app/src/main/assets"
CATALOG = ASSETS / "examples/manifest.json"
CATEGORIES = ["Documents", "Spreadsheets", "Presentations", "Books"]


def assemble():
    source = (ROOT / "app/src/main/java/app/smallthingz/alldoc/core/DocumentFormats.kt").read_text()
    expected = Counter(ext for group in re.findall(r'format\("[^"\n]+", "([^"\n]+)"', source) for ext in group.split())
    entries = []
    for group in ("office", "text"):
        manifest = json.loads((ROOT / f"scripts/fixtures/examples-{group}.json").read_text())
        if manifest.get("version") != 1:
            raise ValueError(f"Invalid {group} manifest version")
        for raw in manifest["examples"]:
            entry = {key: raw[key] for key in ("id", "title", "asset", "mime", "extension", "description", "category")}
            if not all(isinstance(value, str) for value in entry.values()):
                raise ValueError("Example fields must be strings")
            if not re.fullmatch(r"[a-z0-9][a-z0-9-]{0,63}", entry["id"]):
                raise ValueError(f"Invalid example identity: {entry['id']}")
            if not re.fullmatch(rf"examples/{group}/[A-Za-z0-9][A-Za-z0-9._ -]{{0,100}}", entry["asset"]):
                raise ValueError(f"Invalid example path: {entry['asset']}")
            if not 1 <= len(entry["title"]) <= 80 or len(entry["description"]) > 160:
                raise ValueError(f"Example labels are too long: {entry['id']}")
            if not 1 <= len(entry["category"]) <= 40 or not 1 <= len(entry["mime"]) <= 128:
                raise ValueError(f"Invalid category/MIME: {entry['id']}")
            asset = ASSETS / entry["asset"]
            if asset.suffix[1:] != entry["extension"] or not asset.is_file() or asset.is_symlink():
                raise ValueError(f"Invalid example payload: {entry['asset']}")
            if not 1 <= asset.stat().st_size <= 512 * 1024:
                raise ValueError(f"Example exceeds 512 KiB: {entry['asset']}")
            payload = asset.read_bytes()
            entry.update(bytes=len(payload), sha256=hashlib.sha256(payload).hexdigest())
            entries.append(entry)
    if not 1 <= len(entries) <= 256 or sum(entry["bytes"] for entry in entries) > 4 * 1024 * 1024:
        raise ValueError("Example corpus exceeds its storage budget")
    for key in ("id", "asset"):
        if len({entry[key] for entry in entries}) != len(entries):
            raise ValueError(f"Duplicate example {key}")
    if len({Path(entry["asset"]).name.lower() for entry in entries}) != len(entries):
        raise ValueError("Example filenames must be unique")
    actual = Counter(entry["extension"] for entry in entries)
    if actual != expected:
        raise ValueError(f"Format coverage mismatch: missing={dict(expected-actual)}, extra={dict(actual-expected)}")
    entries.sort(key=lambda entry: (CATEGORIES.index(entry["category"]) if entry["category"] in CATEGORIES else len(CATEGORIES), entry["category"], entry["extension"]))
    result = (json.dumps({"version": 1, "examples": entries}, ensure_ascii=False, separators=(",", ":")) + "\n").encode()
    if len(result) > 256 * 1024:
        raise ValueError("Catalog exceeds 256 KiB")
    return entries, result


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--check", action="store_true", help="Verify the shipped catalog without changing it")
    args = parser.parse_args()
    entries, result = assemble()
    if args.check:
        if not CATALOG.is_file() or CATALOG.read_bytes() != result:
            raise SystemExit("Example catalog is stale; run scripts/build-examples.py")
    else:
        CATALOG.parent.mkdir(parents=True, exist_ok=True)
        CATALOG.write_bytes(result)
    print(json.dumps({"examples": len(entries), "payloadBytes": sum(entry["bytes"] for entry in entries), "catalogBytes": len(result)}))


if __name__ == "__main__":
    main()
