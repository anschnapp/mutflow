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

// Test with mutation testing (manual mode - JUnit extension will automate this)
@Test
fun testIsPositive() {
    val calculator = Calculator()

    // Run 0: Baseline - discover mutations
    val run0 = MutFlow.underTest(runNumber = 0, testId = "testIsPositive") {
        calculator.isPositive(5)
    }
    assertTrue(run0.result)
    // run0.discovered = [DiscoveredPoint("...", variantCount=3)]

    // Run 1-N: Test each mutation
    for (runNumber in 1..MutFlow.totalMutations(run0.discovered)) {
        val runN = MutFlow.underTest(
            runNumber = runNumber,
            testId = "testIsPositive",
            discovered = run0.discovered
        ) {
            calculator.isPositive(5)
        }
        // If assertion passes with mutation active → mutation survived
    }
}
```

1. **Run 0 (baseline)**: Test executes normally, discovers 1 mutation point with 3 variants (>=, <, ==)
2. **Run 1-N**: Each run activates one variant. If test still passes → mutation survived → test gap found

## Current Features

- Kotlin K2 compiler plugin (transforms `>` operator)
- BDD-style `MutFlow.underTest { }` API for explicit test scoping
- Scoped mutations via `@MutationTarget` annotation

## Planned Features

- JUnit 6 extension for automatic session orchestration
- Trap mechanism to pin surviving mutants while fixing tests
- Additional mutation operators (arithmetic, boolean, null checks)
- Auto-generated test IDs from call site

## Documentation

See [DESIGN.md](DESIGN.md) for the full design document, tradeoffs, and implementation plan.

## Acknowledgments

This project was developed with the assistance of AI coding assistants (Claude).

