# mutflow Kotlin/Native spike (Phase 0)

Throwaway project answering the gating question from
[DESIGN-KOTLIN-NATIVE.md](../DESIGN-KOTLIN-NATIVE.md):

> Does the existing mutflow IR transformation survive the Kotlin/Native backend?

Nothing here ships. The mutflow compiler plugin is used **unmodified** from
mavenLocal; the runtime side is replaced by a hand-written native stub
(`stub-registry/`) that reads `MUTFLOW_ACTIVE_MUTATION` from the environment.

## Layout

| Path | What |
|---|---|
| `stub-registry/` | Native klib providing `io.github.anschnapp.mutflow.MutationRegistry` (stub, env-var driven) and `@MutationTarget` under their real FQNs |
| `app/` | `Calculator` (`@MutationTarget`, contains `x > 0`) + kotlin-test suite; forces the compiler plugin onto the linuxX64 compilation via `-Xplugin` |

## How to run

```bash
# 1. Publish the compiler plugin to mavenLocal (from the repo root)
cd .. && ./gradlew :mutflow-compiler-plugin:publishToMavenLocal && cd spike

# 2. Build + baseline run (no active mutation).
#    Uses the repo root's gradle wrapper; Gradle picks up spike/settings.gradle.kts
#    from the current directory, so this stays a fully separate build.
../gradlew :app:linuxX64Test

# Expect in output: "[mutflow-stub] discovered: spike.Calculator_0 ..." lines
# and all 4 tests passing.

# 3. Mutation run: activate one mutation by hand and run the test binary directly
MUTFLOW_ACTIVE_MUTATION=spike.Calculator_0:0 \
  ./app/build/bin/linuxX64/debugTest/test.kexe

# Expect: a test FAILS (nonzero exit) -> the mutation was killed.
# In the real Phase-3 Gradle task this exit code gets inverted:
# nonzero = killed = good, zero = survived = build failure.
```

## Success criteria

1. Native compilation with the plugin succeeds (no backend crash - the analogue
   of the JVM-world lowering crashes documented in CLAUDE memory).
2. Baseline run discovers the expected mutation points and passes.
3. Setting `MUTFLOW_ACTIVE_MUTATION` flips behavior and fails the suite.

If (1) fails badly, Phase 0 says: stop here cheaply and reassess.
