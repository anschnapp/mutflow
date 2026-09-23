package sample

import io.github.anschnapp.mutflow.MutFlow
import io.github.anschnapp.mutflow.junit.MutFlowTest
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals

/** Compiling this target is the regression; the tests then kill every mutant of the chain. */
@MutFlowTest
class WideEqualityTargetTest {

    private fun target(vararg overrides: Pair<Int, Int>): WideEqualityTarget {
        val fields = IntArray(16) { it + 1 }
        overrides.forEach { (index, value) -> fields[index] = value }
        return WideEqualityTarget(
            fields[0], fields[1], fields[2], fields[3], fields[4], fields[5], fields[6], fields[7],
            fields[8], fields[9], fields[10], fields[11], fields[12], fields[13], fields[14], fields[15]
        )
    }

    @Test
    fun `equal when every field matches`() {
        assertEquals(target(), MutFlow.underTest { target() })
    }

    @Test
    fun `each field takes part in equality`() {
        MutFlow.underTest {
            repeat(16) { index -> assertNotEquals(target(), target(index to 0), "field ${index + 1}") }
        }
    }

    @Test
    fun `a different type is never equal`() {
        MutFlow.underTest { assertNotEquals<Any>(target(), "not a target") }
    }
}
