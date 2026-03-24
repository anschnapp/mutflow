<p align="center">
  <img src="https://github.com/anschnapp/mutflow/blob/master/logo.png" alt="mutflow">
</p>

<p align="center">
  Mutation testing inside your Kotlin tests. Compile once, catch gaps.
</p>

<p align="center">
  <a href="https://central.sonatype.com/artifact/io.github.anschnapp.mutflow/mutflow-gradle-plugin"><img src="https://img.shields.io/maven-central/v/io.github.anschnapp.mutflow/mutflow-gradle-plugin" alt="Maven Central"></a>
</p>

> **Early Stage:** mutflow is young and still evolving. Its dual-compilation approach is built to keep production builds clean — mutations only exist in test compilation. The project hasn't seen broad adoption yet, so bug reports and feedback are very welcome!

## What is this?

mutflow brings mutation testing to Kotlin with minimal overhead. Instead of the traditional approach (compile and run each mutant separately), mutflow:

1. **Compiles once** — All mutation variants are injected at compile time as conditional branches
2. **Discovers dynamically** — Mutation points are found during baseline test execution
3. **Runs all mutations** — Every discovered mutation is tested by default, no configuration needed

## Why?

Traditional mutation testing is powerful but expensive. Most teams skip it entirely.

mutflow trades exhaustiveness for practicality: low setup cost, no separate tooling, runs in your normal test suite. Some mutation testing is better than none.

## What mutflow tests (and what it doesn't)

mutflow closes the gap between code coverage and assertion quality. Coverage tells you code was executed; mutflow verifies your assertions actually catch behavioral changes.

**Important:** mutflow only tests code reached within `MutFlow.underTest { }` blocks. Unreached code produces no mutations - mutflow won't warn you. This is intentional (keeps scope focused) but means you should use coverage tools separately to ensure code is exercised at all.

