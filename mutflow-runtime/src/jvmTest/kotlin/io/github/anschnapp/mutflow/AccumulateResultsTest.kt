package io.github.anschnapp.mutflow

import java.io.File
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class AccumulateResultsTest {

    private lateinit var dir: File

    @BeforeTest
    fun setup() {
        MutationRegistry.reset()
        MutFlow.reset()
        dir = Files.createTempDirectory("mutflow-results").toFile()
    }

    @AfterTest
    fun cleanup() {
        dir.deleteRecursively()
    }

    private fun checkPoint(pointId: String, variants: Int = 2): Int? =
        MutationRegistry.check(pointId, variants, "Target.kt:${pointId.last()}", ">", ">=,<")

    private fun createSession(mode: VerificationMode, testClassName: String = "sample.TargetTest"): MutFlowSession {
        val id = MutFlow.createSession(
            selection = Selection.MostLikelyStable,
            shuffle = Shuffle.PerChange,
            maxRuns = Int.MAX_VALUE,
            verificationMode = mode,
            testClassName = testClassName,
            resultsDirectory = File(dir, "nested").path
        )
        return checkNotNull(MutFlow.getSession(id))
    }

    /** Baseline reaching two points, then every mutation: variant 0 of point 1 is killed, the rest survive. */
    private fun runEverything(session: MutFlowSession) {
        MutFlow.startRun(session.id, 0)
        session.underTest { checkPoint("t.Target_1"); checkPoint("t.Target_2") }
        MutFlow.endRun(session.id)

        var run = 1
        while (true) {
            val mutation = MutFlow.selectMutationForRun(session.id, run) ?: break
            MutFlow.startRun(session.id, run, mutation)
            session.underTest {
                val variant = checkPoint("t.Target_1")
                checkPoint("t.Target_2")
                if (variant == 0) session.markTestFailed("catches it")
            }
            session.recordMutationResult()
            MutFlow.endRun(session.id)
            run++
        }
    }

    @Test
    fun `ACCUMULATE writes one results file per test class when the session closes`() {
        val session = createSession(VerificationMode.ACCUMULATE)
        runEverything(session)

        MutFlow.closeSession(session.id)

        val file = File(dir, "nested/sample.TargetTest.json")
        assertTrue(file.isFile, "results file created, directory included")
        val content = MutflowFiles.parseSessionResultsJson(file.readText())
        assertEquals("sample.TargetTest", content.testClass)
        assertEquals(
            listOf(
                MutationRecord("t.Target_1", 0, "(Target.kt:1) > → >=", MutationStatus.KILLED, listOf("catches it")),
                MutationRecord("t.Target_1", 1, "(Target.kt:1) > → <", MutationStatus.SURVIVED),
                MutationRecord("t.Target_2", 0, "(Target.kt:2) > → >=", MutationStatus.SURVIVED),
                MutationRecord("t.Target_2", 1, "(Target.kt:2) > → <", MutationStatus.SURVIVED)
            ),
            content.mutations
        )
    }

    @Test
    fun `mutations the class reached but never ran are recorded as untested`() {
        val session = createSession(VerificationMode.ACCUMULATE)
        MutFlow.startRun(session.id, 0)
        session.underTest { checkPoint("t.Target_1") }
        MutFlow.endRun(session.id)

        val records = session.collectResults()

        assertEquals(listOf(MutationStatus.UNTESTED, MutationStatus.UNTESTED), records.map { it.status })
    }

    @Test
    fun `other modes write nothing`() {
        val session = createSession(VerificationMode.LENIENT)
        runEverything(session)

        MutFlow.closeSession(session.id)

        assertFalse(File(dir, "nested").exists())
    }

    @Test
    fun `a session without a test class name warns instead of writing`() {
        val session = createSession(VerificationMode.ACCUMULATE, testClassName = "")
        runEverything(session)

        MutFlow.closeSession(session.id)

        assertFalse(File(dir, "nested").exists())
    }
}
