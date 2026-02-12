<p align="center">
  <img src="logo.svg" alt="mutflow" width="400">
</p>

<p align="center">
  A Kotlin compiler plugin for lightweight, progressive mutation testing.
</p>

<p align="center">
  <a href="https://central.sonatype.com/artifact/io.github.anschnapp.mutflow/mutflow-gradle-plugin"><img src="https://img.shields.io/maven-central/v/io.github.anschnapp.mutflow/mutflow-gradle-plugin" alt="Maven Central"></a>
</p>

> **Early Stage Software:** mutflow is in an experimental phase. While the core architecture is designed to keep production builds clean (mutations only exist in a separate test compilation), it has not yet been broadly tested in the wild. If you're working on a high-stakes production system, please evaluate carefully before adopting. Bug reports and feedback are very welcome!

## What is this?

mutflow brings mutation testing to Kotlin with minimal overhead. Instead of the traditional approach (compile and run each mutant separately), mutflow:

1. **Compiles once** — All mutation variants are injected at compile time as conditional branches
2. **Discovers dynamically** — Mutation points are found during baseline test execution
3. **Selects intelligently** — Prioritizes under-tested mutations based on touch counts
4. **Progresses over builds** — Different mutations are tested across builds, like continuous fuzzing

## Why?

Traditional mutation testing is powerful but expensive. Most teams skip it entirely.

mutflow trades exhaustiveness for practicality: low setup cost, no separate tooling, runs in your normal test suite. Some mutation testing is better than none.

## What mutflow tests (and what it doesn't)

mutflow closes the gap between code coverage and assertion quality. Coverage tells you code was executed; mutflow verifies your assertions actually catch behavioral changes.

**Important:** mutflow only tests code reached within `MutFlow.underTest { }` blocks. Unreached code produces no mutations - mutflow won't warn you. This is intentional (keeps scope focused) but means you should use coverage tools separately to ensure code is exercised at all.

## Status

Core mutation testing features are working:
- **Relational comparisons** (`>`, `<`, `>=`, `<=`) with 2 variants each (boundary + flip)
- **Equality swaps** (`==` ↔ `!=`) — detects missing equality/inequality assertions
- **Boolean logic swaps** (`&&` ↔ `||`) — detects missing short-circuit logic assertions
- **Arithmetic operators** (`+`, `-`, `*`, `/`, `%`) — simple swaps to detect wrong operations
- **Constant boundary mutations** — numeric constants in comparisons are mutated by +1/-1
- **Boolean return mutations** — boolean return values replaced with `true`/`false`
- **Nullable return mutations** — nullable return values replaced with `null`
- **Recursive operator nesting** — multiple mutation types apply to the same expression

The extensible mutation operator architecture (`MutationOperator` for calls, `ReturnMutationOperator` for returns) makes it easy to add new mutation types. `@SuppressMutations` annotation allows skipping mutations on specific code. Not yet production-ready, but ready for experimentation.

## Setup

Add the mutflow Gradle plugin to your `build.gradle.kts`:

```kotlin
plugins {
    kotlin("jvm") version "2.3.0"
    id("io.github.anschnapp.mutflow") version "<latest-version>"
}
```

Once available, the plugin automatically:
- Adds `mutflow-core` to your implementation dependencies (for `@MutationTarget` annotation)
- Adds `mutflow-junit6` to your test dependencies (for `@MutFlowTest` annotation)
- Configures the compiler plugin for mutation injection

**Important:** The plugin uses a dual-compilation approach — your production JAR remains clean (no mutation code), while tests run against mutated code.

## Quick Start

```kotlin
// Mark code under test
@MutationTarget
class Calculator {
    fun isPositive(x: Int) = x > 0
}

// Test with mutation testing - simple!
@MutFlowTest
class CalculatorTest {
    private val calculator = Calculator()

    @Test
    fun `isPositive returns true for positive numbers`() {
        val result = MutFlow.underTest {
            calculator.isPositive(5)
        }
        assertTrue(result)
    }

    @Test
    fun `isPositive returns false for negative numbers`() {
        val result = MutFlow.underTest {
            calculator.isPositive(-5)
        }
        assertFalse(result)
    }
}
```

