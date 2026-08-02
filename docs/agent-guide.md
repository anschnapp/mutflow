# mutflow Agent Guide

Task-oriented reference for coding agents and for anyone who wants the short version.
Every snippet below is complete and copy-pasteable. For the narrative explanation of
each feature, see [README.md](../README.md); for compiler internals, see [DESIGN.md](../DESIGN.md).

mutflow is a Kotlin K2 compiler plugin for mutation testing. Mutations are injected at
compile time into a separate, test-only compilation (mutant schemata), so the whole test
suite runs against mutated code without recompiling per mutant, and production artifacts
stay clean.

## Add mutflow to a Gradle build

The Gradle plugin wires everything up. Do not declare `mutflow-core` or `mutflow-junit6`
manually; the plugin adds them.

```kotlin
// build.gradle.kts
plugins {
    kotlin("jvm") version "2.4.0"
    id("io.github.anschnapp.mutflow") version "<latest-version>"
}
```

## Pick the mutflow version that matches your Kotlin version

Compiler plugins are bound to the compiler's internal APIs. Each mutflow release requires
the Kotlin version it was built against and will not work with older Kotlin versions.

| mutflow     | Kotlin        |
|-------------|---------------|
| 1.1.0+      | 2.4.x         |
| up to 1.0.3 | 2.2.x - 2.3.x |

A mismatch usually shows up as an obscure compiler failure such as `NoClassDefFoundError`
during compilation. Check the Kotlin version first when that happens.

## Choose what gets mutated

Two mechanisms, usable together. A class is mutated if it matches either one.

```kotlin
// Option 1: annotate production code
@MutationTarget
class Calculator {
    fun isPositive(x: Int) = x > 0
}
```

```kotlin
// Option 2: configure in build.gradle.kts, no annotation on production code
mutflow {
    targets = listOf(
        "com.example.Calculator",  // exact class
        "com.example.service.*",   // all classes in a package
        "com.example.service.**",  // package and all subpackages
        "com.example.*Service",    // glob pattern
    )
}
```

## Write a mutation test

Annotate the test class with `@MutFlowTest` and wrap the exercised code in
`MutFlow.underTest { }`. The wrapper is required: it marks the region where mutations are
active. Code outside it runs unmutated.

```kotlin
@MutFlowTest
class CalculatorTest {
    private val calculator = Calculator()

    @Test
    fun `isPositive returns true for positive numbers`() {
        val result = MutFlow.underTest { calculator.isPositive(5) }
        assertTrue(result)
    }

    @Test
    fun `isPositive returns false for zero`() {
        val result = MutFlow.underTest { calculator.isPositive(0) }
        assertFalse(result)
    }
}
```

There is no separate Gradle task: mutation testing happens during the normal test run
(`./gradlew test`). `@MutFlowTest` orchestrates one baseline run plus one run per mutation.

## Interpret the results

- Tests showing `PASSED` during mutation runs is the expected outcome. When a test's
  assertion catches a mutation, the exception is swallowed and the test reports green.
- The summary printed at the end of the class is the real result: killed vs survived.
- A surviving mutation fails the build with `MutantSurvivedException`. That means a gap in
  test coverage, not a bug in mutflow.
- Mutations are named by source location and operator, e.g. `(Calculator.kt:7) > → >=`.
  A `#2` suffix disambiguates repeated operators on one line.

## Cap the number of runs on large codebases

By default every discovered mutation is tested.

```kotlin
@MutFlowTest(maxRuns = 20)  // baseline + up to 19 mutation runs
class CalculatorTest { /* ... */ }
```

## Scope mutations in integration tests

When a test reaches several targets but only one is under test:

```kotlin
// whitelist: only these classes produce active mutations
@MutFlowTest(includeTargets = [Calculator::class])
class CalculatorIntegrationTest { /* ... */ }

// blacklist: skip infrastructure reached by underTest
@MutFlowTest(excludeTargets = [AuditLogger::class, MetricsService::class])
class PaymentServiceTest { /* ... */ }
```

Combining both narrows with `includeTargets` first, then removes `excludeTargets`.

## Control whether survivors fail the build

```kotlin
@MutFlowTest(verificationMode = VerificationMode.STRICT)   // default: survivors fail
@MutFlowTest(verificationMode = VerificationMode.LENIENT)  // survivors reported only
@MutFlowTest(verificationMode = VerificationMode.DISABLED) // skip mutation runs entirely
```

Use `STRICT` in CI, `LENIENT` while building coverage up incrementally, `DISABLED` for
fast feedback from regular tests only.

