package sample

import io.github.anschnapp.mutflow.ActiveMutation
import io.github.anschnapp.mutflow.MutFlow
import io.github.anschnapp.mutflow.MutationRegistry
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

/**
 * A null left operand from Java must fail `&&` and `||` exactly as in uninstrumented Kotlin.
 * The fused mutation once compared the left operand with `!=`, which accepted the null, so
 * `null && true` returned true even with no mutation active.
 */
class PlatformBooleanTest {

    private val target = PlatformBooleanTarget()

    @BeforeTest
    fun setup() {
        MutationRegistry.reset()
        MutFlow.reset()
    }

    private fun assertBothThrow() {
        assertFailsWith<NullPointerException> { target.and(true) }
        assertFailsWith<NullPointerException> { target.or(false) }
    }

    @Test
    fun `a null left operand throws outside a session`() {
        assertBothThrow()
    }

    @Test
    fun `a null left operand throws while the mutation is inactive`() {
        MutationRegistry.withSession { assertBothThrow() }
    }

    @Test
    fun `a null left operand throws in the mutant`() {
        // check() runs before the operand, so both points register although both calls throw.
        val points = MutationRegistry.withSession { assertBothThrow() }.second.discoveredPoints
            .filter { it.pointId.contains("PlatformBooleanTarget") }
        assertEquals(setOf("&&", "||"), points.map { it.originalOperator }.toSet())

        for (point in points) {
            MutationRegistry.withSession(ActiveMutation(point.pointId, 0)) { assertBothThrow() }
        }
    }
}
