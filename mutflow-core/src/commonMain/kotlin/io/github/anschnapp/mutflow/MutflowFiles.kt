package io.github.anschnapp.mutflow

/**
 * Serialization of the discovery/result files used by the Native
 * orchestration path (DESIGN-KOTLIN-NATIVE.md).
 *
 * On Native, one process = one run: the Gradle orchestrator launches the test
 * binary once for discovery and once per mutation, and these files are how
 * the binary reports back across the process boundary (the JVM path never
 * needs them - everything stays in memory there).
 *
 * The JSON is hand-rolled on purpose: mutflow's runtime has zero external
 * dependencies today, and pulling in kotlinx.serialization just to write two
 * small fixed-schema files (parsed later by the Gradle plugin on the JVM,
 * Phase 3) would be a heavy trade. The builders are pure String functions so
 * they can be unit-tested in commonTest without any file IO.
 *
 * Both formats carry a formatVersion so the Phase 3 parser can detect a
 * mismatch between the plugin and runtime versions instead of misreading.
 */
object MutflowFiles {

    /** Bumped whenever the schema of either file changes incompatibly. */
    const val FORMAT_VERSION: Int = 1

    /**
     * Builds the discovery file content.
     *
     * @param points All mutation points discovered during the baseline run,
     *   in discovery order
     * @param touchCounts How many `underTest {}` blocks touched each point
     *   (same meaning as the JVM baseline's touch counts; feeds the
     *   MostLikely* selection strategies in the Gradle orchestrator)
     */
    fun buildDiscoveryJson(
        points: List<DiscoveredPoint>,
        touchCounts: Map<String, Int>
    ): String = buildString {
        append("{\"formatVersion\":").append(FORMAT_VERSION)
        append(",\"points\":[")
        for ((index, point) in points.withIndex()) {
            if (index > 0) append(',')
            // One point per line keeps the file diffable/readable when
            // debugging an orchestration run by hand.
            append('\n')
            append("{\"pointId\":").append(jsonString(point.pointId))
            append(",\"variantCount\":").append(point.variantCount)
            append(",\"sourceLocation\":").append(jsonString(point.sourceLocation))
            append(",\"originalOperator\":").append(jsonString(point.originalOperator))
            append(",\"variantOperators\":[")
            for ((opIndex, op) in point.variantOperators.withIndex()) {
                if (opIndex > 0) append(',')
                append(jsonString(op))
            }
            append(']')
            append(",\"occurrenceOnLine\":").append(point.occurrenceOnLine)
            append(",\"touchCount\":").append(touchCounts[point.pointId] ?: 0)
            append('}')
        }
        if (points.isNotEmpty()) append('\n')
        append("]}")
        append('\n')
    }

    /**
     * Builds the per-mutation result file content.
     *
     * The mutation verdict itself (killed/survived) is decided by the Gradle
     * orchestrator from the process exit code; this file adds what the exit
     * code cannot express:
     *
     * @param touched Whether the active mutation point was reached at all -
     *   lets the orchestrator distinguish "survived" from "mutation never
     *   executed" (which usually points at a test-selection problem)
     * @param timedOut Whether a run hit the mutation timeout (likely an
     *   infinite loop caused by the mutation); the process still exits
     *   nonzero, but the orchestrator can report it as TIMED_OUT instead
     *   of KILLED
     */
    fun buildResultJson(
        pointId: String,
        variantIndex: Int,
        touched: Boolean,
        timedOut: Boolean
    ): String = buildString {
        append("{\"formatVersion\":").append(FORMAT_VERSION)
        append(",\"pointId\":").append(jsonString(pointId))
        append(",\"variantIndex\":").append(variantIndex)
        append(",\"touched\":").append(touched)
        append(",\"timedOut\":").append(timedOut)
        append("}\n")
    }

    /** Builds and writes the discovery file (overwrites any existing file). */
    fun writeDiscoveryFile(
        path: String,
        points: List<DiscoveredPoint>,
        touchCounts: Map<String, Int>
    ) {
        writeTextFile(path, buildDiscoveryJson(points, touchCounts))
    }

    /** Builds and writes the result file (overwrites any existing file). */
    fun writeResultFile(
        path: String,
        pointId: String,
        variantIndex: Int,
        touched: Boolean,
        timedOut: Boolean
    ) {
        writeTextFile(path, buildResultJson(pointId, variantIndex, touched, timedOut))
    }

    /**
     * Encodes [value] as a JSON string literal, including the surrounding
     * quotes. Escapes the two mandatory characters (`"` and `\`) plus all
     * control characters below U+0020, per the JSON spec. Operator strings
     * are benign today, but source locations contain user file names, so
     * correctness here is not optional.
     */
    internal fun jsonString(value: String): String = buildString(value.length + 2) {
        append('"')
        for (ch in value) {
            when {
                ch == '"' -> append("\\\"")
                ch == '\\' -> append("\\\\")
                ch == '\n' -> append("\\n")
                ch == '\r' -> append("\\r")
                ch == '\t' -> append("\\t")
                ch < ' ' -> {
                    // Remaining control chars as \u00XX (code is always < 0x20,
                    // so two hex digits padded to four).
                    append("\\u")
                    append(ch.code.toString(16).padStart(4, '0'))
                }
                else -> append(ch)
            }
        }
        append('"')
    }
}
