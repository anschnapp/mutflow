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

**Test code:**
```kotlin
@MutFlowTest
class CalculatorTest {
    @Test
    fun testIsPositive() {
        val result = MutFlow.underTest {  // parameterless with JUnit extension
            isPositive(5)
        }
        assertTrue(result)
    }
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
        // Compiler injects nested when expressions for multiple mutation types
        return when (MutationRegistry.check(
            pointId = "sample.Calculator_0",
            variantCount = 2,
            sourceLocation = "Calculator.kt:4",
            originalOperator = ">",
            variantOperators = ">=,<"
        )) {
            0 -> x >= 0  // operator mutation: include equality
            1 -> x < 0   // operator mutation: direction flip
            else -> when (MutationRegistry.check(
                pointId = "sample.Calculator_1",
                variantCount = 2,
                sourceLocation = "Calculator.kt:4",
                originalOperator = "0",
                variantOperators = "1,-1"
            )) {
                0 -> x > 1   // constant mutation: increment
                1 -> x > -1  // constant mutation: decrement
                else -> x > 0  // original
            }
        }
    }
}
```

This nested structure is generated recursively by the compiler plugin. Each matching `MutationOperator` wraps the expression, with the `else` branch feeding into the next operator. Since only one mutation is active at runtime, there's no complexity — the active mutation's branch executes, all others fall through to original.

### Runtime Discovery Model

Mutation points are discovered **dynamically at runtime**, not statically at class load:

1. **Discovery run**: Code executes normally (no `activeMutation`). Each `MutationRegistry.check()` call registers "I exist with these variants" along with display metadata (source location, operator descriptions), and returns `null` (use original). After execution, the registry returns: *"discovered 5 mutation points with their variant counts"*.

**Note:** Point IDs use the format `ClassName_N` (e.g., `sample.Calculator_0`), but display names show source location and operator (e.g., `(Calculator.kt:7) > → >=`). Phase 2 will switch to IR-hash based IDs for stability across refactoring.

2. **Mutation runs**: The caller specifies which mutation to activate via `ActiveMutation(pointId, variantIndex)`. When that point calls `check()`, it returns the active variant index instead of `null`.

This dynamic discovery matters because:
- Different `underTest` blocks exercise different code paths
- Only mutations actually reached by the test are counted
- Same class called from different tests may hit different mutation points

## Key Features

### 1. Explicit Test Scoping with `MutFlow.underTest`

Tests explicitly mark the action under test using BDD-style structure:

```kotlin
@MutFlowTest
class CalculatorTest {
    @Test
    fun testIsPositive() {
        // given
        val x = 5

        // when
        val result = MutFlow.underTest {  // parameterless when using @MutFlowTest
            isPositive(x)
        }

        // then
        assertTrue(result)
    }
}
```

The `MutFlow.underTest` block:
- Wraps only the action under test (the "when" in given/when/then)
- Returns the result for assertions outside the block
- Assertions stay outside — they should fail when mutations change behavior
- When using `@MutFlowTest`, the JUnit extension manages session lifecycle internally

### 2. Global Baseline and Run Model

Mutation testing operates at the **test class level** with a global registry:

1. **Run 0 (baseline)**: ALL test cases in the class execute first, discovering mutation points
2. **Run 1+**: ALL test cases execute with the **same mutation** active

```kotlin
// With @MutFlowTest, the JUnit extension orchestrates all runs automatically:
// - Run 0 (baseline): All tests execute, mutation points discovered
// - Run 1+: All tests execute with same mutation active

@MutFlowTest
class CalculatorTest {
    @Test fun testIsPositive() {
        val result = MutFlow.underTest { calculator.isPositive(5) }
        assertTrue(result)
    }

    @Test fun testIsNegative() {
        val result = MutFlow.underTest { calculator.isPositive(-5) }
        assertFalse(result)
    }
}

// If ANY test fails during a mutation run, the mutation is killed
```

