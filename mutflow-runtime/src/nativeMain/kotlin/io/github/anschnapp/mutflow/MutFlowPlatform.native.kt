package io.github.anschnapp.mutflow

// Native actuals for MutFlowPlatform.kt (shared linux/mingw source set).
//
// The session-machinery actuals (session IDs, thread IDs, concurrent maps)
// collapse to trivial implementations here: on Native, `underTest {}` is
// served by the ProcessRun model and never reaches the session code paths.
// They still need real implementations because the session API is common
// code that compiles for every target.
//
// See mutflow-core's Platform.native.kt for the platform.posix background;
// core's helpers are `internal` to that module, so this module carries its
// own getenv binding.

import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.toKString
import kotlin.random.Random
import platform.posix.getenv

internal actual fun randomSessionIdValue(): String {
    // UUID-shaped random hex string. java.util.UUID does not exist here and
    // kotlin.uuid is still experimental, so we roll 32 hex digits ourselves;
    // uniqueness requirements are trivial (ids only need to differ within
    // one process).
    val hex = "0123456789abcdef"
    val chars = CharArray(36)
    for (i in chars.indices) {
        chars[i] = when (i) {
            8, 13, 18, 23 -> '-'
            else -> hex[Random.nextInt(16)]
        }
    }
    return chars.concatToString()
}

// kotlin-test on Native runs all tests sequentially on one thread, and the
// ProcessRun model never routes by thread anyway - a constant is correct.
internal actual fun currentThreadId(): Long = 0L

internal actual fun <K, V> threadSafeMutableMapOf(): MutableMap<K, V> = mutableMapOf()

// Default Random is seeded from system entropy on Native, which is exactly
// what Shuffle.PerRun wants ("new seed each run").
internal actual fun generateSeed(): Long = Random.nextLong()

// ---- ProcessRun resolution (the Native orchestration entry point) ----

@OptIn(ExperimentalForeignApi::class)
private fun envVar(name: String): String? = getenv(name)?.toKString()

private const val DEFAULT_TIMEOUT_MS = 60_000L

// Resolved once, at the first underTest call. `by lazy` instead of eager
// top-level init keeps binary startup free of mutflow work when tests never
// call underTest.
private val processRun: ProcessRun by lazy {
    val activeMutation = envVar("MUTFLOW_ACTIVE_MUTATION")?.takeIf { it.isNotBlank() }
    val discoveryFile = envVar("MUTFLOW_DISCOVERY_FILE")?.takeIf { it.isNotBlank() }

    val mode = when {
        activeMutation != null -> {
            if (discoveryFile != null) {
                // Orchestrator contract violation (should set one or the
                // other); warn and let the mutation run win, because silently
                // overwriting the discovery file of a previous run would be
                // the worse failure mode.
                println("[mutflow] WARNING: both MUTFLOW_ACTIVE_MUTATION and MUTFLOW_DISCOVERY_FILE are set; running in mutation mode")
            }
            // pointId may itself contain ':'-free dots but the variant index
            // never contains ':', so split on the LAST ':'.
            val sep = activeMutation.lastIndexOf(':')
            require(sep > 0 && sep < activeMutation.length - 1) {
                "MUTFLOW_ACTIVE_MUTATION must look like <pointId>:<variantIndex>, got: $activeMutation"
            }
            val pointId = activeMutation.substring(0, sep)
            val variantIndex = activeMutation.substring(sep + 1).toIntOrNull()
                ?: error("MUTFLOW_ACTIVE_MUTATION variant index is not a number: $activeMutation")
            val timeoutMs = envVar("MUTFLOW_TIMEOUT_MS")?.toLongOrNull() ?: DEFAULT_TIMEOUT_MS
            println("[mutflow] Mutation mode: $pointId variant $variantIndex (timeout ${timeoutMs}ms)")
            ProcessRunMode.Mutation(
                pointId = pointId,
                variantIndex = variantIndex,
                resultFilePath = envVar("MUTFLOW_RESULT_FILE")?.takeIf { it.isNotBlank() },
                timeoutMs = timeoutMs
            )
        }
        discoveryFile != null -> {
            println("[mutflow] Discovery mode: writing $discoveryFile")
            ProcessRunMode.Discovery(discoveryFile)
        }
        else -> ProcessRunMode.Inactive
    }
    ProcessRun(mode)
}

internal actual fun currentProcessRun(): ProcessRun? = processRun
