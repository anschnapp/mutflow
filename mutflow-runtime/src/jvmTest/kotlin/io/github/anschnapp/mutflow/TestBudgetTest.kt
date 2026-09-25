package io.github.anschnapp.mutflow

import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

class TestBudgetTest {

    @BeforeTest
    fun setup() {
        MutationRegistry.reset()
        MutFlow.reset()
    }

    @AfterTest
    fun cleanup() {
        // A leaked interrupt would surface as a spurious failure in whatever test runs next.
        assertFalse(Thread.interrupted(), "interrupt status leaked out of a budgeted test")
    }

    private fun checkPoint(pointId: String = "budget.Target_0"): Int? =
        MutationRegistry.check(pointId, 1, "Target.kt:1", "a", "b")

    private fun session(budget: TestBudget): MutFlowSession {
        val id = MutFlow.createSession(
            selection = Selection.MostLikelyStable,
            shuffle = Shuffle.PerChange,
            maxRuns = Int.MAX_VALUE,
            testBudget = budget
        )
        return checkNotNull(MutFlow.getSession(id))
    }

    /** Baseline run of one test that reaches a point and takes [sleepMs]. */
    private fun MutFlowSession.baseline(testId: String, sleepMs: Long) {
        MutFlow.startRun(id, 0)
        runTest(testId) { underTest { checkPoint(); Thread.sleep(sleepMs) } }
        MutFlow.endRun(id)
    }

    private fun MutFlowSession.startMutationRun(): Mutation {
        val mutation = checkNotNull(MutFlow.selectMutationForRun(id, 1))
        MutFlow.startRun(id, 1, mutation)
        return mutation
    }

    // ---- TestBudget arithmetic ----

    @Test
    fun `limit scales the baseline and adds slack`() {
        val budget = TestBudget(factor = 3, slackMs = 500)
        assertEquals(3 * 40 + 500L, budget.limitForMutationRun(40))
    }

    @Test
    fun `a test the baseline never saw falls back to the baseline timeout`() {
        val budget = TestBudget(factor = 3, slackMs = 500, baselineTimeoutMs = 7_000)
        assertEquals(7_000, budget.limitForMutationRun(null))
        assertEquals(7_000, budget.limitForBaseline())
    }

    @Test
    fun `factor zero disables every limit`() {
        assertFalse(TestBudget.DISABLED.enabled)
        assertEquals(0, TestBudget.DISABLED.limitForMutationRun(40))
        assertEquals(0, TestBudget.DISABLED.limitForBaseline())
    }

    @Test
    fun `negative values are rejected`() {
        assertFailsWith<IllegalArgumentException> { TestBudget(factor = -1) }
        assertFailsWith<IllegalArgumentException> { TestBudget(slackMs = -1) }
        assertFailsWith<IllegalArgumentException> { TestBudget(graceMs = -1) }
    }

    // ---- runTest ----

    @Test
    fun `a test within its budget runs normally and returns its value`() {
        val session = session(TestBudget(factor = 1, slackMs = 500))
        session.baseline("fast", sleepMs = 10)
        session.startMutationRun()

        val value = session.runTest("fast") { session.underTest { checkPoint(); 42 } }

        assertEquals(42, value)
        session.recordMutationResult()
        assertTrue(session.didMutationSurvive(), "no test failed, so the mutant survived")
    }

    @Test
    fun `a test exceeding its budget is interrupted and reported as timed out`() {
        val session = session(TestBudget(factor = 1, slackMs = 100))
        session.baseline("waits", sleepMs = 10)
        val mutation = session.startMutationRun()

        val start = System.nanoTime()
        val timeout = assertFailsWith<MutationTimedOutException> {
            session.runTest("waits") { session.underTest { checkPoint(); Thread.sleep(30_000) } }
        }
        val elapsedMs = (System.nanoTime() - start) / 1_000_000

        assertTrue(elapsedMs < 5_000, "interrupted well before the sleep ended, took $elapsedMs ms")
        assertIs<InterruptedException>(timeout.cause, "the interrupt the test saw is kept as the cause")
        assertTrue("'waits'" in timeout.message!!, timeout.message)
        assertTrue(session.getDisplayName(mutation) in timeout.message!!, timeout.message)
        assertFalse(MutationRegistry.hasActiveSession(), "the registry lock was released")

        session.markTestTimedOut()
        session.recordMutationResult()
        assertEquals(MutationResult.TimedOut, session.getSummary().results[mutation])
    }

