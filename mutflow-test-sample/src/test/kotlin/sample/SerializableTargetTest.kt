package sample

import io.github.anschnapp.mutflow.MutFlow
import io.github.anschnapp.mutflow.Mutation
import io.github.anschnapp.mutflow.MutationRegistry
import io.github.anschnapp.mutflow.Selection
import io.github.anschnapp.mutflow.Shuffle
import kotlinx.serialization.json.Json
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Code another compiler plugin generates into a target must not be mutated: nobody wrote it, and
 * its mutants are reported on line 0 or on the class declaration.
 */
class SerializableTargetTest {

    @BeforeTest
    fun setup() {
        MutationRegistry.reset()
        MutFlow.reset()
    }

    /** Runs [block] as a baseline and returns every mutation discovered in the target, without its location. */
    private fun discoveredMutations(block: () -> Unit): List<String> {
        val sessionId = MutFlow.createSession(Selection.MostLikelyStable, Shuffle.PerChange, maxRuns = Int.MAX_VALUE)
        val session = MutFlow.getSession(sessionId)!!
        try {
            MutFlow.startRun(sessionId, 0, null)
            session.underTest(block)
            MutFlow.endRun(sessionId)
            return session.getState().discoveredPoints
                .filterKeys { it.contains("SerializableTarget") }
                .flatMap { (pointId, variants) -> (0 until variants).map { session.getDisplayName(Mutation(pointId, it)) } }
                .map { it.substringAfter(") ") }
        } finally {
            MutFlow.closeSession(sessionId)
        }
    }

    @Test
    fun `only the hand-written function of a serializable class is mutated`() {
        val mutations = discoveredMutations {
            val target = SerializableTarget("a", 1)
            target.isEmpty()
            // Runs the generated serializer both ways, and write$Self with it.
            val json = Json.encodeToString(SerializableTarget.serializer(), target)
            Json.decodeFromString(SerializableTarget.serializer(), json)
        }
        assertEquals(listOf("== → !="), mutations, "Generated serialization code must have no mutation points")
    }
}