**Key principles:**
- **Same mutation for all tests**: A run activates one mutation across the entire test suite
- **Global discovery**: Mutation points from all tests are merged into a single registry
- **Touch counting**: During baseline, we count how many tests touch each mutation point
- **Run limit**: Tests run up to N times (configured), or until all mutations are exhausted

This means:
- We can determine if a mutation **survives the entire test suite**
- Mutations touched by fewer tests are identified as higher risk
- Precise feedback: when a mutant survives, you know exactly which one

### 3. Selection and Shuffle Modes

Mutation selection is controlled by two orthogonal parameters:

```kotlin
enum class Selection {
    PureRandom,       // Uniform random selection
    MostLikelyRandom, // Weighted random favoring least-touched points
    MostLikelyStable  // Deterministic: always pick least-touched point
}

enum class Shuffle {
    PerRun,    // Different seed each CI build/JVM run
    PerChange  // Same seed until discovered points change
}
```

**Selection strategies** (which mutation to pick):

| Selection | Behavior |
|-----------|----------|
| `PureRandom` | Uniform random selection among untested mutations |
| `MostLikelyRandom` | Random but weighted toward mutations touched by fewer tests |
| `MostLikelyStable` | Deterministically pick the mutation touched by fewest tests |

The "touch count" is calculated during baseline (run 0): each time a test executes a mutation point, that point's touch count increments. Mutations touched by fewer tests are considered higher risk and prioritized by `MostLikely*` strategies.

**Shuffle modes** (when to change the seed):

| Shuffle | Behavior |
|---------|----------|
| `PerRun` | New random seed each JVM/CI run — exploratory |
| `PerChange` | Seed based on `hash(discoveredPoints)` — stable until code changes |

**Typical workflow:**
1. During development: use `MostLikelyRandom` + `PerRun` to explore high-risk mutations
2. For merge requests: use `MostLikelyStable` + `PerChange` for reproducible results
3. Over time: cover all mutations across many builds

### 4. Global Mutation Registry

The mutation registry is a **global in-memory state** shared across all tests:

```kotlin
GlobalRegistry {
    // From baseline (run 0): which points exist and their variant counts
    discoveredPoints: Map<PointId, VariantCount>

    // From baseline (run 0): how many tests touched each point
    touchCounts: Map<PointId, Int>

    // Updated each mutation run: which mutations have been tested
    testedMutations: Set<Mutation>  // Mutation = (pointId, variantIndex)
}
```

**Lifecycle:**
1. **Run 0 (all tests)**: Baseline discovery — mutation points merged globally, touch counts accumulated
2. **Run 1+**: For each run:
   - Select a mutation point (using Selection strategy + touch counts)
   - Pick a variant for that point (excluding already-tested variants)
   - Add to `testedMutations`, activate, execute lambda
3. **Exhaustion**: If no untested mutations remain → throw `MutationsExhaustedException`

This ensures:
- No mutation is tested twice within an execution
- Touch counts guide selection toward under-tested mutation points
- Natural termination when all mutations are covered
- Run count (configured in JUnit) is the normal limit; exception is early-exit for small codebases

### 5. Trapping Surviving Mutants (Planned)

When a mutant survives, its mutation ID is printed with source location:
```
MUTANT SURVIVED: (Calculator.kt:7) > → <
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
        trap = listOf("sample.Calculator_0:1")
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

### 6. Scoped Mutations via Annotations

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

Additionally, you can suppress mutations on specific functions using `@SuppressMutations`:

```kotlin
@MutationTarget
class Calculator {
    fun isPositive(x: Int) = x > 0  // mutations injected

