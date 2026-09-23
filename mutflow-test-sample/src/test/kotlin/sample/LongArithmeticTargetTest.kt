package sample

import io.github.anschnapp.mutflow.MutFlow
import io.github.anschnapp.mutflow.junit.MutFlowTest
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals

/** Compiling this target is the regression; the tests then kill every mutant of the chains. */
@MutFlowTest
class LongArithmeticTargetTest {

    private val target = LongArithmeticTarget()

    @Test
    fun `sums sixteen terms`() {
        val result = MutFlow.underTest {
            target.sum(1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12, 13, 14, 15, 16)
        }
        assertEquals(136, result)
    }

    @Test
    fun `weights eight terms`() {
        // 1*2 + (2*3 - (3*4 + (4*5 - (5*6 + (6*7 - (7*8 + 8*9))))))
        val result = MutFlow.underTest { target.weighted(1, 2, 3, 4, 5, 6, 7, 8) }
        assertEquals(-80, result)
    }
}
