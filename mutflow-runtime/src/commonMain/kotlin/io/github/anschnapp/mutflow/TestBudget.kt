package io.github.anschnapp.mutflow

/**
 * Wall-clock budget for each test during mutation runs.
 *
 * The loop guard ([MutationRegistry.checkTimeout]) only sees loops inside
 * mutated code. A mutation that makes the code under test wait forever - a
 * flow that never emits, a latch that is never released, a future that never
 * completes - parks the test thread outside any loop, and the run hangs. This
 * budget covers that case: every test gets a wall-clock limit derived from
 * its own baseline duration, and a test that exceeds it is interrupted and
 * recorded as timed out (see `MutFlowSession.runTest`).
 *
 * The limit is relative on purpose. A fixed number is either too tight for
 * slow suites or too loose to be useful; three times what the same test took
 * a moment ago in the same JVM, plus a fixed slack for jitter, is both.
 *
 * Every value can be overridden through the environment:
 * `MUTFLOW_TEST_BUDGET_FACTOR`, `MUTFLOW_TEST_BUDGET_SLACK_MS`,
 * `MUTFLOW_BASELINE_TIMEOUT_MS` and `MUTFLOW_TEST_BUDGET_GRACE_MS`. This is
 * how the Gradle DSL reaches a multiplatform project, whose test annotation
 * is synthesized with default values.
 *
 * @property factor Multiplier applied to a test's baseline duration. 0 disables
 *   the budget entirely.
 * @property slackMs Fixed allowance added on top of the scaled baseline, so a
 *   test that took 2 ms in baseline is not held to 6 ms.
 * @property baselineTimeoutMs Absolute limit for a test during the baseline
 *   run, where no reference exists yet, and for a test the baseline never saw.
 *   0 leaves those unlimited.
 * @property graceMs How long after the first interrupt to keep waiting for the
 *   test to return before the run is abandoned (the JVM exits with a
 *   diagnostic, because a thread that ignores interruption cannot be stopped
 *   and holds the registry lock every later run needs). 0 never abandons.
 */
data class TestBudget(
    val factor: Int = DEFAULT_FACTOR,
    val slackMs: Long = DEFAULT_SLACK_MS,
    val baselineTimeoutMs: Long = DEFAULT_BASELINE_TIMEOUT_MS,
    val graceMs: Long = DEFAULT_GRACE_MS
) {
    init {
        require(factor >= 0) { "factor must not be negative, got: $factor" }
        require(slackMs >= 0) { "slackMs must not be negative, got: $slackMs" }
        require(baselineTimeoutMs >= 0) { "baselineTimeoutMs must not be negative, got: $baselineTimeoutMs" }
        require(graceMs >= 0) { "graceMs must not be negative, got: $graceMs" }
    }

    val enabled: Boolean
        get() = factor > 0

    /**
     * The limit for one test in a mutation run, or 0 for none.
     *
     * @param baselineDurationMs What the test took in the baseline run, or null
     *   if the baseline never ran it (a different filter, or a test that
     *   skipped itself); such a test falls back to [baselineTimeoutMs].
     */
    fun limitForMutationRun(baselineDurationMs: Long?): Long = when {
        !enabled -> 0
        baselineDurationMs == null -> baselineTimeoutMs
        else -> baselineDurationMs * factor + slackMs
    }

    /** The limit for one test in the baseline run, or 0 for none. */
    fun limitForBaseline(): Long = if (enabled) baselineTimeoutMs else 0

    companion object {
        const val DEFAULT_FACTOR = 3
        const val DEFAULT_SLACK_MS = 1_000L
        const val DEFAULT_BASELINE_TIMEOUT_MS = 60_000L
        const val DEFAULT_GRACE_MS = 10_000L

        /** No budget: only the loop guard remains. */
        val DISABLED = TestBudget(factor = 0)

        /**
         * Applies the `MUTFLOW_*` environment overrides on top of [base]. An
         * unparseable value warns and keeps the base value, so a typo in a
         * build script does not silently drop the setting.
         */
        fun fromEnvironment(base: TestBudget = TestBudget()): TestBudget = TestBudget(
            factor = override("MUTFLOW_TEST_BUDGET_FACTOR", base.factor) { it.toIntOrNull()?.takeIf { n -> n >= 0 } },
            slackMs = override("MUTFLOW_TEST_BUDGET_SLACK_MS", base.slackMs) { it.toLongOrNull()?.takeIf { n -> n >= 0 } },
            baselineTimeoutMs = override("MUTFLOW_BASELINE_TIMEOUT_MS", base.baselineTimeoutMs) { it.toLongOrNull()?.takeIf { n -> n >= 0 } },
            graceMs = override("MUTFLOW_TEST_BUDGET_GRACE_MS", base.graceMs) { it.toLongOrNull()?.takeIf { n -> n >= 0 } }
        )

        private fun <T> override(name: String, base: T, parse: (String) -> T?): T {
            val raw = environmentVariable(name) ?: return base
            val parsed = parse(raw)
            if (parsed == null) {
                println("[mutflow] WARNING: Invalid $name value: '$raw'. Falling back to $base")
                return base
            }
            return parsed
        }
    }
}
