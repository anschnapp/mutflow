package io.github.anschnapp.mutflow

/**
 * Main entry point for mutation testing in tests.
 *
 * Wraps the code under test and manages mutation sessions.
 */
object MutFlow {

    /**
     * Executes the block under mutation testing control.
     *
     * @param runNumber 0 for baseline (discovery), 1-N for mutation runs
     * @param testId Identifier for this test case
     *               TODO: Compiler plugin should auto-generate this from call site
     * @param discovered Discovered mutation points from run 0. Required for runNumber > 0.
     * @param block The code under test
     * @return Result containing the block's return value and session information
     */
    fun <T> underTest(
        runNumber: Int,
        testId: String,
        discovered: List<DiscoveredPoint>? = null,
        block: () -> T
    ): UnderTestResult<T> {
        require(runNumber >= 0) { "runNumber must be non-negative, got: $runNumber" }

        val activeMutation = if (runNumber == 0) {
            null
        } else {
            requireNotNull(discovered) {
                "discovered mutations required for runNumber > 0"
            }
            selectMutation(runNumber, discovered)
        }

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

    /**
     * Calculates the total number of mutation runs needed.
     *
     * @param discovered Discovered mutation points from run 0
     * @return Total number of mutations (runs 1 through N)
     */
    fun totalMutations(discovered: List<DiscoveredPoint>): Int {
        return discovered.sumOf { it.variantCount }
    }

    /**
     * Selects which mutation to activate for the given run number.
     *
     * Run 1 activates the first variant of the first point,
     * Run 2 activates the second variant (or first of next point), etc.
     *
     * @param runNumber The mutation run number (1-based)
     * @param discovered The discovered mutation points
     * @return The mutation to activate
     * @throws IllegalArgumentException if runNumber exceeds available mutations
     */
    internal fun selectMutation(runNumber: Int, discovered: List<DiscoveredPoint>): ActiveMutation {
        require(runNumber > 0) { "runNumber must be positive for mutation selection" }
        require(discovered.isNotEmpty()) { "No mutation points discovered" }

        var mutationIndex = runNumber - 1  // 0-indexed
        var pointIndex = 0

        for (point in discovered) {
            if (mutationIndex < point.variantCount) {
                return ActiveMutation(pointIndex, mutationIndex)
            }
            mutationIndex -= point.variantCount
            pointIndex++
        }

        val total = totalMutations(discovered)
        throw IllegalArgumentException(
            "runNumber $runNumber exceeds available mutations ($total)"
        )
    }
}

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
