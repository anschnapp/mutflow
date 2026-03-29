package io.github.anschnapp.mutflow.junit

import org.junit.jupiter.api.ClassTemplate
import org.junit.jupiter.api.extension.ExtendWith
import kotlin.reflect.KClass

/**
 * Marks a test class for mutation testing with mutflow.
 *
 * This is a meta-annotation that combines @ClassTemplate and @ExtendWith(MutFlowExtension::class).
 * The test class will be executed multiple times:
 * - Run 0: Baseline/discovery run (no mutations active)
 * - Run 1+: Mutation runs (one mutation active per run)
 *
 * By default, all discovered mutations are tested. For large codebases, you can limit
 * the number of runs with [maxRuns].
 *
 * Usage:
 * ```
 * @MutFlowTest
 * class CalculatorTest {
 *     val calculator = Calculator()
 *
 *     @Test
 *     fun testIsPositive() {
 *         val result = MutFlow.underTest { calculator.isPositive(5) }
 *         assertTrue(result)
 *     }
 * }
 * ```
 *
 * With traps (pin specific mutations for debugging):
 * ```
 * @MutFlowTest(traps = ["(Calculator.kt:8) > → >="])
 * class CalculatorTest { ... }
 * ```
 *
 * @param maxRuns Maximum number of runs (including baseline). Default runs all mutations.
 *               Set to a lower value (e.g., 20) to limit runs for large codebases.
 * @param traps Mutations to test first, before normal selection. Use display name format
 *              from mutation survivor output, e.g., "(Calculator.kt:8) > → >=".
 *              Trapped mutations run in order provided.
 * @param includeTargets Only test mutations from these @MutationTarget classes.
 *                       Empty (default) means all discovered classes are included.
 * @param excludeTargets Skip mutations from these @MutationTarget classes.
 *                       Empty (default) means no classes are excluded.
 *                       When both are specified, include narrows first, then exclude removes from that set.
 * @param timeoutMs Maximum time in milliseconds for each mutation run before it is considered
 *                  timed out (likely an infinite loop). Default is 60000 (60 seconds).
 *                  Set to 0 to disable timeout detection.
 * @param skipCauseNotAllCasesCovered When true, skips mutation testing and only runs the
 *                  baseline (regular tests). Use this while you are still writing test cases
 *                  and are not yet ready for mutation testing. Default is false.
 */
@Target(AnnotationTarget.CLASS)
@Retention(AnnotationRetention.RUNTIME)
@ClassTemplate
@ExtendWith(MutFlowExtension::class)
annotation class MutFlowTest(
    val maxRuns: Int = Int.MAX_VALUE,
    val traps: Array<String> = [],
    val includeTargets: Array<KClass<*>> = [],
    val excludeTargets: Array<KClass<*>> = [],
    val timeoutMs: Long = 60_000,
    val skipCauseNotAllCasesCovered: Boolean = false
)
