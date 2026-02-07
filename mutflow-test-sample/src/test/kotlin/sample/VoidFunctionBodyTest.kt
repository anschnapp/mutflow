package sample

import io.github.anschnapp.mutflow.MutFlow
import io.github.anschnapp.mutflow.MutationRegistry
import io.github.anschnapp.mutflow.Selection
import io.github.anschnapp.mutflow.Shuffle
import kotlin.test.*

/**
 * Tests the void function body removal mutation operator.
 *
 * The operator replaces the entire body of Unit functions with an empty body,
 * removing all side effects. This catches tests that don't verify side effects.
 */
class VoidFunctionBodyTest {

    private val calculator = Calculator()

    @BeforeTest
    fun setup() {
        MutationRegistry.reset()
        MutFlow.reset()
    }

    @Test
    fun `void function body removal generates mutation`() {
        // recordResult is a Unit function, should get a body removal mutation

        MutFlow.underTest(run = 0, selection = Selection.MostLikelyStable, shuffle = Shuffle.PerChange) {
            calculator.recordResult(42)
        }

        val state = MutFlow.getRegistryState()
        val point = state.discoveredPoints.entries.find {
            it.key.contains("Calculator")
        }
        assertNotNull(point, "Should discover a mutation point for recordResult")
        assertEquals(1, point.value, "Void body removal should have 1 variant (removed)")
    }

    @Test
    fun `void function body removal skips side effects`() {
        // Original: recordResult(42) sets lastResult to 42
        // Mutation: recordResult(42) does nothing, lastResult stays 0

        // Baseline
        calculator.lastResult = 0
        MutFlow.underTest(run = 0, selection = Selection.MostLikelyStable, shuffle = Shuffle.PerChange) {
            calculator.recordResult(42)
        }
        assertEquals(42, calculator.lastResult, "Baseline: recordResult should set lastResult")

        // Mutation run (body removed)
        calculator.lastResult = 0
        MutFlow.underTest(run = 1, selection = Selection.MostLikelyStable, shuffle = Shuffle.PerChange) {
            calculator.recordResult(42)
        }
        assertEquals(0, calculator.lastResult, "Mutation: recordResult should do nothing, lastResult stays 0")
    }
}
