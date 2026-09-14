package sample

import io.github.anschnapp.mutflow.MutFlow
import io.github.anschnapp.mutflow.Mutation
import io.github.anschnapp.mutflow.Selection
import io.github.anschnapp.mutflow.Shuffle
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Generated data class members carry no mutation points; the hand-written member still does. */
class DataClassTargetTest {

    @Test
    fun `only the author's member is mutated`() {
        val sessionId = MutFlow.createSession(Selection.MostLikelyStable, Shuffle.PerChange, Int.MAX_VALUE)
        val session = checkNotNull(MutFlow.getSession(sessionId))
        try {
            MutFlow.startRun(sessionId, 0)
            session.underTest {
                val a = DataClassTarget(1, -2)
                val b = a.copy(y = 2)
                check(a != b && a == DataClassTarget(1, -2) && a.hashCode() == DataClassTarget(1, -2).hashCode())
                check(a.toString().isNotEmpty() && a.component1() == 1)
                assertEquals(3, a.manhattan())
            }
            MutFlow.endRun(sessionId)

            val names = session.getState().discoveredPoints.keys.flatMap { pointId ->
                (0 until session.getState().discoveredPoints.getValue(pointId)).map { session.getDisplayName(Mutation(pointId, it)) }
            }
            assertTrue(names.isNotEmpty(), "manhattan() has mutation points")
            assertTrue(names.all { it.startsWith("(DataClassTarget.kt:13)") }, "every point is on the manhattan() line, got $names")
        } finally {
            MutFlow.closeSession(sessionId)
        }
    }
}