A carefully [selected set of mutation operators](#mutation-operators) is built in, with an extensible architecture for adding new ones. See [features](#features) for the full list of capabilities.

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

### Disabling Mutation Testing

You can completely disable mutation testing without removing the plugin. When disabled, no compiler plugin is registered and no extra compilation happens — zero overhead.

```kotlin
// In build.gradle.kts
mutflow {
    enabled = false
}
```

Or via command line:
```bash
./gradlew test -Pmutflow.enabled=false
```

Or in `gradle.properties`:
```properties
mutflow.enabled=false
```

When disabled, your code still compiles normally (`@MutationTarget` and `@MutFlowTest` annotations are still available), but tests run without any mutations — the mutation summary will show 0 mutations discovered.

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

That's it! For a more detailed walkthrough, check out this [blog post on dev.to](https://dev.to/5n4p_/i-built-a-single-compile-mutation-testing-lib-for-kotlin-which-runs-inside-your-normal-test-suite-4253).

The `@MutFlowTest` annotation handles everything:
- **Run without mutations**: Discovers mutation points, all tests pass normally
- **Mutation runs**: Each mutation is activated across all tests; if any test catches it (assertion fails), the mutation is killed and tests appear green
- **Survivor detection**: If no test catches a mutation, `MutantSurvivedException` is thrown and the build fails

### Example Output

```
CalculatorTest > Run without mutations > isPositive returns true for positive numbers() PASSED
CalculatorTest > Run without mutations > isPositive returns true at boundary() PASSED
CalculatorTest > Run without mutations > isPositive returns false for negative numbers() PASSED
CalculatorTest > Run without mutations > isPositive returns false for zero() PASSED

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
║  ├─ Survived:                  0  ✓                           ║
║  └─ Timed out:                 0  ✓                           ║
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

By default, `@MutFlowTest` runs all discovered mutations — no configuration needed. For large codebases where running all mutations is too slow, you can limit the number of runs:

```kotlin
@MutFlowTest(maxRuns = 20)  // Baseline + up to 19 mutation runs
class CalculatorTest { ... }
```

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

### Timeout Detection

Mutations that flip loop conditions (e.g., `<` to `>`) can cause infinite loops. mutflow automatically detects this by injecting a timeout check at the top of every loop body in `@MutationTarget` classes.

- **Default timeout**: 60 seconds per mutation run
- **On timeout**: The test **fails** with a `MutationTimedOutException` suggesting to add `// mutflow:ignore` on the affected line
- **Timed-out mutations** appear in the summary with a `⏱` marker

```kotlin
// Custom timeout (or 0 to disable)
@MutFlowTest(timeoutMs = 30_000)
class CalculatorTest { ... }
```

The timeout check is nearly free: a single `System.nanoTime()` comparison per loop iteration during mutation runs, and an instant null-check return during baseline or production execution.

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

## Mutation Operators

- [**Relational comparisons**](#how-relational-comparison-mutations-work) — `>`, `<`, `>=`, `<=` with 2 variants each (boundary + flip)
- [**Constant boundary**](#how-constant-boundary-mutations-work) — Numeric constants in comparisons mutated by +1/-1 (e.g., `0 → 1`, `0 → -1`)
- [**Arithmetic**](#how-arithmetic-mutations-work) — `+` ↔ `-`, `*` ↔ `/`, `%` → `/` (with safe division to avoid div-by-zero)
- [**Equality swaps**](#how-equality-swap-mutations-work) — `==` ↔ `!=` (1 variant each)
- [**Boolean logic swaps**](#how-boolean-logic-mutations-work) — `&&` ↔ `||` (1 variant each)
- [**Boolean inversion**](#how-boolean-inversion-mutations-work) — `expr` → `!expr` for boolean function calls, property accesses, and variables/parameters (1 variant each)
- [**Boolean return**](#how-boolean-return-mutations-work) — Boolean return values replaced with `true`/`false` (explicit returns only)
- [**Nullable return**](#how-nullable-return-mutations-work) — Nullable return values replaced with `null` (explicit returns only)
- [**Void function body**](#how-void-function-body-mutations-work) — Unit function bodies replaced with empty bodies, detecting untested side effects
- **Recursive operator nesting** — Multiple mutation types combine on the same expression
- **Type-agnostic** — Works with `Int`, `Long`, `Double`, `Float`, `Short`, `Byte`, `Char`

## Features

**Core**
- **JUnit 6 integration** — `@MutFlowTest` annotation for automatic multi-run orchestration
- **K2 compiler plugin** — Transforms `@MutationTarget` classes with multiple mutation types
- **Parameterless API** — Simple `MutFlow.underTest { }` when using JUnit extension
- **Runs all mutations by default** — Zero-config: `@MutFlowTest` tests every discovered mutation

**Reporting**
- **Summary reporting** — Visual summary at end of test class showing killed/survived mutations
- **Readable mutation names** — Source location and operator descriptions (e.g., `(Calculator.kt:7) > → >=`, `(Calculator.kt:7) 0 → 1`). When the same operator appears multiple times on one line, an occurrence suffix disambiguates (e.g., `> → >= #2`)
- **IDE-clickable links** — Source locations in IntelliJ-compatible format for quick navigation
- **Mutation result tracking** — Killed mutations show as PASSED (exception swallowed), survivors fail the build

**Control**
- **`@SuppressMutations`** — Skip mutations on specific classes or functions
- **Comment-based line suppression** — `// mutflow:ignore` and `// mutflow:falsePositive` to skip individual lines (zero production overhead)
- **Target filtering** — `includeTargets`/`excludeTargets` to scope mutations by class in integration tests
- **Trap mechanism** — Pin specific mutations to run first while debugging test gaps

**Robustness**
- **Timeout detection** — Mutations that cause infinite loops (e.g., flipping `<` in a loop condition) are automatically detected and reported. Compiler-injected `checkTimeout()` at the top of every loop body ensures even tight loops are caught. Test fails with actionable guidance to add `// mutflow:ignore`
- **Partial run detection** — Automatically skips mutation testing when running single tests from IDE (prevents false positives)
- **Parallel test safe** — Mutation test classes can run alongside other tests in parallel; `underTest {}` blocks serialize automatically via a synchronized lock, without using `ThreadLocal` (keeping the door open for coroutine/reactive support)
- **Session-based architecture** — Clean lifecycle, no leaked global state

**Extensibility**
- **Extensible architecture** — `MutationOperator` (for calls), `ReturnMutationOperator` (for returns), `WhenMutationOperator` (for boolean logic), and `FunctionBodyMutationOperator` (for function bodies) interfaces for adding new mutation types

## How Relational Comparison Mutations Work

Relational comparison mutations verify that your tests exercise boundary conditions and direction of comparisons.

**Example:** For `fun isPositive(x: Int) = x > 0`:

| Mutation | Code becomes | Caught by test |
|----------|--------------|----------------|
| `> → >=` | `x >= 0` | `isPositive(0)` should be false |
| `> → <` | `x < 0` | `isPositive(1)` should be true |

Each relational operator produces 2 variants — a boundary mutation (include/exclude equality) and a direction flip:

| Original | Boundary variant | Flip variant |
|----------|-----------------|--------------|
| `>` | `>=` | `<` |
| `>=` | `>` | `<=` |
| `<` | `<=` | `>` |
| `<=` | `<` | `>=` |

If your tests only use values far from the boundary (e.g., `isPositive(5)` and `isPositive(-5)`), the boundary variant may survive — revealing the gap.

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

## How Arithmetic Mutations Work

Arithmetic mutations verify that your tests detect when math operations are swapped.

**Example:** For `fun total(price: Int, tax: Int) = price + tax`:

| Mutation | Code becomes | Caught by test |
|----------|--------------|----------------|
| `+ → -` | `price - tax` | `total(100, 10)` should be `110` |

The full set of arithmetic swaps:

| Original | Mutated to |
|----------|------------|
| `+` | `-` |
| `-` | `+` |
| `*` | `/` (with safe division) |
| `/` | `*` |
| `%` | `/` |

**Safe division:** When mutating `*` to `/`, mutflow guards against division by zero. If the divisor is 0, it computes `divisor / dividend` instead; if both are 0, it returns 1. This prevents `ArithmeticException` from masking the actual mutation test.

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

## How Boolean Logic Mutations Work

Boolean logic mutations verify that your tests distinguish between `&&` (AND) and `||` (OR) conditions.

**Example:** For `fun isInRange(x: Int) = x >= 0 && x <= 100`:

| Mutation | Code becomes | Caught by test |
|----------|--------------|----------------|
| `&& → \|\|` | `x >= 0 \|\| x <= 100` | `isInRange(-5)` should be false |

And for `fun isOutOfRange(x: Int) = x < 0 || x > 100`:

| Mutation | Code becomes | Caught by test |
|----------|--------------|----------------|
| `\|\| → &&` | `x < 0 && x > 100` | `isOutOfRange(-5)` should be true |

These mutations catch tests that only exercise the "happy path" where both conditions agree. If `&&` and `||` would produce the same result for all your test inputs, the mutation survives — revealing that your tests don't cover the case where the two conditions disagree.

## How Boolean Inversion Mutations Work

Boolean inversion mutations verify that your tests detect when a boolean value is flipped. Every boolean expression — function calls, property accesses, and variable/parameter reads — is mutated by wrapping it in `!`.

**Function calls and property accesses (`expr()` → `!expr()`):**

```kotlin
fun isActive(user: User): Boolean {
    return user.isEnabled()
}
```

| Mutation | Code becomes | Caught by test |
|----------|--------------|----------------|
| `isEnabled() → !isEnabled()` | `return !user.isEnabled()` | `isActive(enabledUser)` should be true |

**Boolean variables and parameters (`varName` → `!varName`):**

```kotlin
fun inheritedSelection(invertSelection: Boolean, parentSelected: Boolean): Boolean {
    return if (invertSelection) !parentSelected else parentSelected
}
```

| Mutation | Code becomes | Caught by test |
|----------|--------------|----------------|
| `invertSelection → !invertSelection` | `if (!invertSelection)` | Test with `invertSelection=true` should invert |
| `parentSelected → !parentSelected` | `!(!parentSelected)` / `!parentSelected` | Test should verify both branches |

**Negation removal is implicit:** There is no separate "remove `!`" mutation. Adding `!` to the inner expression of `!expr` produces `!(!expr)`, which evaluates to `expr` — achieving the same effect. This simplification covers all boolean types uniformly without special-casing negation.

These mutations catch tests that don't verify the polarity of boolean results. If your test calls a function but doesn't assert the actual boolean value, the inversion mutation will survive.

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

## How Void Function Body Mutations Work

Void function body mutations verify that your tests check side effects, not just that a function can be called without error.

**Example:** For a Unit function with side effects:
```kotlin
fun addItem(item: String) {
    items.add(item)
    updateCount()
}
```

| Mutation | Original | Becomes | Caught when |
|----------|----------|---------|-------------|
| empty body | full body | `{ }` | Test asserts `items` contains the added item |

**Common weak test patterns this catches:**
```kotlin
// WEAK: Only calls the function, doesn't verify anything
service.addItem("apple")
// No assertion!

// WEAK: Only checks that no exception is thrown
assertDoesNotThrow { service.addItem("apple") }

// STRONG: Verifies the side effect
service.addItem("apple")
assertEquals(listOf("apple"), service.getItems())  // Catches empty body mutation
```

**Note:** Void function body mutations only apply to functions that return Unit, have non-empty bodies, and are not property accessors (getters/setters).

## Manual API

For advanced use cases or custom integrations, you can use the explicit API with selection and shuffle parameters:

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

Selection strategies (`PureRandom`, `MostLikelyRandom`, `MostLikelyStable`) and shuffle modes (`PerRun`, `PerChange`) control how mutations are prioritized. These are internal implementation details — the `@MutFlowTest` annotation uses sensible defaults automatically.

## Troubleshooting

### Code coverage (JaCoCo/Kover) reports 0% when mutflow is enabled

mutflow compiles your sources twice — once normally (`main`) and once with mutations injected (`mutatedMain`). During tests, the mutated classes are loaded instead of the original ones. Since coverage tools instrument the `main` classes, they see no execution.

**Solution:** Run coverage and mutation testing as separate steps:

```bash
./gradlew test -Pmutflow.enabled=false   # coverage run
./gradlew test                            # mutation testing run
```

See [Disabling Mutation Testing](#disabling-mutation-testing) for all configuration options.

## Documentation

See [DESIGN.md](DESIGN.md) for the full design document, architecture details, and implementation plan.

---
Co-developed with an AI assistant.
