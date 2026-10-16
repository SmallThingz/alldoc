#!/usr/bin/env python3
"""Report APK payload sizes, native symbol/debug sections, and duplicate assets.

Reads only; installed code payload = APK + extracted native libraries. That value
excludes filesystem block rounding, ART compilation, app data and runtime caches.
"""
import argparse
from collections import defaultdict
import hashlib
import json
from pathlib import Path
import struct
import zipfile


def elf_sections(data):
    if data[:6] != b"\x7fELF\x02\x01":
        return {"error": "Expected little-endian ELF64"}
    machine = struct.unpack_from("<H", data, 18)[0]
    offset = struct.unpack_from("<Q", data, 40)[0]
    stride, count, names_index = struct.unpack_from("<HHH", data, 58)
    sections = [struct.unpack_from("<IIQQQQIIQQ", data, offset + i * stride) for i in range(count)]
    names = sections[names_index]
    strings = data[names[4]:names[4] + names[5]]
    sizes = {}
    for section in sections:
        end = strings.find(b"\0", section[0])
        name = strings[section[0]:end].decode("ascii", errors="replace")
        sizes[name] = section[5]
    return {"machine": {183: "arm64-v8a", 62: "x86_64"}.get(machine, machine),
            "stripped": ".symtab" not in sizes,
            "debugBytes": sum(size for name, size in sizes.items() if name.startswith((".debug", ".zdebug"))),
            "textBytes": sizes.get(".text", 0), "rodataBytes": sizes.get(".rodata", 0)}


def group(name):
    if name.startswith("lib/"): return "native"
    if name.endswith(".dex"): return "dex"
    if name.startswith("assets/"): return "assets"
    if name.startswith("res/"): return "resources"
    if name.startswith("META-INF/"): return "metadata"
    return "manifest/table/other"


def inspect(path):
    buckets = defaultdict(lambda: {"entries": 0, "compressedBytes": 0, "uncompressedBytes": 0})
    libraries, assets, entries, duplicates = [], [], [], defaultdict(list)
    with zipfile.ZipFile(path) as package:
        for entry in package.infolist():
            if entry.is_dir(): continue
            size = {"name": entry.filename, "compressedBytes": entry.compress_size, "uncompressedBytes": entry.file_size,
                    "compressionMethod": entry.compress_type}
            entries.append(size)
            bucket = buckets[group(entry.filename)]
            bucket["entries"] += 1
            bucket["compressedBytes"] += entry.compress_size
            bucket["uncompressedBytes"] += entry.file_size
            if entry.filename.startswith("lib/"):
                data = package.read(entry)
                libraries.append(size | {"sha256": hashlib.sha256(data).hexdigest()} | elf_sections(data))
            if entry.filename.startswith("assets/"):
                assets.append(size)
                duplicates[(entry.file_size, hashlib.sha256(package.read(entry)).digest())].append(entry.filename)
    apk_bytes = path.stat().st_size
    native_bytes = buckets["native"]["uncompressedBytes"]
    return {"apk": str(path), "sha256": hashlib.sha256(path.read_bytes()).hexdigest(),
            "apkBytes": apk_bytes,
            "zipUncompressedBytes": sum(bucket["uncompressedBytes"] for bucket in buckets.values()),
            "extractedNativeBytes": native_bytes, "installedCodePayloadBytes": apk_bytes + native_bytes,
            "groups": dict(sorted(buckets.items())), "libraries": libraries,
            "dex": [entry for entry in entries if entry["name"].endswith(".dex")],
            "largestEntries": sorted(entries, key=lambda item: item["compressedBytes"], reverse=True)[:10],
            "assets": sorted(assets, key=lambda item: item["uncompressedBytes"], reverse=True),
            "duplicateAssets": [names for names in duplicates.values() if len(names) > 1]}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("apk", nargs="*", type=Path)
    parser.add_argument("--json", type=Path, help="Save exact byte counts and SHA-256 receipts")
    parser.add_argument("--baseline", type=Path, help="Compare APK byte counts with an earlier JSON receipt")
    args = parser.parse_args()
    paths = args.apk or sorted(Path("app/build/outputs/apk/release").glob("*.apk"))
    if not paths: parser.error("No release APK found; provide APK paths or build release first")
    report = [inspect(path) for path in paths]
    if args.baseline:
        baseline = {Path(item["apk"]).name: item for item in json.loads(args.baseline.read_text())}
        for item in report:
            previous = baseline[Path(item["apk"]).name]
            item["baseline"] = {"sha256": previous["sha256"], "apkBytes": previous["apkBytes"],
                                "savedBytes": previous["apkBytes"] - item["apkBytes"]}
    if args.json:
        args.json.parent.mkdir(parents=True, exist_ok=True)
        args.json.write_text(json.dumps(report, indent=2) + "\n")
    for item in report:
        print(item["apk"])
        print(f"  sha256: {item['sha256']}")
        if "baseline" in item:
            print(f"  Saved versus baseline: {item['baseline']['savedBytes']:,} bytes")
        for key in ("apkBytes", "zipUncompressedBytes", "extractedNativeBytes", "installedCodePayloadBytes"):
            print(f"  {key}: {item[key]:,} ({item[key] / 1048576:.2f} MiB)")
        for name, sizes in item["groups"].items():
            print(f"  {name}: {sizes['compressedBytes']:,} compressed / {sizes['uncompressedBytes']:,} bytes")
        for lib in item["libraries"]:
            print(f"  {lib['name']}: {lib['uncompressedBytes']:,} bytes; stripped={lib.get('stripped')}; debug={lib.get('debugBytes')} bytes")
        for dex in item["dex"]:
            print(f"  {dex['name']}: method={dex['compressionMethod']}; {dex['compressedBytes']:,} compressed / {dex['uncompressedBytes']:,} bytes")
        print(f"  Duplicate asset groups: {len(item['duplicateAssets'])}")


if __name__ == "__main__":
    main()