    @SuppressMutations
    fun debugLog(x: Int): Boolean {
        // no mutations here - logging code doesn't need mutation testing
        return x > 100
    }
}
```

The `@SuppressMutations` annotation can be applied to:
- **Classes**: Skip all mutations in the entire class
- **Functions**: Skip mutations in specific functions only

### 7. Partial Run Detection

When running a single test method from an IDE (e.g., IntelliJ's "Run Test" on one method), mutation testing is automatically skipped. This prevents false positives — mutations that would be killed by *other* tests in the class would incorrectly appear as survivors.

**How it works:**
1. At session creation, the extension counts `@Test` methods in the class
2. During baseline, each executed test is tracked
3. After baseline, if `executedTests < expectedTests`, mutation runs are skipped

**Example output when running a single test:**
```
[mutflow] Starting baseline run (discovery)
[mutflow] Discovered mutation point: (Calculator.kt:7) > with 2 variants
[mutflow] Partial test run detected (1/3 tests) - skipping mutation testing
```

The baseline still runs normally (tests execute, mutations are discovered), but no mutation runs occur. This ensures you get your test results quickly without misleading mutation feedback.

**Rationale:** Mutation testing evaluates the *entire test suite's* ability to catch mutations. Running it with a subset produces meaningless results — better to skip and provide a clear message.

## Architecture

### Module Responsibilities

```
┌─────────────────────────────────────────────────────────────────┐
│                         Test Execution                          │
├─────────────────────────────────────────────────────────────────┤
│  mutflow-junit6           │  @MutFlowTest meta-annotation       │
│                           │  @ClassTemplate + @ExtendWith       │
│                           │  MutFlowExtension: thin adapter     │
│                           │    that calls MutFlow session mgmt  │
│                           │  Depends on: mutflow-runtime        │
├─────────────────────────────────────────────────────────────────┤
│  mutflow-runtime          │  MutFlowSession: per-class state    │
│                           │  MutFlow: session management +      │
│                           │    underTest() API (parameterless   │
│                           │    and explicit versions)           │
│                           │  Selection: PureRandom, MostLikely* │
│                           │  Shuffle: PerRun, PerChange         │
│                           │  Depends on: mutflow-core           │
├─────────────────────────────────────────────────────────────────┤
│  mutflow-compiler-plugin  │  Transforms @MutationTarget classes │
│                           │  Injects MutationRegistry.check()   │
│                           │  MutationOperator: extension iface  │
│                           │  RelationalComparisonOperator:      │
│                           │    handles >, <, >=, <= operators   │
│                           │  ConstantBoundaryOperator:          │
│                           │    mutates constants by +1/-1       │
│                           │  Depends on: mutflow-core           │
├─────────────────────────────────────────────────────────────────┤
│  mutflow-core             │  @MutationTarget annotation         │
│                           │  @SuppressMutations annotation      │
│                           │  MutationRegistry (per-underTest    │
│                           │    session for discovery/activation)│
│                           │  Shared types between all modules   │
│                           │  Depends on: nothing                │
└─────────────────────────────────────────────────────────────────┘
```

The `mutflow-core` module contains the bridge between compiler-generated code and test
runtime. Both sides depend on it, but not on each other, keeping coupling minimal.

### Session-Based Architecture

State is scoped to sessions rather than being globally mutable:

```kotlin
// JUnit extension creates session at class start
val sessionId = MutFlow.createSession(selection, shuffle, maxRuns)

// Each class template invocation:
val mutation = MutFlow.selectMutationForRun(sessionId, run)  // null for baseline
MutFlow.startRun(sessionId, run, mutation)
// ... all tests execute ...
MutFlow.endRun(sessionId)

