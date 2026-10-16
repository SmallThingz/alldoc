#!/usr/bin/env python3
"""Read-only, bounded AllDoc idle samples inside root's guarded host window.

Settle the desired screen first (no spinner, keyboard/caret or interaction), then:
  guarded-run --abort-on-busy -- python3 scripts/check-idle.py --serial DEVICE \
    --apk app.apk --case home --output .tmp/idle/home.json

Never installs/launches/stops the app or changes counters/device settings. Exit 0
means complete observations, not a zero-work performance guarantee. Exit 2 means
missing/inconsistent measurements. Discard every run whose outer guard exits 75.
Release devices can deny /proc/PID/fd; --proc-access run-as is for debug builds.
"""
import argparse
import datetime
import hashlib
import json
import os
from pathlib import Path
import re
import shlex
import signal
import subprocess
import sys
import time

PACKAGE = "app.smallthingz.alldoc"
INTERVAL = 10.0


def guard_owner():
    """Inspect only our bounded ancestor chain; the guard must keep monitoring."""
    pid = os.getppid()
    for _ in range(12):
        if pid <= 1:
            break
        directory = Path("/proc") / str(pid)
        arguments = directory.joinpath("cmdline").read_bytes().split(b"\0")
        if b"--abort-on-busy" in arguments and any(Path(os.fsdecode(arg)).name == "guarded-run" for arg in arguments if arg):
            return pid
        status = directory.joinpath("status").read_text()
        parent = re.search(r"^PPid:\s+(\d+)$", status, re.MULTILINE)
        if not parent:
            break
        pid = int(parent[1])
    raise RuntimeError("Run only inside guarded-run --abort-on-busy after host-quiet; no guard ancestor found")


def sha256(path):
    result = hashlib.sha256()
    with path.open("rb") as source:
        for chunk in iter(lambda: source.read(1024 * 1024), b""):
            result.update(chunk)
    return result.hexdigest()


def process_stat(raw, expected_pid):
    opening, closing = raw.find("("), raw.rfind(")")
    if opening < 1 or closing <= opening or int(raw[:opening].strip()) != expected_pid:
        raise ValueError("Unexpected /proc process identity")
    fields = raw[closing + 1:].split()
    # fields starts at Linux stat field 3 (state); utime/stime/starttime are 14/15/22.
    if len(fields) < 20:
        raise ValueError("Incomplete /proc process counters")
    return {"pid": expected_pid, "name": raw[opening + 1:closing], "userTicks": int(fields[11]),
            "systemTicks": int(fields[12]), "startTicks": int(fields[19])}


