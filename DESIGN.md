# mutflow - Design Document

## Overview

mutflow is a Kotlin compiler plugin for lightweight, low-overhead mutation testing. It targets developers and teams who currently do no mutation testing due to the high cost and complexity of traditional tools.

## The Problem

Traditional mutation testing (e.g., Pitest) works by:
1. Generating mutants (modified versions of code)
2. Compiling each mutant separately
3. Running tests against each mutant
4. Reporting which mutants survived

This is thorough but **expensive**: many compilation cycles, long execution times, complex tooling setup. Most teams skip mutation testing entirely.

## The Approach: Mutant Schemata

mutflow uses the "mutant schemata" (or "meta-mutant") technique:

1. **Compile once**: The compiler plugin injects ALL mutation variants into the code at compile time, guarded by conditional switches
2. **Runtime selection**: At test runtime, a control mechanism activates exactly one mutation per run
3. **Multiple runs**: Tests execute multiple times — once as baseline, then with different single mutations
4. **Fail on survivors**: If a mutant survives (tests pass when they shouldn't), the test fails with actionable feedback

### Example Transformation

**Test code — before:**
```kotlin
@Test
fun testIsPositive() {
    val result = MutFlow.underTest {
        isPositive(5)
    }
    assertTrue(result)
}
```

**Test code — after compiler plugin:**
```kotlin
@Test
fun testIsPositive() {
    val result = MutFlow.underTest(mutTestCaseId = "abc123") {
        isPositive(5)
    }
    assertTrue(result)
}
```

**Production code — before:**
```kotlin
@MutationTarget
class Calculator {
    fun isPositive(x: Int): Boolean {
        return x > 0
    }
}
```

**Production code — after compiler plugin:**
```kotlin
@MutationTarget
class Calculator {
    fun isPositive(x: Int): Boolean {
        val pointId = "a1b2c3d4"  // IR hash of expression
        val variantCount = 3

        return when (MutationRegistry.check(pointId, variantCount)) {
            0 -> x >= 0
            1 -> x < 0
            2 -> x == 0
            else -> x > 0  // original (null or -1)
        }
    }
}
```

### Runtime Discovery Model

Mutation points are discovered **dynamically at runtime**, not statically at class load:

1. **Discovery run**: Code executes normally (no `activeMutation`). Each `MutationRegistry.check()` call registers "I exist with these variants" and returns `null` (use original). After execution, the registry returns: *"discovered 5 mutation points with their variant counts"*.

2. **Mutation runs**: The caller specifies which mutation to activate via `ActiveMutation(pointIndex, variantIndex)`. When that point calls `check()`, it returns the active variant index instead of `null`.

This dynamic discovery matters because:
- Different `underTest` blocks exercise different code paths
- Only mutations actually reached by the test are counted
- Same class called from different tests may hit different mutation points

## Key Features

### 1. Explicit Test Scoping with `MutFlow.underTest`

Tests explicitly mark the action under test using BDD-style structure:

```kotlin
@Test
fun testIsPositive() {
    // given
    val x = 5

    // when
    val result = MutFlow.underTest {
        isPositive(x)
    }

    // then
    assertTrue(result)
}
```

The `MutFlow.underTest` block:
- Wraps only the action under test (the "when" in given/when/then)
- Returns the result for assertions outside the block
- Assertions stay outside — they should fail when mutations change behavior

### 2. Session-Based Run Model

Each test execution follows a session model:

1. **Discovery run**: Execute normally, count mutation points encountered
2. **Mutation runs**: Each run activates exactly ONE mutation, specified by point index and variant index
3. **Default: 3-8 runs per test** (configurable, orchestrated by JUnit extension)

This means:
- Precise feedback: when a mutant survives, you know exactly which one
- Progressive coverage: different mutations tested across builds as code changes
- Low overhead: small fixed number of runs, not proportional to mutation count

### 3. Deterministic Reproducibility

Mutation activation is explicit and deterministic:
- **Mutation point index**: Which mutation point to activate (0-based, in discovery order)
- **Variant index**: Which variant at that point (0-based)

The test orchestrator (JUnit extension) decides which mutations to test based on discovery results and its own selection strategy.

### 4. Trapping Surviving Mutants

When a mutant survives, its mutation ID is printed:
```
MUTANT SURVIVED: a1b2c3d4:COMPARISON:1
```

This ID can be passed back to **trap** the mutant — ensuring it runs every time while you fix the test gap:

```kotlin
@Test
fun testCalculate() {
    // given
    val x = 10
    val y = 5

    // when
    val result = MutFlow.underTest(
        trap = listOf("a1b2c3d4:COMPARISON:1")
    ) {
        calculate(x, y)
    }

    // then
    assertEquals(15, result)
}
```

Traps are a **temporary debugging aid**:
1. Mutation survives → you get its ID
2. Add ID to `trap` list to pin it
3. Fix your test until it catches the mutation
4. Remove the ID once fixed

Trapped mutations run **in addition to** normal mutation selection.

### 5. Scoped Mutations via Annotations

The compiler plugin only injects mutations into annotated classes:

```kotlin
@MutationTarget
class Calculator {
    // mutations injected here
}

class Logger {
    // no mutations - not under test
}
```

This limits bytecode bloat and keeps mutations relevant.

## Architecture

### Module Responsibilities

```
┌─────────────────────────────────────────────────────────────────┐
│                         Test Execution                          │
├─────────────────────────────────────────────────────────────────┤
│  mutflow-junit6         │  Orchestrates multiple runs per test  │
│                         │  Sets run number (0 = baseline, 1-N)  │
│                         │  Depends on: core, runtime            │
├─────────────────────────────────────────────────────────────────┤
│  mutflow-runtime        │  MutFlow.underTest { } API            │
│                         │  Session lifecycle management         │
│                         │  Depends on: core                     │
├─────────────────────────────────────────────────────────────────┤
│  mutflow-compiler-plugin│  Transforms @MutationTarget classes   │
│                         │  Assigns mutTestCaseId to underTest   │
│                         │  Injects MutationRegistry.check()     │
│                         │  Depends on: core                     │
├─────────────────────────────────────────────────────────────────┤
│  mutflow-core           │  @MutationTarget annotation           │
│                         │  MutationRegistry (shared state)      │
│                         │  Shared types between all modules     │
│                         │  Depends on: nothing                  │
└─────────────────────────────────────────────────────────────────┘
```

The `mutflow-core` module contains the bridge between compiler-generated code and test
runtime. Both sides depend on it, but not on each other, keeping coupling minimal.

### Test Framework Adapters

The JUnit modules (`mutflow-junit6`, future `mutflow-junit5`) are intentionally thin adapters:
- Core orchestration logic lives in `mutflow-runtime`
- Adapters only handle framework-specific lifecycle hooks and result reporting
- This keeps framework-specific code minimal and enables easy porting

JUnit 5 support is planned but not part of the initial tracer bullet.

### Data Flow

```
1. Compile time:
   ┌──────────────────┐      ┌─────────────────────────────────┐
   │ underTest { }    │ ───► │ underTest(mutTestCaseId="X") { }│
   └──────────────────┘      └─────────────────────────────────┘
   ┌──────────────────┐      ┌─────────────────────────────────┐
   │ x > 0            │ ───► │ when(registry.check("pt1",...)) │
   └──────────────────┘      └─────────────────────────────────┘

2. Discovery run:
   underTest("X") starts session (no activeMutation)
        │
        ▼
   registry.check("pt1", 2) → registers: point 0 has 2 variants, returns null
   registry.check("pt2", 3) → registers: point 1 has 3 variants, returns null
        │
        ▼
   underTest ends → registry returns:
       mutationPointCount = 2
       discoveredPoints = [DiscoveredPoint("pt1", 2), DiscoveredPoint("pt2", 3)]

3. Mutation run:
   underTest("X", activeMutation = ActiveMutation(1, 0)) starts session
        │
        ▼
   registry.check("pt1", ...) → point 0, not active, returns null
   registry.check("pt2", ...) → point 1, active! returns variantIndex 0
        │
        ▼
   code takes mutated path (variant at index 0)
        │
        ▼
   test assertion fails → mutation killed ✓
   test assertion passes → mutation survived, report it
```

## Technical Decisions

### Kotlin K2 Only
- K1 is deprecated; K2 is the future
- Maintaining both is too much overhead for an experimental project
- By the time mutflow matures, K2 will be standard

### Test Build Only
The compiler plugin is applied ONLY to test compilation, never production:
- Gradle plugin applies to `testCompile` tasks only
- Runtime guards detect non-test context and fail fast
- Build verification can scan production artifacts for mutation markers

## Tradeoffs and Limitations

### Advantages
- **Low overhead**: Compile once, not once per mutant
- **Low friction**: No separate tool, runs in normal tests
- **Reproducible**: Seed-based determinism, trapped mutations for debugging
- **Pragmatic**: "Some mutation testing > none" philosophy

### Limitations
- **Not exhaustive per session**: Each session tests a small fixed number of mutations (3-8), not all. Coverage grows over many builds.
- **Bytecode bloat**: Injected branches increase class size (64KB method limit is a risk)
- **Coverage interference**: Extra branches affect coverage reports (may need separate non-mutated build)
- **Debugging complexity**: Stack traces through mutated code can be confusing
- **Equivalent mutants**: Some mutations produce identical behavior (noise)
- **Shared state**: Sequential mutation runs may need state invalidation (future: user-provided hooks)

## Prior Art

- **[Metamutator (SpoonLabs)](https://github.com/SpoonLabs/metamutator)** — Java implementation of mutant schemata. Same core technique. Project appears inactive. Requires separate CLI tool.
- **[Pitest](https://pitest.org/)** — Industry standard JVM mutation testing. Traditional approach (compile per mutant). Thorough but slow.
- **[Arcmutate](https://www.arcmutate.com/)** — Pitest plugin for Kotlin bytecode understanding.
- **[Mutant-Kraken](https://conf.researchr.org/details/icst-2024/mutation-2024-papers/2/Mutant-Kraken-A-Mutation-Testing-Tool-for-Kotlin)** — Kotlin mutation testing via AST manipulation. Traditional approach.
- **[Untch et al. (1993)](https://dl.acm.org/doi/10.1145/154183.154265)** — Original academic paper on mutant schemata technique.

### What mutflow adds
- Kotlin-native K2 compiler plugin (not Java tooling adapted for Kotlin)
- BDD-style `MutFlow.underTest` API for explicit test scoping
- One mutation per run for precise feedback
- Trap mechanism to pin surviving mutants while fixing test gaps
- IR-hash based mutation point identification (stable across refactoring)

## Implementation Phases

### Phase 1: Tracer Bullet
End-to-end proof that the architecture works. Thin slice through all layers:
- K2 compiler plugin that transforms a single mutation type (e.g., `>` to `>=`) in `@MutationTarget` classes
- `MutFlow.underTest { }` runtime API that orchestrates the session
- Discovery run counts mutation points, mutation runs activate one mutation each (specified by pointIndex + variantIndex)
- JUnit 6 extension for session orchestration (re-runs test with lifecycle)
- Surviving mutant prints mutation ID and fails the test

Not useful as a tool yet — the goal is to validate the full loop works before investing in breadth.

#### Phase 1 Progress

**Completed:**
- `mutflow-core`: `MutationRegistry` with `check()`, `startSession()`, `endSession()` API
- `mutflow-core`: Supporting types (`ActiveMutation`, `DiscoveredPoint`, `SessionResult`)
- `mutflow-compiler-plugin`: K2 compiler plugin that transforms `>` comparisons
- `mutflow-compiler-plugin`: Transformation produces `when(MutationRegistry.check(...))` branches
- `mutflow-runtime`: `MutFlow.underTest { }` API with explicit mutation control
- `mutflow-test-sample`: Integration tests proving full workflow with `MutFlow.underTest`

**Runtime API (manual mode):**
```kotlin
// Discovery run: find mutation points
val discovery = MutFlow.underTest(testId = "myTest") {
    calculator.isPositive(5)
}
// discovery.discovered contains the mutation points found

// Mutation run: activate a specific mutation
val result = MutFlow.underTest(
    testId = "myTest",
    activeMutation = ActiveMutation(pointIndex = 0, variantIndex = 1)
) {
    calculator.isPositive(5)
}
// result.activeMutation confirms which mutation was active
```

**Transformation example:**
```kotlin
// Before (in @MutationTarget class)
fun isPositive(x: Int) = x > 0

// After compiler plugin
fun isPositive(x: Int) = when (MutationRegistry.check("sample.Calculator_0", 3)) {
    0 -> x >= 0   // variant: greater-or-equal
    1 -> x < 0    // variant: less-than
    2 -> x == 0   // variant: equals
    else -> x > 0 // original
}
```

**Remaining for Phase 1:**
- `mutflow-junit6`: JUnit 6 extension for automatic multi-run orchestration (including mutation selection logic)
- Surviving mutant detection and reporting
- Compiler plugin: auto-generate `testId` from call site (currently manual)

### Phase 2: Core Features
- Multiple mutation types (arithmetic, boolean, null checks, etc.)
- IR-hash based mutation point IDs
- Trap mechanism for pinning survivors during debugging
- Gradle plugin for easy setup

### Phase 3: Polish
- More mutation operators
- Configuration options (run count, etc.)
- Documentation
- State invalidation hooks
- Mutation suppression annotations

## Open Questions

- How to handle IR hash stability across Kotlin compiler versions?
- Best approach for counting mutations inside loops/recursion? (per-invocation vs per-source-location)
- How to present surviving mutants clearly in test output? (IDE integration?)
