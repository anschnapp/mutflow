# mutflow Kotlin/Native example

Minimal Kotlin Multiplatform project showing mutation testing on native
targets. Production code lives in `commonMain`, tests are plain kotlin-test
in `commonTest` using the multiplatform `MutFlow.underTest {}` API.

Like `../example/`, this is a standalone build consuming mutflow from
mavenLocal:

```bash
# 1. Publish all mutflow artifacts (from the repo root)
cd .. && ./gradlew publishToMavenLocal && cd example-native

# 2. Plain test run - the uninstrumented binary, mutflow stays out of the way
../gradlew linuxX64Test

# 3. Mutation testing - orchestrates the instrumented test binary:
#    one baseline discovery run, then one process per mutation
../gradlew mutflowLinuxX64Test

# or for all native targets runnable on this host:
../gradlew mutflowNativeTest
```

Expected output of step 3: 6 mutations discovered from `Calculator.isPositive`,
all 6 killed, each by the matching boundary test.

## How it differs from the JVM path

The Gradle plugin creates a second, instrumented compilation per native
target (`mutatedMain`/`mutatedTest`) plus a dedicated test binary. Production
klibs and binaries never contain instrumentation - same philosophy as the
JVM path's `mutatedMain` source set. The run loop lives in the
`mutflow<Target>Test` Gradle task instead of a JUnit extension: the binary is
executed once per mutation, and a failing test suite (nonzero exit) means the
mutation was killed.

Configuration lives in the Gradle DSL instead of the `@MutFlowTest`
annotation:

```kotlin
mutflow {
    nativeMaxMutationRuns = 20        // default: unlimited
    nativeTimeoutMs = 60_000          // infinite loop protection
    nativeVerificationMode = "STRICT" // STRICT | LENIENT | DISABLED
}
```

See `../DESIGN-KOTLIN-NATIVE.md` for the full architecture.
