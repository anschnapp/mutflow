package sample

import io.github.anschnapp.mutflow.ActiveMutation
import io.github.anschnapp.mutflow.DiscoveredPoint
import io.github.anschnapp.mutflow.MutFlow
import io.github.anschnapp.mutflow.MutationRegistry
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Pins the semantics of the fused `&&` / `||` mutation, activating the point directly instead
 * of going through selection so the assertions are about one known mutant.
 *
 * The property that needs pinning is short-circuiting: the fused form leaves the right operand
 * in a branch, so the mutant must skip it exactly where the operator it stands for would.
 */
class FusedBooleanMutationTest {

    private val target = ShortCircuitTarget()

    @BeforeTest
    fun setup() {
        MutationRegistry.reset()
        MutFlow.reset()
        target.reset()
    }

    /** Runs a discovery session and returns the point for the given operator. */
    private fun discover(operator: String): DiscoveredPoint {
        val (_, result) = MutationRegistry.withSession {
            target.and(true, true)
            target.or(false, true)
        }
        target.reset()
        return result.discoveredPoints.single {
            it.pointId.contains("ShortCircuitTarget") && it.originalOperator == operator
        }
    }

    private fun <T> withMutation(point: DiscoveredPoint, block: () -> T): T =
        MutationRegistry.withSession(ActiveMutation(point.pointId, 0)) { block() }.first

    @Test
    fun `the and point offers exactly one variant`() {
        val point = discover("&&")
        assertEquals(1, point.variantCount)
        assertEquals(listOf("||"), point.variantOperators)
    }

    @Test
    fun `the or point offers exactly one variant`() {
        val point = discover("||")
        assertEquals(1, point.variantCount)
        assertEquals(listOf("&&"), point.variantOperators)
    }

    @Test
    fun `and is unchanged while the mutation is inactive`() {
        val results = MutationRegistry.withSession {
            listOf(target.and(true, true), target.and(true, false), target.and(false, true))
        }.first
        assertEquals(listOf(true, false, false), results)
        assertEquals(2, target.rightOperandEvaluations, "a false left operand must skip b")
    }

    @Test
    fun `the and mutant behaves as or`() {
        val point = discover("&&")
        val results = withMutation(point) {
            listOf(target.and(true, false), target.and(false, false), target.and(false, true))
        }
        assertEquals(listOf(true, false, true), results)
    }

    @Test
    fun `the and mutant short-circuits on a true left operand`() {
        val point = discover("&&")
        val result = withMutation(point) { target.and(true, false) }
        assertEquals(true, result)
        assertEquals(0, target.rightOperandEvaluations, "`a || b` must not evaluate b when a is true")
    }

    @Test
    fun `or is unchanged while the mutation is inactive`() {
        val results = MutationRegistry.withSession {
            listOf(target.or(true, false), target.or(false, true), target.or(false, false))
        }.first
        assertEquals(listOf(true, true, false), results)
        assertEquals(2, target.rightOperandEvaluations, "a true left operand must skip b")
    }

    @Test
    fun `the or mutant behaves as and`() {
        val point = discover("||")
        val results = withMutation(point) {
            listOf(target.or(true, true), target.or(true, false), target.or(false, true))
        }
        assertEquals(listOf(true, false, false), results)
    }

    @Test
    fun `the or mutant short-circuits on a false left operand`() {
        val point = discover("||")
        val result = withMutation(point) { target.or(false, true) }
        assertEquals(false, result)
        assertEquals(0, target.rightOperandEvaluations, "`a && b` must not evaluate b when a is false")
    }
}
