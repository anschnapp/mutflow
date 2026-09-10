package sample

import io.github.anschnapp.mutflow.MutFlow
import io.github.anschnapp.mutflow.MutationStatus
import io.github.anschnapp.mutflow.MutflowFiles
import io.github.anschnapp.mutflow.Selection
import io.github.anschnapp.mutflow.Shuffle
import io.github.anschnapp.mutflow.VerificationMode
import org.junit.jupiter.api.Test
import java.io.File
import java.nio.file.Files
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * What an ACCUMULATE results file holds for real mutated code. A session is driven by hand
 * against [Calculator.isPositive] with a single weak assertion, so some mutants are killed and
 * some survive, and the file is read back through the same parser the Gradle report task uses.
 */
class AccumulateResultsFileTest {

    @Test
    fun `the results file records every mutation the class reached with its verdict`() {
        val dir = Files.createTempDirectory("mutflow-accumulate").toFile()
        val calculator = Calculator()
        val sessionId = MutFlow.createSession(
            selection = Selection.MostLikelyStable,
            shuffle = Shuffle.PerChange,
            maxRuns = Int.MAX_VALUE,
            includeTargets = listOf(Calculator::class.java.name),
            verificationMode = VerificationMode.ACCUMULATE,
            testClassName = "sample.AccumulateResultsFileTest",
            resultsDirectory = dir.path
        )
        val session = checkNotNull(MutFlow.getSession(sessionId))
        try {
            MutFlow.startRun(sessionId, 0)
            assertTrue(session.underTest { calculator.isPositive(5) })
            MutFlow.endRun(sessionId)

            var run = 1
            while (true) {
                val mutation = MutFlow.selectMutationForRun(sessionId, run) ?: break
                MutFlow.startRun(sessionId, run, mutation)
                if (!session.underTest { calculator.isPositive(5) }) session.markTestFailed("isPositive(5)")
                session.recordMutationResult()
                MutFlow.endRun(sessionId)
                run++
            }
        } finally {
            MutFlow.closeSession(sessionId)
        }

        val content = MutflowFiles.parseSessionResultsJson(File(dir, "sample.AccumulateResultsFileTest.json").readText())
        dir.deleteRecursively()

        assertEquals("sample.AccumulateResultsFileTest", content.testClass)
        val byStatus = content.mutations.groupBy { it.status }
        val killed = byStatus[MutationStatus.KILLED].orEmpty()
        val survived = byStatus[MutationStatus.SURVIVED].orEmpty()
        assertTrue(killed.isNotEmpty(), "isPositive(5) catches at least one mutant, got $content")
        assertTrue(survived.isNotEmpty(), "one assertion cannot catch every mutant, got $content")
        assertTrue(killed.all { it.killedBy == listOf("isPositive(5)") })
        assertTrue(survived.all { it.killedBy.isEmpty() })
        assertTrue(content.mutations.all { it.pointId.startsWith("sample.Calculator_") }, "target filter applied")
    }
}
