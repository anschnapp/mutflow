package sample

import io.github.anschnapp.mutflow.MutFlow
import io.github.anschnapp.mutflow.MutationRegistry
import io.github.anschnapp.mutflow.Selection
import io.github.anschnapp.mutflow.Shuffle
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Regression test: Kotlin null-safety operators (`?:`, `?.`, `!!`) must produce
 * NO mutation points.
 *
 * These operators desugar to a compiler-synthesized `x == null` check in IR.
 * EqualitySwapOperator skips null comparisons, so exercising a class that
 * contains only null-safety constructs must discover zero mutation points.
 * Before the fix, `?:` and `?.` each produced a spurious `== → !=` point.
 */
class NullSafetyTargetTest {

    private val target = NullSafetyTarget()

    @BeforeTest
    fun setup() {
        MutationRegistry.reset()
        MutFlow.reset()
    }

    @Test
    fun `null-safety operators produce no mutation points`() {
        MutFlow.underTest(run = 0, selection = Selection.MostLikelyStable, shuffle = Shuffle.PerChange) {
            target.elvis(null, 7)
        }
        MutFlow.underTest(run = 0, selection = Selection.MostLikelyStable, shuffle = Shuffle.PerChange) {
            target.safeLength("abc")
        }
        MutFlow.underTest(run = 0, selection = Selection.MostLikelyStable, shuffle = Shuffle.PerChange) {
            target.bang(42)
        }

        val points = MutFlow.getRegistryState().discoveredPoints
            .filter { it.key.contains("NullSafetyTarget") }

        assertTrue(
            points.isEmpty(),
            "Null-safety operators must not produce mutation points, but found: " +
                points.entries.joinToString { "${it.key}=${it.value}" }
        )
    }
}
