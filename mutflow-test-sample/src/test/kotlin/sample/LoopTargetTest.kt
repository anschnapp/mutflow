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

/**
 * The `i + 1` → `i - 1` mutant makes [LoopTarget.countTo] loop forever. Its test's budget runs
 * out long before the loop guard's own 20 s timeout, and the tight loop never looks at the
 * interrupt, so the budget has to end it through the loop guard; otherwise the grace period
 * expires and the test JVM exits. Drives a session by hand so the timeout can be asserted
 * instead of failing this class.
 */
class LoopTargetTest {

    private val target = LoopTarget()

    private fun MutFlowSession.runTestUnderTest() =
        runTest("counts") { assertEquals(5L, underTest { target.countTo(5) }) }

    @Test
    fun `a mutation that loops past its budget is recorded as timed out`() {
        val sessionId = MutFlow.createSession(
            selection = Selection.MostLikelyStable,
            shuffle = Shuffle.PerChange,
            maxRuns = Int.MAX_VALUE,
            timeoutMs = 20_000,
            testBudget = TestBudget(factor = 3, slackMs = 200, graceMs = 3_000)
        )
        val session = checkNotNull(MutFlow.getSession(sessionId))
        try {
            MutFlow.startRun(sessionId, 0)
            session.runTestUnderTest()
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
                    session.markTestFailed("counts")
                }
                session.recordMutationResult()
                MutFlow.endRun(sessionId)
                run++
            }

            val results = session.getSummary().results
            val endless = results.entries.single { session.getDisplayName(it.key).endsWith("+ → -") }
            assertEquals(MutationResult.TimedOut, endless.value, "the endless loop was cut off at the budget")
            assertEquals(listOf(session.getDisplayName(endless.key)), timedOut)
        } finally {
            MutFlow.closeSession(sessionId)
        }
    }
}
