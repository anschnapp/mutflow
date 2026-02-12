package io.github.anschnapp.mutflow

import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import kotlin.random.Random

/**
 * Main entry point for mutation testing in tests.
 *
 * Manages mutation testing sessions and provides the underTest API.
 *
 * Usage with JUnit extension (@MutFlowTest):
 * ```
 * @MutFlowTest
 * class MyTest {
 *     @Test
 *     fun testSomething() {
 *         val result = MutFlow.underTest { myCode() }
 *         assertEquals(expected, result)
 *     }
 * }
 * ```
 *
 * Usage without JUnit extension (manual):
 * ```
 * MutFlow.underTest(run = 0, Selection.MostLikelyStable, Shuffle.PerChange) { myCode() }
 * MutFlow.underTest(run = 1, Selection.MostLikelyStable, Shuffle.PerChange) { myCode() }
 * ```
 */
object MutFlow {

    // Active sessions, keyed by session ID
    private val sessions = mutableMapOf<String, MutFlowSession>()

    // Maps thread ID to session ID, set in startRun, cleared in endRun.
    // Allows parameterless underTest() to find the right session when
    // multiple test classes run in parallel on different threads.
    private val threadToSession = ConcurrentHashMap<Long, String>()

    // ==================== Session Management (for JUnit extension) ====================

    /**
     * Creates a new session for a test class.
     * Called by JUnit extension at the start of a @MutFlowTest class.
     *
     * @param selection How to select mutations
     * @param shuffle When to reseed selection
     * @param maxRuns Maximum number of runs
     * @param expectedTestCount Number of test methods in the class (for partial run detection)
     * @param traps Mutations to test first, by display name (e.g., "(Calculator.kt:8) > → >=")
     * @return The session ID
     */
    fun createSession(
        selection: Selection,
        shuffle: Shuffle,
        maxRuns: Int,
        expectedTestCount: Int = 0,
        traps: List<String> = emptyList(),
        includeTargets: List<String> = emptyList(),
        excludeTargets: List<String> = emptyList()
    ): String {
        val id = UUID.randomUUID().toString()
        val session = MutFlowSession(
            id = id,
            selection = selection,
            shuffle = shuffle,
            maxRuns = maxRuns,
            expectedTestCount = expectedTestCount,
            traps = traps,
            includeTargets = includeTargets,
            excludeTargets = excludeTargets
        )
        sessions[id] = session
        return id
    }

    /**
     * Closes a session and cleans up its state.
     * Called by JUnit extension when a @MutFlowTest class finishes.
     */
    fun closeSession(sessionId: String) {
        val session = sessions.remove(sessionId)
        session?.printSummary()
    }

    /**
     * Selects the next mutation for a run.
     * Called by JUnit extension when building invocation contexts.
     *
     * @param sessionId The session ID
     * @param run Run number (must be >= 1)
     * @return The selected mutation, or null if exhausted
     */
    fun selectMutationForRun(sessionId: String, run: Int): Mutation? {
        val session = sessions[sessionId]
            ?: error("Session not found: $sessionId")
        return session.selectMutationForRun(run)
    }

    /**
     * Starts a run within a session.
     * Called by JUnit extension before each class template invocation.
     *
     * @param sessionId The session ID
     * @param run Run number: 0 = baseline, 1+ = mutation runs
     * @param mutation Pre-selected mutation for this run (null for baseline)
     */
    fun startRun(sessionId: String, run: Int, mutation: Mutation? = null) {
        val session = sessions[sessionId]
            ?: error("Session not found: $sessionId")
        threadToSession[Thread.currentThread().id] = sessionId
        session.startRun(run, mutation)
    }

    /**
     * Ends the current run within a session.
     * Called by JUnit extension after each class template invocation.
     */
    fun endRun(sessionId: String) {
        sessions[sessionId]?.endRun()
        threadToSession.remove(Thread.currentThread().id)
    }

    /**
     * Returns the session for the given ID.
     */
    fun getSession(sessionId: String): MutFlowSession? = sessions[sessionId]

    /**
     * Returns true if the session has untested mutations remaining.
     */
    fun hasUntestedMutations(sessionId: String): Boolean {
        return sessions[sessionId]?.hasUntestedMutations() ?: false
    }

    // ==================== underTest API ====================

    /**
     * Executes the block under mutation testing using the active session.
     *
     * This is the preferred API when using @MutFlowTest annotation.
     * The JUnit extension manages session lifecycle and run numbers automatically.
     *
     * @param block The code under test
     * @return The result of the block
     * @throws IllegalStateException if no session is active
     * @throws MutationsExhaustedException if all mutations have been tested
     */
    fun <T> underTest(block: () -> T): T {
        val sessionId = threadToSession[Thread.currentThread().id]
            ?: error("No active MutFlow session on this thread. Use @MutFlowTest annotation or call underTest(run, selection, shuffle) directly.")

        val session = sessions[sessionId]
            ?: error("Session not found: $sessionId")

        return session.underTest(block)
    }

