package sample

import io.github.anschnapp.mutflow.MutFlow
import io.github.anschnapp.mutflow.MutationRegistry
import io.github.anschnapp.mutflow.Selection
import io.github.anschnapp.mutflow.Shuffle
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Regression test: a target class containing a range `for` loop must compile and its loop body
 * must still be mutated. Before the fix the compiler crashed on this class.
 */
class RangeLoopTargetTest {

    private val target = RangeLoopTarget()

    @BeforeTest
    fun setup() {
        MutationRegistry.reset()
        MutFlow.reset()
    }

    @Test
    fun `range for loop compiles and is mutated`() {
        val result = MutFlow.underTest(run = 0, selection = Selection.MostLikelyStable, shuffle = Shuffle.PerChange) {
            target.sumBelow(4)
        }
        assertEquals(6, result)

        val points = MutFlow.getRegistryState().discoveredPoints.filter { it.key.contains("RangeLoopTarget") }
        assertTrue(points.isNotEmpty(), "Expected mutation points inside the loop body")
    }
}
