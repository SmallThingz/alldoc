#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/.."
: "${JUNIT_JAR:?Set JUNIT_JAR to JUnit 4.13.2}"
: "${HAMCREST_JAR:?Set HAMCREST_JAR to Hamcrest 1.3}"
: "${POI_CP:?Set POI_CP to the classpath of POI 5.5.1 core, scratchpad, and their runtime dependencies}"
CP="$JUNIT_JAR:$HAMCREST_JAR:$POI_CP"
OUT=$(mktemp -d)
trap 'rm -rf "$OUT"' EXIT
javac -cp "$CP" -d "$OUT" app/src/main/java/org/smallthingz/alldoc/core/*.java app/src/test/java/org/smallthingz/alldoc/core/*.java
tests=()
for file in app/src/test/java/org/smallthingz/alldoc/core/*Test.java; do
  tests+=("org.smallthingz.alldoc.core.$(basename "$file" .java)")
done
java -Xmx256m -cp "$OUT:$CP" org.junit.runner.JUnitCore "${tests[@]}"