def main():
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--serial", required=True)
    parser.add_argument("--apk", required=True, type=Path)
    parser.add_argument("--case", required=True, choices=("home", "text", "office", "pdf", "settings", "background"))
    parser.add_argument("--output", required=True, type=Path)
    parser.add_argument("--proc-access", choices=("shell", "run-as"), default="shell")
    args = parser.parse_args()
    if not args.apk.is_file():
        parser.error("--apk must name the exact installed APK")
    if args.output.exists():
        parser.error("Use a fresh output path; existing receipts are not overwritten")
    guard = guard_owner()
    deadline = time.monotonic() + 180
    adb = ["adb", "-s", args.serial]
    report = {"package": PACKAGE, "case": args.case, "status": "incomplete", "guardPid": guard,
              "guardExitMustBeZero": True, "intervalSeconds": INTERVAL, "procAccess": args.proc_access,
              "startedUtc": datetime.datetime.now(datetime.timezone.utc).isoformat(),
              "driverSha256": sha256(Path(__file__)), "issues": []}
    args.output.parent.mkdir(parents=True, exist_ok=True)

    def save():
        temporary = args.output.with_suffix(args.output.suffix + ".tmp")
        temporary.write_text(json.dumps(report, indent=2) + "\n")
        temporary.replace(args.output)

    def command(*parts):
        remaining = deadline - time.monotonic()
        if remaining <= 0:
            raise TimeoutError("Idle driver exceeded its 180-second overall bound")
        result = subprocess.run(adb + list(parts), stdin=subprocess.DEVNULL, capture_output=True,
                                text=True, timeout=min(8, remaining))
        if result.returncode:
            raise RuntimeError((result.stderr.strip() or result.stdout.strip() or "ADB command failed")[:400])
        if len(result.stdout) > 1024 * 1024:
            raise RuntimeError("Unexpectedly large diagnostic output")
        return result.stdout.strip()

    def shell(*parts):
        return command("shell", shlex.join(parts))

    def proc(*parts):
        prefix = ("run-as", PACKAGE) if args.proc_access == "run-as" else ()
        return shell(*prefix, *parts)

    def pid():
        value = shell("pidof", PACKAGE).split()
        if len(value) != 1 or not value[0].isdigit() or int(value[0]) <= 1:
            raise RuntimeError("Expected exactly one running AllDoc main process; do not relaunch during this sample")
        return int(value[0])

    def optional(label, read):
        try:
            return read()
        except (RuntimeError, ValueError, subprocess.TimeoutExpired) as error:
            report["issues"].append(f"{label}: {error}")
            return None

    def frames():
        raw = shell("dumpsys", "gfxinfo", PACKAGE)
        totals = re.findall(r"Total frames rendered:\s*(\d+)", raw)
        starts = re.findall(r"Stats since:\s*(\d+)ns", raw)
        if len(totals) != 1 or len(starts) != 1:
            raise ValueError("Expected one gfxinfo frame counter and reset epoch")
        return {"total": int(totals[0]), "sinceNanos": int(starts[0])}

    def descriptors(expected_pid):
        names = proc("ls", "-1", f"/proc/{expected_pid}/fd").splitlines()
        if not names or any(not name.isdigit() for name in names):
            raise ValueError("No readable numeric FD listing; a denied listing is not a zero count")
        return len(names)

    def snapshot():
        expected_pid = pid()
        started = time.monotonic()
        cpu = process_stat(proc("cat", f"/proc/{expected_pid}/stat"), expected_pid)
        result = {"sampledMonotonic": started, "cpu": cpu,
                  "frames": optional("gfxinfo", frames), "fdCount": optional("FD count", lambda: descriptors(expected_pid))}
        verify = process_stat(proc("cat", f"/proc/{expected_pid}/stat"), expected_pid)
        if pid() != expected_pid or verify["startTicks"] != cpu["startTicks"]:
            raise RuntimeError("AllDoc restarted during a snapshot; measurements discarded")
        result["sampleDurationSeconds"] = time.monotonic() - started
        return result

    def interrupted(number, _frame):
        raise InterruptedError(f"Driver interrupted by signal {number}; discard this sample")

    signal.signal(signal.SIGTERM, interrupted)
    signal.signal(signal.SIGINT, interrupted)
    save()
    try:
        report["device"] = {"serial": command("get-serialno"), "model": shell("getprop", "ro.product.model"),
                            "sdk": shell("getprop", "ro.build.version.sdk"),
                            "fingerprint": shell("getprop", "ro.build.fingerprint")}
        paths = shell("pm", "path", PACKAGE).splitlines()
        if len(paths) != 1 or not paths[0].startswith("package:"):
            raise RuntimeError("Expected one installed base APK; split installations need their own exact-artifact check")
        installed = shell("sha256sum", paths[0][8:]).split()[0]
        expected = sha256(args.apk)
        report["apk"] = {"path": str(args.apk), "sha256": expected, "installedSha256": installed,
                         "bytes": args.apk.stat().st_size}
        if not re.fullmatch(r"[0-9a-f]{64}", installed) or installed != expected:
            raise RuntimeError("Installed APK does not match --apk; no idle sample taken")
        ticks = optional("clock tick frequency", lambda: int(shell("getconf", "CLK_TCK")))
        report["ticksPerSecond"] = ticks
        report["before"] = snapshot()
        report["status"] = "sampling"
        save()
        if deadline - time.monotonic() <= INTERVAL:
            raise TimeoutError("Not enough remaining time for the full idle interval")
        wait_started = time.monotonic()
        time.sleep(INTERVAL)
        report["noInputWaitSeconds"] = time.monotonic() - wait_started
        report["after"] = snapshot()
        if guard_owner() != guard:
            raise RuntimeError("Quiet-window guard changed or exited; measurements discarded")
        if shell("pm", "path", PACKAGE).splitlines() != paths or shell("sha256sum", paths[0][8:]).split()[0] != expected or sha256(args.apk) != expected:
            raise RuntimeError("APK changed during the interval; measurements discarded")
        before, after = report["before"], report["after"]
        if (before["cpu"]["pid"], before["cpu"]["startTicks"]) != (after["cpu"]["pid"], after["cpu"]["startTicks"]):
            raise RuntimeError("AllDoc PID/start time changed across the interval; measurements discarded")
        delta = {key: after["cpu"][key] - before["cpu"][key] for key in ("userTicks", "systemTicks")}
        if any(value < 0 for value in delta.values()):
            raise RuntimeError("CPU counters went backwards; measurements discarded")
        if before["frames"] is not None and after["frames"] is not None:
            if before["frames"]["sinceNanos"] != after["frames"]["sinceNanos"] or after["frames"]["total"] < before["frames"]["total"]:
                report["issues"].append("gfxinfo reset during the interval; frame delta unavailable")
            else:
                delta["frames"] = after["frames"]["total"] - before["frames"]["total"]
        if before["fdCount"] is not None and after["fdCount"] is not None:
            delta["fdCount"] = after["fdCount"] - before["fdCount"]
        report["delta"] = delta
        report["cpuSampleSpanSeconds"] = after["sampledMonotonic"] - before["sampledMonotonic"]
        report["status"] = "complete" if not report["issues"] else "partial"
        report["interpretation"] = "Observed deltas only. CPU may include runtime/GC work; investigate nonzero frames or rising FDs. Main-process counters only; no input or system interference detector is included."
    except Exception as error:
        report["status"] = "discarded"
        report["issues"].append(str(error))
    finally:
        report["finishedUtc"] = datetime.datetime.now(datetime.timezone.utc).isoformat()
        save()
    print(json.dumps({"status": report["status"], "receipt": str(args.output), "delta": report.get("delta")}))
    return 0 if report["status"] == "complete" else 2


if __name__ == "__main__":
    raise SystemExit(main())
