<p align="center">
  <img src="logo.svg" alt="mutflow" width="400">
</p>

<p align="center">
  A Kotlin compiler plugin for lightweight, progressive mutation testing.
</p>

## What is this?

mutflow brings mutation testing to Kotlin with minimal overhead. Instead of the traditional approach (compile and run each mutant separately), mutflow:

1. **Compiles once** — All mutation variants are injected at compile time as conditional branches
2. **Discovers dynamically** — Mutation points are found during baseline test execution
3. **Selects intelligently** — Prioritizes under-tested mutations based on touch counts
4. **Progresses over builds** — Different mutations are tested across builds, like continuous fuzzing

## Why?

Traditional mutation testing is powerful but expensive. Most teams skip it entirely.

mutflow trades exhaustiveness for practicality: low setup cost, no separate tooling, runs in your normal test suite. Some mutation testing is better than none.

## Status

**Phase 1 Complete** — The tracer bullet is working end-to-end. The JUnit 6 integration runs your test class multiple times (baseline + mutation runs), and killed mutations are visible in test output. Not yet production-ready, but the core architecture is validated.

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
CalculatorTest > Baseline > isPositive returns false for negative numbers() PASSED
CalculatorTest > Baseline > isPositive returns false for zero() PASSED

CalculatorTest > Mutation: sample.Calculator_0:0 > isPositive returns true for positive numbers() PASSED
CalculatorTest > Mutation: sample.Calculator_0:0 > isPositive returns false for negative numbers() PASSED
CalculatorTest > Mutation: sample.Calculator_0:0 > isPositive returns false for zero() PASSED

CalculatorTest > Mutation: sample.Calculator_0:1 > isPositive returns true for positive numbers() PASSED
...

╔════════════════════════════════════════════════════════════════╗
║                    MUTATION TESTING SUMMARY                    ║
╠════════════════════════════════════════════════════════════════╣
║  Total mutations discovered:   3                              ║
║  Tested this run:              3                              ║
║  ├─ Killed:                    3  ✓                           ║
║  └─ Survived:                  0  ✓                           ║
║  Remaining untested:           0                              ║
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

## Current Features

- **JUnit 6 integration** — `@MutFlowTest` annotation for automatic multi-run orchestration
- **K2 compiler plugin** — Transforms `>` operator in `@MutationTarget` classes
- **Session-based architecture** — Clean lifecycle, no leaked global state
- **Parameterless API** — Simple `MutFlow.underTest { }` when using JUnit extension
- **Selection strategies** — `PureRandom`, `MostLikelyRandom`, `MostLikelyStable`
- **Shuffle modes** — `PerRun` (exploratory) and `PerChange` (stable)
- **Touch count tracking** — Prioritizes under-tested mutation points
- **Mutation result tracking** — Killed mutations show as PASSED (exception swallowed), survivors fail the build
- **Summary reporting** — Visual summary at end of test class showing killed/survived mutations

## Planned Features

- Trap mechanism to pin surviving mutants while fixing tests
- Additional mutation operators (arithmetic, boolean, null checks, all comparison operators)
- Variant descriptions in display names (e.g., `> → >=` instead of `:0`)
- Gradle plugin for easy setup
- IR-hash based mutation point IDs (stable across refactoring)

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
