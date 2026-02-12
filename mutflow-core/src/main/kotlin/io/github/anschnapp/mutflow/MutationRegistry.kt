package io.github.anschnapp.mutflow

import java.util.Collections
import java.util.concurrent.ConcurrentHashMap

/**
 * Central registry for mutation point tracking and activation.
 *
 * This is the bridge between compiler-generated code and the test runtime.
 * The compiler plugin injects calls to [check] at each mutation point.
 * The runtime controls sessions via [startSession] and [endSession].
 *
 * Thread safety: Use [withSession] to ensure mutual exclusion when multiple
 * test classes may run in parallel. The lock is held for the duration of the
 * block execution, so only one mutation session is active at a time.
 */
object MutationRegistry {

    @Volatile
    private var currentSession: Session? = null
    private val lock = Any()

    /**
     * Called by compiler-injected code at each mutation point.
     *
     * @param pointId Stable identifier for this mutation point (currently ClassName_N format, will be IR hash in Phase 2)
     * @param variantCount Number of mutation variants available at this point
     * @param sourceLocation Source file and line number (e.g., "Calculator.kt:5")
     * @param originalOperator The original operator (e.g., ">")
     * @param variantOperators Comma-separated variant operators (e.g., ">=,<,==")
     * @param occurrenceOnLine 1-based occurrence index when the same operator appears multiple times on the same line
     * @return Variant index to use (0-based), or null to use original code
     */
    fun check(
        pointId: String,
        variantCount: Int,
        sourceLocation: String,
        originalOperator: String,
        variantOperators: String,
        occurrenceOnLine: Int = 1
    ): Int? {
        val session = currentSession ?: return null

        // Register point if not seen in this session (atomic add for thread safety)
        if (session.seenPointIds.add(pointId)) {
            session.discoveredPoints.add(
                DiscoveredPoint(
                    pointId = pointId,
                    variantCount = variantCount,
                    sourceLocation = sourceLocation,
                    originalOperator = originalOperator,
                    variantOperators = variantOperators.split(","),
                    occurrenceOnLine = occurrenceOnLine
                )
            )
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
     * Executes [block] within a synchronized mutation session.
     *
     * Acquires a lock, starts a session, executes the block, ends the session,
     * and releases the lock. This ensures only one mutation session is active at
     * a time, even when multiple test classes run in parallel.
     *
     * @param activeMutation If set, which mutation to activate during this session
     * @param block The code to execute within the session
     * @return A pair of the block's result and the session result
     */
    fun <T> withSession(
        activeMutation: ActiveMutation? = null,
        block: () -> T
    ): Pair<T, SessionResult> {
        synchronized(lock) {
            check(currentSession == null) { "Session already active" }
            currentSession = Session(activeMutation)
            try {
                val result = block()
                val session = currentSession!!
                return result to SessionResult(
                    mutationPointCount = session.discoveredPoints.size,
                    discoveredPoints = session.discoveredPoints.toList()
                )
            } finally {
                currentSession = null
            }
        }
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
        val discoveredPoints: MutableList<DiscoveredPoint> = Collections.synchronizedList(mutableListOf()),
        val seenPointIds: MutableSet<String> = ConcurrentHashMap.newKeySet()
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
 * @property pointId Stable identifier (currently ClassName_N format, will be IR hash in Phase 2)
 * @property variantCount Number of available variants
 * @property sourceLocation Source file and line number (e.g., "Calculator.kt:5")
 * @property originalOperator The original operator (e.g., ">")
 * @property variantOperators List of variant operators (e.g., [">=", "<", "=="])
 * @property occurrenceOnLine 1-based occurrence index when the same operator appears multiple times on the same line
 */
data class DiscoveredPoint(
    val pointId: String,
    val variantCount: Int,
    val sourceLocation: String,
    val originalOperator: String,
    val variantOperators: List<String>,
    val occurrenceOnLine: Int = 1
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
