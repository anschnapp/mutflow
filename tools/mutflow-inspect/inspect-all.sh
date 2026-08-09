#!/usr/bin/env bash
# mutflow-inspect-all: run the mutated binary on EVERY KMP target (JVM, JS,
# WASM, Native) and aggregate the per-platform JSON results into one HTML
# dashboard so you can see killed vs survived mutants by eye, per platform.
#
# Usage:
#   ./inspect-all.sh                 # build + run all targets + write report-all.html
#   ./inspect-all.sh --no-build      # skip the Gradle build (reuse existing classes)
#
# Output: report-all.html in this directory (open in a browser).
set -euo pipefail
cd "$(dirname "$0")"

ROOT="$(cd ../.. && pwd)"
OUT="report-all.html"
RESULTS_DIR="$ROOT/mutflow-test-kmp/build/inspect-results"

# 1. Build the mutated KMP classes for every target (unless --no-build).
if [[ "${1:-}" != "--no-build" ]]; then
  echo "[inspect-all] building mutated KMP classes for all targets..."
  (cd "$ROOT" && ./gradlew \
      :mutflow-test-kmp:compileKotlinJvm \
      :mutflow-test-kmp:compileKotlinJs \
      :mutflow-test-kmp:compileKotlinWasmJs \
      :mutflow-test-kmp:compileKotlinLinuxX64 \
      --console=plain -q)
fi

# 2. Run the inspector test on each target. Each writes inspect-results/<platform>.json.
#    --rerun-tasks forces the test to actually re-run (and rewrite its JSON) even
#    when Gradle considers the task up-to-date.
echo "[inspect-all] running inspector on JVM..."
(cd "$ROOT" && ./gradlew :mutflow-test-kmp:jvmTest --tests 'sample.PlatformInspectorTest' --rerun-tasks --console=plain -q)

echo "[inspect-all] running inspector on JS (node)..."
(cd "$ROOT" && ./gradlew :mutflow-test-kmp:jsNodeTest --tests 'sample.PlatformInspectorTest' --rerun-tasks --console=plain -q)

echo "[inspect-all] running inspector on WASM (node)..."
(cd "$ROOT" && ./gradlew :mutflow-test-kmp:wasmJsNodeTest --tests 'sample.PlatformInspectorTest' --rerun-tasks --console=plain -q)

echo "[inspect-all] running inspector on Native (linuxX64)..."
(cd "$ROOT" && ./gradlew :mutflow-test-kmp:linuxX64Test --tests 'sample.PlatformInspectorTest' --rerun-tasks --console=plain -q)

# 3. Aggregate the JSON files into one HTML dashboard.
echo "[inspect-all] aggregating results..."
python3 - "$ROOT" "$OUT" <<'PY'
import json, os, sys, html

root, out = sys.argv[1], sys.argv[2]
platforms = ["jvm", "js", "wasmJs", "linuxX64"]
data = {}
# Each target writes inspect-results/<platform>.json into its own working dir
# (JVM/native: mutflow-test-kmp/build/inspect-results; JS/WASM: the package dir).
# Glob the whole repo for the files.
found = {}
for dirpath, dirnames, filenames in os.walk(root):
    if "inspect-results" in dirnames:
        d = os.path.join(dirpath, "inspect-results")
        for f in os.listdir(d):
            if f.endswith(".json"):
                p = f[:-5]
                full = os.path.join(d, f)
                # Prefer the most recently written file for each platform (stale
                # copies can linger in build/ dirs from earlier runs).
                if p not in found or os.path.getmtime(full) > os.path.getmtime(found[p]):
                    found[p] = full
for p in platforms:
    path = found.get(p)
    if path and os.path.exists(path):
        with open(path) as f:
            data[p] = json.load(f)
    else:
        data[p] = None

def esc(s):
    return html.escape(str(s))

rows = []
for p in platforms:
    d = data[p]
    if d is None:
        rows.append(f"<tr><td class='mono'>{p}</td><td colspan='5' class='detail'>no results (target did not run)</td></tr>")
        continue
    variants = d.get("variants", [])
    killed = sum(1 for v in variants if v.get("killed"))
    survived = len(variants) - killed
    rows.append(
        f"<tr class='plat'><td class='mono'>{p}</td>"
        f"<td>{len(variants)}</td><td>{killed}</td><td>{survived}</td>"
        f"<td>{100.0*killed/len(variants):.1f}%</td>"
        f"<td class='detail'>{esc('; '.join(v['operator'] for v in variants[:8]))}{' …' if len(variants)>8 else ''}</td></tr>"
    )

# Per-platform detail tables.
detail = []
for p in platforms:
    d = data[p]
    if d is None:
        continue
    variants = d.get("variants", [])
    detail.append(f"<h2>{p}</h2><table><tr><th>Point</th><th>Variant</th><th>Operator</th><th>Location</th><th>Status</th><th>Detail</th></tr>")
    for v in variants:
        badge = "k" if v.get("killed") else "s"
        label = "KILLED" if v.get("killed") else "SURVIVED"
        detail.append(
            f"<tr><td class='mono'>{esc(v.get('pointId'))}</td>"
            f"<td>{v.get('variant')}</td>"
            f"<td class='mono'>{esc(v.get('operator'))}</td>"
            f"<td class='mono'>{esc(v.get('location'))}</td>"
            f"<td><span class='badge {badge}'>{label}</span></td>"
            f"<td class='detail'>{esc(v.get('detail',''))}</td></tr>"
        )
    detail.append("</table>")

html_doc = f"""<!DOCTYPE html><html><head><meta charset='utf-8'>
<title>mutflow multiplatform binary inspection</title>
<style>
body{{font-family:system-ui,sans-serif;margin:2rem;color:#1a1a1a}}
h1{{font-size:1.4rem}}h2{{font-size:1.1rem;margin-top:2rem}}
.killed{{color:#0a7d33;font-weight:600}}.survived{{color:#b00020;font-weight:600}}
table{{border-collapse:collapse;margin-top:.5rem;width:100%}}
th,td{{border:1px solid #ddd;padding:6px 10px;text-align:left;font-size:.82rem;vertical-align:top}}
th{{background:#f4f4f4}}.mono{{font-family:ui-monospace,monospace}}
.detail{{color:#555;font-size:.75rem;max-width:520px}}
.badge{{display:inline-block;padding:1px 8px;border-radius:10px;font-size:.75rem;color:#fff}}
.badge.k{{background:#0a7d33}}.badge.s{{background:#b00020}}
tr.plat td{{font-weight:600}}
</style></head><body>
<h1>mutflow multiplatform binary inspection</h1>
<p>Mutated classes: <span class='mono'>sample.Calculator</span> &middot; targets: JVM, JS, WASM, Native</p>
<table><tr><th>Platform</th><th>Variants</th><th>Killed</th><th>Survived</th><th>Kill rate</th><th>Operators</th></tr>
{''.join(rows)}
</table>
{''.join(detail)}
</body></html>"""

with open(out, "w") as f:
    f.write(html_doc)
print(f"[inspect-all] wrote {out}")
for p in platforms:
    d = data[p]
    if d is None:
        print(f"[inspect-all] {p}: no results")
        continue
    variants = d.get("variants", [])
    killed = sum(1 for v in variants if v.get("killed"))
    print(f"[inspect-all] {p}: {killed}/{len(variants)} killed")
PY
