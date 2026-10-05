package sample

import io.github.anschnapp.mutflow.MutFlow
import io.github.anschnapp.mutflow.junit.MutFlowTest
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals

/** The pattern route to a file's top-level functions; see the sample build script for the pattern. */
@MutFlowTest
class PatternTopLevelTargetTest {

    @Test
    fun `sign of positive, zero and negative values`() {
        assertEquals(1, MutFlow.underTest { sign(5) })
        assertEquals(1, MutFlow.underTest { sign(1) })
        assertEquals(0, MutFlow.underTest { sign(0) })
        assertEquals(-1, MutFlow.underTest { sign(-1) })
        assertEquals(-1, MutFlow.underTest { sign(-3) })
    }
}
