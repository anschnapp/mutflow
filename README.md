<p align="center">
  <img src="logo.svg" alt="mutflow" width="400">
</p>

<p align="center">
  A Kotlin compiler plugin for lightweight, progressive mutation testing.
</p>

## What is this?

mutflow brings mutation testing to Kotlin with minimal overhead. Instead of the traditional approach (compile and run each mutant separately), mutflow:

1. **Compiles once** — All mutation variants are injected at compile time as conditional branches
2. **Discovers dynamically** — Mutation points are counted during baseline test execution
3. **Selects intelligently** — Prioritizes under-tested mutations based on touch counts
4. **Progresses over builds** — Different mutations are tested across builds, like continuous fuzzing

## Why?

Traditional mutation testing is powerful but expensive. Most teams skip it entirely.

mutflow trades exhaustiveness for practicality: low setup cost, no separate tooling, runs in your normal test suite. Some mutation testing is better than none.

## Status

**Under Construction** — This project is in early development and **not yet ready for use**. The core architecture is being validated through a "tracer bullet" implementation. Expect breaking changes and incomplete functionality.

## How It Works

```kotlin
// Mark code under test
@MutationTarget
class Calculator {
    fun isPositive(x: Int) = x > 0
}

// Test with mutation testing
@Test
fun testIsPositive() {
    val calculator = Calculator()

    // run=0: Baseline/discovery run (ALL tests run this first)
    val baseline = MutFlow.underTest(
        run = 0,
        selection = Selection.MostLikelyStable,
        shuffle = Shuffle.PerChange
    ) {
        calculator.isPositive(5)
    }
    assertTrue(baseline)

    // run=1, 2, ...: Mutation runs (ALL tests run with the SAME mutation active)
    val run1 = MutFlow.underTest(
        run = 1,
        selection = Selection.MostLikelyStable,
        shuffle = Shuffle.PerChange
    ) {
        calculator.isPositive(5)
    }
    assertTrue(run1) // Fails if mutation changes behavior → mutation killed!
}
```

### The Model

1. **run=0 (baseline)**: All tests execute first, discovering mutation points and tracking which tests touch each point
2. **run=1, 2, ...**: All tests execute with the **same mutation** active — if any test fails, the mutation is killed
3. **Exhaustion**: When all mutations have been tested, `MutationsExhaustedException` signals completion

### Selection Strategies

| Selection | Behavior |
|-----------|----------|
| `PureRandom` | Uniform random selection among untested mutations |
| `MostLikelyRandom` | Random but weighted toward mutations touched by fewer tests |
| `MostLikelyStable` | Deterministically pick the mutation touched by fewest tests |

Mutations touched by fewer tests are considered higher risk — `MostLikely*` strategies prioritize these.

### Shuffle Modes

| Shuffle | Behavior |
|---------|----------|
| `PerRun` | New random seed each JVM/CI run — exploratory |
| `PerChange` | Seed based on discovered points — stable until code changes |

**Typical workflow:**
- Development: `MostLikelyRandom` + `PerRun` to explore high-risk mutations
- Merge requests: `MostLikelyStable` + `PerChange` for reproducible results

## Current Features

- Kotlin K2 compiler plugin (transforms `>` operator)
- High-level `MutFlow.underTest(run, selection, shuffle) { }` API
- Three selection strategies: `PureRandom`, `MostLikelyRandom`, `MostLikelyStable`
- Two shuffle modes: `PerRun` (exploratory) and `PerChange` (stable)
- Global mutation registry with touch count tracking
- Automatic mutation exhaustion detection
- Scoped mutations via `@MutationTarget` annotation

## Planned Features

- JUnit 6 extension for automatic run orchestration
- Trap mechanism to pin surviving mutants while fixing tests
- Additional mutation operators (arithmetic, boolean, null checks)
- Surviving mutant detection and reporting

## Documentation

See [DESIGN.md](DESIGN.md) for the full design document, tradeoffs, and implementation plan.

## Acknowledgments

This project was developed with the assistance of AI coding assistants (Claude).
