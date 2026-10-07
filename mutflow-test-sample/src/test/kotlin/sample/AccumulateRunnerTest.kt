package sample

import io.github.anschnapp.mutflow.MutFlow
import io.github.anschnapp.mutflow.MutFlowSession
import io.github.anschnapp.mutflow.MutationStatus
import io.github.anschnapp.mutflow.MutationTimedOutException
import io.github.anschnapp.mutflow.MutflowFiles
import io.github.anschnapp.mutflow.VerificationMode
import io.github.anschnapp.mutflow.junit4.MutFlowRunner
import io.github.anschnapp.mutflow.junit4.MutFlowTest
import org.junit.Ignore
import org.junit.jupiter.api.Test
import org.junit.runner.JUnitCore
import org.junit.runner.Request
import org.junit.runner.RunWith
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.junit.Test as JUnit4Test

/**
 * The JUnit 4 counterpart of [AccumulateExtensionTest]: under [MutFlowRunner], ACCUMULATE leaves
 * survivors to the report task but fails the class on a timed-out mutant, as every mode does, and
 * still writes the class's results file. The class below is run from here so its outcome can be asserted.
 */
class AccumulateRunnerTest {

    @Test
    fun `a timeout fails the class, survivors do not, and the verdicts go to the results file`() {
        val file = File(MutFlowSession.DEFAULT_RESULTS_DIRECTORY, "${AccumulateUnderRunner::class.java.name}.json")
        file.delete()

        val result = JUnitCore().run(Request.runner(MutFlowRunner(AccumulateUnderRunner::class.java)))

        val failure = result.failures.single()
        assertTrue(failure.exception is MutationTimedOutException, "only the timeout fails the class, got ${result.failures}")
        assertTrue("ready → !ready" in failure.description.displayName, "and it names the mutation, got ${failure.description}")
        assertTrue(file.isFile, "results file written to $file")
        val byStatus = MutflowFiles.parseSessionResultsJson(file.readText()).mutations.groupBy { it.status }
        assertTrue(
            byStatus[MutationStatus.TIMED_OUT].orEmpty().any { "ready → !ready" in it.displayName },
            "the parked mutant is recorded as timed out, got $byStatus"
        )
        assertTrue(byStatus[MutationStatus.SURVIVED].orEmpty().isNotEmpty(), "the weak assertion leaves survivors, got $byStatus")
        assertTrue(byStatus[MutationStatus.KILLED].orEmpty().isNotEmpty(), "and kills some, got $byStatus")
    }
}

/*
 * `@Ignore` keeps the vintage engine from running this as a test of its own; JUnitCore above is
 * handed the runner directly and does not consult it. A short slack keeps the parked mutant's wait short.
 */
@Ignore("driven by AccumulateRunnerTest")
@RunWith(MutFlowRunner::class)
@MutFlowTest(verificationMode = VerificationMode.ACCUMULATE, testBudgetFactor = 3, testBudgetSlackMs = 200)
class AccumulateUnderRunner {
    @JUnit4Test
    fun awaits() {
        assertTrue(MutFlow.underTest { WaitingTarget().awaitWhenReady(true) })
    }

    @JUnit4Test
    fun `isPositive is true for 5`() {
        assertTrue(MutFlow.underTest { Calculator().isPositive(5) })
    }
}
