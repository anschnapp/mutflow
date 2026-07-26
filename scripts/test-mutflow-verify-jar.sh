#!/bin/bash
#
# test-mutflow-verify-jar.sh - end-to-end test for mutflow-verify-jar.sh.
#
# Publishes mutflow to mavenLocal, generates a throwaway consumer project that
# applies the real Gradle plugin, and checks both sides of its dual compilation:
#
#   main        -> the production jar, must be clean (only @MutationTarget references)
#   mutatedMain -> jarred here, must be rejected with exit code 1
#
# The project is generated instead of using example/ because example/ is not part
# of the repository, so CI has no copy of it.
#
# Run from anywhere: scripts/test-mutflow-verify-jar.sh
# Exit codes: 0 = all cases passed, 1 = a case failed.

root=$(cd "$(dirname "$0")/.." && pwd)
verify="$root/scripts/mutflow-verify-jar.sh"
kotlin_version=$(sed -n 's/^kotlinVersion=//p' "$root/gradle.properties")
mutflow_version="0.1.0-SNAPSHOT"  # default project version, see root build.gradle.kts

tmp=$(mktemp -d "${TMPDIR:-/tmp}/mutflow-verify-test.XXXXXX") || exit 1
trap 'rm -rf "$tmp"' EXIT

failures=0

# expect <wanted exit code> <text that must appear> <args for mutflow-verify-jar.sh...>
expect() {
    wanted_code=$1
    wanted_text=$2
    shift 2

    output=$("$verify" "$@" 2>&1)
    code=$?

    if [ "$code" != "$wanted_code" ]; then
        echo "FAIL: $* -> exit $code, expected $wanted_code"
        echo "$output" | sed 's/^/      /'
        failures=$((failures + 1))
        return
    fi
    case "$output" in
        *"$wanted_text"*) ;;
        *)
            echo "FAIL: $* -> output does not contain '$wanted_text'"
            echo "$output" | sed 's/^/      /'
            failures=$((failures + 1))
            return
            ;;
    esac
    echo "ok: exit $code, contains '$wanted_text'"
}

echo "== publishing mutflow $mutflow_version to mavenLocal"
(cd "$root" && ./gradlew publishToMavenLocal -q) || exit 1

echo "== generating a consumer project that applies the mutflow Gradle plugin"
sample="$tmp/sample"
mkdir -p "$sample/src/main/kotlin/com/example"

cat > "$sample/settings.gradle.kts" <<EOF
pluginManagement {
    repositories {
        mavenLocal()
        gradlePluginPortal()
        mavenCentral()
    }
}
dependencyResolutionManagement {
    repositories {
        mavenLocal()
        mavenCentral()
    }
}
rootProject.name = "verify-jar-sample"
EOF

cat > "$sample/build.gradle.kts" <<EOF
plugins {
    kotlin("jvm") version "$kotlin_version"
    id("io.github.anschnapp.mutflow") version "$mutflow_version"
}

group = "com.example"
version = "1.0"
EOF

cat > "$sample/src/main/kotlin/com/example/Calculator.kt" <<'EOF'
package com.example

import io.github.anschnapp.mutflow.MutationTarget

@MutationTarget
class Calculator {
    fun isPositive(x: Int): Boolean = x > 0
}
EOF

echo "== building the consumer project (real plugin, dual compilation)"
(cd "$root" && ./gradlew -p "$sample" jar mutatedMainClasses -q) || exit 1

prod_jar=$(ls "$sample"/build/libs/*.jar 2>/dev/null | head -1)
mutated_classes="$sample/build/classes/kotlin/mutatedMain"
if [ ! -f "$prod_jar" ] || [ ! -d "$mutated_classes" ]; then
    echo "ERROR: consumer build output not found (jar: $prod_jar, classes: $mutated_classes)" >&2
    exit 1
fi

# guard against a vacuous "clean jar" result
if ! unzip -Z1 "$prod_jar" | grep -q 'com/example/Calculator.class'; then
    echo "ERROR: production jar does not contain the expected class" >&2
    exit 1
fi

# same class as the production jar, but from the mutated compilation
(cd "$mutated_classes" && jar cf "$tmp/mutated.jar" .) || exit 1

# a Spring Boot style artifact with the mutated jar nested inside
mkdir -p "$tmp/boot/BOOT-INF/lib"
cp "$tmp/mutated.jar" "$tmp/boot/BOOT-INF/lib/lib.jar"
(cd "$tmp/boot" && jar cf "$tmp/boot.jar" .) || exit 1

echo "== production jar must be clean"
expect 0 "OK: no mutations found" "$prod_jar"

echo "== mutated compilation must be rejected"
expect 1 "MUTATION" "$tmp/mutated.jar"
expect 1 "com/example/Calculator.class" "$tmp/mutated.jar"

echo "== mutations inside a nested jar must be found"
expect 1 "BOOT-INF/lib/lib.jar!/com/example/Calculator.class" "$tmp/boot.jar"

echo "== bundled mutflow core is reported, annotations are fine"
core_jar=$(ls "$root"/mutflow-core/build/libs/*.jar | grep -v -e sources -e javadoc | head -1)
annotations_jar=$(ls "$root"/mutflow-annotations/build/libs/*.jar | grep -v -e sources -e javadoc | head -1)
expect 1 "RUNTIME" "$core_jar"
expect 0 "OK: no mutations found" "$annotations_jar"

echo "== usage errors"
expect 2 "no such file" "$tmp/does-not-exist.jar"
expect 2 "Usage:"

if [ "$failures" -gt 0 ]; then
    echo "$failures case(s) FAILED"
    exit 1
fi

echo "all cases passed"
exit 0
