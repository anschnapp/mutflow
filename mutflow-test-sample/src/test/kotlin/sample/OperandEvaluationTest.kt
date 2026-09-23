package sample

import io.github.anschnapp.mutflow.ActiveMutation
import io.github.anschnapp.mutflow.DiscoveredPoint
import io.github.anschnapp.mutflow.MutFlow
import io.github.anschnapp.mutflow.MutationRegistry
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

/**
 * Pins the semantics of mutations over hoisted operands, activating each point directly
 * instead of going through selection so the assertions are about one known mutant.
 *
 * The operands are evaluated once into temporaries and the original and every variant read
 * them, so the property that needs pinning is that this is invisible: each operand runs
 * exactly once, in source order, whichever variant is active.
 */
class OperandEvaluationTest {

    private val target = OperandEvaluationTarget()

    @BeforeTest
    fun setup() {
        MutationRegistry.reset()
        MutFlow.reset()
        target.reset()
    }

    /** Runs [block] in a discovery session and returns the target's points, in discovery order. */
    private fun discover(block: () -> Any?): List<DiscoveredPoint> {
        val (_, result) = MutationRegistry.withSession { block() }
        target.reset()
        return result.discoveredPoints.filter { it.pointId.contains("OperandEvaluationTarget") }
    }

    /** Runs [block] once per variant of every point [block] reaches, returning each result. */
    private fun <T> everyVariant(block: () -> T): List<Pair<String, T>> =
        discover { block() }.flatMap { point ->
            List(point.variantCount) { variant ->
                target.reset()
                val result = MutationRegistry.withSession(ActiveMutation(point.pointId, variant)) { block() }.first
                "${point.originalOperator} → ${point.variantOperators[variant]}" to result
            }
        }

    /** Asserts that the original and every variant evaluate exactly [operands], in that order. */
    private fun assertEvaluatedOnceInOrder(vararg operands: Int, block: () -> Any) {
        target.reset()
        MutationRegistry.withSession { block() }
        assertEquals(operands.toList(), target.evaluated, "original")
        for (point in discover(block)) {
            repeat(point.variantCount) { variant ->
                target.reset()
                MutationRegistry.withSession(ActiveMutation(point.pointId, variant)) { block() }
                assertEquals(
                    operands.toList(), target.evaluated,
                    "${point.originalOperator} → ${point.variantOperators[variant]}"
                )
            }
        }
    }

    @Test
    fun `arithmetic evaluates each operand once and in order`() {
        assertEvaluatedOnceInOrder(5, 3) { target.minus(5, 3) }
        assertEquals(listOf("+ → -" to 2), everyVariant { target.minus(5, 3) })
    }

    @Test
    fun `safe division reads the hoisted operands instead of evaluating them again`() {
        assertEvaluatedOnceInOrder(6, 3) { target.times(6, 3) }
        assertEquals(listOf("* → /" to 2), everyVariant { target.times(6, 3) })
        assertEquals(listOf("* → /" to 0), everyVariant { target.times(6, 0) })
        assertEquals(listOf("* → /" to 1), everyVariant { target.times(0, 0) })
    }

    @Test
    fun `stacked relational and constant boundary mutations share one evaluation`() {
        val points = discover { target.positive(1) }
        assertEquals(listOf(">", "0"), points.map { it.originalOperator })
        assertEvaluatedOnceInOrder(1) { target.positive(1) }
        assertEquals(
            listOf("> → >=" to true, "> → <" to false, "0 → 1" to false, "0 → -1" to true),
            everyVariant { target.positive(1) }
        )
    }

    @Test
    fun `equality negates the evaluated comparison`() {
        assertEvaluatedOnceInOrder(4, 4) { target.same(4, 4) }
        assertEquals(listOf("== → !=" to false), everyVariant { target.same(4, 4) })
    }

    @Test
    fun `boolean inversion negates the evaluated call`() {
        assertEquals(listOf("isOdd() → !isOdd()" to false), everyVariant { target.odd(3) })
        assertEvaluatedOnceInOrder(3) { target.odd(3) }
    }

    @Test
    fun `a point registers itself even when an operand throws`() {
        val (_, result) = MutationRegistry.withSession {
            assertFailsWith<UnsupportedOperationException> { target.failing(1) }
        }
        val plus = result.discoveredPoints.filter { it.pointId.contains("OperandEvaluationTarget") }
        assertEquals(listOf("+"), plus.map { it.originalOperator })
    }

    @Test
    fun `a return keeps the line of a hoisted value`() {
        val points = discover { target.explicitReturn(1) }
        assertEquals(setOf(">", "0", "return ..."), points.map { it.originalOperator }.toSet())
        assertEquals(setOf("OperandEvaluationTarget.kt:56"), points.map { it.sourceLocation }.toSet())
    }
}
