#!/usr/bin/env python3
# /// script
# requires-python = ">=3.10"
# dependencies = ["jsonschema>=4.23,<5"]
# ///
"""Qualify an installed release through the optional cross-UID test client.

Prepare only: python scripts/check-release.py --prepare-only
Run after installation: uv run scripts/check-release.py --serial DEVICE
Never installs, uninstalls, clears app data or changes device settings.
"""
import argparse
import hashlib
import json
from pathlib import Path
import shutil
import struct
import subprocess
import sys
import time
import zipfile
import zlib

PACKAGE = "app.smallthingz.alldoc"
OFFICE = Path("app/src/androidTest/assets/office")
HTML_ACTIVE_CONTENT = "ALLDOC_ACTIVE_CONTENT_MUST_STAY_OMITTED"


def pdf_bytes():
    objects = [b"<< /Type /Catalog /Pages 2 0 R >>",
               b"<< /Type /Pages /Kids [3 0 R 5 0 R] /Count 2 >>"]
    for page in range(2):
        contents = f"BT /F1 20 Tf 20 330 Td (AllDoc release PDF page {page + 1}) Tj ET".encode()
        objects += [b"<< /Type /Page /Parent 2 0 R /MediaBox [0 0 300 400] /Resources << /Font << /F1 7 0 R >> >> /Contents " + str(4 + page * 2).encode() + b" 0 R >>",
                    b"<< /Length " + str(len(contents)).encode() + b" >>\nstream\n" + contents + b"\nendstream"]
    objects.append(b"<< /Type /Font /Subtype /Type1 /BaseFont /Helvetica >>")
    data = bytearray(b"%PDF-1.4\n")
    offsets = []
    for index, obj in enumerate(objects, 1):
        offsets.append(len(data)); data.extend(f"{index} 0 obj\n".encode() + obj + b"\nendobj\n")
    start = len(data)
    data.extend(f"xref\n0 {len(objects) + 1}\n0000000000 65535 f \n".encode())
    for offset in offsets: data.extend(f"{offset:010} 00000 n \n".encode())
    data.extend(f"trailer\n<< /Size {len(objects) + 1} /Root 1 0 R >>\nstartxref\n{start}\n%%EOF\n".encode())
    return bytes(data)


def archive(path, parts):
    with zipfile.ZipFile(path, "w") as package:
        for name, data in parts.items():
            info = zipfile.ZipInfo(name, (2026, 1, 1, 0, 0, 0))
            info.compress_type = zipfile.ZIP_STORED if name == "mimetype" else zipfile.ZIP_DEFLATED
            package.writestr(info, data)


