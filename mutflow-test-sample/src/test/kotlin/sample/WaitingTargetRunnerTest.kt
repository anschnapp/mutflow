package sample

import io.github.anschnapp.mutflow.MutFlow
import io.github.anschnapp.mutflow.MutationTimedOutException
import io.github.anschnapp.mutflow.VerificationMode
import io.github.anschnapp.mutflow.junit4.MutFlowRunner
import io.github.anschnapp.mutflow.junit4.MutFlowTest
import org.junit.Ignore
import org.junit.jupiter.api.Test
import org.junit.runner.JUnitCore
import org.junit.runner.Request
import org.junit.runner.RunWith
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.junit.Test as JUnit4Test

/**
 * The JUnit 4 counterpart of [WaitingTargetTest]: the runner itself has to put every test method
 * under the budget, whether the test calls `underTest` or is wrapped whole. The classes below are
 * run from here and their outcome asserted, so the timed-out mutation does not fail this class.
 */
class WaitingTargetRunnerTest {

    @Test
    fun `the runner cuts off a mutation that parks the test thread`() {
        for (testClass in listOf(ExplicitUnderTest::class.java, WrappedTestMethods::class.java)) {
            val name = testClass.simpleName
            val result = JUnitCore().run(Request.runner(MutFlowRunner(testClass)))

            val timedOut = result.failures.filter { it.exception is MutationTimedOutException }
            assertEquals(1, timedOut.size, "$name: exactly the parked mutant times out, got ${result.failures}")
            val displayName = timedOut.single().description.displayName
            assertTrue(displayName.startsWith("Mutation: "), "$name: reported as a mutation run, got $displayName")
            assertTrue(displayName.contains("ready → !ready"), "$name: the inverted condition is the one cut off, got $displayName")
            assertEquals(timedOut, result.failures, "$name: nothing else fails in LENIENT mode")
        }
    }
}

/*
 * `@Ignore` keeps the vintage engine from running these as tests of their own; the runner is
 * handed to JUnitCore directly above, which does not consult the annotation. A short slack keeps
 * the parked mutant's wait short.
 */
@Ignore("driven by WaitingTargetRunnerTest")
@RunWith(MutFlowRunner::class)
@MutFlowTest(verificationMode = VerificationMode.LENIENT, testBudgetFactor = 3, testBudgetSlackMs = 200)
class ExplicitUnderTest {
    @JUnit4Test
    fun awaits() {
        assertTrue(MutFlow.underTest { WaitingTarget().awaitWhenReady(true) })
    }
}

@Ignore("driven by WaitingTargetRunnerTest")
@RunWith(MutFlowRunner::class)
@MutFlowTest(verificationMode = VerificationMode.LENIENT, testBudgetFactor = 3, testBudgetSlackMs = 200, wrapTestMethods = true)
class WrappedTestMethods {
    @JUnit4Test
    fun awaits() {
        assertTrue(WaitingTarget().awaitWhenReady(true))
    }
}
