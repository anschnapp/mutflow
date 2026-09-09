package io.github.anschnapp.mutflow.junit4

import io.github.anschnapp.mutflow.VerificationMode
import kotlin.reflect.KClass

/**
 * Optional settings for a class run with [MutFlowRunner]. Mirrors the JUnit 6 `@MutFlowTest`
 * knob for knob; the same `MUTFLOW_MAX_RUNS`, `MUTFLOW_TIMEOUT_MS` and `MUTFLOW_VERIFICATION_MODE`
 * environment variables override it.
 *
 * ```kotlin
 * @RunWith(MutFlowRunner::class)
 * @MutFlowTest(includeTargets = [Calculator::class])
 * class CalculatorTest { ... }
 * ```
 *
 * @property wrapTestMethods Run every test method, with its rules and `@Before`/`@After`, inside
 *   `underTest`, so existing tests need no `MutFlow.underTest {}` call. Tests must then not call
 *   it themselves, as `underTest` blocks do not nest. Off by default, matching JUnit 6.
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
    val wrapTestMethods: Boolean = false
)