def prepare(directory):
    directory.mkdir(parents=True, exist_ok=True)
    for ext in ("docx", "xlsx", "pptx"):
        shutil.copyfile(OFFICE / f"sample.{ext}", directory / f"sample.{ext}")
    legacy = Path("scripts/fixtures/simple.doc")
    if digest(legacy.read_bytes()) != "4876e828e62d490fa188c7c4e47013e2e0dfdf02f5903dfd374bc0d2b2049ca1":
        raise AssertionError("Legacy Word fixture checksum changed")
    shutil.copyfile(legacy, directory / "sample.doc")
    (directory / "sample.md").write_text("# AllDoc release Markdown\n\nRead **formatted content** in one continuous document.\n\n- First item\n- Second item\n\nUnicode: café, हिन्दी, 日本語.\n", encoding="utf-8")
    (directory / "sample.html").write_text('<!doctype html><html><head><title>AllDoc release HTML</title><script>' + HTML_ACTIVE_CONTENT + '</script></head><body><h1>AllDoc release HTML</h1><p>Readable document content.</p></body></html>', encoding="utf-8")
    (directory / "sample.fb2").write_text('<?xml version="1.0" encoding="UTF-8"?><FictionBook xmlns="http://www.gribuser.ru/xml/fictionbook/2.0"><body><title><p>AllDoc release FictionBook</p></title><section><p>A short chapter for the reader.</p></section></body></FictionBook>', encoding="utf-8")
    pdf = pdf_bytes()
    (directory / "sample.pdf").write_bytes(pdf)
    archive(directory / "sample.odt", {
        "mimetype": "application/vnd.oasis.opendocument.text",
        "META-INF/manifest.xml": '<manifest:manifest xmlns:manifest="urn:oasis:names:tc:opendocument:xmlns:manifest:1.0"><manifest:file-entry manifest:full-path="/" manifest:media-type="application/vnd.oasis.opendocument.text"/><manifest:file-entry manifest:full-path="content.xml" manifest:media-type="text/xml"/></manifest:manifest>',
        "content.xml": '<office:document-content xmlns:office="urn:oasis:names:tc:opendocument:xmlns:office:1.0" xmlns:text="urn:oasis:names:tc:opendocument:xmlns:text:1.0" office:version="1.2"><office:body><office:text><text:p>AllDoc release OpenDocument</text:p></office:text></office:body></office:document-content>',
    })
    archive(directory / "sample.epub", {
        "mimetype": "application/epub+zip",
        "META-INF/container.xml": '<container version="1.0" xmlns="urn:oasis:names:tc:opendocument:xmlns:container"><rootfiles><rootfile full-path="OEBPS/package.opf" media-type="application/oebps-package+xml"/></rootfiles></container>',
        "OEBPS/package.opf": '<package xmlns="http://www.idpf.org/2007/opf" version="2.0" unique-identifier="book"><metadata xmlns:dc="http://purl.org/dc/elements/1.1/"><dc:title>AllDoc release EPUB</dc:title><dc:identifier id="book">alldoc-release-smoke</dc:identifier><dc:language>en</dc:language></metadata><manifest><item id="chapter" href="chapter.xhtml" media-type="application/xhtml+xml"/></manifest><spine><itemref idref="chapter"/></spine></package>',
        "OEBPS/chapter.xhtml": '<html xmlns="http://www.w3.org/1999/xhtml"><head><title>AllDoc release EPUB</title></head><body><p>AllDoc release EPUB chapter</p></body></html>',
    })
    archive(directory / "sample.pages", {"QuickLook/Preview.pdf": pdf})
    # file, expected semantic text, JSON format, JSON fidelity, preview page index
    return [
        ("sample.md", "Read formatted content in one continuous document.", "md", "SEMANTIC_LAYOUT", 0),
        ("sample.html", "AllDoc release HTML", "html", "TEXT_STRUCTURE", 0),
        ("sample.doc", "This is a simple file created with Word 97", "doc", "TEXT_STRUCTURE", 0),
        ("sample.pdf", "AllDoc release PDF page 2", "pdf", "extracted-text", 1),
        ("sample.docx", "AllDoc reader", "docx", "TEXT_STRUCTURE", 0),
        ("sample.xlsx", "Reader", "xlsx", "TEXT_STRUCTURE", 0),
        ("sample.pptx", "AllDoc slides", "pptx", "TEXT_STRUCTURE", 1),
        ("sample.odt", "AllDoc release OpenDocument", "odt", "TEXT_STRUCTURE", 0),
        ("sample.epub", "AllDoc release EPUB chapter", "epub", "TEXT_STRUCTURE", 0),
        ("sample.fb2", "AllDoc release FictionBook", "fb2", "TEXT_STRUCTURE", 0),
        ("sample.pages", "AllDoc release PDF page 2", "pages", "EMBEDDED_PREVIEW_ONLY", 1),
    ]


