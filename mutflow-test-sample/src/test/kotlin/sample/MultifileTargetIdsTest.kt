package sample

import io.github.anschnapp.mutflow.MutFlow
import io.github.anschnapp.mutflow.Selection
import io.github.anschnapp.mutflow.Shuffle
import org.junit.jupiter.api.Test
import kotlin.test.assertTrue

/**
 * The parts of a `@file:JvmMultifileClass` facade each number their points from zero, so ids on
 * the shared facade name would collide and the two `<` here would be one mutant to a session.
 * Ids carry the part class instead.
 */
class MultifileTargetIdsTest {

    @Test
    fun `each part has ids of its own`() {
        val sessionId = MutFlow.createSession(Selection.MostLikelyStable, Shuffle.PerChange, Int.MAX_VALUE)
        val session = checkNotNull(MutFlow.getSession(sessionId))
        try {
            MutFlow.startRun(sessionId, 0)
            session.underTest {
                check(isBlankish("a") && !isSmall(10))
            }
            MutFlow.endRun(sessionId)

            val ids = session.getState().discoveredPoints.keys
            val stringPart = ids.filter { it.startsWith("sample.MultifileTarget__MultifileStringTargetKt_") }
            val numberPart = ids.filter { it.startsWith("sample.MultifileTarget__MultifileNumberTargetKt_") }
            assertTrue(stringPart.isNotEmpty(), "the string part has points of its own, got $ids")
            assertTrue(numberPart.isNotEmpty(), "the number part has points of its own, got $ids")
            assertTrue(stringPart.size + numberPart.size == ids.size, "every id names its part, got $ids")
        } finally {
            MutFlow.closeSession(sessionId)
        }
    }
}
