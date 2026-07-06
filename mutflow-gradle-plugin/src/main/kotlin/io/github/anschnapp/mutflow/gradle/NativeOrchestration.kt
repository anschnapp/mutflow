package io.github.anschnapp.mutflow.gradle

import io.github.anschnapp.mutflow.DiscoveredPoint
import io.github.anschnapp.mutflow.DiscoveryFileContent
import io.github.anschnapp.mutflow.ResultFileContent

/**
 * Pure decision logic of the Native orchestration loop, kept free of Gradle
 * and process-execution concerns so it can be unit tested directly.
 *
 * The actual loop (exec the test binary once per mutation) lives in
 * [MutflowNativeTest]; everything that can be computed from plain values -
 * which mutations to run, what a finished run means, how the summary looks -
 * is here.
 */

/** One planned mutation run: a discovered point plus the variant to activate. */
internal data class PlannedMutation(
    val point: DiscoveredPoint,
    val variantIndex: Int,
    val touchCount: Int
) {
    /** Value for the MUTFLOW_ACTIVE_MUTATION environment variable. */
    val activeMutationValue: String
        get() = "${point.pointId}:$variantIndex"

    /**
     * Human-readable name, character-identical to the JVM path's
     * MutFlowSession.getDisplayName so survivor names stay copy-pasteable
     * across both paths.
     */
    val displayName: String
        get() {
            val variantOp = point.variantOperators.getOrNull(variantIndex) ?: "?"
            val occurrenceSuffix = if (point.occurrenceOnLine > 1) " #${point.occurrenceOnLine}" else ""
            return "(${point.sourceLocation}) ${point.originalOperator} → $variantOp$occurrenceSuffix"
        }
}

/** Outcome of a single mutation run, derived from exit code and result file. */
internal sealed class RunVerdict {
    /** A test failed: the mutation was caught. [killedBy] is the first failed test, if parseable. */
    data class Killed(val killedBy: String?) : RunVerdict()

    /**
     * All tests passed with the mutation active. [touched] false means the
     * mutated point was never even executed - usually a sign that no
     * underTest block covers that code, which is worth calling out
     * separately from a plain survivor.
     */
    data class Survived(val touched: Boolean) : RunVerdict()

    /** The run hit the in-process mutation deadline or the orchestrator's hard process timeout. */
    data object TimedOut : RunVerdict()
}

internal object NativeOrchestration {

    /**
     * Expands discovery output into the ordered list of mutation runs.
     *
     * The order is the MostLikelyStable strategy from the JVM path with the
     * same tie-breakers (fewest-touched point first, then pointId, then
     * variantIndex). With the default unlimited [maxMutationRuns] every
     * mutation is tested and the order is cosmetic; with a cap it decides
     * which mutations make the cut, and mutations touched by fewer tests are
     * the ones most likely to survive - testing those first maximizes the
     * information per run.
     */
    fun planMutations(discovery: DiscoveryFileContent, maxMutationRuns: Int): List<PlannedMutation> {
        val all = discovery.points.flatMap { point ->
            (0 until point.variantCount).map { variantIndex ->
                PlannedMutation(
                    point = point,
                    variantIndex = variantIndex,
                    touchCount = discovery.touchCounts[point.pointId] ?: 0
                )
            }
        }
        val ordered = all.sortedWith(
            compareBy({ it.touchCount }, { it.point.pointId }, { it.variantIndex })
        )
        return if (maxMutationRuns >= ordered.size) ordered else ordered.take(maxMutationRuns)
    }

    /**
     * Classifies a finished mutation run.
     *
     * This is the exit-code inversion at the heart of the Native design:
     * a FAILING test binary is the good outcome (the mutation was killed),
     * a PASSING one means the mutation survived.
     *
     * @param exitCode The process exit code, or null if the orchestrator had
     *   to kill the process because it exceeded the hard timeout
     * @param result Parsed result file, or null if the binary did not write
     *   one (e.g. it crashed before the first underTest block)
     * @param firstFailedTest First failed test parsed from the run output
     */
    fun classifyRun(exitCode: Int?, result: ResultFileContent?, firstFailedTest: String?): RunVerdict = when {
        exitCode == null -> RunVerdict.TimedOut
        result?.timedOut == true -> RunVerdict.TimedOut
        // Without a result file we cannot know whether the point was touched;
        // assume it was so the mutation still counts as a plain survivor.
        exitCode == 0 -> RunVerdict.Survived(touched = result?.touched ?: true)
        else -> RunVerdict.Killed(firstFailedTest)
    }

