package sample

import io.github.anschnapp.mutflow.MutFlow
import io.github.anschnapp.mutflow.Selection
import io.github.anschnapp.mutflow.Shuffle
import io.github.anschnapp.mutflow.junit.MutFlowTest
import org.junit.jupiter.api.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Tests that an invalid trap produces a warning but doesn't break the test.
 * Check the console output for:
 *   [mutflow] WARNING: Trap not found: (Calculator.kt:999) > → >=
 *   [mutflow]   Available mutations:
 *   [mutflow]     (Calculator.kt:8) 0 → -1
 *   ...
 */
@MutFlowTest(
    maxRuns = 2,
    selection = Selection.MostLikelyStable,
    shuffle = Shuffle.PerChange,
    // This trap won't match any mutation (wrong line number)
    traps = ["(Calculator.kt:999) > → >="]
)
class TrapNotFoundTest {

    private val calculator = Calculator()

    @Test
    fun `isPositive returns true for positive numbers`() {
        val result = MutFlow.underTest {
            calculator.isPositive(5)
        }
        assertTrue(result, "isPositive(5) should be true")
    }

    @Test
    fun `isPositive returns false for zero`() {
        val result = MutFlow.underTest {
            calculator.isPositive(0)
        }
        assertFalse(result, "isPositive(0) should be false")
    }
}
