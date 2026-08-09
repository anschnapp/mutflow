# mutflow-inspect

Runs the **mutated binary** against a battery of test inputs on **every KMP
target** (JVM, JS, WASM, Native) and renders an HTML dashboard showing which
mutants are **killed** vs **survived**, with per-input detail (baseline → mutant)
so you can see *why* each mutant changed behavior.

This inspects the actual compiled artifact — the `.class`/`.js`/`.wasm`/native
code that the mutflow compiler plugin injected `MutationRegistry.check(...)`
guards into — not the source or an isolated test harness.

## Usage

```bash
./inspect-all.sh            # build + run all 4 targets + write report-all.html
./inspect-all.sh --no-build # skip the Gradle build (reuse existing classes)
```

Then open `report-all.html` in a browser. It shows a per-platform summary table
(variants / killed / survived / kill rate) plus a full per-platform detail table
of every point × variant with a KILLED/SURVIVED badge and the exact inputs whose
results changed.

## What it does

1. Builds the mutated KMP classes for every target
   (`:mutflow-test-kmp:compileKotlin{Jvm,Js,WasmJs,LinuxX64}`).
2. Runs `sample.PlatformInspectorTest` on each target. The test runs a
   **baseline** session over a battery of inputs to discover every mutation
   point and record the expected (original) results, then for **each point ×
   each variant** runs the same battery with that mutant active. A variant is
   **killed** if any input's result differs from baseline; otherwise it
   **survived**.
3. Each target writes its results to `inspect-results/<platform>.json` in its
   own working directory (JVM/native: `mutflow-test-kmp/`; JS/WASM: the package
   dir under `build/`).
4. `inspect-all.sh` globs the repo for those JSON files (preferring the most
   recently written copy per platform) and aggregates them into one
   `report-all.html`.

## Files

- `PlatformInspectorTest.kt` (in `mutflow-test-kmp/src/commonTest`) — the
  multiplatform inspector; the battery lives here. Add/remove inputs to
  broaden or narrow coverage.
- `PlatformInspectorActual.{jvm,js,wasm,native}.kt` — per-target file I/O.
- `inspect-all.sh` — build + run all targets + aggregate into `report-all.html`.
- `report-all.html` — generated dashboard (regenerated on each run).

## Notes

- Inputs that throw are recorded as `CRASH:<Exception>` rather than aborting the
  run, so a crashing mutant is still reported (and counted as killed).
- The `ConstructorCallOperator` deliberately skips `Regex(...)` constructor
  calls: replacing a `Regex` with `null` makes the subsequent `.containsMatchIn()`
  a null-deref that is a catchable NPE on JVM/JS but an **uncatchable segfault**
  on Kotlin/Native and Kotlin/Wasm, which would kill the whole test process.
- The tool targets the KMP `sample.Calculator` artifact. Point it at another
  module's mutated classes by editing the build tasks in `inspect-all.sh`.