That's it! The `@MutFlowTest` annotation handles everything:
- **Baseline run**: Discovers mutation points, all tests pass normally
- **Mutation runs**: Each mutation is activated across all tests; if any test catches it (assertion fails), the mutation is killed and tests appear green
- **Survivor detection**: If no test catches a mutation, `MutantSurvivedException` is thrown and the build fails

### Example Output

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

**How to read this output:**
- **All tests PASSED** — This is the expected result! During mutation runs, when a test's assertion fails (catching the mutation), the exception is swallowed and the test appears green.
- **Summary shows killed/survived** — After all runs complete, the summary shows which mutations were killed (good) vs survived (gap in coverage).
- **Build fails with `MutantSurvivedException`** — Only if a mutation survives (no test caught it). This indicates missing test coverage.

## Configuration

```kotlin
@MutFlowTest(
    maxRuns = 5,                           // Baseline + up to 4 mutation runs
    selection = Selection.MostLikelyStable, // Prioritize under-tested mutations
    shuffle = Shuffle.PerChange             // Stable across builds until code changes
)
class CalculatorTest { ... }
```

### Selection Strategies

| Selection | Behavior |
|-----------|----------|
| `PureRandom` | Uniform random selection among untested mutations |
| `MostLikelyRandom` | Random but weighted toward mutations touched by fewer tests |
| `MostLikelyStable` | Deterministically pick the mutation touched by fewest tests |

### Shuffle Modes

| Shuffle | Behavior |
|---------|----------|
| `PerRun` | New random seed each JVM/CI run — exploratory |
| `PerChange` | Seed based on discovered points — stable until code changes |

**Typical workflow:**
- Development: `MostLikelyRandom` + `PerRun` to explore high-risk mutations
- Merge requests: `MostLikelyStable` + `PerChange` for reproducible results

### Target Filtering (Integration Tests)

When an integration test exercises multiple `@MutationTarget` classes but you only want to test mutations in specific ones:

```kotlin
// Only test mutations from Calculator — ignore other @MutationTarget classes reached by underTest
@MutFlowTest(includeTargets = [Calculator::class])
class CalculatorIntegrationTest { ... }

// Test everything except infrastructure classes
@MutFlowTest(excludeTargets = [AuditLogger::class, MetricsService::class])
class PaymentServiceTest { ... }
```

- `includeTargets`: Whitelist — only these classes produce active mutations
- `excludeTargets`: Blacklist — these classes are skipped
- Both can be combined: include narrows first, exclude removes from that set
- Empty (default) = no filtering, all discovered mutations are candidates

All mutation points are still discovered during baseline (for accurate touch counts), but only filtered mutations are selected, counted in the summary, and considered for exhaustion.

### Traps (Pinning Mutations)

When a mutation survives, you can **trap** it to run it first every time while you fix the test gap:

```kotlin
@MutFlowTest(
    traps = ["(Calculator.kt:8) > → >="]  // Copy from survivor output
)
class CalculatorTest { ... }
```

**How traps work:**
1. Mutation survives → build fails with display name like `(Calculator.kt:8) > → >=`
2. Copy the display name into `traps` array
3. Trapped mutation now runs first every time (before random selection)
4. Fix your test until it catches the mutation
5. Remove the trap

Traps run in the order provided, regardless of selection strategy. After all traps are exhausted, normal selection continues.

**Invalid trap handling:** If a trap doesn't match any discovered mutation (e.g., code moved), a warning is printed with available mutations:
```
[mutflow] WARNING: Trap not found: (Calculator.kt:999) > → >=
[mutflow]   Available mutations:
[mutflow]     (Calculator.kt:8) 0 → -1
[mutflow]     (Calculator.kt:8) > → >=
```

### Suppressing Mutations

mutflow provides three levels of suppression granularity:

**Class level** — skip all mutations in a class:
```kotlin
@MutationTarget
@SuppressMutations
class LegacyCalculator { ... }
```