// JUnit extension closes session when class finishes
MutFlow.closeSession(sessionId)
```

Benefits:
- **Clean lifecycle**: create → runs → close
- **State isolation**: Each test class has its own session
- **No leaked state**: Explicit cleanup
- **Single source of truth**: All state in MutFlow object

### Test Framework Adapters

The JUnit extension (`mutflow-junit6`) is intentionally a thin adapter:
- Uses JUnit 6's `@ClassTemplate` mechanism to run the class multiple times
- `MutFlowExtension` implements `ClassTemplateInvocationContextProvider`
- All orchestration logic lives in `mutflow-runtime` (session management, mutation selection)
- Extension only handles: session creation/cleanup, run start/end calls, display names

This keeps framework-specific code minimal (~100 lines) and enables easy porting to other frameworks.

### Data Flow

```
1. Compile time:
   ┌──────────────────┐      ┌───────────────────────────────────────────────────┐
   │ x > 0            │ ───► │ when(registry.check(pointId, 2, "Calc.kt:7", ">", │
   └──────────────────┘      │                     ">=,<"))                      │
                             └───────────────────────────────────────────────────┘

2. Baseline (run=0) — ALL tests run first:

   Test A: underTest(run=0, selection, shuffle) { calculator.isPositive(5) }
        │
        ▼
   registry.check("sample.Calculator_0", 2) → registers point, touchCount++, returns null
   registry.check("sample.Calculator_1", 2) → registers point, touchCount++, returns null
        │
        ▼
   Returns: block result (T)

   Test B: underTest(run=0, selection, shuffle) { calculator.validate(-1) }
        │
        ▼
   registry.check("sample.Calculator_0", 2) → already known, touchCount++, returns null
   registry.check("sample.Validator_0", 2) → registers point, touchCount++, returns null
        │
        ▼
   Returns: block result (T)

   After all run=0 complete:
   GlobalRegistry {
       discoveredPoints: {sample.Calculator_0: 2, sample.Calculator_1: 2, sample.Validator_0: 2}
       touchCounts: {sample.Calculator_0: 2, sample.Calculator_1: 1, sample.Validator_0: 1}
       testedMutations: {}
   }

3. Mutation runs (run=1, 2, ...) — ALL tests run with SAME mutation:

   First underTest(run=1, selection=MostLikelyRandom, shuffle=PerChange):
        │
        ▼
   Select point: sample.Calculator_1 (lowest touch count, weighted random)
   Select variant: 0 (from range 0..1)
   Add (sample.Calculator_1, 0) to testedMutations
   Activate mutation (sample.Calculator_1, 0)
        │
        ▼
   All tests execute with (sample.Calculator_1, 0) active:
   registry.check("sample.Calculator_0", ...) → not active, returns null
   registry.check("sample.Calculator_1", ...) → active! returns 0
   registry.check("sample.Validator_0", ...) → not active, returns null
        │
        ▼
   If ANY test fails → mutation killed
   If ALL tests pass → mutation survived, report it

4. Exhaustion:
   underTest(run=N, ...) where all mutations tested
        │
        ▼
   No untested mutations remain
        │
        ▼
   Throws MutationsExhaustedException → JUnit stops iteration
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

### Phase 1: Tracer Bullet ✓ COMPLETE

End-to-end proof that the architecture works. Thin slice through all layers.

#### What Was Built

**mutflow-core:**
- `MutationRegistry` with `check()`, `startSession()`, `endSession()` API
- Supporting types (`ActiveMutation`, `DiscoveredPoint`, `SessionResult`)
- `@MutationTarget` annotation for scoping mutations

**mutflow-compiler-plugin:**
- K2 compiler plugin with extensible mutation operator mechanism
- Two operator interfaces for different IR node types:
  - `MutationOperator` — for `IrCall` nodes (comparison operators, etc.)
  - `ReturnMutationOperator` — for `IrReturn` nodes (return statement mutations)
- `RelationalComparisonOperator` handles all comparison operators (`>`, `<`, `>=`, `<=`)
  - Each operator produces 2 variants: boundary mutation + direction flip
- `ConstantBoundaryOperator` mutates numeric constants in comparisons
  - Produces 2 variants: +1 and -1 of the original constant
  - Detects poorly tested boundaries that operator mutations miss