def png_size(data, max_width):
    if data[:8] != b"\x89PNG\r\n\x1a\n": raise AssertionError("PNG signature missing")
    offset = 8
    dimensions = None
    image_data = False
    while offset + 12 <= len(data):
        size = struct.unpack_from(">I", data, offset)[0]
        kind = data[offset + 4:offset + 8]
        end = offset + 12 + size
        if end > len(data): raise AssertionError("Truncated PNG chunk")
        body = data[offset + 8:offset + 8 + size]
        expected = struct.unpack_from(">I", data, offset + 8 + size)[0]
        if zlib.crc32(kind + body) & 0xffffffff != expected: raise AssertionError("PNG checksum mismatch")
        if kind == b"IHDR":
            if offset != 8 or size != 13: raise AssertionError("Invalid PNG header")
            dimensions = struct.unpack_from(">II", body)
        elif kind == b"IDAT": image_data |= size > 0
        elif kind == b"IEND":
            if size or end != len(data) or not image_data: raise AssertionError("Incomplete PNG")
            break
        offset = end
    else: raise AssertionError("PNG end marker missing")
    if dimensions is None or not 1 <= dimensions[0] <= max_width or not 1 <= dimensions[1] <= 8192:
        raise AssertionError(f"Unexpected preview dimensions: {dimensions}")
    return list(dimensions)