**Function level** — skip mutations in a specific function:
```kotlin
@MutationTarget
class Calculator {
    @SuppressMutations
    fun debugLog(x: Int): Boolean = x > 100
}
```

**Line level** — skip mutations on a single line using comments:
```kotlin
@MutationTarget
class Calculator {
    fun process(x: Int): Boolean {
        val threshold = x > 100 // mutflow:ignore this is just a heuristic
        // mutflow:falsePositive equivalent mutant, boundary doesn't matter
        val inRange = x >= 0
        return threshold && inRange
    }
}
```

Two comment keywords are supported — same technical effect, different intent:
- `mutflow:ignore` — the code is not worth testing (logging, debug utilities, heuristics)
- `mutflow:falsePositive` — the mutation is an equivalent mutant or not meaningful to test

Free-form text after the keyword documents the reason for reviewers.

**Inline comment** suppresses mutations on the same line. **Standalone comment** (on its own line) suppresses mutations on the next line. Comments have zero production overhead — they are stripped by the compiler and nothing appears in the production bytecode.

## Current Features

- **JUnit 6 integration** — `@MutFlowTest` annotation for automatic multi-run orchestration
- **K2 compiler plugin** — Transforms `@MutationTarget` classes with multiple mutation types
- **All relational comparisons** — `>`, `<`, `>=`, `<=` with 2 variants each (boundary + flip)
- **Equality swaps** — `==` ↔ `!=` (1 variant each)
- **Boolean logic swaps** — `&&` ↔ `||` (1 variant each)
- **Arithmetic operators** — `+` ↔ `-`, `*` ↔ `/`, `%` → `/` (with safe division to avoid div-by-zero)
- **Constant boundary mutations** — Numeric constants in comparisons mutated by +1/-1 (e.g., `0 → 1`, `0 → -1`)
- **Boolean return mutations** — Boolean return values replaced with `true`/`false` (explicit returns only)
- **Nullable return mutations** — Nullable return values replaced with `null` (explicit returns only)
- **Recursive operator nesting** — Multiple mutation types combine on the same expression
- **Type-agnostic** — Works with `Int`, `Long`, `Double`, `Float`, `Short`, `Byte`, `Char`
- **`@SuppressMutations`** — Skip mutations on specific classes or functions
- **Comment-based line suppression** — `// mutflow:ignore` and `// mutflow:falsePositive` to skip individual lines (zero production overhead)
- **Extensible architecture** — `MutationOperator` (for calls), `ReturnMutationOperator` (for returns), and `WhenMutationOperator` (for boolean logic) interfaces for adding new mutation types
- **Session-based architecture** — Clean lifecycle, no leaked global state
- **Parameterless API** — Simple `MutFlow.underTest { }` when using JUnit extension
- **Selection strategies** — `PureRandom`, `MostLikelyRandom`, `MostLikelyStable`
- **Shuffle modes** — `PerRun` (exploratory) and `PerChange` (stable)
- **Touch count tracking** — Prioritizes under-tested mutation points
- **Mutation result tracking** — Killed mutations show as PASSED (exception swallowed), survivors fail the build
- **Summary reporting** — Visual summary at end of test class showing killed/survived mutations
- **Readable mutation names** — Source location and operator descriptions (e.g., `(Calculator.kt:7) > → >=`, `(Calculator.kt:7) 0 → 1`). When the same operator appears multiple times on one line, an occurrence suffix disambiguates (e.g., `> → >= #2`)
- **IDE-clickable links** — Source locations in IntelliJ-compatible format for quick navigation
- **Partial run detection** — Automatically skips mutation testing when running single tests from IDE (prevents false positives)
- **Trap mechanism** — Pin specific mutations to run first while debugging test gaps
- **Target filtering** — `includeTargets`/`excludeTargets` to scope mutations by class in integration tests
- **Parallel test safe** — Mutation test classes can run alongside other tests in parallel; `underTest {}` blocks serialize automatically via a synchronized lock, without using `ThreadLocal` (keeping the door open for coroutine/reactive support)