    /**
     * Executes the block under mutation testing with explicit parameters.
     *
     * Use this for manual testing or when not using @MutFlowTest annotation.
     * Creates an ephemeral session for the call.
     *
     * @param run Run number: 0 = baseline/discovery, 1+ = mutation runs
     * @param selection How to select which mutation to test
     * @param shuffle When to reseed the selection
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
        // Use the legacy session for backwards compatibility
        return legacyUnderTest(run, selection, shuffle, block)
    }

    // ==================== Legacy support (for existing tests) ====================

    // Legacy global state for explicit underTest(run, selection, shuffle) calls
    private val legacyDiscoveredPoints = mutableMapOf<String, Int>()
    private val legacyTouchCounts = mutableMapOf<String, Int>()
    private val legacyTestedMutations = mutableSetOf<Mutation>()
    private var legacyGlobalSeed: Long? = null

    private fun <T> legacyUnderTest(
        run: Int,
        selection: Selection,
        shuffle: Shuffle,
        block: () -> T
    ): T {
        require(run >= 0) { "run must be non-negative, got: $run" }

        return if (run == 0) {
            legacyExecuteBaseline(block)
        } else {
            legacyExecuteMutationRun(run, selection, shuffle, block)
        }
    }

    private fun <T> legacyExecuteBaseline(block: () -> T): T {
        val (result, sessionResult) = MutationRegistry.withSession(activeMutation = null) {
            block()
        }

        for (point in sessionResult.discoveredPoints) {
            legacyDiscoveredPoints[point.pointId] = point.variantCount
            legacyTouchCounts[point.pointId] = (legacyTouchCounts[point.pointId] ?: 0) + 1
        }

        return result
    }

    private fun <T> legacyExecuteMutationRun(
        run: Int,
        selection: Selection,
        shuffle: Shuffle,
        block: () -> T
    ): T {
        if (legacyDiscoveredPoints.isEmpty()) {
            val (result, _) = MutationRegistry.withSession(activeMutation = null) {
                block()
            }
            return result
        }

        val mutation = legacySelectMutation(run, selection, shuffle)
            ?: throw MutationsExhaustedException("All mutations have been tested")

        legacyTestedMutations.add(mutation)

        val active = ActiveMutation(pointId = mutation.pointId, variantIndex = mutation.variantIndex)
        val (result, _) = MutationRegistry.withSession(activeMutation = active) {
            block()
        }
        return result
    }

    private fun legacySelectMutation(
        run: Int,
        selection: Selection,
        shuffle: Shuffle
    ): Mutation? {
        val untestedMutations = legacyBuildUntestedMutations()

        if (untestedMutations.isEmpty()) {
            return null
        }

        val seed = when (shuffle) {
            Shuffle.PerRun -> legacyGetOrCreateGlobalSeed() + run
            Shuffle.PerChange -> legacyComputePointsHash() + run
        }

        return when (selection) {
            Selection.PureRandom -> legacySelectPureRandom(untestedMutations, seed)
            Selection.MostLikelyRandom -> legacySelectMostLikelyRandom(untestedMutations, seed)
            Selection.MostLikelyStable -> legacySelectMostLikelyStable(untestedMutations)
        }
    }

    private fun legacyBuildUntestedMutations(): List<Mutation> {
        val untested = mutableListOf<Mutation>()
        for ((pointId, variantCount) in legacyDiscoveredPoints) {
            for (variantIndex in 0 until variantCount) {
                val mutation = Mutation(pointId, variantIndex)
                if (mutation !in legacyTestedMutations) {
                    untested.add(mutation)
                }
            }
        }
        return untested
    }

    private fun legacySelectPureRandom(mutations: List<Mutation>, seed: Long): Mutation {
        val random = Random(seed)
        return mutations[random.nextInt(mutations.size)]
    }

    private fun legacySelectMostLikelyRandom(mutations: List<Mutation>, seed: Long): Mutation {
        val weights = mutations.map { mutation ->
            val touchCount = legacyTouchCounts[mutation.pointId] ?: 1
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

        return mutations.last()
    }

    private fun legacySelectMostLikelyStable(mutations: List<Mutation>): Mutation {
        return mutations.minWith(
            compareBy(
                { legacyTouchCounts[it.pointId] ?: 0 },
                { it.pointId },
                { it.variantIndex }
            )
        )
    }

    private fun legacyComputePointsHash(): Long {
        var hash = 17L
        for ((pointId, variantCount) in legacyDiscoveredPoints.entries.sortedBy { it.key }) {
            hash = hash * 31 + pointId.hashCode()
            hash = hash * 31 + variantCount
        }
        return hash
    }

    private fun legacyGetOrCreateGlobalSeed(): Long {
        if (legacyGlobalSeed == null) {
            legacyGlobalSeed = System.currentTimeMillis() xor System.nanoTime()
            println("[mutflow] Generated seed: $legacyGlobalSeed")
        }
        return legacyGlobalSeed!!
    }

    // ==================== Query methods ====================

    /**
     * Returns the current state of the legacy global registry.
     * For session-based usage, use getSession(id).getState() instead.
     */
    fun getRegistryState(): RegistryState {
        return RegistryState(
            discoveredPoints = legacyDiscoveredPoints.toMap(),
            touchCounts = legacyTouchCounts.toMap(),
            testedMutations = legacyTestedMutations.toSet()
        )
    }

    // ==================== Testing support ====================

    /**
     * Resets all stored state (both sessions and legacy).
     * Intended for testing only.
     */
    fun reset() {
        sessions.clear()
        threadToSession.clear()

        legacyDiscoveredPoints.clear()
        legacyTouchCounts.clear()
        legacyTestedMutations.clear()
        legacyGlobalSeed = null
    }

    /**
     * Sets the legacy global seed explicitly.
     * Intended for testing only.
     */
    internal fun setSeed(seed: Long) {
        legacyGlobalSeed = seed
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

/**
 * Thrown when a mutation survives (all tests pass with the mutation active).
 * This indicates a gap in test coverage - the tests didn't detect the mutation.
 */
class MutantSurvivedException(
    val mutation: Mutation,
    val displayName: String = "${mutation.pointId}:${mutation.variantIndex}"
) : AssertionError(
    "MUTANT SURVIVED: $displayName\n" +
    "The mutation was not detected by any test. Consider adding a test that would fail when this mutation is active.\n" +
    "To debug this mutation, add it to your test annotation: traps = [\"$displayName\"]"
)
