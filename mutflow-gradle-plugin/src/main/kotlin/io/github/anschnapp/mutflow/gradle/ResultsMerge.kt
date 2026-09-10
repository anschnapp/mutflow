package io.github.anschnapp.mutflow.gradle

import io.github.anschnapp.mutflow.MutationStatus
import io.github.anschnapp.mutflow.SessionResultsContent

/**
 * Pure merge and rendering logic of the ACCUMULATE report, kept free of
 * Gradle so it can be unit tested. The task around it is [MutflowReport].
 *
 * The merge answers the question a single test class cannot: was this
 * mutant caught by *any* test? A production class is often exercised by
 * several test classes (a view model by its own test and by every screen
 * test that drives it), and a mutant surviving one of them means nothing
 * until every class that reached it has spoken.
 */

/** One mutation with the verdicts of every test class that reached it, merged. */
internal data class MergedMutation(
    val pointId: String,
    val variantIndex: Int,
    val displayName: String,
    val status: MutationStatus,
    /** Test classes whose baseline reached the mutation point. */
    val reachedBy: List<String>,
    /** Test class to the tests in it that failed with the mutation active. */
    val killedBy: Map<String, List<String>>,
    /** Test classes in which the mutation hit a timeout. */
    val timedOutIn: List<String>
) {
    /** Production class the mutation sits in; point IDs are `ClassName_N`. */
    val productionClass: String
        get() = pointId.substringBeforeLast('_')

    /** Source line from the display name `(File.kt:line) ...`, for ordering. */
    val line: Int
        get() = displayName.substringAfter(':', "").substringBefore(')').toIntOrNull() ?: 0
}

internal data class MergedResults(
    val testClasses: List<String>,
    val mutations: List<MergedMutation>
) {
    val killed: Int get() = mutations.count { it.status == MutationStatus.KILLED || it.status == MutationStatus.TIMED_OUT }
    val timedOut: Int get() = mutations.count { it.status == MutationStatus.TIMED_OUT }
    val survived: Int get() = mutations.count { it.status == MutationStatus.SURVIVED }
    val untested: Int get() = mutations.count { it.status == MutationStatus.UNTESTED }
    val survivors: List<MergedMutation> get() = mutations.filter { it.status == MutationStatus.SURVIVED }
}

internal object ResultsMerge {

    /**
     * Merges per-class results. A mutation is identified by point ID and
     * variant, which is stable as long as every class ran against the same
     * compiled code - true within one Gradle invocation, which is the
     * precondition of this mode.
     *
     * Verdict precedence: killed by any class wins; a timeout in any class
     * counts as killed (the mutant changed behaviour observably, even if no
     * assertion named it); otherwise a survivor if any class actually ran it;
     * otherwise untested.
     */
    fun merge(sessions: List<SessionResultsContent>): MergedResults {
        val byMutation = linkedMapOf<Pair<String, Int>, MutableList<Pair<String, io.github.anschnapp.mutflow.MutationRecord>>>()
        for (session in sessions.sortedBy { it.testClass }) {
            for (record in session.mutations) {
                byMutation.getOrPut(record.pointId to record.variantIndex) { mutableListOf() }.add(session.testClass to record)
            }
        }
        val merged = byMutation.map { (key, seen) ->
            val statuses = seen.map { it.second.status }
            MergedMutation(
                pointId = key.first,
                variantIndex = key.second,
                displayName = seen.first().second.displayName,
                status = when {
                    MutationStatus.KILLED in statuses -> MutationStatus.KILLED
                    MutationStatus.TIMED_OUT in statuses -> MutationStatus.TIMED_OUT
                    MutationStatus.SURVIVED in statuses -> MutationStatus.SURVIVED
                    else -> MutationStatus.UNTESTED
                },
                reachedBy = seen.map { it.first },
                killedBy = seen.filter { it.second.status == MutationStatus.KILLED }
                    .associate { it.first to it.second.killedBy },
                timedOutIn = seen.filter { it.second.status == MutationStatus.TIMED_OUT }.map { it.first }
            )
        }.sortedWith(compareBy({ it.productionClass }, { it.line }, { it.displayName }, { it.variantIndex }))
        return MergedResults(
            testClasses = sessions.map { it.testClass }.sorted(),
            mutations = merged
        )
    }

