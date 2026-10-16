#!/usr/bin/env python3
"""Build AllDoc's pinned Craft readers. Run from the AllDoc project root."""
import argparse
import fcntl
import struct
import hashlib
import json
import os
from pathlib import Path
import shutil
import subprocess

ROOT = Path.cwd()
BASE = Path("native/render")

def digest(path):
    return hashlib.sha256(path.read_bytes()).hexdigest()

def prepare():
    manifest = json.loads((BASE / "projection.json").read_text())
    vendor = BASE / "vendor"
    vendor.mkdir(parents=True, exist_ok=True)
    for path in vendor.iterdir():
        if path.is_dir() and path.name not in {*manifest, "office-fonts"}:
            shutil.rmtree(path)
    for name, component in manifest.items():
        upstream = Path("upstream") / name
        current = subprocess.check_output(["git", "-C", str(upstream), "rev-parse", "HEAD"], text=True).strip()
        if current != component["commit"]:
            raise RuntimeError(f"{name}: pin changed; review/rebase its mobile patch before building")
        if subprocess.check_output(["git", "-C", str(upstream), "status", "--porcelain"], text=True).strip():
            raise RuntimeError(f"{name}: upstream submodule must be clean")
    inputs = {str(path): digest(path) for folder in [BASE/"patches", BASE/"office-fonts"] for path in folder.rglob("*") if path.is_file()}
    inputs[str(BASE/"projection.json")] = digest(BASE/"projection.json")
    stamp = Path(".tmp/native/render/projection-stamp.json")
    stamp.parent.mkdir(parents=True, exist_ok=True)
    if stamp.exists():
        saved = json.loads(stamp.read_text())
        if saved["inputs"] == inputs and all(Path(path).is_file() and digest(Path(path)) == value for path,value in saved["outputs"].items()):
            actual = {str(path) for path in vendor.rglob("*") if path.is_file()}
            if actual == set(saved["outputs"]):
                return
    for name, component in manifest.items():
        upstream = Path("upstream") / name
        target = vendor / name
        if target.exists():
            shutil.rmtree(target)
        target.mkdir()
        for member in component["files"]:
            if member in component["added"]:
                continue
            src = upstream / member
            dest = target / member
            dest.parent.mkdir(parents=True, exist_ok=True)
            shutil.copyfile(src, dest)
        patch = BASE / "patches" / f"{name}.patch"
        subprocess.run(["patch", "--batch", "--forward", "-p1", "-d", str(vendor), "-i", os.path.relpath(patch, vendor)], check=True, stdout=subprocess.DEVNULL)
    shutil.copytree(BASE / "office-fonts", vendor / "office-fonts", dirs_exist_ok=True)
    outputs = {str(path): digest(path) for path in vendor.rglob("*") if path.is_file()}
    stamp.write_text(json.dumps({"inputs": inputs, "outputs": outputs}, sort_keys=True))

def verify_elf(path, abi):
    data = path.read_bytes()
    machine = 183 if abi == "arm64-v8a" else 62
    if data[:6] != b"\x7fELF\x02\x01" or struct.unpack_from("<HH", data, 16) != (3, machine):
        raise RuntimeError("Expected 64-bit Android PIE executable")
    offset = struct.unpack_from("<Q", data, 32)[0]
    size, count = struct.unpack_from("<HH", data, 54)
    for i in range(count):
        kind, flags, _, _, _, _, _, alignment = struct.unpack_from("<IIQQQQQQ", data, offset+i*size)
        if kind == 1 and alignment < 16384:
            raise RuntimeError("ELF load alignment below 16 KiB")
        if kind == 0x6474e551 and flags & 1:
            raise RuntimeError("Executable native stack")
    if b"/system/bin/linker64\0" not in data:
        raise RuntimeError("Missing Android linker")

def source_hashes():
    paths = [p for base in [BASE/"core", BASE/"patches", BASE/"office-fonts"] for p in base.rglob("*") if p.is_file()]
    paths += [BASE/"projection.json", Path("native/runner/allrun.c")]
    return {str(path): digest(path) for path in sorted(paths)}

