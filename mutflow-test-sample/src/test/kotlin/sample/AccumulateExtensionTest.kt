package sample

import io.github.anschnapp.mutflow.MutFlow
import io.github.anschnapp.mutflow.MutFlowSession
import io.github.anschnapp.mutflow.MutationStatus
import io.github.anschnapp.mutflow.MutationTimedOutException
import io.github.anschnapp.mutflow.MutflowFiles
import io.github.anschnapp.mutflow.VerificationMode
import io.github.anschnapp.mutflow.junit.MutFlowTest
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.junit.platform.engine.TestExecutionResult
import org.junit.platform.engine.discovery.DiscoverySelectors.selectClass
import org.junit.platform.testkit.engine.EngineTestKit
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * ACCUMULATE under the JUnit 6 extension: survivors are left to the report task, but a timed-out
 * mutant fails the class right away, as in every other mode, so the line can be marked
 * `// mutflow:ignore`. The results file is still written. The class below is tagged out of the
 * module's own test run and executed from here so its outcome can be asserted.
 */
class AccumulateExtensionTest {

    @Test
    fun `a timeout fails the class, survivors do not, and the verdicts go to the results file`() {
        val file = File(MutFlowSession.DEFAULT_RESULTS_DIRECTORY, "${AccumulateUnderExtension::class.java.name}.json")
        file.delete()

        val failures = EngineTestKit.engine("junit-jupiter")
            .selectors(selectClass(AccumulateUnderExtension::class.java))
            .execute()
            .testEvents()
            .failed()
            .list()
            .map { it.getPayload(TestExecutionResult::class.java).get().throwable.get() }

        assertEquals(1, failures.size, "only the timeout fails the class, got $failures")
        assertTrue(failures.single() is MutationTimedOutException, "got $failures")
        assertTrue("ready → !ready" in failures.single().message.orEmpty(), "and it names the mutation, got ${failures.single().message}")
        assertTrue(file.isFile, "results file written to $file")
        val byStatus = MutflowFiles.parseSessionResultsJson(file.readText()).mutations.groupBy { it.status }
        assertTrue(byStatus[MutationStatus.TIMED_OUT].orEmpty().any { "ready → !ready" in it.displayName }, "got $byStatus")
        assertTrue(byStatus[MutationStatus.SURVIVED].orEmpty().isNotEmpty(), "the weak assertion leaves survivors, got $byStatus")
    }
}

@Tag("fixture")
@MutFlowTest(verificationMode = VerificationMode.ACCUMULATE, testBudgetFactor = 3, testBudgetSlackMs = 200)
class AccumulateUnderExtension {
    @Test
    fun awaits() {
        assertTrue(MutFlow.underTest { WaitingTarget().awaitWhenReady(true) })
    }

    @Test
    fun `isPositive is true for 5`() {
        assertTrue(MutFlow.underTest { Calculator().isPositive(5) })
    }
}
