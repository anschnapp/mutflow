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
3. **Selects at runtime** — Each subsequent run activates exactly one mutation for precise feedback
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

    // run=0: Baseline/discovery run
    val baseline = MutFlow.underTest(testId = "testIsPositive", run = 0, shuffle = Shuffle.OnChange) {
        calculator.isPositive(5)
    }
    assertTrue(baseline)

    // run=1, 2, ...: Mutation runs (each tests a pseudo-randomly selected mutation)
    val run1 = MutFlow.underTest(testId = "testIsPositive", run = 1, shuffle = Shuffle.OnChange) {
        calculator.isPositive(5)
    }
    assertTrue(run1) // May fail if mutation changes behavior → mutation killed!

    val run2 = MutFlow.underTest(testId = "testIsPositive", run = 2, shuffle = Shuffle.OnChange) {
        calculator.isPositive(5)
    }
    assertTrue(run2)
}
```

1. **run=0 (baseline)**: Discovers mutation points, stores them for subsequent runs
2. **run=1, 2, ...**: Each run activates a pseudo-randomly selected mutation
3. **Shuffle modes**:
   - `Shuffle.EachTime` — Different mutations each CI build (exploratory)
   - `Shuffle.OnChange` — Same mutations until code changes (stable CI)

## Current Features

- Kotlin K2 compiler plugin (transforms `>` operator)
- High-level `MutFlow.underTest(testId, run, shuffle) { }` API
- Two shuffle modes: `EachTime` (exploratory) and `OnChange` (stable)
- Pseudo-random mutation selection based on testId + run + code hash
- Scoped mutations via `@MutationTarget` annotation
- In-memory baseline storage per test

## Planned Features

- JUnit 6 extension for automatic session orchestration
- Trap mechanism to pin surviving mutants while fixing tests
- Additional mutation operators (arithmetic, boolean, null checks)
- Auto-generated test IDs from call site

## Documentation

See [DESIGN.md](DESIGN.md) for the full design document, tradeoffs, and implementation plan.

## Acknowledgments

This project was developed with the assistance of AI coding assistants (Claude).

