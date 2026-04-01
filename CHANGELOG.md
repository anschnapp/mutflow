# Changelog

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