    /** The markdown report written to the build directory. */
    fun renderReport(results: MergedResults): String = buildString {
        appendLine("# Mutation testing report")
        appendLine()
        appendLine("| | |")
        appendLine("|---|---|")
        appendLine("| Test classes | ${results.testClasses.size} |")
        appendLine("| Mutations | ${results.mutations.size} |")
        appendLine("| Killed | ${results.killed}${if (results.timedOut > 0) " (${results.timedOut} by timeout)" else ""} |")
        appendLine("| Survived | ${results.survived} |")
        appendLine("| Untested | ${results.untested} |")
        appendLine("| Score | ${score(results.killed, results.survived)} |")

        val survivors = results.survivors
        if (survivors.isNotEmpty()) {
            appendLine()
            appendLine("## Survivors")
            appendLine()
            appendLine("Each one is behaviour no test asserts. Add a test that fails while the mutation")
            appendLine("is active, or mark an equivalent mutant with `// mutflow:falsePositive`.")
            for ((productionClass, group) in survivors.groupBy { it.productionClass }) {
                val total = results.mutations.count { it.productionClass == productionClass }
                appendLine()
                appendLine("### $productionClass (${group.size} of $total)")
                appendLine()
                appendLine("| Mutation | Reached by |")
                appendLine("|---|---|")
                for (mutation in group) {
                    appendLine("| ${mutation.displayName.escapeCell()} | ${mutation.reachedBy.joinToString(", ").escapeCell()} |")
                }
            }
        }

        val timedOut = results.mutations.filter { it.status == MutationStatus.TIMED_OUT }
        if (timedOut.isNotEmpty()) {
            appendLine()
            appendLine("## Timed out (counted as killed)")
            appendLine()
            appendLine("The mutant made a test hang or loop; add `// mutflow:ignore` on the line if that is expected.")
            appendLine()
            for (mutation in timedOut) {
                appendLine("- ${mutation.displayName} in ${mutation.timedOutIn.joinToString(", ")}")
            }
        }

        appendLine()
        appendLine("## Per production class")
        appendLine()
        appendLine("| Class | Mutations | Killed | Survived | Untested | Score |")
        appendLine("|---|---|---|---|---|---|")
        for ((productionClass, group) in results.mutations.groupBy { it.productionClass }) {
            val killed = group.count { it.status == MutationStatus.KILLED || it.status == MutationStatus.TIMED_OUT }
            val survived = group.count { it.status == MutationStatus.SURVIVED }
            val untested = group.count { it.status == MutationStatus.UNTESTED }
            appendLine("| $productionClass | ${group.size} | $killed | $survived | $untested | ${score(killed, survived)} |")
        }
    }

    /** The few lines printed to the build log. */
    fun renderConsoleSummary(results: MergedResults, reportPath: String): String = buildString {
        appendLine("[mutflow] Merged ${results.testClasses.size} test class(es): ${results.mutations.size} mutations, " +
            "${results.killed} killed" + (if (results.timedOut > 0) " (${results.timedOut} by timeout)" else "") +
            ", ${results.survived} survived, ${results.untested} untested, score ${score(results.killed, results.survived)}")
        for (survivor in results.survivors) {
            appendLine("[mutflow] SURVIVED ${survivor.displayName} (reached by ${survivor.reachedBy.joinToString(", ")})")
        }
        appendLine("[mutflow] Report: $reportPath")
    }

    /** The build failure, or null if the build passes. */
    fun buildFailureMessage(results: MergedResults, failOnSurvivors: Boolean): String? {
        if (!failOnSurvivors || results.survived == 0) return null
        return "mutflow: ${results.survived} mutation(s) survived every test class that reached them:\n" +
            results.survivors.joinToString("\n") { "  - ${it.displayName}" } +
            "\nAdd tests that catch them, or set mutflow { failOnSurvivors = false } to report only."
    }

    private fun score(killed: Int, survived: Int): String =
        if (killed + survived == 0) "n/a" else "${killed * 100 / (killed + survived)}%"

    private fun String.escapeCell(): String = replace("|", "\\|")
}