def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--abis", nargs="+", choices=["arm64-v8a", "x86_64"], default=["arm64-v8a", "x86_64"])
    parser.add_argument("--ndk", default="../../.android/sdk/ndk/29.0.14206865")
    parser.add_argument("--host", action="store_true")
    parser.add_argument("--prepare-only", action="store_true")
    parser.add_argument("--jobs", type=int, default=2)
    args = parser.parse_args()
    if not 1 <= args.jobs <= 4:
        parser.error("jobs must be 1 through 4")
    prepare()
    if args.prepare_only:
        print("Pinned source projection ready")
        return
    cache = Path(".tmp/native/render")
    cache.mkdir(parents=True, exist_ok=True)
    tools = Path(args.ndk) / "toolchains/llvm/prebuilt/linux-x86_64/bin"
    env = os.environ.copy()
    env["CARGO_TARGET_DIR"] = str(ROOT / cache / "target")
    env["CARGO_BUILD_JOBS"] = str(args.jobs)
    for key in ["RUSTFLAGS", "CARGO_ENCODED_RUSTFLAGS", "CRAFT_FONTS_DIR"]:
        env.pop(key, None)
    if args.host:
        targets = [("host", None)]
    else:
        targets = [(abi, "aarch64-linux-android" if abi == "arm64-v8a" else "x86_64-linux-android") for abi in args.abis]
    before = source_hashes()
    for abi, triple in targets:
        command = ["cargo", "build", "--locked", "--release", "--bin", "render", "--manifest-path", str(BASE / "core/Cargo.toml")]
        if triple:
            command += ["--target", triple]
            env["CARGO_TARGET_" + triple.upper().replace("-", "_") + "_LINKER"] = str(ROOT / tools / (triple + "28-clang"))
        with (cache / f"build-{abi}.log").open("w") as log:
            subprocess.run(command, env=env, stdout=log, stderr=subprocess.STDOUT, check=True)
        binary = cache / "target"
        if triple:
            binary /= triple
        binary /= "release/render"
        destination = Path("app/src/main/jniLibs") / abi if triple else cache / "host"
        destination.mkdir(parents=True, exist_ok=True)
        shutil.copy2(binary, destination / "liballrender.so")
        compiler = str(tools / (triple + "28-clang")) if triple else "cc"
        flags = ["-std=c11", "-O2", "-DNDEBUG", "-fPIE", "-pie", "-fstack-protector-strong", "-D_FORTIFY_SOURCE=2", "-ffunction-sections", "-fdata-sections", "-Wall", "-Wextra", "-Werror", "-Wl,--gc-sections,-z,relro,-z,now,-z,noexecstack,-z,max-page-size=16384"]
        subprocess.run([compiler, *flags, "native/runner/allrun.c", "-o", str(destination / "liballrun.so")], check=True)
        if triple:
            subprocess.run([str(tools / "llvm-strip"), "--strip-unneeded", str(destination / "liballrun.so")], check=True)
        for name in ["liballrender.so", "liballrun.so"]:
            (destination / name).chmod(0o755)
        if source_hashes() != before:
            raise RuntimeError("Renderer sources changed during build")
        if triple:
            for name in ["liballrender.so", "liballrun.so"]:
                verify_elf(destination/name, abi)
        receipt = {"abi": abi, "sources_sha256": before, "binaries": {name: {"bytes": (destination/name).stat().st_size, "sha256": digest(destination/name)} for name in ["liballrender.so", "liballrun.so"]}, "pins": {name: value["commit"] for name,value in json.loads((BASE/"projection.json").read_text()).items()}}
        (cache / f"receipt-{abi}.json").write_text(json.dumps(receipt, indent=2)+"\n")
        print(json.dumps(receipt), flush=True)

if __name__ == "__main__":
    cache = Path(".tmp/native/render")
    cache.mkdir(parents=True, exist_ok=True)
    with (cache/"build.lock").open("w") as lock:
        fcntl.flock(lock, fcntl.LOCK_EX | fcntl.LOCK_NB)
        main()
