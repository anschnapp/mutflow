package sample

import io.github.anschnapp.mutflow.ActiveMutation
import io.github.anschnapp.mutflow.MutationRegistry
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Verifies the compiler-injected mutation points work on every KMP target.
 *
 * The baseline test proves the `MutationRegistry.check(...)` calls are injected
 * and executed; the active-mutation test proves a selected variant actually
 * changes behavior.
 */
class CalculatorTest {

    @Test
    fun baselineDiscoversMutationPoints() {
        val (_, session) = MutationRegistry.withSession {
            val calc = Calculator()
            calc.add(1, 2)
            calc.isPositive(5)
            calc.isInRange(50)
            calc.max(3, 1)
            calc.startsWithA("x")
            calc.normalized(" x ")
            calc.hasEven(listOf(1, 2, 3))
            calc.sameRef("a", "a")
            calc.notSameRef("a", "b")
            calc.greet(null)
            calc.lengthOf("abc")
            calc.emptyListReturn()
            calc.doubleThenSet(21)
        }
        assertTrue(
            session.mutationPointCount > 0,
            "expected mutation points, got ${session.mutationPointCount}"
        )
        // The string/collection operators must produce mutation points in a real
        // compilation (endsWith→startsWith, trim→"", filter→filterNot, isEmpty→isNotEmpty).
        val operators = session.discoveredPoints.map { it.originalOperator }.toSet()
        assertTrue(operators.any { it.startsWith("endsWith") }, "expected endsWith mutation, got: $operators")
        assertTrue(operators.contains("trim"), "expected trim mutation, got: $operators")
        assertTrue(operators.contains("filter"), "expected filter mutation, got: $operators")
        assertTrue(operators.any { it.startsWith("isNotEmpty") }, "expected isNotEmpty mutation, got: $operators")
        // The reference-equality / elvis / safe-call operators must also fire.
        assertTrue(operators.contains("==="), "expected === mutation, got: $operators")
        assertTrue(operators.contains("!=="), "expected !== mutation, got: $operators")
        assertTrue(operators.contains("?:"), "expected ?: (elvis) mutation, got: $operators")
        assertTrue(operators.contains("?."), "expected ?. (safe-call) mutation, got: $operators")
        // The empty-collection return fires on the listOf return (its point is labeled
        // "return ...", shared with ObjectReturn; the emptyList variant is asserted in
        // the compiler-plugin unit test). Confirm a return point exists for the line.
        assertTrue(operators.contains("return ..."), "expected a return mutation, got: $operators")
        // The assign-const operator fires on the `result = x * 2` assignment.
        assertTrue(operators.contains("="), "expected assign-const (=) mutation, got: $operators")
    }

    @Test
    fun activeMutationChangesBehavior() {
        // Discover the points first.
        val (_, baseline) = MutationRegistry.withSession {
            Calculator().add(1, 2)
        }
        val point = baseline.discoveredPoints.first()

        // Activate variant 0 of the first point and re-run.
        val (result, _) = MutationRegistry.withSession(ActiveMutation(point.pointId, 0)) {
            Calculator().add(1, 2)
        }

        // add(1,2) is 3 normally; the first mutation point on this line is the
        // arithmetic swap (+ → -), so the active mutant must differ.
        assertEquals(-1, result, "active mutation should change add(1,2) from 3 to -1")
    }
}
