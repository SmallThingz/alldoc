#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/.."
: "${JUNIT_JAR:?Set JUNIT_JAR to JUnit 4.13.2}"
: "${HAMCREST_JAR:?Set HAMCREST_JAR to Hamcrest 1.3}"
OUT=$(mktemp -d)
trap 'rm -rf "$OUT"' EXIT
javac -cp "$JUNIT_JAR:$HAMCREST_JAR" -d "$OUT" app/src/main/java/org/smallthingz/alldoc/core/*.java app/src/test/java/org/smallthingz/alldoc/core/*.java
java -cp "$OUT:$JUNIT_JAR:$HAMCREST_JAR" org.junit.runner.JUnitCore org.smallthingz.alldoc.core.DocumentEngineTest
