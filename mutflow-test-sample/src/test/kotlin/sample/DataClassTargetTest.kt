package sample

import io.github.anschnapp.mutflow.MutFlow
import io.github.anschnapp.mutflow.Mutation
import io.github.anschnapp.mutflow.Selection
import io.github.anschnapp.mutflow.Shuffle
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Compiler-generated members carry no mutation points; the hand-written ones still do. */
class DataClassTargetTest {

    @Test
    fun `only the author's members are mutated`() {
        val sessionId = MutFlow.createSession(Selection.MostLikelyStable, Shuffle.PerChange, Int.MAX_VALUE)
        val session = checkNotNull(MutFlow.getSession(sessionId))
        try {
            MutFlow.startRun(sessionId, 0)
            session.underTest {
                val a = DataClassTarget(1, -2)
                val b = a.copy(y = 2)
                check(a != b && a == DataClassTarget(1, -2) && a.hashCode() == DataClassTarget(1, -2).hashCode())
                check(a.toString().isNotEmpty() && a.component1() == 1)
                check(!a.isOrigin && !a.isFar)
                check(!DelegatingDistance(a).isDistant())
                assertEquals(3, a.manhattan())
            }
            MutFlow.endRun(sessionId)

            val names = session.getState().discoveredPoints.keys.flatMap { pointId ->
                (0 until session.getState().discoveredPoints.getValue(pointId)).map { session.getDisplayName(Mutation(pointId, it)) }
            }
            // Line 19 is the author-written `get() = manhattan() > 10`, line 21 is manhattan().
            assertTrue(
                names.any { it.startsWith("(DataClassTarget.kt:19)") },
                "the hand-written accessor is mutated, got $names"
            )
            assertTrue(
                names.any { it.startsWith("(DataClassTarget.kt:21)") },
                "manhattan() is mutated, got $names"
            )
            assertTrue(
                names.all { it.startsWith("(DataClassTarget.kt:19)") || it.startsWith("(DataClassTarget.kt:21)") },
                "no point outside the hand-written members, got $names"
            )
        } finally {
            MutFlow.closeSession(sessionId)
        }
    }
}
