# mutflow on Kotlin/Native - Design Proposal

> **Status: PROPOSAL / IN PROGRESS - nothing described here is implemented or shipped.**
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
  Native backend. **This is the riskiest assumption and is gated by a spike (see
  Phased Plan).**
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
1. Baseline:   exec test binary with MUTFLOW_MODE=discovery
               -> binary runs all tests, registry collects points + touch counts
               -> on exit, registry serializes to build/mutflow/discovery.json

2. Selection:  Gradle task reads discovery.json and runs mutation selection
               (same mutflow-runtime code, executed in the Gradle JVM process)

3. Loop:       for each selected mutation:
               exec test binary with MUTFLOW_ACTIVE_MUTATION=<pointId>:<variantIndex>
               -> binary activates that one mutation at startup, runs all tests once,
                  writes a result file (which test failed, if any), exits

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

### What needs a new design (open)

- **Partial run detection**: the JVM mechanism counts `@Test` methods via JUnit. The
  principle ("never report survivors from a partial suite") still applies; the Native
  mechanism is open (likely: compare executed test count between baseline and mutation
  runs, or detect test filtering flags passed to the binary).
- **`underTest {}` resolution**: with one mutation per process there is no run counter
  and no session to look up. The parameterless API likely reads process-global state
  initialized from the env var at startup. Semantics to be confirmed in the spike.
- **Traps and configuration**: `@MutFlowTest(traps = [...])` is a JUnit annotation.
  On the Native path, traps, run limits, and target filtering need a home in the
  Gradle DSL (e.g., `mutflow { traps = listOf(...) }`) and/or an `expect` annotation
  that actualizes to the JUnit machinery on JVM and to metadata on Native.

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
| `linuxX64`, `macosX64`, `macosArm64`, `mingwX64` | Planned: straightforward (exec binary on build host) |
| Apple simulators (`iosSimulatorArm64`, `iosX64`, `watchosSimulatorArm64`, ...) | Planned: same model; env vars need the `SIMCTL_CHILD_` prefix to reach the simulated process |
| iOS/watchOS/tvOS device targets, Android Native | Out of scope: no standard Gradle test execution exists for these |
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
- Everything that differentiates mutflow survives: single compilation, no separate
  tool, `underTest {}` scoping, traps, copy-pasteable survivor names, build fails
  on survivors.

JVM stays the flagship interactive experience; Native is the platform reach.

## Phased Plan

Each phase keeps the JVM path green and releasable.

1. **Phase 0 - Spike (gate for everything else):** on a branch, apply the existing
   compiler plugin to a Native test compilation with a stubbed registry (hardcoded
   `check()` reading an env var). Answers the riskiest question: does the IR
   transformation survive the Native backend? If this fails badly, stop here cheaply.
2. **Phase 1 - KMP conversion:** `mutflow-core`/`mutflow-runtime`/`mutflow-annotations`
   become multiplatform modules. Pure structural refactor, JVM behavior identical,
   verified against the full regression harness. Released on its own as a canary.
3. **Phase 2 - Native runtime:** native targets for core/runtime, discovery and result
   file serialization, env-var activation.
4. **Phase 3 - Gradle orchestration:** the process-per-mutation task, exit-code
   inversion, summary reporting, plus a KMP example project (the Native sibling of
   `example/`). Usable end-to-end is the shipping gate.

## Open Questions

- Exact discovery/result file format (JSON schema, versioning between plugin and runtime).
- `underTest {}` semantics in the per-process model (see above).
- Where traps and class-level configuration live on the Native path (Gradle DSL vs
  expect/actual annotation).
- Partial run detection mechanism without JUnit introspection.
- How `MutationsExhaustedException` maps to the Gradle loop (likely: the task simply
  stops when selection returns null; no exception needed).
- Whether the summary should also be written as a machine-readable report file
  (useful for CI annotations; not needed on the JVM path today).
