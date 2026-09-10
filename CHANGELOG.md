# Changelog
## [1.2.2] - 2026-09-10
### Fixed
- Boolean inversion no longer mutates calls whose result is discarded (`list.add(x)` as a statement). Inverting an unused value is an equivalent mutant that no test can kill; in a run over 652 mutants these accounted for every "ignored" verdict. The inner expressions of such a call are still mutated (`rows.add(x > 0)` keeps its `>` mutations). (#21)

## [1.2.1] - 2026-09-08
### Fixed
- Compiler crash on range `for` loops inside mutation targets. Kotlin lowers `for (i in 0 until n)` into a while loop whose body block has to start with the loop-variable declarations; `ForLoopsLowering` pattern-matches that shape and rejected the injected timeout check in front of them, failing the build with `Backend Internal error: ... No 'next' statement in for-loop`. For loops with `FOR_LOOP_INNER_WHILE` origin the check is now inserted after those declarations instead of wrapping the body. `while` and `do-while` loops are unchanged. (#19)
- `ClassCastException` on non-local returns from inline lambdas. A `return` inside `x?.let { return it }` sits in a lambda whose return type is `Nothing`, and the `when` generated around the nullable-return mutation was typed by that lambda, so the backend cast the returned value to `Void` and threw `class java.lang.Integer cannot be cast to class java.lang.Void` at runtime, even with no mutation active. The `when` is now typed by the return's target function. (#20)

### Contributors
Thanks to @rikshot for both fixes, each shipped with a regression target in the sample module.


## [1.2.0] - 2026-09-06
### Added
- **Kotlin Multiplatform support** -- mutation testing for native targets, to our knowledge the first mutation testing tool for Kotlin/Native. Apply the same Gradle plugin, write plain kotlin-test tests in `commonTest` with the multiplatform `MutFlow.underTest {}` API, and run `mutflow<Target>Test` (or the `mutflowNativeTest` umbrella). One process per mutation, orchestrated by Gradle with exit-code inversion; production klibs and binaries stay instrumentation-free via a dedicated second test compilation. Initial targets: `linuxX64`, `mingwX64`. Configuration lives in the Gradle DSL (`maxMutationRuns`, `timeoutMs`, `verificationMode`). Timeout detection (both the in-process deadline and the orchestrator's hard process kill) and comment-based suppression are verified end-to-end on native; timed-out mutations fail the build with the affected line, same fail-loudly rule as the JVM. See the "Kotlin Multiplatform Support" README section and DESIGN-MULTIPLATFORM.md.
- `mutflow-annotations`, `mutflow-core` and `mutflow-runtime` are now Kotlin Multiplatform modules (JVM behavior unchanged and regression-verified; JVM actuals are the previous implementations verbatim).
- **The `jvm()` target of a Multiplatform project** now gets mutation testing too, via `mutflowJvmTest`. It uses the ordinary in-process JUnit path, so every mutation run appears in the IDE test tree exactly as in a plain `kotlin("jvm")` project. `commonTest` sources cannot name the JVM-only `@MutFlowTest` (they also compile for Native), so the compiler plugin synthesizes it onto the instrumented test compilation; nothing changes for hand-annotated `kotlin("jvm")` projects. The stock `jvmTest` task keeps running uninstrumented code.

### Known limitations (multiplatform path)
- No traps and no random selection strategies; mutations run in the deterministic most-likely-to-survive order.
- With `maxMutationRuns` set, each target selects its own subset, so the JVM and native targets may test different mutations. Unlimited runs (the default) are unaffected.
- The Gradle wiring is generic over native targets, so it is designed to work on any target where Kotlin/Native tests can run at all, but it is verified only on `linuxX64`. mingwX64 cross-compiles and has not yet been exercised on a Windows host. Apple targets are not published: their klibs cross-compile from Linux without a Mac, but running their tests does need one, and a mutation testing tool for a target whose tests have never been executed is not a promise worth making yet. Simulator targets additionally need `SIMCTL_CHILD_`-prefixed environment variables, which is not implemented.
- Because Kotlin Multiplatform resolves dependencies per target variant, the published target set *is* the supported set: a project declaring a target mutflow does not publish gets a resolution failure rather than a degraded experience. To try an unpublished target, build mutflow yourself with `./gradlew publishToMavenLocal -Pmutflow.extraNativeTargets=macosArm64` and consume it from `mavenLocal()` - no build-file edits needed. See "Trying an unpublished target" in the README.


## [1.1.1] - 2026-08-27
### Fixed
- Baseline discovery no longer loses mutation points when the block under test throws. `MutationRegistry.withSession()` assembled its result only on the normal return path, so an exception escaping `MutFlow.underTest {}` - the natural shape of a test asserting an expected exception - discarded every point that block had discovered; those mutations were never selected, tested or reported. This hit the exception type swap operator added in 1.1.0 hardest, since its mutation point sits on the `throw` and is reachable only on a throwing path, but the defect goes back to the initial release: projects with error-case tests should expect this version to discover mutations that earlier ones silently skipped.
- Exception type swap display names no longer repeat the source type. `IllegalStateException -> IllegalStateException -> IllegalArgumentException` now reads `IllegalStateException -> IllegalArgumentException`. Traps pinned against the old spelling must be updated.

## [1.1.0] - 2026-08-27
### Added
- Exception type swap mutation operator. A `throw` of one exception type is mutated into a sibling type (for example `IllegalArgumentException` -> `IllegalStateException`), catching tests that assert *something* was thrown without asserting *what*. Nine exception types are covered. Pairs are chosen so that neither type is a subtype of the other, otherwise a `catch` of the shared supertype would still match and the mutant would be equivalent. (#16)
- `ThrowMutationOperator`, a fifth operator interface for `IrThrow` nodes, alongside `MutationOperator`, `ReturnMutationOperator`, `FunctionBodyMutationOperator` and `WhenMutationOperator`.

### Changed
- The mutation summary now lists every test that killed a mutation instead of only the first one. `MutationResult.Killed` carries `testNames: Set<String>` in place of `testName: String`, which is a source-incompatible change if you read mutation results programmatically. (#17)

### Contributors
Thanks to @trancee for the exception type swap operator and the multi-killer tracking.

## [1.0.5] - 2026-08-11
### Changed
- Gradle wrapper and JUnit patch version updates. (#15)

## [1.0.4] - 2026-07-05
### Fixed
- Arithmetic mutations on `Double` and `Float` no longer lose precision. The `when` wrapper generated around a mutated expression was hardcoded to `Boolean`, so a `Boolean`-typed `when` around a `Double` expression silently truncated fractional values. The wrapper now carries the original expression's type. (#12)

## [1.0.3] - 2026-07-04
### Fixed
- Equality swap operator no longer mutates null comparisons. Kotlin's null-safety operators (`?:`, `?.`) desugar to a synthesized `x == null` check, which previously produced confusing `== -> !=` mutations on code with no visible equality operator (and, for safe-calls, an always-crashing mutant). Explicit `x == null` / `x != null` are skipped too, since inverting a null check is typically an equivalent mutant with little signal.

## [1.0.0] - 2026-04-01

mutflow's first stable release. The public API (`@MutFlowTest`, `MutFlow.underTest {}`, `@MutationTarget`, Gradle DSL) is now considered stable.

### Added
- Gradle-based mutation targets -- define which classes to mutate via `mutflow { targets = listOf(...) }` in your build script, without annotating production code. Supports exact class names, package wildcards (`*`), recursive wildcards (`**`), and glob patterns. Can be combined freely with `@MutationTarget`. (#2)
- Verification modes -- control how surviving mutations are handled with `@MutFlowTest(verificationMode = ...)`: `STRICT` (default, survivors fail the build), `LENIENT` (survivors reported but don't fail), `DISABLED` (mutation runs skipped entirely). Can be overridden globally via `MUTFLOW_VERIFICATION_MODE` environment variable for phased CI pipelines. (#3)
- Typed `SessionId` for improved internal session identification (#6)
- Troubleshooting section in README (JaCoCo/Kover compatibility)

### Contributors
Thanks to @rusio for the feedback that led to Gradle-based mutation targets and verification modes, and for the typed SessionId contribution.

## [0.9.0] - 2026-03-17
### Fixed
- Mutation runs are now skipped when baseline tests fail - previously, failing tests were incorrectly counted as "mutation killed", making all mutations appear green

## [0.8.0] - 2026-03-03
### Changed
- Boolean inversion operator simplified - always adds `!` instead of two cases (remove/add). The "remove negation" case is implicit: `!(!expr)` = `expr`
- Boolean inversion now matches property accesses in addition to plain function calls
### Added
- Boolean variable/parameter inversion - boolean variables and parameters are now mutated (`varName → !varName`)

## [0.7.0] - 2026-03-02
### Added
- Boolean inversion mutation operator (`!expr` → `expr`, `expr` → `!expr`)
  - Removes `!` from any negated boolean expression
  - Adds `!` to plain boolean function calls (not comparisons or logic operators, which are already covered by other mutations)

## [0.6.0] - 2026-02-27
### Changed
- All mutation points are now tested by default (`maxRuns` defaults to all instead of 5)
- Removed `selection` and `shuffle` parameters from `@MutFlowTest` - simpler API, less configuration needed

## [0.5.0] - 2026-02-13
### Added
- Mutation timeout support to prevent infinite loops caused by condition mutations
  - Configurable per-mutation timeout via `@MutFlowTest(timeout = ...)` and `MutFlow.configure(timeout = ...)`
  - Timed-out mutations fail the test with a hint to deactivate the mutation on that line, preventing silent accumulation of long-running mutations

## [0.4.0] - 2026-02-13
### Added
- Thread-safe mutation session to support concurrent test execution
- Gradle setting to disable mutation injection while keeping test structure intact

## [0.3.0] - 2026-02-12
### Added
- Boolean logic swap mutation operator (`&&` <-> `||`)
- Fine-grained locking for safe parallel execution of mutation tests

## [0.2.0] - 2026-02-10
### Added
- Equality/inequality swap mutation operator (`==` <-> `!=`)

## [0.1.0] - 2026-02-09
### Added
- Initial release
- Relational comparison mutations (`<`, `<=`, `>`, `>=`)
- Constant boundary mutations
- Arithmetic operator mutations (`+` <-> `-`, `*` <-> `/`, `%` <-> `/`)
- Boolean return mutations
- Nullable return mutations (always return `null`)
- Void function body mutations (replace body with empty body)
- JUnit 6 extension with `@MutFlowTest`
- Include/exclude filters for `@MutFlowTest`
- `@MutFlowIgnore` annotation for suppressing mutations on specific lines
- Gradle plugin for easy integration