- `BooleanReturnOperator` mutates boolean return values
  - Produces 2 variants: `true` and `false`
  - Only matches explicit return statements (block-bodied functions)
  - Skips synthetic returns from expression-bodied functions (detected by zero-width source span)
- Recursive operator application: multiple operators can match the same expression
- Type-agnostic: works with `Int`, `Long`, `Double`, `Float`, etc.
- Respects `@SuppressMutations` annotation on classes and functions

**mutflow-runtime:**
- `MutFlowSession`: Per-class state (discovered points, touch counts, tested mutations)
- `MutFlow`: Session management + `underTest()` API (parameterless and explicit versions)
- Selection strategies: `PureRandom`, `MostLikelyRandom`, `MostLikelyStable`
- Shuffle modes: `PerRun`, `PerChange`
- Touch count tracking during baseline
- `MutationsExhaustedException` when all mutations tested

**mutflow-junit6:**
- `@MutFlowTest` meta-annotation combining `@ClassTemplate` + `@ExtendWith`
- `MutFlowExtension` implementing `ClassTemplateInvocationContextProvider`
- Session lifecycle management (create, startRun, endRun, close)
- Mutation selection at context creation for accurate display names

**mutflow-test-sample:**
- Integration tests demonstrating both APIs

#### Target API (Achieved)

```kotlin
// Simple: use @MutFlowTest annotation
@MutFlowTest(maxRuns = 4, selection = Selection.MostLikelyStable, shuffle = Shuffle.PerChange)
class CalculatorTest {
    @Test
    fun testIsPositive() {
        val result = MutFlow.underTest {  // parameterless!
            calculator.isPositive(5)
        }
        assertTrue(result)
    }
}
```

**Example output:**
```
CalculatorTest > Baseline > isPositive returns true for positive numbers() PASSED
CalculatorTest > Baseline > isPositive returns true at boundary() PASSED
CalculatorTest > Baseline > isPositive returns false for negative numbers() PASSED
CalculatorTest > Baseline > isPositive returns false for zero() PASSED

CalculatorTest > Mutation: (Calculator.kt:7) > → >= > ... PASSED
CalculatorTest > Mutation: (Calculator.kt:7) > → < > ... PASSED
CalculatorTest > Mutation: (Calculator.kt:7) 0 → 1 > ... PASSED
CalculatorTest > Mutation: (Calculator.kt:7) 0 → -1 > ... PASSED

╔════════════════════════════════════════════════════════════════╗
║                    MUTATION TESTING SUMMARY                    ║
╠════════════════════════════════════════════════════════════════╣
║  Total mutations discovered:   4                              ║
║  Tested this run:              4                              ║
║  ├─ Killed:                    4  ✓                           ║
║  └─ Survived:                  0  ✓                           ║
║  Remaining untested:           0                              ║
╠════════════════════════════════════════════════════════════════╣
║  DETAILS:                                                      ║
║  ✓ (Calculator.kt:7) > → >=                                     ║
║      killed by: isPositive returns false for zero()            ║
║  ✓ (Calculator.kt:7) > → <                                      ║
║      killed by: isPositive returns true at boundary()          ║
║  ✓ (Calculator.kt:7) 0 → 1                                      ║
║      killed by: isPositive returns true at boundary()          ║
║  ✓ (Calculator.kt:7) 0 → -1                                     ║
║      killed by: isPositive returns false for zero()            ║
╚════════════════════════════════════════════════════════════════╝
```

**Key behavior:**
- **Killed mutations**: When a test assertion fails during a mutation run, the exception is **swallowed** and the test appears as PASSED. This is intentional — a failing assertion means the test caught the mutation (good!). The mutation is recorded as "killed" internally.
- **Surviving mutations**: After all tests in a mutation run complete, if NO test caught the mutation (all tests passed naturally), `MutantSurvivedException` is thrown. This fails the build and indicates a gap in test coverage.
- **Summary**: At the end of each test class, a summary shows which mutations were tested and their results.

