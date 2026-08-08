package sample

import io.github.anschnapp.mutflow.MutFlow
import io.github.anschnapp.mutflow.MutationRegistry
import io.github.anschnapp.mutflow.Selection
import io.github.anschnapp.mutflow.Shuffle
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Regression test for Kotlin null-safety operators (`?:`, `?.`, `!!`).
 *
 * These operators desugar to a compiler-synthesized `x == null` check in IR.
 * Two things must hold:
 *  1. EqualitySwapOperator must NOT produce spurious `== ↔ !=` points on the
 *     synthetic null checks — the developer never wrote an equality operator,
 *     so those points would be misleading (see EqualitySwapOperator).
 *  2. The dedicated elvis (`?:`) and safe-call (`?.`) mutators DO fire on these
 *     constructs, producing `?:` / `?.` points. The `!!` not-null assertion has
 *     no dedicated mutator, so it produces none.
 */
class NullSafetyTargetTest {

    private val target = NullSafetyTarget()

    @BeforeTest
    fun setup() {
        MutationRegistry.reset()
        MutFlow.reset()
    }

    @Test
    fun `null-safety operators produce only their dedicated mutation points`() {
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

        // elvis (`?:`) and safe-call (`?.`) each produce exactly one dedicated point;
        // the `!!` not-null assertion has no mutator so produces none. Crucially, the
        // synthetic `x == null` checks must NOT yield spurious equality-swap points
        // (EqualitySwapOperator skips null comparisons) — so the total is exactly 2.
        assertEquals(
            2,
            points.size,
            "expected exactly elvis + safe-call points (no equality swaps), got: $points"
        )
    }
}
