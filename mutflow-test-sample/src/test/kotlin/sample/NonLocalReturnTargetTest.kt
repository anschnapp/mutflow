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
 * Regression test: a non-local `return it` inside `?.let { }` must keep returning the value.
 * Before the fix the baseline run threw ClassCastException (Integer cannot be cast to Void).
 */
class NonLocalReturnTargetTest {

    private val target = NonLocalReturnTarget()

    @BeforeTest
    fun setup() {
        MutationRegistry.reset()
        MutFlow.reset()
    }

    @Test
    fun `non-local return keeps its value and is mutated`() {
        val result = MutFlow.underTest(run = 0, selection = Selection.MostLikelyStable, shuffle = Shuffle.PerChange) {
            target.firstPresent(null, 7)
        }
        assertEquals(7, result)

        val points = MutFlow.getRegistryState().discoveredPoints.filter { it.key.contains("NonLocalReturnTarget") }
        assertTrue(points.isNotEmpty(), "Expected nullable-return mutation points")
    }
}