    @Test
    fun `a test that swallows the interrupt but returns in time is still timed out`() {
        val session = session(TestBudget(factor = 1, slackMs = 100))
        session.baseline("swallows", sleepMs = 10)
        session.startMutationRun()

        assertFailsWith<MutationTimedOutException> {
            session.runTest("swallows") {
                try {
                    Thread.sleep(30_000)
                } catch (_: InterruptedException) {
                    // A test that recovers from the interrupt still exceeded its budget.
                }
                "done"
            }
        }
    }

    @Test
    fun `the interrupt repeats until the test returns`() {
        val session = session(TestBudget(factor = 1, slackMs = 100, graceMs = 0))
        session.baseline("stubborn", sleepMs = 10)
        session.startMutationRun()

        var interrupts = 0
        assertFailsWith<MutationTimedOutException> {
            session.runTest("stubborn") {
                while (interrupts < 3) {
                    try {
                        Thread.sleep(30_000)
                    } catch (_: InterruptedException) {
                        interrupts++
                    }
                }
            }
        }
        assertEquals(3, interrupts)
    }

    @Test
    fun `a test that ignores interruption past the grace period is abandoned`() {
        val session = session(TestBudget(factor = 1, slackMs = 100, graceMs = 300))
        session.baseline("ignores", sleepMs = 10)
        session.startMutationRun()

        val abandoned = CountDownLatch(1)
        var message: String? = null
        session.onTestAbandoned = { message = it; abandoned.countDown() }

        assertFailsWith<MutationTimedOutException> {
            session.runTest("ignores") {
                // Stands in for code that swallows the interrupt and keeps waiting.
                while (abandoned.count > 0) {
                    try {
                        Thread.sleep(5)
                    } catch (_: InterruptedException) {
                    }
                }
            }
        }

        assertTrue(abandoned.await(5, TimeUnit.SECONDS), "abandon handler ran")
        assertTrue("ABORT" in message!!, message)
        assertTrue("'ignores'" in message!!, message)
    }

    @Test
    fun `a disabled budget never interrupts`() {
        val session = session(TestBudget.DISABLED)
        session.baseline("slow", sleepMs = 10)
        session.startMutationRun()

        val value = session.runTest("slow") { Thread.sleep(150); "finished" }

        assertEquals("finished", value)
    }

    @Test
    fun `the baseline run is limited by the absolute baseline timeout`() {
        val session = session(TestBudget(baselineTimeoutMs = 100))
        MutFlow.startRun(session.id, 0)

        val timeout = assertFailsWith<MutationTimedOutException> {
            session.runTest("hangs in baseline") { Thread.sleep(30_000) }
        }
        assertTrue("baseline" in timeout.message!!, timeout.message)
        assertNull(session.getActiveMutation())
    }

    @Test
    fun `baseline durations add up over several runTest calls of one test`() {
        val session = session(TestBudget(factor = 1, slackMs = 0))
        MutFlow.startRun(session.id, 0)
        session.runTest("split") { session.underTest { checkPoint(); Thread.sleep(60) } }
        session.runTest("split") { Thread.sleep(60) }
        MutFlow.endRun(session.id)
        session.startMutationRun()

        // A budget of only one half (~60 ms) would cut this off; the sum (~120 ms) lets it through.
        val value = session.runTest("split") { Thread.sleep(90); "ok" }

        assertEquals("ok", value)
    }

    @Test
    fun `runTest requires an active run`() {
        val session = session(TestBudget())
        assertFailsWith<IllegalStateException> { session.runTest("t") { } }
    }

    // ---- environment overrides ----

    @Test
    fun `fromEnvironment keeps the base values when nothing is set`() {
        val base = TestBudget(factor = 5, slackMs = 7, baselineTimeoutMs = 9, graceMs = 11)
        assertEquals(base, TestBudget.fromEnvironment(base))
    }
}
