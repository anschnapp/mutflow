#!/bin/bash
#
# mutflow-verify-jar.sh - fail if a production artifact contains mutflow mutations.
#
# Usage: mutflow-verify-jar.sh [--allow-bundled-runtime] <artifact.jar> [more.jar ...]
#
# Mutated classes reference io/github/anschnapp/mutflow/MutationRegistry, so that
# reference is the marker we look for. Nested archives (Spring Boot BOOT-INF/lib,
# fat jars) are scanned as well. The mutflow-annotations classes (MutationTarget,
# SuppressMutations) are fine in production, the core/runtime classes are not
# (use --allow-bundled-runtime if you ship them on purpose).
#
# Exit codes: 0 = clean, 1 = findings, 2 = usage error or missing unzip.

MARKER="io/github/anschnapp/mutflow/MutationRegistry"
MUTFLOW_DIR="io/github/anschnapp/mutflow"

allow_runtime=0
mutations=0
runtimes=0
archives=0

usage() {
    echo "Usage: mutflow-verify-jar.sh [--allow-bundled-runtime] <artifact.jar> [more.jar ...]"
}

# check_archive <file on disk> <display name>
check_archive() {
    archives=$((archives + 1))
    dir="$tmp/$archives"
    mkdir "$dir"
    if ! unzip -qq -o "$1" -d "$dir" >/dev/null 2>&1; then
        echo "ERROR: cannot read archive: $2" >&2
        exit 2
    fi

    # classes carrying injected mutation switches (mutflow's own classes may mention it)
    for f in $(grep -rlF --include='*.class' "$MARKER" "$dir"); do
        entry=${f#"$dir"/}
        case "$entry" in "$MUTFLOW_DIR"/*) continue ;; esac
        echo "MUTATION  $2!/$entry"
        mutations=$((mutations + 1))
    done

    # bundled mutflow core/runtime classes
    for f in $(find "$dir/$MUTFLOW_DIR" -name '*.class' 2>/dev/null); do
        case "${f##*/}" in MutationTarget.class | SuppressMutations.class) continue ;; esac
        echo "RUNTIME   $2!/${f#"$dir"/}"
        runtimes=$((runtimes + 1))
    done

    # nested archives
    for f in $(find "$dir" \( -name '*.jar' -o -name '*.war' \) 2>/dev/null); do
        check_archive "$f" "$2!/${f#"$dir"/}"
    done
}

if [ "$1" = "--allow-bundled-runtime" ]; then
    allow_runtime=1
    shift
fi

if [ "$1" = "-h" ] || [ "$1" = "--help" ]; then
    usage
    exit 0
fi

if [ $# -eq 0 ]; then
    usage >&2
    exit 2
fi

if ! command -v unzip >/dev/null 2>&1; then
    echo "ERROR: 'unzip' is required but not installed" >&2
    exit 2
fi

tmp=$(mktemp -d "${TMPDIR:-/tmp}/mutflow-verify.XXXXXX") || exit 2
trap 'rm -rf "$tmp"' EXIT

for jar in "$@"; do
    if [ ! -f "$jar" ]; then
        echo "ERROR: no such file: $jar" >&2
        exit 2
    fi
    check_archive "$jar" "$jar"
done

if [ "$mutations" -gt 0 ]; then
    echo "FAILED: found $mutations class(es) containing mutflow mutations." >&2
    echo "The artifact was built with the mutflow compiler plugin applied to production code." >&2
    exit 1
fi

if [ "$runtimes" -gt 0 ] && [ "$allow_runtime" -eq 0 ]; then
    echo "FAILED: found $runtimes bundled mutflow runtime class(es)." >&2
    echo "Only mutflow-annotations belongs on a production classpath." >&2
    echo "Re-run with --allow-bundled-runtime if this is intentional." >&2
    exit 1
fi

echo "OK: no mutations found."
exit 0
