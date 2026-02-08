package sample

import io.github.anschnapp.mutflow.MutFlow
import io.github.anschnapp.mutflow.MutationRegistry
import io.github.anschnapp.mutflow.Selection
import io.github.anschnapp.mutflow.Shuffle
import kotlin.test.*

/**
 * Tests comment-based line suppression (mutflow:ignore / mutflow:falsePositive).
 */
class CommentSuppressionTest {

    private val calculator = Calculator()

    @BeforeTest
    fun setup() {
        MutationRegistry.reset()
        MutFlow.reset()
    }

    @Test
    fun `inline mutflow-ignore suppresses mutations on that line`() {
        // isLargeIgnored has: x > 100 // mutflow:ignore
        // No mutations should be discovered for this comparison

        MutFlow.underTest(run = 0, selection = Selection.MostLikelyStable, shuffle = Shuffle.PerChange) {
            calculator.isLargeIgnored(150)
        }

        val state = MutFlow.getRegistryState()
        assertTrue(
            state.discoveredPoints.isEmpty(),
            "No mutations should be discovered for mutflow:ignore line, but found: ${state.discoveredPoints}"
        )
    }

    @Test
    fun `inline mutflow-ignore preserves original behavior`() {
        assertTrue(calculator.isLargeIgnored(150), "isLargeIgnored(150) should be true")
        assertFalse(calculator.isLargeIgnored(50), "isLargeIgnored(50) should be false")
    }

    @Test
    fun `standalone mutflow-falsePositive suppresses mutations on next line`() {
        // isSmallFalsePositive has:
        //   // mutflow:falsePositive equivalent mutant, boundary doesn't matter here
        //   return x < 10
        // No mutations should be discovered (both comparison and return are on the suppressed line)

        MutFlow.underTest(run = 0, selection = Selection.MostLikelyStable, shuffle = Shuffle.PerChange) {
            calculator.isSmallFalsePositive(5)
        }

        val state = MutFlow.getRegistryState()
        assertTrue(
            state.discoveredPoints.isEmpty(),
            "No mutations should be discovered for mutflow:falsePositive line, but found: ${state.discoveredPoints}"
        )
    }

    @Test
    fun `standalone mutflow-falsePositive preserves original behavior`() {
        assertTrue(calculator.isSmallFalsePositive(5), "isSmallFalsePositive(5) should be true")
        assertFalse(calculator.isSmallFalsePositive(15), "isSmallFalsePositive(15) should be false")
    }

    @Test
    fun `mixed suppression only suppresses annotated lines`() {
        // mixedSuppression has:
        //   val first = a > 0 // mutflow:ignore not relevant   <-- suppressed
        //   val second = b > 0                                  <-- NOT suppressed
        // Only the second comparison should produce mutations

        MutFlow.underTest(run = 0, selection = Selection.MostLikelyStable, shuffle = Shuffle.PerChange) {
            calculator.mixedSuppression(5, 5)
        }

        val state = MutFlow.getRegistryState()
        // Should discover mutations only for non-suppressed lines:
        // - b > 0: Relational comparison (2 variants: >=, <)
        // - b > 0: Constant boundary on 0 (2 variants: 1, -1)
        // - return first && second: Boolean return (2 variants: true, false)
        // Total: 6 mutations from 3 mutation points
        // The suppressed line (a > 0) should NOT contribute any mutation points
        assertEquals(
            3, state.discoveredPoints.size,
            "Should discover 3 mutation points for non-suppressed lines only, but found: ${state.discoveredPoints}"
        )
    }
}