## Planned Features

- Additional mutation operators (negation removal)

## How Equality Swap Mutations Work

Equality swap mutations verify that your tests distinguish between `==` and `!=` conditions.

**Example:** For `fun isZero(x: Int) = x == 0`:

| Mutation | Code becomes | Caught by test |
|----------|--------------|----------------|
| `== → !=` | `x != 0` | `isZero(0)` should be true |

And for `fun isNotZero(x: Int) = x != 0`:

| Mutation | Code becomes | Caught by test |
|----------|--------------|----------------|
| `!= → ==` | `x == 0` | `isNotZero(5)` should be true |

If your tests only exercise values where `==` and `!=` produce different results for obvious inputs, the mutations will be killed. But if tests use ambiguous inputs or don't assert the return value, survivors reveal the gap.

## How Constant Boundary Mutations Work

The constant boundary mutation detects poorly tested boundaries that operator mutations alone cannot find.

**Example:** For `fun isPositive(x: Int) = x > 0`:

| Mutation | Code becomes | Caught by test |
|----------|--------------|----------------|
| `> → >=` | `x >= 0` | `isPositive(0)` should be false |
| `> → <` | `x < 0` | `isPositive(1)` should be true |
| `0 → 1` | `x > 1` | `isPositive(1)` should be true |
| `0 → -1` | `x > -1` | `isPositive(0)` should be false |

If your tests only use values far from the boundary (e.g., `isPositive(5)` and `isPositive(-5)`), the constant mutations will survive — revealing the gap in boundary testing.

## How Boolean Return Mutations Work

Boolean return mutations verify that your tests actually check return values, not just that the code runs without error.

**Example:** For a function with explicit returns:
```kotlin
fun isInRange(x: Int, min: Int, max: Int): Boolean {
    if (x < min) return false
    if (x > max) return false
    return true
}
```

| Mutation | Original | Becomes | Caught when |
|----------|----------|---------|-------------|
| `return false → true` | `return false` | `return true` | Test asserts false for out-of-range |
| `return false → false` | `return false` | `return false` | (no change - original behavior) |
| `return true → true` | `return true` | `return true` | (no change - original behavior) |
| `return true → false` | `return true` | `return false` | Test asserts true for in-range |

**Note:** Boolean return mutations only apply to explicit `return` statements in block-bodied functions. Expression-bodied functions (`fun foo() = expr`) are mutated via their expression operators instead.

## How Nullable Return Mutations Work

Nullable return mutations verify that your tests check actual return values, not just non-null.

**Example:** For a function that returns nullable:
```kotlin
fun findUser(id: Int): User? {
    val user = database.query(id)
    if (user != null) {
        return user
    }
    return null
}
```

| Mutation | Original | Becomes | Caught when |
|----------|----------|---------|-------------|
| `return user → null` | `return user` | `return null` | Test asserts actual user properties |

**Common weak test patterns this catches:**
```kotlin
// WEAK: Only checks non-null, not the actual value
val user = findUser(1)
assertNotNull(user)  // Would still pass with null mutation? NO - but doesn't verify content

// WEAK: Uses the value but doesn't verify it
val user = findUser(1)
println(user?.name)  // No assertion at all!

// STRONG: Verifies actual content
val user = findUser(1)
assertEquals("Alice", user?.name)  // Catches null mutation
```

**Note:** Nullable return mutations only apply to explicit `return` statements in block-bodied functions that return nullable types. The mutation replaces the return value with `null`.

## Manual API

For testing or custom integrations, you can use the explicit API:

```kotlin
// Baseline
MutFlow.underTest(run = 0, Selection.MostLikelyStable, Shuffle.PerChange) {
    calculator.isPositive(5)
}

// Mutation runs
MutFlow.underTest(run = 1, Selection.MostLikelyStable, Shuffle.PerChange) {
    calculator.isPositive(5)
}
```

## Documentation

See [DESIGN.md](DESIGN.md) for the full design document, architecture details, and implementation plan.

## Acknowledgments

This project was developed with the assistance of AI coding assistants (Claude).
