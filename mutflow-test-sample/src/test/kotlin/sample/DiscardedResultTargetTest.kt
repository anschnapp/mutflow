package sample

import io.github.anschnapp.mutflow.MutFlow
import io.github.anschnapp.mutflow.Mutation
import io.github.anschnapp.mutflow.MutationRegistry
import io.github.anschnapp.mutflow.Selection
import io.github.anschnapp.mutflow.Shuffle
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Boolean inversion must skip calls whose result is discarded, and only those.
 * Mutations are identified by their display names, e.g. `(DiscardedResultTarget.kt:16) add() → !add()`.
 */
class DiscardedResultTargetTest {

    private val target = DiscardedResultTarget()

    @BeforeTest
    fun setup() {
        MutationRegistry.reset()
        MutFlow.reset()
    }

    /** Runs [block] as a baseline and returns the display names of every discovered mutation. */
    private fun discoveredMutations(block: () -> Unit): List<String> {
        val sessionId = MutFlow.createSession(Selection.MostLikelyStable, Shuffle.PerChange, maxRuns = Int.MAX_VALUE)
        val session = MutFlow.getSession(sessionId)!!
        try {
            MutFlow.startRun(sessionId, 0, null)
            session.underTest(block)
            MutFlow.endRun(sessionId)
            return session.getState().discoveredPoints
                .filterKeys { it.contains("DiscardedResultTarget") }
                .flatMap { (pointId, variants) -> (0 until variants).map { session.getDisplayName(Mutation(pointId, it)) } }
        } finally {
            MutFlow.closeSession(sessionId)
        }
    }

    private fun List<String>.inversions() = filter { it.contains("add() → !add()") }

    @Test
    fun `discarded add is not inverted`() {
        val mutations = discoveredMutations { target.addDiscarded(1) }
        assertEquals(emptyList(), mutations.inversions(), "A discarded boolean result must not get an inversion point")
    }

    @Test
    fun `used add keeps its inversion`() {
        val mutations = discoveredMutations { target.addUsed(1) }
        assertEquals(1, mutations.inversions().size, "Expected the inversion point, got $mutations")
    }

    @Test
    fun `comparison inside a discarded add is still mutated`() {
        val mutations = discoveredMutations { target.addComparison(1) }
        assertTrue(mutations.any { it.contains("> → ") }, "The > inside the argument must still be mutated, got $mutations")
        assertEquals(emptyList(), mutations.inversions(), "No inversion point for the discarded add itself")
    }

    @Test
    fun `discarded add inside a lambda is not inverted`() {
        val mutations = discoveredMutations { target.addAllDiscarded(listOf(1, 2)) }
        assertEquals(emptyList(), mutations.inversions(), "Unit-coerced statement in a lambda body is discarded too")
    }
}
