package sample

import io.github.anschnapp.mutflow.MutFlow
import io.github.anschnapp.mutflow.junit.MutFlowTest
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals

/** Regression for the do-while body scope: compiling this target used to crash the plugin. */
@MutFlowTest
class DoWhileTargetTest {

    private val target = DoWhileTarget()

    @Test
    fun `drains positive values until the first null`() {
        val result = MutFlow.underTest { target.drain(listOf(3, 0, 1, -1, 5, null, 7).iterator()) }
        assertEquals(listOf(3, 1, 5), result)
    }

    @Test
    fun `an immediately exhausted source yields nothing`() {
        assertEquals(emptyList(), MutFlow.underTest { target.drain(emptyList<Int?>().iterator()) })
    }
}
