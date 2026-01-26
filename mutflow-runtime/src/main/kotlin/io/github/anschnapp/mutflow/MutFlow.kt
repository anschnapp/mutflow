package io.github.anschnapp.mutflow

/**
 * Main entry point for mutation testing in tests.
 *
 * Wraps the code under test and manages mutation sessions.
 */
object MutFlow {

    // Storage for baseline info per testId
    private val baselines = mutableMapOf<String, Baseline>()

    // Seed for Shuffle.EachTime mode - generated once per JVM
    private var globalSeed: Long? = null

    /**
     * High-level API for mutation testing.
     *
     * @param testId Identifier for this test case
     * @param run Run number: 0 = baseline/discovery, 1+ = mutation runs
     * @param shuffle How mutations are selected across runs
     * @param block The code under test
     * @return The result of the block
     * @throws IllegalStateException if run > 0 but no baseline exists for testId
     */
    fun <T> underTest(
        testId: String,
        run: Int,
        shuffle: Shuffle,
        block: () -> T
    ): T {
        require(run >= 0) { "run must be non-negative, got: $run" }

        return if (run == 0) {
            executeBaseline(testId, shuffle, block)
        } else {
            executeMutationRun(testId, run, shuffle, block)
        }
    }

    private fun <T> executeBaseline(
        testId: String,
        shuffle: Shuffle,
        block: () -> T
    ): T {
        // Run discovery (no active mutation)
        val result = underTest(testId = testId, activeMutation = null, block = block)

        // Store baseline
        baselines[testId] = Baseline(
            discoveredPoints = result.discovered,
            shuffle = shuffle
        )

        return result.result
    }

    private fun <T> executeMutationRun(
        testId: String,
        run: Int,
        shuffle: Shuffle,
        block: () -> T
    ): T {
        val baseline = baselines[testId]
            ?: error("No baseline found for testId '$testId'. Run with run=0 first.")

        val discoveredPoints = baseline.discoveredPoints

        if (discoveredPoints.isEmpty()) {
            // No mutation points discovered, just run the block
            return underTest(testId = testId, activeMutation = null, block = block).result
        }

        // Calculate which mutation to activate
        val activeMutation = selectMutation(testId, run, shuffle, discoveredPoints)

        // Execute with the selected mutation
        return underTest(testId = testId, activeMutation = activeMutation, block = block).result
    }

    private fun selectMutation(
        testId: String,
        run: Int,
        shuffle: Shuffle,
        discoveredPoints: List<DiscoveredPoint>
    ): ActiveMutation {
        // Calculate total number of mutation variants
        val totalVariants = discoveredPoints.sumOf { it.variantCount }

        // Calculate hash based on shuffle mode
        val hash = when (shuffle) {
            Shuffle.EachTime -> {
                computeHash(testId, run, getOrCreateGlobalSeed())
            }
            Shuffle.OnChange -> {
                val pointsHash = computePointsHash(discoveredPoints)
                computeHash(testId, run, pointsHash)
            }
        }

        // Map hash to a variant index
        val variantIndex = ((hash % totalVariants) + totalVariants) % totalVariants // Handle negative hash

        // Map flat variant index to (pointIndex, variantIndex)
        return mapToMutation(variantIndex.toInt(), discoveredPoints)
    }

    private fun mapToMutation(flatIndex: Int, discoveredPoints: List<DiscoveredPoint>): ActiveMutation {
        var remaining = flatIndex
        for ((pointIndex, point) in discoveredPoints.withIndex()) {
            if (remaining < point.variantCount) {
                return ActiveMutation(pointIndex = pointIndex, variantIndex = remaining)
            }
            remaining -= point.variantCount
        }
        // Fallback (shouldn't happen if flatIndex is within bounds)
        return ActiveMutation(pointIndex = 0, variantIndex = 0)
    }

    private fun computeHash(testId: String, run: Int, seed: Long): Long {
        // Simple but effective hash combining
        var hash = seed
        hash = hash * 31 + testId.hashCode()
        hash = hash * 31 + run
        return hash
    }

    private fun computePointsHash(points: List<DiscoveredPoint>): Long {
        var hash = 17L
        for (point in points) {
            hash = hash * 31 + point.pointId.hashCode()
            hash = hash * 31 + point.variantCount
        }
        return hash
    }

    private fun getOrCreateGlobalSeed(): Long {
        if (globalSeed == null) {
            globalSeed = System.currentTimeMillis() xor System.nanoTime()
            println("[mutflow] Generated seed: $globalSeed")
        }
        return globalSeed!!
    }

    // ==================== Low-level "plumbing" API ====================

    /**
     * Low-level API: Executes the block under mutation testing control.
     *
     * This is the plumbing function used internally. For most cases,
     * prefer the high-level [underTest] with run and shuffle parameters.
     *
     * @param testId Identifier for this test case
     * @param activeMutation Which mutation to activate, or null for discovery run
     * @param block The code under test
     * @return Result containing the block's return value and session information
     */
    fun <T> underTest(
        testId: String,
        activeMutation: ActiveMutation? = null,
        block: () -> T
    ): UnderTestResult<T> {
        MutationRegistry.startSession(testId, activeMutation)

        val result = try {
            block()
        } finally {
            // Session is ended below, but we need to handle exceptions
        }

        val sessionResult = MutationRegistry.endSession()

        return UnderTestResult(
            result = result,
            discovered = sessionResult.discoveredPoints,
            activeMutation = activeMutation
        )
    }

    // ==================== Testing support ====================

    /**
     * Resets all stored baselines and seed. Intended for testing only.
     */
    fun reset() {
        baselines.clear()
        globalSeed = null
    }

    /**
     * Sets the global seed explicitly. Intended for testing only.
     */
    internal fun setSeed(seed: Long) {
        globalSeed = seed
    }
}

/**
 * Determines how mutations are shuffled/selected across runs.
 */
enum class Shuffle {
    /**
     * Mutation selection changes each time tests run (based on random seed).
     * Different CI builds will test different mutations.
     * Seed is printed to stdout for reproducibility.
     */
    EachTime,

    /**
     * Mutation selection only changes when the code changes.
     * Same code = same mutations tested (deterministic).
     * Good for stable CI pipelines.
     */
    OnChange
}

/**
 * Stored baseline information for a test.
 */
internal data class Baseline(
    val discoveredPoints: List<DiscoveredPoint>,
    val shuffle: Shuffle
)

/**
 * Result from executing code under mutation testing control.
 *
 * @property result The return value from the block
 * @property discovered Mutation points discovered during this run (pass to subsequent runs)
 * @property activeMutation Which mutation was active, null for baseline run 0
 */
data class UnderTestResult<T>(
    val result: T,
    val discovered: List<DiscoveredPoint>,
    val activeMutation: ActiveMutation?
)
