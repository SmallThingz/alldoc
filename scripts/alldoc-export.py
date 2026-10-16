#!/usr/bin/env python3
"""Export a caller-selected local document through AllDoc's real cross-UID API."""
import argparse
import base64
from pathlib import Path
import subprocess
import sys
import uuid

parser = argparse.ArgumentParser(description=__doc__)
parser.add_argument("source", type=Path)
parser.add_argument("--format", choices=("text", "json", "png"), default="text")
parser.add_argument("--page", type=int, default=0)
parser.add_argument("--width", type=int, default=1200)
parser.add_argument("--theme", choices=("original", "adaptive", "adaptive_light", "system", "sepia", "high_contrast", "invert"), default="original")
parser.add_argument("--serial")
args = parser.parse_args()
if not args.source.is_file() or args.source.stat().st_size > 128 * 1024 * 1024:
    parser.error("source must be a file of at most 128 MiB")
if args.page < 0 or not 64 <= args.width <= 2048:
    parser.error("page must be nonnegative; width must be 64–2048")
if len(args.source.name) > 512:
    parser.error("source filename is too long")
adb = ["adb"] + (["-s", args.serial] if args.serial else [])
package = "app.smallthingz.alldoc.clienttest"
token = uuid.uuid4().hex
directory = f"files/agent-{token}"

def run(*command, **kwargs):
    return subprocess.run(adb + list(command), check=True, timeout=90, **kwargs)

try:
    run("shell", "run-as", package, "mkdir", "-p", directory, stdout=subprocess.DEVNULL)
    with args.source.open("rb") as source:
        run("shell", "run-as", package, "dd", f"of={directory}/input", "bs=65536",
            stdin=source, stdout=subprocess.DEVNULL, stderr=subprocess.PIPE)
    name = base64.b64encode(args.source.name.encode()).decode()
    response = run("shell", "am", "instrument", "-w", "-e", "token", token,
        "-e", "name64", name, "-e", "format", args.format, "-e", "page", str(args.page),
        "-e", "width", str(args.width), "-e", "theme", args.theme,
        f"{package}/.ExportRunner", stdout=subprocess.PIPE, text=True).stdout
    if "INSTRUMENTATION_RESULT: result=ok" not in response or "INSTRUMENTATION_CODE: 0" not in response:
        raise RuntimeError(response.strip())
    run("exec-out", "run-as", package, "cat", f"{directory}/output", stdout=sys.stdout.buffer)
except (OSError, RuntimeError, subprocess.SubprocessError) as error:
    print(str(error), file=sys.stderr)
    sys.exit(1)
finally:
    try:
        cleaned = subprocess.run(adb + ["shell", "run-as", package, "rm", "-rf", directory],
                                 stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL, timeout=10)
        if cleaned.returncode:
            print("Could not remove staged test-client files.", file=sys.stderr)
    except (OSError, subprocess.SubprocessError):
        print("Could not remove staged test-client files.", file=sys.stderr)

