package io.github.anschnapp.mutflow.junit4

import io.github.anschnapp.mutflow.TestBudget
import io.github.anschnapp.mutflow.VerificationMode
import kotlin.reflect.KClass

/**
 * Optional settings for a class run with [MutFlowRunner]. Mirrors the JUnit 6 `@MutFlowTest`
 * knob for knob; the same `MUTFLOW_MAX_RUNS`, `MUTFLOW_TIMEOUT_MS`, `MUTFLOW_VERIFICATION_MODE`,
 * `MUTFLOW_TEST_BUDGET_FACTOR`, `MUTFLOW_TEST_BUDGET_SLACK_MS`, `MUTFLOW_BASELINE_TIMEOUT_MS` and
 * `MUTFLOW_TEST_BUDGET_GRACE_MS` environment variables override it.
 *
 * ```kotlin
 * @RunWith(MutFlowRunner::class)
 * @MutFlowTest(includeTargets = [Calculator::class])
 * class CalculatorTest { ... }
 * ```
 *
 * @property testBudgetFactor Wall-clock budget for each test method during mutation runs, as a
 *   multiple of what the same test took in the baseline run; catches mutations that make the code
 *   under test wait forever, which the loop-based timeout cannot see. 0 disables the budget. See
 *   [TestBudget].
 * @property testBudgetSlackMs Fixed allowance added to the scaled baseline duration.
 * @property baselineTimeoutMs Absolute limit for a test in the baseline run, where no reference
 *   exists yet. 0 disables it.
 * @property testBudgetGraceMs How long an interrupted test may keep running before the run is
 *   abandoned (the test JVM exits with a diagnostic). 0 never abandons.
 * @property wrapTestMethods Run every test method, with its rules and `@Before`/`@After`, inside
 *   `underTest`, so existing tests need no `MutFlow.underTest {}` call. Tests must then not call
 *   it themselves, as `underTest` blocks do not nest. Off by default, matching JUnit 6. The budget
 *   still covers the test method alone.
 */
@Target(AnnotationTarget.CLASS)
@Retention(AnnotationRetention.RUNTIME)
annotation class MutFlowTest(
    val maxRuns: Int = Int.MAX_VALUE,
    val traps: Array<String> = [],
    val includeTargets: Array<KClass<*>> = [],
    val excludeTargets: Array<KClass<*>> = [],
    val timeoutMs: Long = 60_000,
    val verificationMode: VerificationMode = VerificationMode.STRICT,
    val testBudgetFactor: Int = TestBudget.DEFAULT_FACTOR,
    val testBudgetSlackMs: Long = TestBudget.DEFAULT_SLACK_MS,
    val baselineTimeoutMs: Long = TestBudget.DEFAULT_BASELINE_TIMEOUT_MS,
    val testBudgetGraceMs: Long = TestBudget.DEFAULT_GRACE_MS,
    val wrapTestMethods: Boolean = false
)
