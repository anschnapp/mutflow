package io.github.anschnapp.mutflow.gradle

import io.github.anschnapp.mutflow.DiscoveredPoint
import io.github.anschnapp.mutflow.DiscoveryFileContent
import io.github.anschnapp.mutflow.ResultFileContent
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class NativeOrchestrationTest {

    private fun point(
        pointId: String,
        variantCount: Int = 2,
        sourceLocation: String = "Calculator.kt:5",
        originalOperator: String = ">",
        variantOperators: List<String> = listOf(">=", "<"),
        occurrenceOnLine: Int = 1
    ) = DiscoveredPoint(pointId, variantCount, sourceLocation, originalOperator, variantOperators, occurrenceOnLine)

    private fun result(touched: Boolean = true, timedOut: Boolean = false) =
        ResultFileContent(pointId = "p", variantIndex = 0, touched = touched, timedOut = timedOut)

    // ---- planMutations ----

    @Test
    fun planExpandsEveryVariantAndOrdersLikeMostLikelyStable() {
        // b is touched by fewer tests than a, so despite the alphabetical
        // order its variants must come first (same rule as the JVM path's
        // MostLikelyStable strategy).
        val discovery = DiscoveryFileContent(
            points = listOf(point("sample.A_0"), point("sample.B_0", variantCount = 3)),
            touchCounts = mapOf("sample.A_0" to 4, "sample.B_0" to 1)
        )

        val plan = NativeOrchestration.planMutations(discovery, Int.MAX_VALUE)

        assertEquals(
            listOf("sample.B_0:0", "sample.B_0:1", "sample.B_0:2", "sample.A_0:0", "sample.A_0:1"),
            plan.map { it.activeMutationValue }
        )
    }

    @Test
    fun planBreaksTouchCountTiesByPointIdThenVariantIndex() {
        val discovery = DiscoveryFileContent(
            points = listOf(point("sample.B_0"), point("sample.A_0")),
            touchCounts = mapOf("sample.A_0" to 2, "sample.B_0" to 2)
        )

        val plan = NativeOrchestration.planMutations(discovery, Int.MAX_VALUE)

        assertEquals(
            listOf("sample.A_0:0", "sample.A_0:1", "sample.B_0:0", "sample.B_0:1"),
            plan.map { it.activeMutationValue }
        )
    }

    @Test
    fun planHonorsMaxMutationRuns() {
        val discovery = DiscoveryFileContent(
            points = listOf(point("sample.A_0", variantCount = 5)),
            touchCounts = mapOf("sample.A_0" to 1)
        )

        assertEquals(2, NativeOrchestration.planMutations(discovery, 2).size)
        assertEquals(5, NativeOrchestration.planMutations(discovery, 5).size)
        assertEquals(5, NativeOrchestration.planMutations(discovery, 99).size)
    }

    // ---- display name ----

    @Test
    fun displayNameMatchesJvmFormat() {
        val plan = NativeOrchestration.planMutations(
            DiscoveryFileContent(
                points = listOf(point("sample.A_0", occurrenceOnLine = 2)),
                touchCounts = emptyMap()
            ),
            Int.MAX_VALUE
        )
        // Same format as MutFlowSession.getDisplayName, including the
        // occurrence suffix for repeated operators on one line.
        assertEquals("(Calculator.kt:5) > → >= #2", plan[0].displayName)
        assertEquals("(Calculator.kt:5) > → < #2", plan[1].displayName)
    }

    // ---- classifyRun ----

    @Test
    fun nonzeroExitMeansKilled() {
        val verdict = NativeOrchestration.classifyRun(1, result(), "sample.CalculatorTest.testBoundary")
        assertEquals(RunVerdict.Killed("sample.CalculatorTest.testBoundary"), verdict)
    }

    @Test
    fun zeroExitMeansSurvived() {
        assertEquals(
            RunVerdict.Survived(touched = true),
            NativeOrchestration.classifyRun(0, result(touched = true), null)
        )
        assertEquals(
            RunVerdict.Survived(touched = false),
            NativeOrchestration.classifyRun(0, result(touched = false), null)
        )
    }

    @Test
    fun missingResultFileStillCountsAsPlainSurvivorOnZeroExit() {
        assertEquals(
            RunVerdict.Survived(touched = true),
            NativeOrchestration.classifyRun(0, null, null)
        )
    }

    @Test
    fun timedOutFlagWinsOverExitCode() {
        assertEquals(
            RunVerdict.TimedOut,
            NativeOrchestration.classifyRun(1, result(timedOut = true), "some.Test")
        )
    }

    @Test
    fun killedProcessMeansTimedOut() {
        assertEquals(RunVerdict.TimedOut, NativeOrchestration.classifyRun(null, null, null))
    }

    // ---- parseFirstFailedTest ----

    @Test
    fun parsesFirstFailedTestFromGtestStyleOutput() {
        val output = """
            [==========] Running 4 tests from 1 test cases.
            [ RUN      ] spike.CalculatorTest.testIsPositive
            [       OK ] spike.CalculatorTest.testIsPositive (0 ms)
            [ RUN      ] spike.CalculatorTest.testBoundary
            [  FAILED  ] spike.CalculatorTest.testBoundary (2 ms)
            [ RUN      ] spike.CalculatorTest.testNegative
            [  FAILED  ] spike.CalculatorTest.testNegative (1 ms)
        """.trimIndent()

        assertEquals("spike.CalculatorTest.testBoundary", NativeOrchestration.parseFirstFailedTest(output))
    }

    @Test
    fun returnsNullWhenNoFailedLineExists() {
        assertNull(NativeOrchestration.parseFirstFailedTest("[       OK ] spike.CalculatorTest.testIsPositive"))
    }

    // ---- renderSummary ----

    @Test
    fun summaryCountsVerdictsAndListsDetails() {
        val plan = NativeOrchestration.planMutations(
            DiscoveryFileContent(
                points = listOf(point("sample.A_0", variantCount = 3, variantOperators = listOf(">=", "<", "=="))),
                touchCounts = mapOf("sample.A_0" to 1)
            ),
            Int.MAX_VALUE
        )
        val summary = NativeOrchestration.renderSummary(
            results = listOf(
                plan[0] to RunVerdict.Killed("sample.T.test1"),
                plan[1] to RunVerdict.Survived(touched = true),
                plan[2] to RunVerdict.TimedOut
            ),
            totalMutations = 4
        )

        assertTrue("Total mutations discovered:   4" in summary)
        assertTrue("Killed:                    1" in summary)
        assertTrue("Survived:                  1" in summary)
        assertTrue("Timed out:                 1" in summary)
        assertTrue("Remaining untested:           1" in summary)
        assertTrue("killed by: sample.T.test1" in summary)
        assertTrue("SURVIVED - no test caught this mutation!" in summary)
        assertTrue("TIMED OUT - likely causes an infinite loop" in summary)
        assertTrue("mutflow:falsePositive" in summary)
        assertTrue("mutflow:ignore" in summary)
    }

    @Test
    fun summaryFlagsUntouchedSurvivors() {
        val plan = NativeOrchestration.planMutations(
            DiscoveryFileContent(points = listOf(point("sample.A_0")), touchCounts = emptyMap()),
            Int.MAX_VALUE
        )
        val summary = NativeOrchestration.renderSummary(
            results = listOf(plan[0] to RunVerdict.Survived(touched = false)),
            totalMutations = 2
        )
        assertTrue("the mutated code was never executed!" in summary)
    }
}
