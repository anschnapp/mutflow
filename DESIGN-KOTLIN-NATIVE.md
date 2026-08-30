# mutflow on Kotlin/Native - Design Proposal

> **Status: PROPOSAL / IN PROGRESS - nothing described here is shipped.**
>
> **Phase 0 (spike) passed on 2026-07-04**: the existing compiler plugin works
> unmodified on the Kotlin/Native backend. See [Phase 0 Spike Results](#phase-0-spike-results).
>
> **Phase 2 (native runtime) done on 2026-07-05**: mutflow-annotations/core/runtime
> build as native klibs (linuxX64 + mingwX64), `MutFlow.underTest {}` works in
> commonTest, and the env-var/file contract below is implemented and verified
> end-to-end against the real artifacts. See [Phase 2 Results](#phase-2-results).
>
> **Phase 3 (Gradle orchestration) done on 2026-07-05**: the Gradle plugin wires
> Kotlin Multiplatform projects fully automatically - per-target instrumented
> compilations (production binaries stay clean), the `mutflow<Target>Test`
> orchestration tasks, and the `example-native/` KMP example project verified
> end-to-end. See [Phase 3 Results](#phase-3-results).
>
> This document describes how mutflow will support Kotlin/Native targets. It is a delta
> document: it only covers what differs from the main [DESIGN.md](DESIGN.md). Everything
> not mentioned here (mutation operators, discovery model, selection strategies, traps,
> suppression, verification modes, timeout detection) is shared and works as described there.
>
> No Native support will be shipped until there is a usable end-to-end setup, verified
> by a working example project. The JVM path is not affected by this work and remains
> the primary, stable way to use mutflow.

## Motivation

There is currently **no mutation testing tool for Kotlin/Native at all**. The traditional
approach (Pitest-style: generate a mutant, recompile, run tests, repeat) is structurally
impossible or impractical on Native:

- Pitest and Arcmutate mutate JVM bytecode. Kotlin/Native produces no bytecode - the
  compiler goes from IR through LLVM to a native binary. There is nothing for them to mutate.
- A hypothetical source-level tool would need to recompile and relink per mutant.
  Kotlin/Native compile+link cycles take minutes even for small projects, making
  per-mutant compilation economically unviable.

mutflow's mutant schemata approach compiles **once** and only re-runs a fast-starting
native binary per mutation. It is plausibly the only mutation testing architecture that
can work on Kotlin/Native.

## What Stays the Same

The compiler plugin layer is backend-agnostic and carries over unchanged:

- `IrGenerationExtension` runs on the IR produced by FIR2IR, which is shared across
  JVM, Native, and JS backends in K2. (Compose Multiplatform's compiler plugin works
  the same way.)
- All mutation operators match on FIR2IR output (EQEQ origins, ANDAND/OROR `IrWhen`
  structures, intrinsic comparison calls) and should behave identically under the
  Native backend. **This was the riskiest assumption; the Phase 0 spike confirmed it
  (see Phase 0 Spike Results below).**
- Target scoping (`@MutationTarget`, Gradle target patterns), `@SuppressMutations`,
  comment-based line suppression, and timeout check injection are compile-time and
  backend-neutral.

The mutation engine semantics are also unchanged: discovery model, touch counts,
selection strategies, shuffle modes, variant exhaustion.

## What Is Different: Orchestration

This is the core architectural difference.

### JVM (existing): in-process run loop

On the JVM, JUnit 6's `@ClassTemplate` re-runs the test class N times inside one JVM
process. The `MutFlowExtension` orchestrates: session lifecycle, mutation selection
between runs, thread-to-session routing, and reporting. Multiple runs share one
process and one in-memory registry.

### Native (proposed): process-per-mutation, Gradle-orchestrated

kotlin-test on Native has no extension mechanism, no `@ClassTemplate`, and no way to
re-run a test suite N times in-process. Instead, the run loop moves into the Gradle
plugin, and the process boundary becomes the run boundary:

**One process = one run. The test binary never knows other runs exist.**

A `mutflowNativeTest`-style task orchestrates:

```
1. Baseline:   exec test binary with MUTFLOW_DISCOVERY_FILE=build/mutflow/discovery.json
               -> binary runs all tests; every underTest block runs in a discovery
                  session collecting points + touch counts
               -> the file is rewritten after each underTest block (idempotent
                  overwrite: no shutdown hook needed - Native has no reliable JVM-style
                  shutdown hook - and a crash mid-suite still leaves a valid file)

2. Selection:  Gradle task reads discovery.json and runs mutation selection
               (same mutflow-runtime code, executed in the Gradle JVM process)

3. Loop:       for each selected mutation:
               exec test binary with MUTFLOW_ACTIVE_MUTATION=<pointId>:<variantIndex>
               (optionally MUTFLOW_RESULT_FILE=<path>, MUTFLOW_TIMEOUT_MS=<n>)
               -> binary activates that one mutation inside every underTest block,
                  runs all tests once, writes a small result file, exits

4. Verdict:    exit code inversion at the Gradle level:
               - binary exits nonzero -> a test failed -> mutation KILLED (good)
               - binary exits zero    -> all tests passed -> mutation SURVIVED
                 -> task fails the build (STRICT mode)

5. Report:     Gradle task aggregates result files and prints the summary
               (same summary format as the JVM path)
```

### Role split

| Concern | JVM path | Native path |
|---|---|---|
| Run loop | JUnit extension (in-process) | Gradle task (process per run) |
| Discovery handoff | In-memory `GlobalRegistry` | `build/mutflow/discovery.json` |
| Mutation activation | `MutFlow.startRun()` in-process | `MUTFLOW_ACTIVE_MUTATION` env var at startup |
| Kill detection | Assertion exception swallowed by extension | Nonzero exit code, inverted by Gradle task |
| Survivor handling | `MutantSurvivedException` fails the test | Gradle task fails the build |
| Summary | Printed at class end by extension | Printed by Gradle task after all runs |

### What disappears on the Native path

The per-process model makes several JVM mechanisms unnecessary. Their entire problem
class does not exist when each process has exactly one active mutation:

- **Thread-to-session routing**: no concurrent sessions in one process.
- **`synchronized withSession()`**: no other test classes to serialize against.
- **Session IDs and lifecycle calls**: the process lifecycle is the session lifecycle.

This is a simplification, not a workaround.

### What needs a new design (open, updated after Phase 3)

- **Partial run detection**: largely defused on the Native path - the orchestrator
  always executes the full test binary without any filtering, so a mutation run
  cannot see fewer tests than the baseline unless the user drives the binary by
  hand (outside mutflow's responsibility). Revisit only if the orchestrator ever
  learns to pass test filters through.
- **Traps**: `@MutFlowTest(traps = [...])` is a JUnit annotation. Run limits,
  timeout and verification mode found their Gradle DSL home in Phase 3
  (`nativeMaxMutationRuns`, `nativeTimeoutMs`, `nativeVerificationMode`); traps and
  target filtering are still open (likely `mutflow { nativeTraps = listOf(...) }`,
  matching by the display names the summary prints).

### `underTest {}` resolution (resolved in Phase 2)

`mutflow-runtime` gained an internal `ProcessRun` model: one instance per process,
resolved lazily from the environment on the first `underTest {}` call. The
parameterless `MutFlow.underTest {}` consults `currentProcessRun()` first - an
expect/actual that is hardwired to `null` on the JVM (so the JUnit session machinery
and JVM behavior are untouched, and the orchestration env vars are deliberately
ignored there) and never null on Native:

- **Inactive** (no MUTFLOW_* vars): `underTest` is a transparent pass-through, so
  plain un-orchestrated `:linuxX64Test` runs behave as if mutflow were absent.
- **Discovery** (`MUTFLOW_DISCOVERY_FILE`): each `underTest` block runs in its own
  registry session (same as the JVM baseline - that is what makes touch counts mean
  "number of underTest blocks that hit the point"), accumulates into process-global
  state, and rewrites the discovery file.
- **Mutation** (`MUTFLOW_ACTIVE_MUTATION`): each `underTest` block runs a session
  with the mutation active and the `MUTFLOW_TIMEOUT_MS` deadline armed; a killing
  assertion propagates out and fails the binary (the kill signal). If
  `MUTFLOW_RESULT_FILE` is set, a result JSON is (re)written after every block with
  two flags the exit code cannot express: `touched` (was the mutated point reached
  at all - distinguishes "survived" from "mutation never executed") and `timedOut`
  (deadline hit, likely an infinite loop; reported as TIMED_OUT instead of KILLED).

## Test Authoring in commonTest

Common test code uses kotlin-test, not JUnit. The intended authoring model:

```kotlin
// commonTest - runs on JVM and Native targets
class CalculatorTest {
    @Test
    fun testIsPositive() {
        val result = MutFlow.underTest {   // same API, multiplatform
            calculator.isPositive(5)
        }
        assertTrue(result)
    }
}
```

- `MutFlow.underTest {}` becomes a multiplatform API (`mutflow-runtime` gains native
  targets).
- On the JVM target, the JUnit integration works as today.
- On Native targets, the Gradle task drives the runs; the test code itself is identical.
- Class-level configuration (`@MutFlowTest` parameters) is the open question noted above.

## Module Impact

| Module | Change |
|---|---|
| `mutflow-annotations` | Becomes KMP (annotations are trivially common) |
| `mutflow-core` | Becomes KMP. Registry logic moves to `commonMain`; **JVM actuals keep the current implementation verbatim** (`synchronized`, `ConcurrentHashMap`, `System.nanoTime`). New: discovery/result file serialization (used only by the Native path) |
| `mutflow-runtime` | Becomes KMP. Selection/shuffle logic is pure and moves to `commonMain`. The Gradle plugin reuses it JVM-side for Native orchestration |
| `mutflow-compiler-plugin` | No structural change. Gets registered for native compilations |
| `mutflow-junit6` | **Untouched.** JVM-only, as today |
| `mutflow-gradle-plugin` | Gains the Native orchestration mode (new task type, wired to native test binaries). JVM test wiring unchanged |

Iron rule for the KMP conversion: **the JVM path must be bit-identical in behavior.**
JVM `actual` implementations are the current code, copied as-is. No rewriting JVM
internals "to be more common-friendly". The conversion ships as its own release with
zero behavior change, verified against the full regression harness (test suite,
`example/` project, Spring Boot monorepo setup) before any Native feature lands.

## Supported Targets

mutflow's Native coverage equals where Kotlin/Native tests can run at all. There is no
standard Gradle test execution for device targets in vanilla Kotlin either, so mutflow
inherits the platform's own boundaries and covers everything inside them.

| Target | Status |
|---|---|
| `linuxX64` | **Done (Phase 2)**: runtime klibs build; unit tests and the end-to-end verification run on the Linux dev machine |
| `mingwX64` | **Declared (Phase 2)**: klib cross-compiles from Linux, which proves the commonized posix API usage compiles for Windows; an actual test run needs a Windows host (CI, pre-release) |
| `macosX64`, `macosArm64` | Planned: same model; a macOS host is required even to produce the klibs, so these wait for a Mac/CI (adding them is a build-file one-liner per module) |
| Apple simulators (`iosSimulatorArm64`, `iosX64`, `watchosSimulatorArm64`, ...) | Planned: same model; env vars need the `SIMCTL_CHILD_` prefix to reach the simulated process |
| iOS/watchOS/tvOS device targets, Android Native | Out of scope: no standard Gradle test execution exists for these |
| `jvm()` target **inside a KMP project** | **Done (Phase 4)**: `MutflowKmpSupport` creates `mutatedMain`/`mutatedTest` compilations for the target and registers a `mutflow<Target>Test` task that runs the ordinary in-process JUnit loop. The compiler plugin synthesizes `@MutFlowTest` onto test classes, since `commonTest` cannot name a JVM-only annotation. The stock `jvmTest` task stays a pass-through |
| JS / Wasm (Node or browser) | Not part of this work. Node could reuse the pattern later; browser lacks env vars and file IO and needs a different design |
| Android local unit tests | Separate question: JVM path in principle, but `mutflow-junit6` requires JUnit 6 (Android ecosystem is JUnit 4/5-centric) |

As with all of KMP, each target's tests run only on a matching build host (Linux CI
runs `linuxX64`, a macOS machine runs macOS and simulator targets).

## UX Tradeoff

The JVM path shows mutation runs as test-tree iterations in the IDE
(`Run without mutations`, `Mutation: (Calculator.kt:7) > → >=`). The Native path
cannot replicate this: runs are separate processes driven by a Gradle task, so results
arrive as task output plus the summary report.

This is acceptable because:

- The IDE experience for Native tests is already Gradle-mediated; there is no rich
  in-process native test runner being downgraded.
- In a KMP project, the JVM target keeps the full interactive UX for `commonMain`
  logic, which is where most mutations live. The intended workflow: develop against
  the JVM target (interactive mutation feedback), run Native mutation verification
  in CI (catches `actual` implementations and platform-specific code).
  Implemented in Phase 4: `mutflowJvmTest` runs the same `commonTest` sources
  through the in-process JUnit path, test tree and all.
- Everything that differentiates mutflow survives: single compilation, no separate
  tool, `underTest {}` scoping, traps, copy-pasteable survivor names, build fails
  on survivors.

JVM stays the flagship interactive experience; Native is the platform reach.

## Phase 0 Spike Results

> Executed 2026-07-04 on the `kotlin-native` branch. The spike project lives in
> `spike/` (a standalone Gradle build, not included in the root build, same pattern
> as `example/`). It is throwaway code and will not be merged; only the findings
> below are the deliverable.

**Verdict: PASSED. The existing `mutflow-compiler-plugin` (built against Kotlin
2.4.0) transforms a Kotlin/Native `linuxX64` compilation completely unmodified.**

Setup that was validated:

- **Plugin wiring without the Gradle plugin**: the plugin jar (from mavenLocal) is
  passed to the native compiler via `-Xplugin=<jar>` in `freeCompilerArgs` on
  `KotlinNativeCompile` tasks. The `CompilerPluginRegistrar` / ServiceLoader
  mechanism works identically to the JVM. No plugin options were needed
  (annotation-based targeting).
- **Stubbed registry by FQN substitution**: the plugin resolves
  `io.github.anschnapp.mutflow.MutationRegistry` and `@MutationTarget` purely by
  fully qualified name (`pluginContext.referenceClass`) and never links against
  mutflow-core classes. A hand-written native klib with matching FQNs and
  signatures (reading `MUTFLOW_ACTIVE_MUTATION` via `platform.posix.getenv`) fully
  satisfies the injected call sites. This de-risks Phase 2: any KMP `mutflow-core`
  that preserves FQNs and signatures will be picked up without compiler plugin
  changes.

Observed behavior, all identical to the JVM path:

- `x > 0` in a `@MutationTarget` class produced the same 3 mutation points as on
  JVM: relational (`>` with variants `>=,<`), constant boundary (`0` with variants
  `1,-1`), and boolean return (variants `true,false`). Point IDs, source locations,
  and variant metadata came through unchanged.
- **No backend crashes.** The lowering-conflict class of problems seen on JVM in
  multi-plugin setups (ConstEvaluationLowering, FunctionReferenceLowering) did not
  appear under the Native backend's lowering pipeline.
- **Env-var activation and exit-code kill detection work**: baseline run (no env
  var) discovered points and passed; each of the 6 variants, activated via
  `MUTFLOW_ACTIVE_MUTATION=<pointId>:<variantIndex>`, was killed by the test suite
  (nonzero exit code of `test.kexe`), and in each case the *expected* boundary test
  was the one that failed. This validates the process-per-mutation orchestration
  model end to end at small scale.

Findings to carry into later phases:

- **Mutations land in the main klib.** The spike applies the plugin to the main
  compilation; there is no Native equivalent of the JVM dual-build (`mutatedMain`)
  yet. Deciding whether/how to keep mutations out of production binaries (e.g.,
  only instrument test-linked compilations, or accept instrumented klibs for test
  builds only) is a Phase 3 Gradle plugin concern.
- The spike bypasses `MutFlow.underTest {}` entirely (whole process = one session).
  The `underTest {}` semantics question from Open Questions remains for Phase 2.
- First native build downloads the Kotlin/Native toolchain to `~/.konan`
  (one-time, several minutes); subsequent compile+link cycles for the tiny spike
  were seconds, consistent with the compile-once economics this design relies on.

## Phase 2 Results

> Executed 2026-07-05 on the `kotlin-native` branch, on top of the Phase 1 KMP
> conversion.

**Verdict: the real mutflow runtime works on Kotlin/Native.** The Phase 0 stub
registry is gone; the reworked `spike/` consumes the genuine
`mutflow-annotations`/`mutflow-core`/`mutflow-runtime` klibs from mavenLocal and the
unmodified compiler plugin, and tests use the multiplatform `MutFlow.underTest {}`
exactly as sketched in "Test Authoring in commonTest".

What was built:

- **Native targets** `linuxX64` + `mingwX64` on the three runtime-side modules. All
  native actuals live in a shared `nativeMain` source set (commonized
  `platform.posix` for getenv/file IO); they are drastically simpler than the JVM
  ones because the per-process model has no concurrency (plain collections, no lock).
- **Env-var contract** (the process interface Phase 3's Gradle task will drive):
  `MUTFLOW_DISCOVERY_FILE`, `MUTFLOW_ACTIVE_MUTATION=<pointId>:<variantIndex>`,
  `MUTFLOW_RESULT_FILE` (optional), `MUTFLOW_TIMEOUT_MS` (optional, default 60000).
  No vars set = inactive pass-through. On the JVM these vars are deliberately
  ignored (`currentProcessRun()` is hardwired null there); JVM behavior verified
  unchanged via the full test suite and the `example/` project.
- **File serialization** in `mutflow-core` (`MutflowFiles`): hand-rolled,
  dependency-free JSON with a `formatVersion` field for plugin/runtime version-skew
  detection. Discovery file: points with variant metadata + touch counts, in
  discovery order. Result file: `touched` + `timedOut` flags per mutation run.
  Builders are pure string functions, unit-tested in commonTest on all targets.

End-to-end verification against the reworked spike (linuxX64):

- Plain `test.kexe` run without env vars: green (inactive mode).
- Discovery run: same 3 mutation points as Phase 0 and as the JVM path (relational
  `>`, constant `0`, boolean return), each with touchCount 4 (all 4 tests hit them).
- All 6 variants activated via env var: each killed (nonzero exit) by exactly the
  expected boundary test, result file `touched:true`.
- Bogus mutation id: exit 0 (survives, correctly) with `touched:false` - the signal
  that lets the orchestrator flag "mutation never executed" instead of a plain
  survivor.

## Phase 3 Results

> Executed 2026-07-05 on the `kotlin-native` branch, on top of Phase 1 + 2.

**Verdict: mutation testing on Kotlin/Native is usable end-to-end.** A KMP
project applies the mutflow Gradle plugin exactly like a JVM project applies
it today, and `./gradlew mutflowLinuxX64Test` runs the whole loop. The new
`example-native/` project is the living proof (and the shipping-gate
verification): 6/6 mutations killed, each by the expected boundary test,
with survivor/LENIENT/STRICT behavior verified by temporarily weakening a
test.

What was built:

- **Clean-production compilation model** (the resolution of the Phase 0
  finding "mutations land in the main klib"): per native target the Gradle
  plugin creates a `mutatedMain` compilation (same sources as main, compiler
  plugin applied - `isApplicable` matches the compilation name, same constant
  as the JVM source set) and a `mutatedTest` compilation associated with it,
  linked into a dedicated `mutated` test binary. The regular main compilation,
  all production binaries and the plain `<target>Test` task never see any
  instrumentation (verified by symbol-searching the klibs). This is the
  native equivalent of the JVM `mutatedMain` source set trick.
  - The mutated source set depends on the target's default source set, which
    KGP flags with a warning (`KotlinSourceSetDependsOnDefaultCompilationSourceSet`);
    consumers suppress exactly that id via `kotlin.suppressGradlePluginWarnings`
    in gradle.properties (see `example-native/gradle.properties`). The edge is
    deliberate: it is the only wiring that transitively follows whatever
    source set hierarchy a project uses, and the hierarchy is not observable
    at plugin configuration time (KGP applies the default hierarchy template
    in a lifecycle stage after `afterEvaluate`).
- **Orchestration task** `mutflow<Target>Test` (plus a `mutflowNativeTest`
  umbrella): baseline discovery run, parse `discovery.json`, then one process
  per mutation with the Phase 2 env-var contract, exit-code inversion,
  killed-by extraction from the GTest-style test runner output, hard process
  timeout as a safety net above the in-process deadline, JVM-identical
  summary box, and a build failure listing survivors in STRICT mode.
  Registered only for targets runnable on the build host (mingwX64 on Linux
  still cross-compiles the mutated binary as compile proof, matching how
  KGP's own `mingwX64Test` behaves). Mutation testing stays opt-in: `check`
  runs only the plain tests.
- **File parsers in core** (`MutflowFiles.parseDiscoveryJson`/`parseResultJson`):
  hand-rolled reader next to the hand-rolled writer, round-trip tested in
  commonTest on all targets, with a formatVersion check that turns
  plugin/runtime version skew into a clear error. The Gradle plugin depends
  on core's JVM variant for them.
- **Gradle DSL** for the configuration that lives in `@MutFlowTest` on the
  JVM: `nativeMaxMutationRuns` (default unlimited; the run order is the
  MostLikelyStable strategy with identical tie-breakers, so a cap tests the
  most-likely-to-survive mutations first), `nativeTimeoutMs`,
  `nativeVerificationMode` (STRICT/LENIENT/DISABLED, overridable via the
  MUTFLOW_VERIFICATION_MODE environment variable like on the JVM).
- Result-file nuance surfaced in reporting: a survivor with `touched:false`
  is reported as "the mutated code was never executed" instead of a plain
  survivor.

Still open after Phase 3 (tracked in Open Questions): traps on the native
path, the random selection strategies (only the deterministic
MostLikelyStable order is implemented; PureRandom/MostLikelyRandom need the
seed plumbing moved into a shared pure selector first), and a
machine-readable report file.

## Phase 4 - The JVM Target of a KMP Project (DONE)

> Designed 2026-08-28, rewritten the same day after the POC below invalidated
> the first draft. **Implemented 2026-08-30.** This phase closes the "JVM
> target inside a KMP project" item in Open Questions and validates the UX
> Tradeoff section, whose argument depended on it. What the implementation
> found on top of the plan is recorded in "Results" below.

### The decision

A KMP project runs its native targets through the Gradle orchestrator built in
Phase 3, and its `jvm()` target through the existing in-process JUnit path
(`mutflow-junit6`, `@MutFlowTest`, `@ClassTemplate`) - the same machinery a
plain `kotlin("jvm")` project uses today, unchanged.

The obstacle was never the run model. It was that `@ClassTemplate` needs a
JVM-only annotation physically present on a test class that lives in
`commonTest`, where it cannot be written. The compiler plugin puts it there
instead.

| Project shape | Run model | Class-level opt-in |
|---|---|---|
| `kotlin("jvm")` | in-process JUnit runs | `@MutFlowTest`, hand-written |
| KMP `jvm()` target | in-process JUnit runs | `@MutFlowTest`, synthesized by the compiler plugin |
| KMP native target | one process per mutation | none needed |

The authoring model is then identical on all three: mark production code with
`@MutationTarget`, wrap assertions in `MutFlow.underTest {}`, write ordinary
`kotlin.test` classes. Nothing in `commonTest` names a JVM type.

### POC result (2026-08-28): annotation synthesis works

A throwaway `kotlin("jvm")` project, both mutflow jars applied as raw
`-Xplugin` arguments, a new opt-in compiler option
`annotateTestClasses=<annotation-fqn>`, and a new `TestClassAnnotator` IR
transformer that scans each class body for a call to `MutFlow.underTest` and,
on a hit, appends the named annotation to the class. Verified:

- `javap -v CalculatorTest` shows a class-level
  `RuntimeVisibleAnnotations: io.github.anschnapp.mutflow.junit.MutFlowTest`.
  Runtime retention survives into bytecode.
- A control class in the same file with `@Test` methods but no `underTest` call
  is left unannotated, so `underTest` discriminates correctly.
- `./gradlew test` shows `@ClassTemplate` engaging and re-running the class per
  mutation (`CalculatorTest > Mutation: (Calculator.kt:7) 0 -> -1 > ... PASSED`),
  ending in the normal summary box: 4 discovered, 4 killed, 0 survived, build
  green.

So `@MutFlowTest` reaches JUnit through the compiler rather than through source,
and the whole existing extension works behind it untouched.

Two Kotlin 2.4 details the POC had to discover, worth recording because they
are not in any blog post: `IrClass.annotations` is now `List<IrAnnotation>`
rather than `List<IrConstructorCall>`, and the factory to use is
`IrAnnotationImpl.fromSymbolOwner(type, constructorSymbol)` (an extension in
`org.jetbrains.kotlin.ir.expressions.impl.BuildersKt`). Also
`IrElementVisitorVoid` is a deprecated typealias for the abstract class
`IrVisitorVoid`.

What the POC did **not** cover, and 4.0 must: the KMP `jvm()` target
specifically. The POC used `src/test` of a plain JVM project.

### What this deletes from the previous draft

The first Phase 4 draft routed the `jvm()` target through the Gradle
orchestrator too, which required a new JVM runner module driving the JUnit
Platform Launcher, plus moving env-var resolution into `commonMain` and adding
a way to install a `ProcessRun` programmatically on the JVM. None of that is
needed. The runner module is dropped entirely, the runtime is untouched, and
the JVM keeps the interactive IDE test tree that the orchestrated model would
have destroyed.

The draft's load-bearing claim, that no extension API can promote an ordinary
test class into a re-run template, is correct as stated and is still why a
source-level solution fails. But it constrains what an *extension* can do at
runtime, not what the compiler can do at compile time, and the draft never
considered the second.

### Steps

**4.0 - POC gate (KMP shape).** Throwaway project, `jvm()` + `linuxX64()`, tests
in `commonTest` only. Verify three things: the synthesized annotation lands in
the `jvm()` target's `mutatedTest` bytecode; `kotlin.test.Test` resolves to
`org.junit.jupiter.api.Test` there under `useJUnitPlatform()` (it is a typealias,
and the resolution depends on the `kotlin("test")` variant the project pulls in);
and the same `commonTest` sources still compile and run clean on `linuxX64`
with the annotator switched off for that target. If Jupiter is not what resolves,
the fallback is documenting `kotlin("test-junit5")` as a requirement for the
JVM target rather than any design change.

**4.1 - Promote the annotator out of POC status.** `TestClassAnnotator` gets
real tests, an escape hatch (a class that already carries `@MutFlowTest` is
skipped, which the POC already does, plus an opt-out for a project that wants
to hand-annotate), and a decision on `underTest {}` called from a helper
outside the class body - the current scan is class-local and would miss it.
Options: widen the scan to the whole file, or accept the limitation and
document it. Widening is cheap and has no false-positive cost worth worrying
about, since annotating a class with no mutations simply produces a baseline
run.

**4.2 - Gradle wiring for the `jvm()` target.** Extend `MutflowKmpSupport` past
`KotlinNativeTarget`: `KotlinJvmTarget` exposes `compilations` the same way, so
the `mutatedMain` / `mutatedTest` / `associateWith` block ports over almost
verbatim. Differences: `mutatedTest` gets `mutflow-junit6` on its compile and
runtime classpath, and instead of an orchestrator task the target gets a plain
`Test` task (`mutflowJvmTest`) that runs the `mutatedTest` output with
`useJUnitPlatform()`. The stock `jvmTest` task is never touched, exactly as
stock `<target>Test` is not on native.

**4.3 - Compiler plugin applicability.** `isApplicable` currently matches the
compilation name `mutatedMain` alone, so test compilations are never
instrumented. The annotator needs the plugin applied to `mutatedTest` as well,
but only for a JVM target and only in annotate mode: `applyToCompilation` passes
`annotateTestClasses` and no `target` patterns there, which makes the mutation
transformer a no-op on test code. Native `mutatedTest` must keep getting nothing
at all, since there is no JUnit on that classpath and an unresolvable annotation
FQN would only produce the POC's warning.

**4.4 - Config surface.** The run-loop knobs (`maxRuns`, `timeoutMs`,
`verificationMode`, `traps`) live in the `@MutFlowTest` annotation on the JVM
path and in the Gradle DSL on the native path. In a KMP project the annotation
is synthesized, so a user cannot write those values. Two ways to close it:

1. Synthesize the annotation *with* argument values taken from the Gradle DSL,
   passed down as compiler options.
2. Keep the synthesized annotation parameterless and have `MutFlowExtension`
   read the knobs from the environment, which the `mutflowJvmTest` task sets
   from the same DSL properties.

Prefer (2). It reuses the mechanism `MUTFLOW_VERIFICATION_MODE` already
establishes, needs no IR work for enum and array annotation arguments, and
crucially does not recompile `mutatedTest` every time a config value changes.
Precedence on the JVM stays: environment overrides annotation, so a
hand-annotated plain JVM project is unaffected.

While doing this, rename the `native*` DSL properties to unprefixed names
(`maxMutationRuns`, `timeoutMs`, `verificationMode`) - they now govern both
paths, and nothing has been released from this branch yet.

**4.5 - Gate and docs.** Add a `jvm()` target to `example-native/` and make
both `mutflowJvmTest` and `mutflowLinuxX64Test` green the shipping gate, the
role Phase 3 gave the native-only example. Update the UX Tradeoff section (its
"not implemented yet" caveat comes off), close the JVM-in-KMP open question,
and rename this document since it stops being native-specific.

### Results (2026-08-30)

Implemented as planned in 4.1 through 4.5. `example-native/` gained a `jvm()`
target and now greens 6/6 killed on both `mutflowJvmTest` and
`mutflowLinuxX64Test` from the same `commonTest` sources, `./gradlew build`
included. `example/` (plain `kotlin("jvm")`) is unchanged at 13/13.

4.0 settled two of its three checks standalone, on a throwaway KMP project with
no mutflow in it at all:

- `kotlin.test.Test` in `commonTest` compiles to `org.junit.jupiter.api.Test`
  in the `jvm()` target's bytecode with plain `kotlin("test")`. The reserved
  fallback (documenting `kotlin("test-junit5")` as a user requirement) is not
  needed.
- The same sources compile and run clean on `linuxX64`.

The third (annotation lands in `mutatedTest` bytecode) needs the Gradle wiring
to exist and its mechanism was already proven by the plain-JVM POC, so it was
folded into the 4.5 gate, where `javap` on
`build/classes/kotlin/jvm/mutatedTest/com/example/CalculatorTest.class` shows
the `RuntimeVisibleAnnotations` entry and the stock `jvm/test` copy shows none.

Four things the plan did not anticipate, each of which would have shipped as a
bug:

**`kotlin-test` resolves to the wrong variant in a plugin-created compilation.**
KGP picks the `junit5` variant of `kotlin-test` by inspecting the test framework
of the `Test` task wired to a compilation. A compilation this plugin creates has
no such task when the classpath is resolved, so `kotlin("test")` in `commonTest`
resolved to the bare artifact and `kotlin.test.Test` did not resolve at all in
`mutatedTest`. Fixed by adding `kotlin-test-junit5` explicitly, at the
consumer's Kotlin version via `getKotlinPluginVersion()`.

**The stock `jvmTest` task fails without a pass-through mode.** `commonTest`
calls `MutFlow.underTest {}`, but only `mutatedTest` carries the synthesized
annotation, so stock `jvmTest` hit the "no active MutFlow session" guard and
`./gradlew build` failed for every KMP user. Native never had this problem: with
no `MUTFLOW_*` variables set it falls into `ProcessRunMode.Inactive`, a
transparent pass-through. The fix reuses exactly that seam rather than weakening
the guard: the JVM `currentProcessRun()` actual returns an `Inactive` run when
`MUTFLOW_INACTIVE=true`, and the plugin sets that variable on the `jvm()`
target's stock test task only. A plain `kotlin("jvm")` project keeps the loud
error, where a missing `@MutFlowTest` really is a mistake.

**`maxMutationRuns` meant different things on the two paths.** The native
orchestrator plans that many *mutation* runs; the JUnit extension's `maxRuns`
counts total class invocations, of which the baseline is one. Unifying the DSL
name silently made one value mean N mutations on Native and N-1 on the JVM.
The task now converts at the boundary (saturating at `Int.MAX_VALUE`), so the
DSL name is honest and the annotation keeps its own established meaning.

**A `Test` task does not track environment variables as inputs.** Changing
`mutflow { maxMutationRuns }` left `mutflowJvmTest` `UP-TO-DATE`, reporting the
previous run's verdict. The native path gets this for free because its
equivalents are `@Input` properties on a custom task type; the JVM task now
declares them via `inputs.property`.

Also done under 4.4: the `native*` DSL properties became `maxMutationRuns`,
`timeoutMs` and `verificationMode`, since they now govern both paths. Nothing
had been released from this branch, so no deprecation cycle was needed.

### Deferred out of Phase 4

- **Cross-target selection agreement.** With `maxRuns` set, the JUnit extension
  selects its own subset in-process while the native orchestrator selects from
  Gradle, so the two targets would test different mutations and the aggregate
  report would be confusing. Not wrong, just not comparable. The fix is Gradle
  computing one selected list and passing it to both, which means extracting the
  seeded selection out of `MutFlowSession` into a shared pure selector - the same
  refactor the random selection strategies need. Unlimited runs (the default) has
  no such problem, so this is a sharp edge rather than a blocker.
- **Per-source-set differential mode.** Running every mutation on every target is
  largely redundant for `commonMain` code: mutation testing measures test-suite
  quality, which is a property of shared source plus shared tests, so the verdict
  is the same on every target. The unique value is `actual` implementations,
  which exist on one target only. A "run shared mutations once on the primary
  target, plus each target's own source sets where they live" mode would be both
  cheaper and more honest. Blocked on a compiler plugin change: `getSourceLocation`
  does `substringAfterLast('/')`, so the source-set directory (the only thing
  identifying which target a file belongs to) is discarded. Filename matching
  from the Gradle side is ambiguous because `expect`/`actual` files often share a
  name across source sets. Until then, the `targets` property covers the need.
- Traps on the orchestrated (native) path.
- Random selection strategies (PureRandom, MostLikelyRandom).
- Machine-readable report file.

## Phased Plan

Each phase keeps the JVM path green and releasable.

1. **Phase 0 - Spike (gate for everything else): DONE, passed (see above).** On a
   branch, apply the existing compiler plugin to a Native test compilation with a
   stubbed registry (hardcoded `check()` reading an env var). Answers the riskiest
   question: does the IR transformation survive the Native backend? If this fails
   badly, stop here cheaply.
2. **Phase 1 - KMP conversion: DONE on the `kotlin-native` branch (2026-07-04),
   canary release pending.** `mutflow-core`/`mutflow-runtime`/`mutflow-annotations`
   are multiplatform modules with a `jvm()` target only (native targets follow in
   Phase 2). All logic lives in `commonMain`; the JVM-specific primitives
   (`synchronized`, `ConcurrentHashMap`, `System.nanoTime`/`currentTimeMillis`,
   `UUID`, thread IDs) sit behind `internal expect fun` helpers whose JVM actuals
   are the pre-KMP code verbatim (`Platform.jvm.kt` / `MutFlowPlatform.jvm.kt`).
   Only deliberate API change: `SessionId` wraps a `String` (still a UUID string
   on JVM) instead of `java.util.UUID`, so the type can live in common code.
   Verified: full test suite, `example/` project, and the Spring Boot monorepo
   produce identical results with KMP artifacts vs master artifacts (the monorepo
   comparison ran on Kotlin 2.4.0; its usual 2.2.21 setup fails with *both*
   artifact sets since the Kotlin 2.4.0 bump - a pre-existing compatibility
   issue independent of this conversion).
3. **Phase 2 - Native runtime: DONE on the `kotlin-native` branch (2026-07-05),
   see Phase 2 Results below.** Native targets for annotations/core/runtime,
   discovery and result file serialization, env-var activation, `underTest {}`
   via the ProcessRun model.
4. **Phase 3 - Gradle orchestration: DONE on the `kotlin-native` branch
   (2026-07-05), see Phase 3 Results above.** Process-per-mutation task,
   exit-code inversion, summary reporting, clean-production compilation model,
   and the `example-native/` KMP example project verified end-to-end (the
   shipping gate).
5. **Phase 4 - The JVM target of a KMP project: DONE (2026-08-30).** The
   `jvm()` target joins the existing in-process JUnit path, with the compiler
   plugin synthesizing the `@MutFlowTest` that `commonTest` cannot write.
   `mutflow-junit6` keeps serving plain `kotlin("jvm")` projects unchanged.
   `example-native/` now greens 6/6 on both `mutflowJvmTest` and
   `mutflowLinuxX64Test` from one set of sources. See Phase 4 above.

## Open Questions

- Where traps and target filtering live on the Native path (run limits, timeout
  and verification mode landed in the Gradle DSL in Phase 3 and became
  target-neutral in Phase 4; traps are still open).
- Random selection strategies (PureRandom, MostLikelyRandom) on the Native path:
  the orchestrator currently implements only the deterministic MostLikelyStable
  order. Doing this without duplicating semantics means extracting the seeded
  selection out of MutFlowSession into a shared pure selector - a JVM-touching
  refactor that deserves its own careful change.
- Whether the summary should also be written as a machine-readable report file
  (useful for CI annotations; not needed on the JVM path today).

Resolved along the way: `MutationsExhaustedException` needs no Native mapping
(the Gradle loop simply ends when the plan is exhausted), and partial run
detection is a non-issue while the orchestrator always runs the unfiltered
binary (see "What needs a new design").

## Timeout Path Verification (post-Phase 3)

> Executed 2026-07-06 on the `kotlin-native` branch.

Both timeout layers were exercised end-to-end against `example-native/`, using
a `sumUpTo(n)` loop where the `<= → >=` mutation spins forever for `sumUpTo(0)`:

- **In-process deadline** (`MUTFLOW_TIMEOUT_MS`, set via `nativeTimeoutMs`):
  the injected `checkTimeout()` guard broke the loop after the deadline, the
  test failed with `MutationTimedOutException`, the result file carried
  `timedOut: true`, and the orchestrator reported the mutation as TIMED OUT.
- **Hard process kill** (safety net): with the in-process deadline disabled
  (`nativeTimeoutMs = 0`), the orchestrator killed the spinning binary after
  the hard timeout (`baseline*5 + 2*timeoutMs + 30s`), classified the null
  exit code as TIMED_OUT, and continued the mutation loop normally.

One behavior gap was found and fixed during verification: the orchestrator
originally failed the build only on survivors, letting timed-out mutations
pass silently. On the JVM, `MutationTimedOutException` is rethrown regardless
of verification mode (the documented fail-loudly design), so the native
orchestrator now does the same: timed-out mutations fail the build in STRICT
and LENIENT (the decision logic is `NativeOrchestration.buildFailureMessage`,
unit-tested). The failure message names the mutations and points at the
remedy, `// mutflow:ignore` on the affected line - which was also verified
end-to-end on the native backend (the suppressed loop produces no mutation
points; comment-based suppression is compile-time and backend-neutral).

Incidental finding, not native-specific: compound assignments (`sum += i`,
`i += 1`) are never mutated on any backend - `ArithmeticOperator` matches the
`PLUS`/`MINUS`/... origins but not `PLUSEQ`/`MINUSEQ`/... A possible future
operator improvement, tracked outside this document.