## Split CI into a fast phase and a mutation phase

`MUTFLOW_VERIFICATION_MODE` overrides the annotation for all test classes. Accepts
`STRICT`, `LENIENT`, `DISABLED`, case-insensitive.

```bash
# Phase 1: fast feedback, regular tests only
MUTFLOW_VERIFICATION_MODE=DISABLED ./gradlew test

# Phase 2: full mutation testing
./gradlew test

# Gradual adoption: run mutations, report survivors, don't block
MUTFLOW_VERIFICATION_MODE=LENIENT ./gradlew test
```

## Handle mutations that hang

Flipping a loop condition can produce an infinite loop. mutflow injects a timeout check at
the top of every loop body in target classes; the default budget is 60s per mutation run,
and a timeout fails the test with `MutationTimedOutException`.

```kotlin
@MutFlowTest(timeoutMs = 30_000)  // 0 disables the timeout
class CalculatorTest { /* ... */ }
```

## Pin a surviving mutation while you fix the test

Copy the display name from the survivor output into `traps`. Trapped mutations run first
on every run, regardless of selection strategy. Remove the trap once the test kills it.

```kotlin
@MutFlowTest(traps = ["(Calculator.kt:8) > → >="])
class CalculatorTest { /* ... */ }
```

A trap that matches nothing prints a warning listing the available mutations.

## Suppress mutations that aren't worth testing

```kotlin
// class level
@MutationTarget
@SuppressMutations
class LegacyCalculator { /* ... */ }
```

```kotlin
// function level
@MutationTarget
class Calculator {
    @SuppressMutations
    fun debugLog(x: Int): Boolean = x > 100
}
```

```kotlin
// line level
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

Both comment keywords have the same technical effect and differ in intent: `mutflow:ignore`
for code not worth testing, `mutflow:falsePositive` for equivalent mutants. An inline
comment suppresses the same line; a standalone comment suppresses the next line. Free-form
text after the keyword documents the reason. Suppression works no matter how the class was
targeted, and comments leave no trace in production bytecode.

## Turn mutation testing off entirely

When disabled, no compiler plugin is registered and no extra compilation runs, so overhead
is zero. Annotations remain available, so code still compiles.

```kotlin
mutflow { enabled = false }
```

```bash
./gradlew test -Pmutflow.enabled=false
```

```properties
# gradle.properties
mutflow.enabled=false
```

## Fix JaCoCo or Kover reporting 0% coverage

Expected interaction, not a bug: mutflow compiles sources twice (`main` and `mutatedMain`),
tests load the mutated classes, and coverage tools instrument `main`. Run the two concerns
as separate steps.

```bash
./gradlew test -Pmutflow.enabled=false   # coverage run
./gradlew test                           # mutation testing run
```

## Verify a production artifact contains no mutations

Mutations only ever land in the `mutatedMain` source set, so release JARs are clean by
construction. For a hard gate in a release pipeline, run the shipped check.

```bash
scripts/mutflow-verify-jar.sh build/libs/my-app.jar
```

## What mutflow mutates

Relational comparisons (`>`, `<`, `>=`, `<=`, boundary and flip variants), numeric constant
boundaries (`0 → 1`, `0 → -1`), arithmetic (`+`↔`-`, `*`↔`/`, `%`→`/` with safe division),
equality swaps (`==`↔`!=`), boolean logic swaps (`&&`↔`||`), boolean inversion
(`expr`→`!expr`), boolean returns (replaced with `true`/`false`), nullable returns (replaced
with `null`), and `Unit` function bodies (emptied, to catch untested side effects). Return
operators apply to explicit returns only. Operators nest and work across `Int`, `Long`,
`Double`, `Float`, `Short`, `Byte`, and `Char`.

## Common mistakes

- Calling the code under test outside `MutFlow.underTest { }`. Mutations are inactive there,
  so every mutation survives.
- Adding a separate "mutation test" Gradle task. Mutation runs are part of `./gradlew test`.
- Declaring `mutflow-core` or `mutflow-junit6` as explicit dependencies. The Gradle plugin
  already adds them.
- Treating green mutation runs as the result. Read the summary and the exit status instead.
- Reaching for `MutFlow.underTest(run = ..., Selection..., Shuffle...)`. Those parameters
  exist for custom integrations; `@MutFlowTest` sets sane defaults.
- Suppressing a survivor to get the build green. Suppression is for equivalent mutants and
  code not worth testing; a real survivor means a missing assertion.
