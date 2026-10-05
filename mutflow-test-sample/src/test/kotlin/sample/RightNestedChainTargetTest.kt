package sample

import io.github.anschnapp.mutflow.MutFlow
import io.github.anschnapp.mutflow.junit.MutFlowTest
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals

/**
 * Compiling this target is the regression: hoisting the left operand leaves the whole chain in
 * the copied operand of a right-nested `&&`, so this class only compiles with the fused
 * mutation form. The tests then kill every mutant of the chain.
 */
@MutFlowTest
class RightNestedChainTargetTest {

    private fun target(vararg overrides: Pair<Int, Int>): RightNestedChainTarget {
        val fields = IntArray(16) { it + 1 }
        overrides.forEach { (index, value) -> fields[index] = value }
        return RightNestedChainTarget(
            fields[0], fields[1], fields[2], fields[3], fields[4], fields[5], fields[6], fields[7],
            fields[8], fields[9], fields[10], fields[11], fields[12], fields[13], fields[14], fields[15]
        )
    }

    @Test
    fun `equal when every field matches`() {
        val equal = MutFlow.underTest { target() == target() }
        assertEquals(true, equal)
    }

    @Test
    fun `each field takes part in equality`() {
        val comparisons = MutFlow.underTest {
            List(16) { index -> target() == target(index to 0) }
        }
        comparisons.forEachIndexed { index, equal ->
            assertEquals(false, equal, "field ${index + 1} was ignored by equals")
        }
    }

    @Test
    fun `a different type is never equal`() {
        val equal = MutFlow.underTest { target() == ("not a target" as Any) }
        assertEquals(false, equal)
    }
}
