package sample

import io.github.anschnapp.mutflow.MutFlow
import io.github.anschnapp.mutflow.Selection
import io.github.anschnapp.mutflow.Shuffle
import io.github.anschnapp.mutflow.junit.MutFlowTest
import org.junit.jupiter.api.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Demonstrates the @MutFlowTest annotation approach.
 *
 * With @MutFlowTest, the test class runs multiple times:
 * - Run 0 (Baseline): All tests run, mutation points discovered
 * - Run 1+ (Mutation runs): All tests run with one mutation active
 *
 * Tests use the parameterless MutFlow.underTest { } - the JUnit extension
 * manages session lifecycle and run numbers automatically.
 */
@MutFlowTest(maxRuns = 5, selection = Selection.MostLikelyStable, shuffle = Shuffle.PerChange)
class CalculatorJUnitTest {

    private val calculator = Calculator()

    @Test
    fun `isPositive returns true for positive numbers`() {
        val result = MutFlow.underTest {
            calculator.isPositive(5)
        }
        // During baseline (run 0): result is true (original behavior)
        // During mutation runs: result may differ depending on active mutation
        // If assertion fails during mutation run = mutation killed (good!)
        assertTrue(result, "isPositive(5) should be true")
    }

    @Test
    fun `isPositive returns true at boundary`() {
        // This test catches the constant boundary mutation 0 → 1
        // If mutation is active: x > 1 means isPositive(1) returns false
        val result = MutFlow.underTest {
            calculator.isPositive(1)
        }
        assertTrue(result, "isPositive(1) should be true - it's positive!")
    }

    @Test
    fun `isPositive returns false for negative numbers`() {
        val result = MutFlow.underTest {
            calculator.isPositive(-5)
        }
        assertFalse(result, "isPositive(-5) should be false")
    }

    @Test
    fun `isPositive returns false for zero`() {
        val result = MutFlow.underTest {
            calculator.isPositive(0)
        }
        assertFalse(result, "isPositive(0) should be false")
    }
}
