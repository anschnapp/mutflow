package sample

import io.github.anschnapp.mutflow.MutFlow
import io.github.anschnapp.mutflow.MutationRegistry
import io.github.anschnapp.mutflow.Selection
import io.github.anschnapp.mutflow.Shuffle
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Reproduces the handover report: the exception is asserted OUTSIDE the
 * underTest block (the natural way to write such a test), so it escapes
 * MutationRegistry.withSession.
 */
class ThrowEscapingUnderTestTest {

    private val target = ThrowInIfTarget()

    @BeforeTest
    fun setup() {
        MutationRegistry.reset()
        MutFlow.reset()
    }

    @Test
    fun `mutation points are discovered even when the block throws`() {
        try {
            MutFlow.underTest(run = 0, selection = Selection.MostLikelyStable, shuffle = Shuffle.PerChange) {
                target.fetch(empty = true, id = 7)
            }
        } catch (_: IllegalStateException) {
            // expected - assertion happens outside the block, like a real assertThrows test
        }

        val state = MutFlow.getRegistryState()
        val points = state.discoveredPoints.keys.filter { it.contains("ThrowInIfTarget") }
        assertTrue(points.isNotEmpty(), "Should discover mutation points reached before/at the throw, got: ${state.discoveredPoints.keys}")
    }
}
