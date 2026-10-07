package io.github.anschnapp.mutflow.gradle

import io.github.anschnapp.mutflow.MutationRecord
import io.github.anschnapp.mutflow.MutationStatus
import io.github.anschnapp.mutflow.SessionResultsContent
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ResultsMergeTest {

    private fun record(
        pointId: String,
        variantIndex: Int = 0,
        status: MutationStatus,
        displayName: String = "(${pointId.substringAfterLast('.').substringBefore('_')}.kt:${pointId.last()}) > → >=",
        killedBy: List<String> = emptyList()
    ) = MutationRecord(pointId, variantIndex, displayName, status, killedBy)

    private fun session(testClass: String, vararg records: MutationRecord) =
        SessionResultsContent(testClass, records.toList())

    // ---- merge ----

    @Test
    fun killedInAnyClassWins() {
        val results = ResultsMerge.merge(
            listOf(
                session("t.ScreenTest", record("p.Model_1", status = MutationStatus.SURVIVED)),
                session("t.ModelTest", record("p.Model_1", status = MutationStatus.KILLED, killedBy = listOf("rejects"))),
                session("t.OtherScreenTest", record("p.Model_1", status = MutationStatus.UNTESTED))
            )
        )

        val mutation = results.mutations.single()
        assertEquals(MutationStatus.KILLED, mutation.status)
        assertEquals(listOf("t.ModelTest", "t.OtherScreenTest", "t.ScreenTest"), mutation.reachedBy)
        assertEquals(mapOf("t.ModelTest" to listOf("rejects")), mutation.killedBy)
        assertEquals(1, results.killed)
        assertEquals(0, results.survived)
    }

    @Test
    fun timeoutCountsAsKilledAndIsRemembered() {
        val results = ResultsMerge.merge(
            listOf(
                session("t.A", record("p.Loop_1", status = MutationStatus.SURVIVED)),
                session("t.B", record("p.Loop_1", status = MutationStatus.TIMED_OUT))
            )
        )

        val mutation = results.mutations.single()
        assertEquals(MutationStatus.TIMED_OUT, mutation.status)
        assertEquals(listOf("t.B"), mutation.timedOutIn)
        assertEquals(1, results.killed)
        assertEquals(1, results.timedOut)
        assertEquals(0, results.survived)
    }

    @Test
    fun survivedOnlyWhenNoClassKilledItAndSomeClassRanIt() {
        val results = ResultsMerge.merge(
            listOf(
                session("t.A", record("p.Model_1", status = MutationStatus.SURVIVED)),
                session("t.B", record("p.Model_1", status = MutationStatus.UNTESTED)),
                session("t.C", record("p.Model_2", status = MutationStatus.UNTESTED))
            )
        )

        assertEquals(
            listOf(MutationStatus.SURVIVED, MutationStatus.UNTESTED),
            results.mutations.map { it.status }
        )
        assertEquals(1, results.survived)
        assertEquals(1, results.untested)
    }

    @Test
    fun variantsOfOnePointAreSeparateMutations() {
        val results = ResultsMerge.merge(
            listOf(
                session(
                    "t.A",
                    record("p.Model_1", variantIndex = 0, status = MutationStatus.KILLED),
                    record("p.Model_1", variantIndex = 1, status = MutationStatus.SURVIVED)
                )
            )
        )

        assertEquals(listOf(0, 1), results.mutations.map { it.variantIndex })
        assertEquals(listOf(MutationStatus.KILLED, MutationStatus.SURVIVED), results.mutations.map { it.status })
    }

    @Test
    fun mutationsAreOrderedByProductionClassThenLine() {
        val results = ResultsMerge.merge(
            listOf(
                session(
                    "t.A",
                    record("p.Zeta_1", status = MutationStatus.KILLED, displayName = "(Zeta.kt:30) > → >="),
                    record("p.Alpha_2", status = MutationStatus.KILLED, displayName = "(Alpha.kt:12) > → >="),
                    record("p.Alpha_1", status = MutationStatus.KILLED, displayName = "(Alpha.kt:9) > → >=")
                )
            )
        )

        assertEquals(listOf("p.Alpha_1", "p.Alpha_2", "p.Zeta_1"), results.mutations.map { it.pointId })
        assertEquals("p.Alpha", results.mutations.first().productionClass)
        assertEquals(9, results.mutations.first().line)
    }

    // ---- rendering ----

    @Test
    fun reportListsSurvivorsWithTheClassesThatReachedThem() {
        val results = ResultsMerge.merge(
            listOf(
                session("t.ScreenTest", record("p.Model_1", status = MutationStatus.SURVIVED, displayName = "(Model.kt:1) > → >=")),
                session("t.ModelTest", record("p.Model_1", status = MutationStatus.SURVIVED, displayName = "(Model.kt:1) > → >="),
                    record("p.Model_2", status = MutationStatus.KILLED, displayName = "(Model.kt:2) a → b"))
            )
        )

        val report = ResultsMerge.renderReport(results)

        assertTrue("| Mutations | 2 |" in report, report)
        assertTrue("| Killed | 1 |" in report, report)
        assertTrue("| Survived | 1 |" in report, report)
        assertTrue("| Score | 50% |" in report, report)
        assertTrue("### p.Model (1 of 2)" in report, report)
        assertTrue("| (Model.kt:1) > → >= | t.ModelTest, t.ScreenTest |" in report, report)
        assertTrue("| p.Model | 2 | 1 | 1 | 0 | 50% |" in report, report)
    }

    @Test
    fun reportWithoutSurvivorsHasNoSurvivorSection() {
        val results = ResultsMerge.merge(listOf(session("t.A", record("p.M_1", status = MutationStatus.KILLED))))

        val report = ResultsMerge.renderReport(results)

        assertTrue("## Survivors" !in report, report)
        assertTrue("| Score | 100% |" in report, report)
    }

    @Test
    fun scoreIsNotAvailableWithoutTestedMutations() {
        val results = ResultsMerge.merge(listOf(session("t.A", record("p.M_1", status = MutationStatus.UNTESTED))))

        assertTrue("| Score | n/a |" in ResultsMerge.renderReport(results))
    }

    @Test
    fun consoleSummaryNamesEverySurvivor() {
        val results = ResultsMerge.merge(
            listOf(session("t.A", record("p.M_1", status = MutationStatus.SURVIVED, displayName = "(M.kt:1) > → >=")))
        )

        val summary = ResultsMerge.renderConsoleSummary(results, "/build/report.md")

        assertTrue("1 survived" in summary, summary)
        assertTrue("[mutflow] SURVIVED (M.kt:1) > → >= (reached by t.A)" in summary, summary)
        assertTrue("/build/report.md" in summary, summary)
    }

    // ---- verdict ----

    @Test
    fun survivorsFailTheBuildOnlyWhenAsked() {
        val results = ResultsMerge.merge(
            listOf(session("t.A", record("p.M_1", status = MutationStatus.SURVIVED, displayName = "(M.kt:1) > → >=")))
        )

        val message = ResultsMerge.buildFailureMessage(results, failOnSurvivors = true)
        assertTrue(message!!.contains("1 mutation(s) survived"), message)
        assertTrue(message.contains("(M.kt:1) > → >="), message)
        assertNull(ResultsMerge.buildFailureMessage(results, failOnSurvivors = false))
    }

    @Test
    fun timeoutsAloneDoNotFailTheBuild() {
        val results = ResultsMerge.merge(listOf(session("t.A", record("p.M_1", status = MutationStatus.TIMED_OUT))))

        assertNull(ResultsMerge.buildFailureMessage(results, failOnSurvivors = true))
    }
}
