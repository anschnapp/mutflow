package io.github.anschnapp.mutflow

import kotlin.random.Random

/**
 * Holds all mutation testing state for a single test class execution.
 *
 * Created by JUnit extension at the start of a @MutFlowTest class,
 * closed when the class finishes.
 */
class MutFlowSession internal constructor(
    val id: String,
    val selection: Selection,
    val shuffle: Shuffle,
    val maxRuns: Int,
    private val expectedTestCount: Int = 0
) {
    // Discovered points with their variant counts (built during baseline)
    private val discoveredPoints = mutableMapOf<String, Int>() // pointId -> variantCount

    // Metadata for discovered points (source location, operators)
    private val pointMetadata = mutableMapOf<String, PointMetadata>()

    // Touch counts from baseline run (how many tests touched each point)
    private val touchCounts = mutableMapOf<String, Int>() // pointId -> count

    // Mutations that have been tested in this session
    private val testedMutations = mutableSetOf<Mutation>()

    // Seed for Shuffle.PerRun mode - generated once per session
    private var sessionSeed: Long? = null

    // Track unique test IDs executed during baseline (for partial run detection)
    private val executedTestIds = mutableSetOf<String>()

    // Current run number (set by startRun, cleared by endRun)
    private var currentRun: Int? = null

    // Currently active mutation (for run >= 1)
    private var activeMutation: Mutation? = null

    // Tracks whether any test failed in the current run
    private var testFailedInCurrentRun: Boolean = false

    // Results of mutation testing: mutation -> result
    private val mutationResults = mutableMapOf<Mutation, MutationResult>()

    /**
     * Tracks a test execution during baseline.
     * Called by JUnit extension's AfterTestExecutionCallback.
     *
     * @param testId Unique identifier for the test (typically the test's unique ID)
     */
    fun trackTestExecution(testId: String) {
        executedTestIds.add(testId)
    }

    /**
     * Returns true if this is a partial test run (e.g., running a single test method from IDE).
     * Partial runs skip mutation testing because results would be misleading.
     */
    fun isPartialRun(): Boolean {
        if (expectedTestCount <= 0) return false
        return executedTestIds.size < expectedTestCount
    }

    /**
     * Selects the next mutation to test for the given run.
     * Called by JUnit extension when building invocation contexts.
     *
     * This is called AFTER the previous run has completed, so for run 1+
     * the baseline discovery has already happened.
     *
     * @param run Run number (must be >= 1)
     * @return The selected mutation, or null if exhausted or if this is a partial run
     */
    fun selectMutationForRun(run: Int): Mutation? {
        require(run >= 1) { "selectMutationForRun is only for mutation runs (run >= 1)" }

        if (isPartialRun()) {
            println("[mutflow] Partial test run detected (${executedTestIds.size}/$expectedTestCount tests) - skipping mutation testing")
            return null
        }

        if (discoveredPoints.isEmpty()) {
            return null
        }

        return selectMutation(run)
    }

    /**
     * Starts a new run within this session.
     * Called by JUnit extension before each class template invocation.
     *
     * @param run Run number: 0 = baseline, 1+ = mutation runs
     * @param mutation The pre-selected mutation for this run (null for baseline)
     */
    fun startRun(run: Int, mutation: Mutation? = null) {
        require(run >= 0) { "run must be non-negative, got: $run" }
        currentRun = run
        activeMutation = mutation
        testFailedInCurrentRun = false

        if (mutation != null) {
            testedMutations.add(mutation)
            println("[mutflow] Activated mutation: ${getDisplayName(mutation)}")
        }
    }

    // Name of the first test that killed the current mutation
    private var killedByTest: String? = null

    /**
     * Marks that a test failed in the current run (mutation killed).
     * Called by JUnit extension's exception handler.
     *
     * @param testName The name of the test that killed the mutation
     */
    fun markTestFailed(testName: String) {
        if (!testFailedInCurrentRun && activeMutation != null) {
            // Record first test that killed this mutation
            killedByTest = testName
        }
        testFailedInCurrentRun = true
    }

    /**
     * Records the result of the current mutation run.
     * Called at the end of each mutation run.
     */
    fun recordMutationResult() {
        val mutation = activeMutation ?: return
        if (currentRun == null || currentRun == 0) return

        if (testFailedInCurrentRun) {
            mutationResults[mutation] = MutationResult.Killed(killedByTest ?: "unknown")
        } else {
            mutationResults[mutation] = MutationResult.Survived
        }
        killedByTest = null
    }

    /**
     * Returns true if we're in a mutation run and no test has failed.
     * This means the mutation survived (tests didn't catch it).
     */
    fun didMutationSurvive(): Boolean {
        return currentRun != null && currentRun!! > 0 && activeMutation != null && !testFailedInCurrentRun
    }

    /**
     * Returns the currently active mutation, if any.
     */
    fun getActiveMutation(): Mutation? = activeMutation

    /**
     * Ends the current run.
     * Called by JUnit extension after each class template invocation.
     */
    fun endRun() {
        currentRun = null
        activeMutation = null
    }

    /**
     * Executes the block under mutation testing.
     *
     * For run 0: discovers mutation points and updates touch counts.
     * For run 1+: selects and activates a mutation.
     *
     * @param block The code under test
     * @return The result of the block
     * @throws MutationsExhaustedException if all mutations have been tested
     * @throws IllegalStateException if no run is active
     */
    fun <T> underTest(block: () -> T): T {
        val run = currentRun
            ?: error("No run active. Call startRun() before underTest().")

        return if (run == 0) {
            executeBaseline(block)
        } else {
            executeMutationRun(run, block)
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

        // Merge discovered points and update touch counts
        for (point in sessionResult.discoveredPoints) {
            val isNew = point.pointId !in discoveredPoints
            discoveredPoints[point.pointId] = point.variantCount
            touchCounts[point.pointId] = (touchCounts[point.pointId] ?: 0) + 1
            if (isNew) {
                // Store metadata for display
                pointMetadata[point.pointId] = PointMetadata(
                    sourceLocation = point.sourceLocation,
                    originalOperator = point.originalOperator,
                    variantOperators = point.variantOperators
                )
                val displayLoc = "(${point.sourceLocation})"
                println("[mutflow] Discovered mutation point: $displayLoc ${point.originalOperator} with ${point.variantCount} variants")
            }
        }

        return result
    }

    private fun <T> executeMutationRun(run: Int, block: () -> T): T {
        if (discoveredPoints.isEmpty() || activeMutation == null) {
            // No mutation points discovered or no mutation selected, just run normally
            MutationRegistry.startSession(activeMutation = null)
            val result = block()
            MutationRegistry.endSession()
            return result
        }

        // Execute with the pre-selected mutation active
        val active = ActiveMutation(
            pointId = activeMutation!!.pointId,
            variantIndex = activeMutation!!.variantIndex
        )
        MutationRegistry.startSession(activeMutation = active)

        val result = try {
            block()
        } finally {
            // Session will be ended below
        }

        MutationRegistry.endSession()
        return result
    }

    private fun selectMutation(run: Int): Mutation? {
        val untestedMutations = buildUntestedMutations()

        if (untestedMutations.isEmpty()) {
            return null
        }

        val seed = when (shuffle) {
            Shuffle.PerRun -> getOrCreateSessionSeed() + run
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

        return mutations.last()
    }

    private fun selectMostLikelyStable(mutations: List<Mutation>): Mutation {
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

    private fun getOrCreateSessionSeed(): Long {
        if (sessionSeed == null) {
            sessionSeed = System.currentTimeMillis() xor System.nanoTime()
            println("[mutflow] Session $id - Generated seed: $sessionSeed")
        }
        return sessionSeed!!
    }

    // ==================== Query methods ====================

    /**
     * Returns the current state of this session.
     */
    fun getState(): SessionState {
        return SessionState(
            discoveredPoints = discoveredPoints.toMap(),
            touchCounts = touchCounts.toMap(),
            testedMutations = testedMutations.toSet(),
            currentRun = currentRun,
            activeMutation = activeMutation
        )
    }

    /**
     * Returns true if there are untested mutations remaining.
     */
    fun hasUntestedMutations(): Boolean {
        return buildUntestedMutations().isNotEmpty()
    }

    /**
     * Returns a human-readable display name for a mutation.
     * Format: "(FileName.kt:line) original → variant"
     * Example: "(Calculator.kt:5) > → >="
     */
    fun getDisplayName(mutation: Mutation): String {
        val meta = pointMetadata[mutation.pointId]
        return if (meta != null) {
            val variantOp = meta.variantOperators.getOrNull(mutation.variantIndex) ?: "?"
            "(${meta.sourceLocation}) ${meta.originalOperator} → $variantOp"
        } else {
            // Fallback for mutations without metadata
            "${mutation.pointId}:${mutation.variantIndex}"
        }
    }

    /**
     * Returns a summary of the mutation testing results.
     */
    fun getSummary(): MutationTestingSummary {
        val totalMutations = discoveredPoints.values.sum()
        val tested = mutationResults.size
        val killed = mutationResults.count { it.value is MutationResult.Killed }
        val survived = mutationResults.count { it.value is MutationResult.Survived }
        val untested = totalMutations - tested

        return MutationTestingSummary(
            totalMutations = totalMutations,
            testedThisRun = tested,
            killed = killed,
            survived = survived,
            untested = untested,
            results = mutationResults.toMap()
        )
    }

    /**
     * Prints a summary of mutation testing results.
     */
    fun printSummary() {
        val summary = getSummary()

        println()
        println("╔════════════════════════════════════════════════════════════════╗")
        println("║                    MUTATION TESTING SUMMARY                    ║")
        println("╠════════════════════════════════════════════════════════════════╣")
        println("║  Total mutations discovered: ${summary.totalMutations.toString().padStart(3)}                              ║")
        println("║  Tested this run:            ${summary.testedThisRun.toString().padStart(3)}                              ║")
        println("║  ├─ Killed:                  ${summary.killed.toString().padStart(3)}  ✓                           ║")
        println("║  └─ Survived:                ${summary.survived.toString().padStart(3)}  ${if (summary.survived > 0) "✗" else "✓"}                           ║")
        println("║  Remaining untested:         ${summary.untested.toString().padStart(3)}                              ║")
        println("╠════════════════════════════════════════════════════════════════╣")

        if (summary.results.isNotEmpty()) {
            println("║  DETAILS:                                                      ║")
            for ((mutation, result) in summary.results) {
                val mutationStr = getDisplayName(mutation)
                when (result) {
                    is MutationResult.Killed -> {
                        println("║  ✓ ${mutationStr.padEnd(61)}║")
                        val testLine = "      killed by: ${result.testName}"
                        println("║${testLine.take(64).padEnd(64)}║")
                    }
                    is MutationResult.Survived -> {
                        println("║  ✗ ${mutationStr.padEnd(61)}║")
                        println("║      SURVIVED - no test caught this mutation!${" ".repeat(19)}║")
                    }
                }
            }
        }

        println("╚════════════════════════════════════════════════════════════════╝")
        println()
    }

    // ==================== Testing support ====================

    /**
     * Sets the session seed explicitly. Intended for testing only.
     */
    internal fun setSeed(seed: Long) {
        sessionSeed = seed
    }
}

/**
 * Snapshot of a session's state.
 */
data class SessionState(
    val discoveredPoints: Map<String, Int>,
    val touchCounts: Map<String, Int>,
    val testedMutations: Set<Mutation>,
    val currentRun: Int?,
    val activeMutation: Mutation?
)

/**
 * Result of testing a single mutation.
 */
sealed class MutationResult {
    data class Killed(val testName: String) : MutationResult()
    data object Survived : MutationResult()
}

/**
 * Summary of mutation testing results for a session.
 */
data class MutationTestingSummary(
    val totalMutations: Int,
    val testedThisRun: Int,
    val killed: Int,
    val survived: Int,
    val untested: Int,
    val results: Map<Mutation, MutationResult>
)

/**
 * Metadata for a mutation point, used for display formatting.
 */
internal data class PointMetadata(
    val sourceLocation: String,
    val originalOperator: String,
    val variantOperators: List<String>
)
