# Changelog
## [Unreleased]
### Added
- **Kotlin/Native support (experimental)** -- mutation testing for native targets in Kotlin Multiplatform projects, to our knowledge the first mutation testing tool for Kotlin/Native. Apply the same Gradle plugin, write plain kotlin-test tests in `commonTest` with the multiplatform `MutFlow.underTest {}` API, and run `mutflow<Target>Test` (or the `mutflowNativeTest` umbrella). One process per mutation, orchestrated by Gradle with exit-code inversion; production klibs and binaries stay instrumentation-free via a dedicated second test compilation. Initial targets: `linuxX64`, `mingwX64`. Configuration lives in the Gradle DSL (`nativeMaxMutationRuns`, `nativeTimeoutMs`, `nativeVerificationMode`). Timeout detection (both the in-process deadline and the orchestrator's hard process kill) and comment-based suppression are verified end-to-end on native; timed-out mutations fail the build with the affected line, same fail-loudly rule as the JVM. See the "Kotlin/Native Support" README section and DESIGN-KOTLIN-NATIVE.md.
- `mutflow-annotations`, `mutflow-core` and `mutflow-runtime` are now Kotlin Multiplatform modules (JVM behavior unchanged and regression-verified; JVM actuals are the previous implementations verbatim).
### Known limitations (native path)
- JVM targets inside a KMP project are not wired for mutation testing yet (plain JVM projects are unaffected).
- No traps and no random selection strategies; mutations run in the deterministic most-likely-to-survive order.
- mingwX64 cross-compiles but has not yet been exercised on a Windows host; Apple targets are planned.


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
