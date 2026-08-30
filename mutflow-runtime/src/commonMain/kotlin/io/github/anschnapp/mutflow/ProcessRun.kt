package io.github.anschnapp.mutflow

/**
 * The process-level run model used by the Native orchestration path.
 *
 * TEACHING NOTE: how this relates to the JVM machinery in this module.
 * On the JVM, JUnit re-runs a test class N times inside ONE process, so
 * MutFlow needs sessions, run counters, and thread-to-session routing to keep
 * those in-process runs apart. On Native there is no in-process re-run
 * mechanism at all; instead the Gradle orchestrator (Phase 3) launches the
 * test binary once per run and tells it what to do via environment variables:
 *
 *   MUTFLOW_DISCOVERY_FILE=<path>              -> Discovery mode
 *   MUTFLOW_ACTIVE_MUTATION=<pointId>:<index>  -> Mutation mode
 *   (neither set)                              -> Inactive mode
 *
 * Inactive mode is also what the JVM uses for the stock test task of a
 * multiplatform jvm() target, selected by MUTFLOW_INACTIVE=true. That is the
 * only MUTFLOW_* variable the JVM reads.
 *
 * One process = one run = one [ProcessRun] instance, resolved once from the
 * environment (see MutFlowPlatform.native.kt). `MutFlow.underTest {}`
 * delegates here on Native and never touches the session machinery; on the
 * JVM `currentProcessRun()` is null except in that one pass-through case, so
 * the JVM run loop is untouched.
 *
 * The class itself is common (not native-only) so its logic can be unit
 * tested on the JVM: the file-writing side effects are injected, and the
 * environment parsing lives in the platform actual, not here.
 */
internal sealed class ProcessRunMode {

    /** No mutflow env vars set: `underTest {}` is a transparent pass-through. */
    data object Inactive : ProcessRunMode()

    /** Baseline run: collect mutation points + touch counts, write them to [discoveryFilePath]. */
    data class Discovery(val discoveryFilePath: String) : ProcessRunMode()

    /** Mutation run: activate exactly this mutation inside every `underTest {}` block. */
    data class Mutation(
        val pointId: String,
        val variantIndex: Int,
        /** Where to write the result JSON, or null if the orchestrator did not ask for one. */
        val resultFilePath: String?,
        /** Per-underTest timeout for infinite-loop protection (MUTFLOW_TIMEOUT_MS). */
        val timeoutMs: Long
    ) : ProcessRunMode()
}

internal class ProcessRun(
    private val mode: ProcessRunMode,
    // Injectable writers so commonTest can capture structured output without
    // any file IO. The defaults are the real serializers from mutflow-core.
    private val writeDiscovery: (path: String, points: List<DiscoveredPoint>, touchCounts: Map<String, Int>) -> Unit =
        MutflowFiles::writeDiscoveryFile,
    private val writeResult: (path: String, pointId: String, variantIndex: Int, touched: Boolean, timedOut: Boolean) -> Unit =
        MutflowFiles::writeResultFile
) {

    // ---- Discovery mode state (accumulated across underTest calls) ----
    // mutableMapOf preserves insertion order on every Kotlin target
    // (LinkedHashMap semantics), so the discovery file lists points in the
    // order they were first hit - same order the JVM path prints them.
    private val discoveredPoints = mutableMapOf<String, DiscoveredPoint>()
    private val touchCounts = mutableMapOf<String, Int>()

    // ---- Mutation mode state (accumulated across underTest calls) ----
    private var touched = false
    private var timedOut = false

    fun <T> underTest(block: () -> T): T = when (mode) {
        is ProcessRunMode.Inactive -> block()
        is ProcessRunMode.Discovery -> runDiscovery(mode, block)
        is ProcessRunMode.Mutation -> runMutation(mode, block)
    }

    private fun <T> runDiscovery(mode: ProcessRunMode.Discovery, block: () -> T): T {
        // One registry session per underTest block, exactly like the JVM
        // baseline (MutFlowSession.executeBaseline): that is what makes a
        // "touch count" mean "number of underTest blocks that hit the point".
        val (result, sessionResult) = MutationRegistry.withSession(activeMutation = null) {
            block()
        }

        for (point in sessionResult.discoveredPoints) {
            val isNew = point.pointId !in discoveredPoints
            discoveredPoints[point.pointId] = point
            touchCounts[point.pointId] = (touchCounts[point.pointId] ?: 0) + 1
            if (isNew) {
                println("[mutflow] Discovered mutation point: (${point.sourceLocation}) ${point.originalOperator} with ${point.variantCount} variants")
            }
        }

        // Rewrite the whole file after every underTest call. This is
        // deliberately dumb: it is idempotent, needs no process shutdown hook
        // (Kotlin/Native has no reliable equivalent of a JVM shutdown hook),
        // and a crash mid-suite still leaves a valid file with everything
        // discovered so far. The files are tiny; performance is irrelevant.
        writeDiscovery(mode.discoveryFilePath, discoveredPoints.values.toList(), touchCounts.toMap())

        return result
    }

    private fun <T> runMutation(mode: ProcessRunMode.Mutation, block: () -> T): T {
        val active = ActiveMutation(pointId = mode.pointId, variantIndex = mode.variantIndex)

        // runCatching inside the session, instead of try/catch around it:
        // when a test kills the mutation, the assertion error flies out of
        // [block], but we still need this session's discovered points to know
        // whether the active point was actually reached (withSession offers
        // no result on the exception path - it just rethrows).
        val (outcome, sessionResult) = MutationRegistry.withSession(
            activeMutation = active,
            timeoutMs = mode.timeoutMs
        ) {
            runCatching(block)
        }

        if (sessionResult.discoveredPoints.any { it.pointId == mode.pointId }) {
            touched = true
        }
        if (outcome.exceptionOrNull() is MutationTimedOutException) {
            timedOut = true
        }

        // Same rewrite-every-time strategy as discovery; touched/timedOut
        // accumulate across all underTest blocks of the process.
        if (mode.resultFilePath != null) {
            writeResult(mode.resultFilePath, mode.pointId, mode.variantIndex, touched, timedOut)
        }

        // Rethrows the test failure (= the kill signal that makes the process
        // exit nonzero), or returns the block's value if the mutation survived
        // this particular test.
        return outcome.getOrThrow()
    }
}