def digest(data): return hashlib.sha256(data).hexdigest()


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--serial")
    parser.add_argument("--prepare-only", action="store_true")
    parser.add_argument("--output", type=Path, default=Path(".tmp/release-smoke") / str(time.time_ns()))
    args = parser.parse_args()
    if not Path("api/document.schema.json").is_file(): parser.error("Run from the AllDoc project root")
    fixtures = args.output / "fixtures"
    cases = prepare(fixtures)
    if args.prepare_only:
        print(f"Prepared {len(cases)} document cases in {fixtures}")
        return
    try:
        from jsonschema import Draft202012Validator
    except ImportError:
        parser.error("Install jsonschema or run: uv run scripts/check-release.py")
    validator = Draft202012Validator(json.loads(Path("api/document.schema.json").read_text()))
    adb = ["adb"] + (["-s", args.serial] if args.serial else [])
    def device(*command): return subprocess.check_output(adb + list(command), timeout=30, text=True).strip()
    installed = device("shell", "dumpsys", "package", PACKAGE)
    if "versionName=" not in installed: raise RuntimeError("AllDoc is not installed")
    if "DEBUGGABLE" in installed: raise RuntimeError("Install the release APK before this post-R8 check")
    apk_paths = [line.removeprefix("package:") for line in device("shell", "pm", "path", PACKAGE).splitlines()]
    report = {"package": PACKAGE, "release": True, "sdk": device("shell", "getprop", "ro.build.version.sdk"),
              "serial": device("get-serialno"), "packageDumpSha256": digest(installed.encode()),
              "installedApkSha256": [device("shell", "sha256sum", path).split()[0] for path in apk_paths],
              "schemaSha256": digest(Path("api/document.schema.json").read_bytes()),
              "expectedChecks": len(cases) * 3, "passed": False, "results": [
                  {"file": name, "inputSha256": digest((fixtures / name).read_bytes()),
                   "checks": {mode: {"status": "not_run"} for mode in ("text", "json", "png")}}
                  for name, *_ in cases]}
    (args.output / "package.txt").write_text(installed)
    def save_report():
        (args.output / "report.json").write_text(json.dumps(report, indent=2) + "\n")
    save_report()
    try:
        for (name, expected, document_format, fidelity, page), case in zip(cases, report["results"]):
            for mode, detail in case["checks"].items():
                detail["status"] = "running"
                started = time.monotonic()
                command = [sys.executable, "scripts/alldoc-export.py", str(fixtures / name), "--format", mode,
                           "--page", str(page), "--width", "256", "--theme", "original"]
                if args.serial: command += ["--serial", args.serial]
                try:
                    completed = subprocess.run(command, stdout=subprocess.PIPE, stderr=subprocess.PIPE, timeout=130)
                    (args.output / f"{name}.{mode}").write_bytes(completed.stdout)
                    if completed.stderr: (args.output / f"{name}.{mode}.stderr.txt").write_bytes(completed.stderr)
                    detail.update(bytes=len(completed.stdout), sha256=digest(completed.stdout), exitCode=completed.returncode)
                    if completed.returncode: raise RuntimeError(completed.stderr.decode(errors="replace")[-3000:] or f"Export exited {completed.returncode}")
                    if mode == "text":
                        text = " ".join(completed.stdout.decode("utf-8").split())
                        if expected not in text: raise AssertionError("Expected text missing")
                        if name.endswith(".pages") and not text.startswith("[Saved embedded preview;"):
                            raise AssertionError("iWork text lost preview provenance")
                    elif mode == "json":
                        document = json.loads(completed.stdout)
                        validator.validate(document)
                        if document["format"].lower() != document_format or document["fidelity"] != fidelity or document["truncated"]:
                            raise AssertionError("Wrong format, fidelity or unexpected truncation")
                        text = " ".join(b["text"] for section in document["sections"] for b in section["blocks"])
                        if expected not in " ".join(text.split()): raise AssertionError("JSON content missing")
                        if name.endswith(".pages") and (document.get("contentSource") != "embedded-preview" or document.get("previewPart") != "QuickLook/Preview.pdf"):
                            raise AssertionError("iWork JSON lost preview provenance")
                        if name.endswith(".md") and (document.get("continuous") is not True or document.get("pageCount") != 1):
                            raise AssertionError("Markdown lost its single continuous logical page")
                        detail["format"] = document["format"]; detail["fidelity"] = document["fidelity"]
                    else:
                        detail["dimensions"] = png_size(completed.stdout, 256)
                    if name.endswith(".html") and mode in ("text", "json") and HTML_ACTIVE_CONTENT in completed.stdout.decode("utf-8"):
                        raise AssertionError("Active HTML content leaked into document text")
                    if name.endswith(".md") and mode in ("text", "json") and "**formatted content**" in completed.stdout.decode("utf-8"):
                        raise AssertionError("Markdown exported raw emphasis markers instead of semantic text")
                    detail["status"] = "passed"
                    print(f"PASS {name} {mode}", flush=True)
                except Exception as error:
                    detail.update(status="failed", error=str(error))
                    if isinstance(error, subprocess.TimeoutExpired):
                        for suffix, partial in ((mode, error.stdout), (mode + ".stderr.txt", error.stderr)):
                            if partial: (args.output / f"{name}.{suffix}").write_bytes(partial if isinstance(partial, bytes) else partial.encode())
                    print(f"FAIL {name} {mode}: {error}", file=sys.stderr, flush=True)
                finally:
                    detail["seconds"] = round(time.monotonic() - started, 3)
                    save_report()
            case["passed"] = all(check["status"] == "passed" for check in case["checks"].values())
        report["completedChecks"] = sum(check["status"] in ("passed", "failed") for case in report["results"] for check in case["checks"].values())
        final_paths = [line.removeprefix("package:") for line in device("shell", "pm", "path", PACKAGE).splitlines()]
        report["finalInstalledApkSha256"] = [device("shell", "sha256sum", path).split()[0] for path in final_paths]
        report["artifactUnchanged"] = report["finalInstalledApkSha256"] == report["installedApkSha256"]
        report["passed"] = report["artifactUnchanged"] and all(case["passed"] for case in report["results"])
        if not report["passed"]: raise RuntimeError(f"Release qualification failed; inspect {args.output / 'report.json'}")
    except Exception as error:
        report["passed"] = False; report["error"] = str(error)
        raise
    finally:
        save_report()
    print(f"Post-R8 checks passed; report: {args.output / 'report.json'}")


if __name__ == "__main__":
    try: main()
    except (AssertionError, OSError, RuntimeError, subprocess.SubprocessError, ValueError) as error:
        print(str(error), file=sys.stderr); sys.exit(1)
