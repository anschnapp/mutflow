# mutflow Kotlin/Native spike (Phase 0 harness, upgraded for Phase 2)

Throwaway project, originally built to answer the Phase 0 gating question from
[DESIGN-KOTLIN-NATIVE.md](../DESIGN-KOTLIN-NATIVE.md):

> Does the existing mutflow IR transformation survive the Kotlin/Native backend?

(Answer: yes, passed 2026-07-04.) Since Phase 2 it doubles as the end-to-end
verification harness for the **real** native runtime: the Phase-0 hand-written
`stub-registry/` is gone, and `app/` consumes the genuine multiplatform
`mutflow-core`/`mutflow-runtime` klibs from mavenLocal, with tests written
against the multiplatform `MutFlow.underTest {}` API. The compiler plugin is
still used **unmodified**. Nothing here ships.

## Layout

| Path | What |
|---|---|
| `app/` | `Calculator` (`@MutationTarget`, contains `x > 0`) + kotlin-test suite using `MutFlow.underTest {}`; forces the compiler plugin onto the linuxX64 compilation via `-Xplugin` |

## How to run

```bash
# 1. Publish all mutflow artifacts to mavenLocal (from the repo root)
cd .. && ./gradlew publishToMavenLocal && cd spike

# 2. Build + plain run (no env vars -> mutflow inactive, all tests pass).
#    Uses the repo root's gradle wrapper; Gradle picks up spike/settings.gradle.kts
#    from the current directory, so this stays a fully separate build.
../gradlew :app:linuxX64Test

# 3. Discovery run: collect mutation points + touch counts into a file
MUTFLOW_DISCOVERY_FILE=/tmp/discovery.json \
  ./app/build/bin/linuxX64/debugTest/test.kexe
cat /tmp/discovery.json   # 3 points (>, 0, boolean return), touchCount 4 each

# 4. Mutation run: activate one mutation by hand and run the test binary directly
MUTFLOW_ACTIVE_MUTATION=spike.Calculator_0:0 \
MUTFLOW_RESULT_FILE=/tmp/result.json \
  ./app/build/bin/linuxX64/debugTest/test.kexe

# Expect: a test FAILS (nonzero exit) -> the mutation was killed; result.json
# shows "touched":true. In the real Phase-3 Gradle task this exit code gets
# inverted: nonzero = killed = good, zero = survived = build failure.
```

## Verified (Phase 2, 2026-07-05)

1. Plain run without env vars is green (inactive pass-through).
2. Discovery run finds the same 3 points as the JVM path, with touch counts.
3. All 6 variants are killed by exactly the expected boundary test.
4. A bogus mutation id survives (exit 0) with `"touched":false` in the result
   file - the "mutation never executed" signal for the future orchestrator.