    /**
     * Extracts the first failed test name from a kotlin-test native run.
     * The native test runner prints GTest-style lines such as
     * `[  FAILED  ] spike.CalculatorTest.testBoundary (2 ms)`.
     */
    fun parseFirstFailedTest(output: String): String? =
        FAILED_LINE.find(output)?.groupValues?.get(1)

    private val FAILED_LINE = Regex("""\[\s*FAILED\s*]\s+(\S+)""")

    /**
     * Renders the summary box. Deliberately the same layout as the JVM
     * path's MutFlowSession.printSummary, so mutation reports look the same
     * no matter which path produced them.
     */
    fun renderSummary(
        results: List<Pair<PlannedMutation, RunVerdict>>,
        totalMutations: Int
    ): String = buildString {
        val killed = results.count { it.second is RunVerdict.Killed }
        val survived = results.count { it.second is RunVerdict.Survived }
        val timedOut = results.count { it.second is RunVerdict.TimedOut }
        val untested = totalMutations - results.size

        appendLine()
        appendLine("╔════════════════════════════════════════════════════════════════╗")
        appendLine("║                    MUTATION TESTING SUMMARY                    ║")
        appendLine("╠════════════════════════════════════════════════════════════════╣")
        appendLine("║  Total mutations discovered: ${totalMutations.toString().padStart(3)}                              ║")
        appendLine("║  Tested this run:            ${results.size.toString().padStart(3)}                              ║")
        appendLine("║  ├─ Killed:                  ${killed.toString().padStart(3)}  ✓                           ║")
        appendLine("║  ├─ Survived:                ${survived.toString().padStart(3)}  ${if (survived > 0) "✗" else "✓"}                           ║")
        appendLine("║  └─ Timed out:               ${timedOut.toString().padStart(3)}  ${if (timedOut > 0) "⏱" else "✓"}                           ║")
        appendLine("║  Remaining untested:         ${untested.toString().padStart(3)}                              ║")
        appendLine("╠════════════════════════════════════════════════════════════════╣")

        if (results.isNotEmpty()) {
            appendLine("║  DETAILS:                                                      ║")
            for ((mutation, verdict) in results) {
                val name = mutation.displayName
                when (verdict) {
                    is RunVerdict.Killed -> {
                        appendLine("║  ✓ ${name.padEnd(61)}║")
                        val testLine = "      killed by: ${verdict.killedBy ?: "unknown"}"
                        appendLine("║${testLine.take(64).padEnd(64)}║")
                    }
                    is RunVerdict.Survived -> {
                        appendLine("║  ✗ ${name.padEnd(61)}║")
                        if (verdict.touched) {
                            appendLine("║      SURVIVED - no test caught this mutation!${" ".repeat(19)}║")
                        } else {
                            appendLine("║      SURVIVED - the mutated code was never executed!${" ".repeat(12)}║")
                        }
                    }
                    is RunVerdict.TimedOut -> {
                        appendLine("║  ⏱ ${name.padEnd(61)}║")
                        appendLine("║      TIMED OUT - likely causes an infinite loop${" ".repeat(15)}║")
                    }
                }
            }
        }

        appendLine("╚════════════════════════════════════════════════════════════════╝")

        val survivors = results.filter { it.second is RunVerdict.Survived }
        if (survivors.isNotEmpty()) {
            appendLine()
            appendLine("Each survivor points at untested behavior: add a test that fails while")
            appendLine("the mutation is active, or suppress an equivalent mutant with a")
            appendLine("// mutflow:falsePositive comment on the affected line.")
        }
        if (timedOut > 0) {
            appendLine()
            appendLine("To skip timed-out mutations, add // mutflow:ignore on the affected lines.")
        }
    }
}
