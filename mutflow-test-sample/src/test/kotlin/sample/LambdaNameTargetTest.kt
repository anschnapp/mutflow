package sample

import io.github.anschnapp.mutflow.MutFlow
import io.github.anschnapp.mutflow.Mutation
import io.github.anschnapp.mutflow.MutationRegistry
import io.github.anschnapp.mutflow.Selection
import io.github.anschnapp.mutflow.Shuffle
import java.util.concurrent.Executor
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The body removal of a Unit lambda is named after the call it is passed to or the variable or
 * property it initialises, e.g. `(LambdaNameTarget.kt:23) forEach {} → removed`.
 */
class LambdaNameTargetTest {

    private val target = LambdaNameTarget()

    @BeforeTest
    fun setup() {
        MutationRegistry.reset()
        MutFlow.reset()
    }

    /** Runs [block] as a baseline and returns the original side of every body removal discovered. */
    private fun removals(block: () -> Unit): List<String> {
        val sessionId = MutFlow.createSession(Selection.MostLikelyStable, Shuffle.PerChange, maxRuns = Int.MAX_VALUE)
        val session = MutFlow.getSession(sessionId)!!
        try {
            MutFlow.startRun(sessionId, 0, null)
            session.underTest(block)
            MutFlow.endRun(sessionId)
            return session.getState().discoveredPoints
                .filterKeys { it.contains("LambdaNameTarget") }
                .flatMap { (pointId, variants) -> (0 until variants).map { session.getDisplayName(Mutation(pointId, it)) } }
                .filter { it.contains(" → removed") }
                .map { it.substringAfter(") ").substringBefore(" → removed") + it.substringAfter(" → removed") }
                .sorted()
        } finally {
            MutFlow.closeSession(sessionId)
        }
    }

    @Test
    fun `a lambda passed to a call is named after the call`() {
        assertEquals(listOf("forEach {}", "recordEach()"), removals { target.recordEach(listOf(1)) })
    }

    @Test
    fun `two lambdas on one line are told apart by name`() {
        assertEquals(listOf("forEach {}", "onEach {}", "recordTwice()"), removals { target.recordTwice(listOf(1)) })
    }

    @Test
    fun `a lambda converted to a Java interface is named after the call`() {
        assertEquals(listOf("execute {}", "recordOnExecutor()"), removals { target.recordOnExecutor(Executor { it.run() }) })
    }

    @Test
    fun `a lambda converted to a fun interface is named after the call`() {
        assertEquals(listOf("recordThroughAction()", "runAction {}", "runAction()"), removals { target.recordThroughAction() })
    }

    @Test
    fun `a lambda assigned to a variable is named after the variable`() {
        assertEquals(listOf("record {}", "recordThroughVariable()"), removals { target.recordThroughVariable() })
    }

    @Test
    fun `a lambda assigned to a property is named after the property`() {
        assertEquals(listOf("clear()", "clearAll {}"), removals { LambdaNameTarget().clear() })
    }

    @Test
    fun `a returned lambda keeps the anonymous name`() {
        assertEquals(listOf("<anonymous>()"), removals { target.recorder()() })
    }

    @Test
    fun `a local function keeps its own name`() {
        assertEquals(listOf("record()", "recordThroughLocalFunction()"), removals { target.recordThroughLocalFunction() })
    }
}
