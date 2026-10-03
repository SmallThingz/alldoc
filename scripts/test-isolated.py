#!/usr/bin/env python3
"""Run the unchanged Gradle-resolved JUnit suite in fresh, bounded JVMs.

Prepare current compiled classes and metadata with the commands in
VERIFICATION.md. This deliberately does not discover or execute corpus probes.
"""
import argparse
import json
import os
from pathlib import Path
import resource
import shutil
import subprocess
import tempfile
import time

CLASSES = [
    "AndroidSafetyTest", "GestureGateTest", "ImmersiveGestureObserverTest",
    "ImmersiveWorkspaceTest", "ImmersivePresentationTest", "ImmersiveSelectionTest",
    "ImmersiveSheetTest", "PdfFormUiTest", "PdfToolsTest", "RecoveryMigrationTest",
    "RtfRunEditingTest", "SlideGeometryTest", "SlideImageDecoderTest",
    "SlideRasterStoreTest", "WorkspaceUiTest", "core.DocumentEngineTest",
    "core.LegacyOfficeExtractorTest", "core.PresentationFidelityTest", "core.RtfDocumentTest",
]
REPORTER = r'''
import java.nio.file.*;
import org.junit.runner.*;
import org.junit.runner.notification.*;
public final class LocalJUnitReport {
  public static void main(String[] args) throws Exception {
    JUnitCore core = new JUnitCore();
    core.addListener(new RunListener() {
      public void testStarted(Description d) { System.out.println("START " + d.getDisplayName()); System.out.flush(); }
      public void testFailure(Failure f) { System.out.println(f.getTrace()); System.out.flush(); }
      public void testIgnored(Description d) { System.out.println("IGNORED " + d.getDisplayName()); System.out.flush(); }
    });
    Result r = core.run(Class.forName(args[0]));
    String json = "{\"class\":\"" + args[0] + "\",\"run\":" + r.getRunCount()
        + ",\"failures\":" + r.getFailureCount() + ",\"ignored\":" + r.getIgnoreCount()
        + ",\"milliseconds\":" + r.getRunTime() + "}";
    Files.writeString(Path.of(args[1]), json);
    System.out.println(json);
    System.exit(r.wasSuccessful() ? 0 : 1);
  }
}
'''

def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--runtime", type=Path, default=Path("app/build/local-test-runtime.json"))
    parser.add_argument("--output", type=Path, default=Path("app/build/local-junit-results"))
    parser.add_argument("--timeout", type=int, default=180)
    args = parser.parse_args()
    if args.timeout < 1:
        parser.error("timeout must be positive")
    config = json.loads(args.runtime.read_text())
    home = os.environ.get("JAVA_HOME")
    java = str(Path(home) / "bin/java") if home else shutil.which("java")
    javac = str(Path(home) / "bin/javac") if home else shutil.which("javac")
    if not java or not javac:
        parser.error("JDK 21 is required; set JAVA_HOME")
    junit = next(path for path in config["classpath"].split(os.pathsep) if path.endswith("/junit-4.13.2.jar"))
    output = args.output.resolve()
    output.mkdir(parents=True, exist_ok=True)
    results = []
    failed = False
    with tempfile.TemporaryDirectory(prefix="alldoc-junit-") as helper:
        source = Path(helper) / "LocalJUnitReport.java"
        source.write_text(REPORTER)
        subprocess.run([javac, "-J-Xmx128m", "-cp", junit, str(source)], check=True)
        for short in CLASSES:
            name = "org.smallthingz.alldoc." + short
            report = output / (short + ".json")
            # Never mistake an earlier result for this invocation's outcome.
            report.unlink(missing_ok=True)
            command = [java, "-Xmx512m", "-ea"] + config["jvmArgs"]
            command += [f"-D{key}={value}" for key, value in config["properties"].items()]
            command += ["-cp", config["classpath"] + os.pathsep + helper, "LocalJUnitReport", name, str(report)]
            started = time.monotonic()
            try:
                with (output / (short + ".log")).open("w") as log:
                    process = subprocess.run(command, cwd=config["workingDir"], stdout=log,
                                             stderr=subprocess.STDOUT, timeout=args.timeout)
                result = json.loads(report.read_text()) if report.exists() else {
                    "class": name, "error": "No result", "exit": process.returncode}
                failed |= process.returncode != 0 or "error" in result
            except subprocess.TimeoutExpired:
                result = {"class": name, "error": f"{args.timeout}-second process timeout"}
                failed = True
            result["wall_seconds"] = round(time.monotonic() - started, 2)
            results.append(result)
            print(json.dumps(result), flush=True)
            (output / "summary.json").write_text(json.dumps({
                "classes": results,
                "max_child_rss_kib": resource.getrusage(resource.RUSAGE_CHILDREN).ru_maxrss,
                "all_successful": not failed and len(results) == len(CLASSES),
            }, indent=2))
            if "error" in result:
                break
    print("TOTAL", sum(item.get("run", 0) for item in results),
          "FAILED", sum(item.get("failures", 0) for item in results),
          "IGNORED", sum(item.get("ignored", 0) for item in results), flush=True)
    return 1 if failed or len(results) != len(CLASSES) else 0

if __name__ == "__main__":
    raise SystemExit(main())
