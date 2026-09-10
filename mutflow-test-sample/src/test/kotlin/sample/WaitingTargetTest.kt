package sample

import io.github.anschnapp.mutflow.MutFlow
import io.github.anschnapp.mutflow.MutFlowSession
import io.github.anschnapp.mutflow.MutationResult
import io.github.anschnapp.mutflow.MutationTimedOutException
import io.github.anschnapp.mutflow.Selection
import io.github.anschnapp.mutflow.Shuffle
import io.github.anschnapp.mutflow.TestBudget
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * End-to-end check of the per-test wall-clock budget against real mutated code: the
 * `if (ready)` inversion makes [WaitingTarget.awaitWhenReady] park forever, which no loop
 * guard can see. Drives a session by hand so the timeout can be asserted instead of
 * failing this class.
 */
class WaitingTargetTest {

    private val target = WaitingTarget()

    private fun MutFlowSession.runTestUnderTest(): Boolean =
        runTest("awaits") { underTest { target.awaitWhenReady(true) } }

    @Test
    fun `a mutation that parks the thread is interrupted and recorded as timed out`() {
        val sessionId = MutFlow.createSession(
            selection = Selection.MostLikelyStable,
            shuffle = Shuffle.PerChange,
            maxRuns = Int.MAX_VALUE,
            testBudget = TestBudget(factor = 3, slackMs = 200)
        )
        val session = checkNotNull(MutFlow.getSession(sessionId))
        try {
            MutFlow.startRun(sessionId, 0)
            assertTrue(session.runTestUnderTest())
            MutFlow.endRun(sessionId)

            var run = 1
            val timedOut = mutableListOf<String>()
            while (true) {
                val mutation = MutFlow.selectMutationForRun(sessionId, run) ?: break
                MutFlow.startRun(sessionId, run, mutation)
                try {
                    session.runTestUnderTest()
                } catch (_: MutationTimedOutException) {
                    session.markTestTimedOut()
                    timedOut += session.getDisplayName(mutation)
                } catch (_: AssertionError) {
                    session.markTestFailed("awaits")
                }
                session.recordMutationResult()
                MutFlow.endRun(sessionId)
                run++
            }

            val results = session.getSummary().results
            val inversion = results.entries.single { session.getDisplayName(it.key).endsWith("ready → !ready") }
            assertEquals(MutationResult.TimedOut, inversion.value, "the parked mutant was cut off by the budget")
            assertEquals(listOf(session.getDisplayName(inversion.key)), timedOut)
        } finally {
            MutFlow.closeSession(sessionId)
        }
    }
}
