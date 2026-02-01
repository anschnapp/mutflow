package sample

import io.github.anschnapp.mutflow.MutFlow
import io.github.anschnapp.mutflow.Selection
import io.github.anschnapp.mutflow.Shuffle
import io.github.anschnapp.mutflow.junit.MutFlowTest
import org.junit.jupiter.api.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Tests the trap mechanism for pinning specific mutations.
 *
 * Traps allow you to test specific mutations first, regardless of selection strategy.
 * This is useful for debugging surviving mutants.
 */
@MutFlowTest(
    maxRuns = 3,
    selection = Selection.MostLikelyStable,
    shuffle = Shuffle.PerChange,
    // Trap the constant boundary mutation - it will run first
    traps = ["(Calculator.kt:8) 0 → -1"]
)
class TrapMechanismTest {

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
