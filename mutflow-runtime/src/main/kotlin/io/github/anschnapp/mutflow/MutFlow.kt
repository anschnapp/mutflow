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
     * @param testId Identifier for this test case
     *               TODO: Compiler plugin should auto-generate this from call site
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
