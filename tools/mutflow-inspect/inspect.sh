#!/usr/bin/env bash
# mutflow-inspect: run the mutated binary against a test battery and render an
# HTML dashboard of which mutants are killed vs survived.
#
# Usage:
#   ./inspect.sh                 # build + run + write report.html
#   ./inspect.sh --no-build      # skip the Gradle build (use existing classes)
#
# Output: report.html in this directory (open in a browser).
set -euo pipefail
cd "$(dirname "$0")"

ROOT="$(cd ../.. && pwd)"
OUT="report.html"

# 1. Build the mutated KMP JVM classes (unless --no-build).
if [[ "${1:-}" != "--no-build" ]]; then
  echo "[inspect] building mutated KMP JVM classes..."
  (cd "$ROOT" && ./gradlew :mutflow-test-kmp:compileKotlinJvm --console=plain -q)
fi

STDLIB="$(find "$HOME/.gradle" -name 'kotlin-stdlib-2.4.0.jar' | head -1)"
CORE="$ROOT/mutflow-core/build/libs/mutflow-core-jvm-0.1.0-SNAPSHOT.jar"
SAMPLE="$ROOT/mutflow-test-kmp/build/classes/kotlin/jvm/main"
CP="$STDLIB:$CORE:$SAMPLE"

echo "[inspect] compiling inspector..."
javac -cp "$CP" -d . BinaryInspector.java

echo "[inspect] running mutated binary..."
java -cp "$CP:." BinaryInspector > "$OUT"

echo "[inspect] wrote $OUT"
echo "[inspect] summary:"
grep -oE 'killed: <b>[0-9]+</b>|survived: <b>[0-9]+</b>' "$OUT" | sed 's/<[^>]*>//g'
