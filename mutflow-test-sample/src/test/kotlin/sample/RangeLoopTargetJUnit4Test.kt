package sample

import io.github.anschnapp.mutflow.junit4.MutFlowRunner
import io.github.anschnapp.mutflow.junit4.MutFlowTest
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.test.assertEquals

/**
 * Whole-method wrapping: the tests never call `MutFlow.underTest`, the runner wraps each of them.
 */
@RunWith(MutFlowRunner::class)
@MutFlowTest(wrapTestMethods = true)
class RangeLoopTargetJUnit4Test {

    private val target = RangeLoopTarget()

    @Test
    fun `sums the numbers below n`() {
        assertEquals(6, target.sumBelow(4))
    }

    @Test
    fun `sum below zero and one is zero`() {
        assertEquals(0, target.sumBelow(0))
        assertEquals(0, target.sumBelow(1))
    }
}
