package io.github.anschnapp.mutflow

import java.time.Duration
import kotlin.test.*

class SessionTimeoutTest {

    @BeforeTest
    fun setup() {
        MutationRegistry.reset()
        MutFlow.reset()
    }

    private fun simulateMutationPoint(): Int? =
        MutationRegistry.check("point-a", 2, "Test.kt:1", ">", ">=,<,==")

    // Helper that runs a baseline and returns the session id, leaving the session open.
    private fun sessionWithBaseline(timeout: Duration): SessionId {
        val sessionId = MutFlow.createSession(
            selection = Selection.MostLikelyStable,
            shuffle = Shuffle.PerChange,
            maxRuns = 10,
            timeout = timeout
        )
        MutFlow.startRun(sessionId, 0)
        MutFlow.underTest { simulateMutationPoint() }
        MutFlow.endRun(sessionId)
        return sessionId
    }

    @Test
    fun `checkTimeout is a no-op when no session is active`() {
        // Should never throw without an active session
        MutationRegistry.checkTimeout()
    }

    @Test
    fun `baseline run never times out even with a very short timeout configured`() {
        // Baseline uses withSession(activeMutation = null), so no deadline is set —
        // the timeout parameter only applies to mutation runs.
        val sessionId = MutFlow.createSession(
            selection = Selection.MostLikelyStable,
            shuffle = Shuffle.PerChange,
            maxRuns = 10,
            timeout = Duration.ofMillis(1)
        )

        MutFlow.startRun(sessionId, 0)
        MutFlow.underTest {
            simulateMutationPoint()
            Thread.sleep(10) // sleep well past the 1 ms configured timeout
            MutationRegistry.checkTimeout() // must not throw
        }
        MutFlow.endRun(sessionId)

        MutFlow.closeSession(sessionId)
    }

    @Test
    fun `mutation run throws MutationTimedOutException when deadline is exceeded`() {
        val sessionId = sessionWithBaseline(Duration.ofMillis(1))

        val mutation = MutFlow.selectMutationForRun(sessionId, 1)!!
        MutFlow.startRun(sessionId, 1, mutation)

        assertFailsWith<MutationTimedOutException> {
            MutFlow.underTest {
                Thread.sleep(10) // exceed the 1 ms deadline
                MutationRegistry.checkTimeout()
            }
        }

        MutFlow.endRun(sessionId)
        MutFlow.closeSession(sessionId)
    }

    @Test
    fun `mutation run with generous timeout completes without timing out`() {
        val sessionId = sessionWithBaseline(Duration.ofSeconds(30))

        val mutation = MutFlow.selectMutationForRun(sessionId, 1)!!
        MutFlow.startRun(sessionId, 1, mutation)

        // Multiple checkTimeout calls should be fine within the deadline
        MutFlow.underTest {
            repeat(5) { MutationRegistry.checkTimeout() }
        }

        MutFlow.endRun(sessionId)
        MutFlow.closeSession(sessionId)
    }

    @Test
    fun `timed-out mutation is recorded as TimedOut in summary`() {
        val sessionId = sessionWithBaseline(Duration.ofMillis(1))

        val mutation = MutFlow.selectMutationForRun(sessionId, 1)!!
        MutFlow.startRun(sessionId, 1, mutation)
        val session = MutFlow.getSession(sessionId)!!

        try {
            MutFlow.underTest {
                Thread.sleep(10)
                MutationRegistry.checkTimeout()
            }
        } catch (_: MutationTimedOutException) {
            // Simulates what MutFlowExtension.TestExecutionExceptionHandler does
            session.markTestTimedOut()
        }

        session.recordMutationResult()
        MutFlow.endRun(sessionId)

        val summary = session.getSummary()
        assertEquals(1, summary.timedOut)
        assertEquals(0, summary.survived)
        assertEquals(0, summary.killed)

        MutFlow.closeSession(sessionId)
    }

    @Test
    fun `timed-out mutation does not count as survived`() {
        val sessionId = sessionWithBaseline(Duration.ofMillis(1))

        val mutation = MutFlow.selectMutationForRun(sessionId, 1)!!
        MutFlow.startRun(sessionId, 1, mutation)
        val session = MutFlow.getSession(sessionId)!!

        try {
            MutFlow.underTest {
                Thread.sleep(10)
                MutationRegistry.checkTimeout()
            }
        } catch (_: MutationTimedOutException) {
            session.markTestTimedOut()
        }

        assertFalse(session.didMutationSurvive(), "A timed-out mutation must not be considered survived")

        MutFlow.endRun(sessionId)
        MutFlow.closeSession(sessionId)
    }

    @Test
    fun `multiple checkTimeout calls before deadline do not throw`() {
        val sessionId = sessionWithBaseline(Duration.ofSeconds(30))

        val mutation = MutFlow.selectMutationForRun(sessionId, 1)!!
        MutFlow.startRun(sessionId, 1, mutation)

        MutFlow.underTest {
            // Simulates a loop body checked many times — all within the deadline
            repeat(100) { MutationRegistry.checkTimeout() }
        }

        MutFlow.endRun(sessionId)
        MutFlow.closeSession(sessionId)
    }
}