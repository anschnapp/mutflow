package io.github.anschnapp.mutflow

import kotlin.random.Random

/**
 * Main entry point for mutation testing in tests.
 *
 * Manages a global mutation registry and orchestrates mutation runs.
 */
object MutFlow {

    // Global registry: discovered points with their variant counts
    private val discoveredPoints = mutableMapOf<String, Int>() // pointId -> variantCount

    // Global registry: touch counts from baseline runs
    private val touchCounts = mutableMapOf<String, Int>() // pointId -> count

    // Global registry: mutations that have been tested in this execution
    private val testedMutations = mutableSetOf<Mutation>()

    // Seed for Shuffle.PerRun mode - generated once per JVM
    private var globalSeed: Long? = null

    /**
     * High-level API for mutation testing.
     *
     * @param run Run number: 0 = baseline/discovery, 1+ = mutation runs
     * @param selection How to select which mutation to test
     * @param shuffle When to reseed the selection (per run or per code change)
     * @param block The code under test
     * @return The result of the block
     * @throws MutationsExhaustedException if all mutations have been tested
     */
    fun <T> underTest(
        run: Int,
        selection: Selection,
        shuffle: Shuffle,
        block: () -> T
    ): T {
        require(run >= 0) { "run must be non-negative, got: $run" }

        return if (run == 0) {
            executeBaseline(block)
        } else {
            executeMutationRun(run, selection, shuffle, block)
        }
    }

    private fun <T> executeBaseline(block: () -> T): T {
        // Run discovery (no active mutation)
        MutationRegistry.startSession(activeMutation = null)

        val result = try {
            block()
        } finally {
            // Session will be ended below
        }

        val sessionResult = MutationRegistry.endSession()

        // Merge discovered points into global registry and update touch counts
        for (point in sessionResult.discoveredPoints) {
            discoveredPoints[point.pointId] = point.variantCount
            touchCounts[point.pointId] = (touchCounts[point.pointId] ?: 0) + 1
        }

        return result
    }

    private fun <T> executeMutationRun(
        run: Int,
        selection: Selection,
        shuffle: Shuffle,
        block: () -> T
    ): T {
        if (discoveredPoints.isEmpty()) {
            // No mutation points discovered, just run the block normally
            MutationRegistry.startSession(activeMutation = null)
            val result = block()
            MutationRegistry.endSession()
            return result
        }

        // Select mutation to test
        val mutation = selectMutation(run, selection, shuffle)
            ?: throw MutationsExhaustedException("All mutations have been tested")

        // Mark as tested
        testedMutations.add(mutation)

        // Execute with the selected mutation active
        val activeMutation = ActiveMutation(pointId = mutation.pointId, variantIndex = mutation.variantIndex)
        MutationRegistry.startSession(activeMutation = activeMutation)

        val result = try {
            block()
        } finally {
            // Session will be ended below
        }

        MutationRegistry.endSession()
        return result
    }

    private fun selectMutation(
        run: Int,
        selection: Selection,
        shuffle: Shuffle
    ): Mutation? {
        // Get all untested mutations
        val untestedMutations = buildUntestedMutations()

        if (untestedMutations.isEmpty()) {
            return null
        }

        // Calculate seed based on shuffle mode
        val seed = when (shuffle) {
            Shuffle.PerRun -> getOrCreateGlobalSeed() + run
            Shuffle.PerChange -> computePointsHash() + run
        }

        return when (selection) {
            Selection.PureRandom -> selectPureRandom(untestedMutations, seed)
            Selection.MostLikelyRandom -> selectMostLikelyRandom(untestedMutations, seed)
            Selection.MostLikelyStable -> selectMostLikelyStable(untestedMutations)
        }
    }

    private fun buildUntestedMutations(): List<Mutation> {
        val untested = mutableListOf<Mutation>()
        for ((pointId, variantCount) in discoveredPoints) {
            for (variantIndex in 0 until variantCount) {
                val mutation = Mutation(pointId, variantIndex)
                if (mutation !in testedMutations) {
                    untested.add(mutation)
                }
            }
        }
        return untested
    }

    private fun selectPureRandom(mutations: List<Mutation>, seed: Long): Mutation {
        val random = Random(seed)
        return mutations[random.nextInt(mutations.size)]
    }

    private fun selectMostLikelyRandom(mutations: List<Mutation>, seed: Long): Mutation {
        // Weight by inverse of touch count (fewer touches = higher weight)
        val weights = mutations.map { mutation ->
            val touchCount = touchCounts[mutation.pointId] ?: 1
            1.0 / touchCount
        }

        val totalWeight = weights.sum()
        val random = Random(seed)
        var pick = random.nextDouble() * totalWeight

        for ((index, weight) in weights.withIndex()) {
            pick -= weight
            if (pick <= 0) {
                return mutations[index]
            }
        }

        // Fallback to last (shouldn't happen)
        return mutations.last()
    }

    private fun selectMostLikelyStable(mutations: List<Mutation>): Mutation {
        // Deterministically pick the mutation with lowest touch count
        // Tie-breaker: alphabetical by pointId, then by variantIndex
        return mutations.minWith(
            compareBy(
                { touchCounts[it.pointId] ?: 0 },
                { it.pointId },
                { it.variantIndex }
            )
        )
    }

    private fun computePointsHash(): Long {
        var hash = 17L
        for ((pointId, variantCount) in discoveredPoints.entries.sortedBy { it.key }) {
            hash = hash * 31 + pointId.hashCode()
            hash = hash * 31 + variantCount
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

    // ==================== Query methods ====================

    /**
     * Returns the current state of the global registry.
     * Useful for debugging and testing.
     */
    fun getRegistryState(): RegistryState {
        return RegistryState(
            discoveredPoints = discoveredPoints.toMap(),
            touchCounts = touchCounts.toMap(),
            testedMutations = testedMutations.toSet()
        )
    }

    // ==================== Testing support ====================

    /**
     * Resets all stored state. Intended for testing only.
     */
    fun reset() {
        discoveredPoints.clear()
        touchCounts.clear()
        testedMutations.clear()
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
 * Determines how mutations are selected.
 */
enum class Selection {
    /**
     * Uniform random selection among untested mutations.
     */
    PureRandom,

    /**
     * Random selection weighted toward mutations touched by fewer tests.
     * Mutations with lower touch counts have higher probability of being selected.
     */
    MostLikelyRandom,

    /**
     * Deterministically pick the mutation touched by fewest tests.
     * Tie-breaker: alphabetical by pointId, then by variantIndex.
     */
    MostLikelyStable
}

/**
 * Determines when to change the selection seed.
 */
enum class Shuffle {
    /**
     * New random seed each JVM/CI run.
     * Good for exploratory testing during development.
     */
    PerRun,

    /**
     * Seed based on hash of discovered points.
     * Same code = same mutations tested (deterministic).
     * Good for stable CI pipelines.
     */
    PerChange
}

/**
 * Identifies a specific mutation (point + variant).
 */
data class Mutation(
    val pointId: String,
    val variantIndex: Int
)

/**
 * Snapshot of the global registry state.
 */
data class RegistryState(
    val discoveredPoints: Map<String, Int>,
    val touchCounts: Map<String, Int>,
    val testedMutations: Set<Mutation>
)

/**
 * Thrown when all mutations have been tested and there are no more to test.
 */
class MutationsExhaustedException(message: String) : RuntimeException(message)
