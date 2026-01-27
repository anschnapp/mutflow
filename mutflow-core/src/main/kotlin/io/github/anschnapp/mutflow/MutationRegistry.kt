package io.github.anschnapp.mutflow

/**
 * Central registry for mutation point tracking and activation.
 *
 * This is the bridge between compiler-generated code and the test runtime.
 * The compiler plugin injects calls to [check] at each mutation point.
 * The runtime controls sessions via [startSession] and [endSession].
 *
 * Thread safety: Currently assumes single-threaded test execution per session.
 */
object MutationRegistry {

    private var currentSession: Session? = null

    /**
     * Called by compiler-injected code at each mutation point.
     *
     * @param pointId Stable identifier for this mutation point (IR hash)
     * @param variantCount Number of mutation variants available at this point
     * @return Variant index to use (0-based), or null to use original code
     */
    fun check(pointId: String, variantCount: Int): Int? {
        val session = currentSession ?: return null

        // Register point if not seen in this session
        if (pointId !in session.seenPointIds) {
            session.seenPointIds.add(pointId)
            session.discoveredPoints.add(DiscoveredPoint(pointId, variantCount))
        }

        // Check if this point is active
        val active = session.activeMutation ?: return null
        if (active.pointId == pointId) {
            return active.variantIndex
        }

        return null
    }

    /**
     * Starts a new mutation testing session.
     *
     * @param activeMutation If set, which mutation to activate during this session
     */
    fun startSession(activeMutation: ActiveMutation? = null) {
        check(currentSession == null) { "Session already active" }
        currentSession = Session(activeMutation)
    }

    /**
     * Ends the current session and returns results.
     *
     * @return Session results including discovered mutation points
     * @throws IllegalStateException if no session is active
     */
    fun endSession(): SessionResult {
        val session = currentSession ?: error("No active session")
        currentSession = null

        return SessionResult(
            mutationPointCount = session.discoveredPoints.size,
            discoveredPoints = session.discoveredPoints.toList()
        )
    }

    /**
     * Returns true if a session is currently active.
     */
    fun hasActiveSession(): Boolean = currentSession != null

    /**
     * Resets the registry state. Intended for testing only.
     */
    fun reset() {
        currentSession = null
    }

    private class Session(
        val activeMutation: ActiveMutation?,
        val discoveredPoints: MutableList<DiscoveredPoint> = mutableListOf(),
        val seenPointIds: MutableSet<String> = mutableSetOf()
    )
}

/**
 * Identifies which mutation to activate during a test run.
 *
 * @property pointId Stable identifier for the mutation point
 * @property variantIndex Index of the variant at that point (0-based)
 */
data class ActiveMutation(
    val pointId: String,
    val variantIndex: Int
)

/**
 * Information about a discovered mutation point.
 *
 * @property pointId Stable identifier (IR hash)
 * @property variantCount Number of available variants
 */
data class DiscoveredPoint(
    val pointId: String,
    val variantCount: Int
)

/**
 * Results from a completed mutation testing session.
 *
 * @property mutationPointCount Total number of mutation points discovered
 * @property discoveredPoints Details of each discovered point in order
 */
data class SessionResult(
    val mutationPointCount: Int,
    val discoveredPoints: List<DiscoveredPoint>
)