**Why this design?**
The goal is that **all tests appear green when mutations are properly killed**. Failed assertions during mutation runs are expected and desirable — they prove your tests can detect code changes. Only when tests fail to catch a mutation does the build fail, alerting you to the coverage gap.

**Transformation:**
```kotlin
// Before (in @MutationTarget class)
fun isPositive(x: Int) = x > 0

// After compiler plugin (nested mutations for operator AND constant)
fun isPositive(x: Int) = when (MutationRegistry.check("..._0", 2, "Calculator.kt:7", ">", ">=,<")) {
    0 -> x >= 0   // operator: boundary (include equality)
    1 -> x < 0    // operator: direction flip
    else -> when (MutationRegistry.check("..._1", 2, "Calculator.kt:7", "0", "1,-1")) {
        0 -> x > 1    // constant: increment
        1 -> x > -1   // constant: decrement
        else -> x > 0 // original
    }
}
```

### Phase 2: Core Features
- ✓ **Variant descriptions in display names** — Mutations now show as `(Calculator.kt:7) > → >=` with clickable source locations
- ✓ **Partial run detection** — Automatically skips mutation testing when running single tests from IDE
- ✓ **All relational comparison operators** — `>`, `<`, `>=`, `<=` with 2 variants each (boundary + flip)
- ✓ **Constant boundary mutations** — Numeric constants in comparisons are mutated by +1/-1 (e.g., `0 → 1`, `0 → -1`)
- ✓ **Boolean return mutations** — Boolean return values mutated to `true`/`false` (explicit returns only)
- ✓ **Recursive operator nesting** — Multiple operators can match the same expression, generating nested `when` blocks
- ✓ **Type-agnostic operand handling** — Works with `Int`, `Long`, `Double`, `Float`, `Short`, `Byte`, `Char`
- ✓ **`@SuppressMutations` annotation** — Skip mutations on specific classes or functions
- ✓ **Extensible mutation operator mechanism** — Two interfaces (`MutationOperator` for calls, `ReturnMutationOperator` for returns) make it easy to add new mutation types
- IR-hash based mutation point IDs (currently uses class name + counter)
- Trap mechanism for pinning survivors during debugging
- Gradle plugin for easy setup
- Smarter likelihood calculations (see below)

#### Smarter Likelihood Calculations

The current touch count metric is a simple proxy for "how well tested is this mutation point". In Phase 2, we could enhance the likelihood calculation by analyzing observed runtime values during baseline.

**Simple cases** (e.g., `x > 5` where one side is a literal):
- Track observed values of `x` during baseline
- If tests only use values far from the boundary (e.g., `[10, 20, 100]` but never `[4, 5, 6]`), specific variants become higher likelihood:
  - `x >= 5` → HIGH likelihood (boundary at 5 never tested)
  - `x == 5` → HIGH likelihood (5 never observed)
  - `x < 5` → LOWER likelihood (tests with x=10 would fail)

**Complex cases** (nested expressions, non-literal boundaries):
- `(x + y) > threshold` — harder to analyze, would need to track computed values
- These cases may remain suboptimal, falling back to basic touch count

The key insight: instead of a separate "boundary analysis" feature with its own warnings/control flow, we integrate this into the existing likelihood score. Boundary-untested variants naturally bubble to the top of selection priority, get tested first, and produce standard "mutation survived" feedback if they survive.

This keeps the system focused on mutation testing rather than becoming a general boundary testing tool.

### Phase 3: Polish
- More mutation operators (arithmetic, boolean, null checks)
- Configuration options (run count, etc.)
- Documentation
- State invalidation hooks
- Equality operators (`==`, `!=`)

## Open Questions

- How to handle IR hash stability across Kotlin compiler versions?
- Best approach for counting mutations inside loops/recursion? (per-invocation vs per-source-location)
- How to present surviving mutants clearly in test output? (IDE integration?)
