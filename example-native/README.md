# mutflow Kotlin Multiplatform example

Minimal Kotlin Multiplatform project showing mutation testing on a `jvm()`
target and on native targets, from one set of sources. Production code lives
in `commonMain`, tests are plain kotlin-test in `commonTest` using the
multiplatform `MutFlow.underTest {}` API.

Like `../example/`, this is a standalone build consuming mutflow from
mavenLocal:

```bash
# 1. Publish all mutflow artifacts (from the repo root)
cd .. && ./gradlew publishToMavenLocal && cd example-native

# 2. Plain test run - the uninstrumented binary, mutflow stays out of the way
../gradlew linuxX64Test

# 3. Mutation testing on native - orchestrates the instrumented test binary:
#    one baseline discovery run, then one process per mutation
../gradlew mutflowLinuxX64Test

# or for all native targets runnable on this host:
../gradlew mutflowNativeTest

# 4. Mutation testing on the JVM target - the same sources, re-run in-process
#    by JUnit, so every run shows up in the IDE test tree
../gradlew mutflowJvmTest
```

Expected output of steps 3 and 4: 6 mutations discovered from
`Calculator.isPositive`, all 6 killed, each by the matching boundary test.
Both targets report the same verdict from the same `commonTest` sources -
that is the point of the example.

`Calculator.sumUpTo` additionally demonstrates the infinite-loop protection:
mutating its loop condition makes the loop spin forever, which the injected
timeout guard breaks after `timeoutMs`. Timed-out mutations fail the
build (same fail-loudly rule as the JVM path); the line carries the documented
remedy, a `// mutflow:ignore` comment, so the example stays green. Remove that
comment to see the timeout in action.

## How the two paths differ

The Gradle plugin creates a second, instrumented compilation per target
(`mutatedMain`/`mutatedTest`). Production klibs, binaries and jars never
contain instrumentation - the same philosophy as the JVM path's `mutatedMain`
source set.

On **native** targets the run loop lives in the `mutflow<Target>Test` Gradle
task instead of a JUnit extension, because kotlin-test on Native has no
extension mechanism: the test binary is executed once per mutation, and a
failing test suite (nonzero exit) means the mutation was killed.

On the **`jvm()`** target the ordinary JUnit path applies, unchanged.
`commonTest` cannot write `@MutFlowTest` (it is a JVM-only annotation, and
these sources also compile for Native), so the compiler plugin synthesizes it
onto the `mutatedTest` bytecode. The stock `jvmTest` task keeps running
uninstrumented code, exactly as stock `linuxX64Test` does.

Configuration lives in the Gradle DSL instead of the `@MutFlowTest`
annotation, and applies to every target:

```kotlin
mutflow {
    maxMutationRuns = 20        // default: unlimited
    timeoutMs = 60_000          // infinite loop protection
    verificationMode = "STRICT" // STRICT | LENIENT | DISABLED
}
```

See [DESIGN-KOTLIN-NATIVE.md](../DESIGN-KOTLIN-NATIVE.md) for the full
architecture, and the [Kotlin Multiplatform Support section of the main
README](../README.md#kotlin-multiplatform-support-experimental) for setup and
current limitations.
