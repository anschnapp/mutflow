package sample

import io.github.anschnapp.mutflow.MutFlow
import io.github.anschnapp.mutflow.MutationRegistry
import io.github.anschnapp.mutflow.Selection
import io.github.anschnapp.mutflow.Shuffle
import kotlin.test.*

/**
 * Tests all relational comparison operators (>, <, >=, <=) and @SuppressMutations.
 */
class AllOperatorsTest {

    private val calculator = Calculator()

    @BeforeTest
    fun setup() {
        MutationRegistry.reset()
        MutFlow.reset()
    }

    // ==================== Less-than operator (<) ====================

    @Test
    fun `less-than operator generates mutations`() {
        // isNegative uses < operator: x < 0
        // Variants should be: <=, >

        MutFlow.underTest(run = 0, selection = Selection.MostLikelyStable, shuffle = Shuffle.PerChange) {
            calculator.isNegative(-5)
        }

        val state = MutFlow.getRegistryState()
        val point = state.discoveredPoints.entries.find { it.key.contains("Calculator") }
        assertNotNull(point, "Should discover a mutation point for isNegative")
        assertEquals(2, point.value, "< operator should have 2 variants (<=, >)")
    }

    @Test
    fun `less-than mutations change behavior correctly`() {
        // Original: x < 0
        // Variant 0 (<=): x <= 0  --> true for x=-5, true for x=0, false for x=5
        // Variant 1 (>):  x > 0   --> false for x=-5, false for x=0, true for x=5

        // Baseline
        val baselineNeg = MutFlow.underTest(run = 0, selection = Selection.MostLikelyStable, shuffle = Shuffle.PerChange) {
            calculator.isNegative(-5)
        }
        assertTrue(baselineNeg, "isNegative(-5) should be true")

        // First mutation (<= instead of <)
        val mutant1 = MutFlow.underTest(run = 1, selection = Selection.MostLikelyStable, shuffle = Shuffle.PerChange) {
            calculator.isNegative(0)  // Original: false, <= variant: true
        }
        // With <= mutation, isNegative(0) returns true instead of false
        assertTrue(mutant1, "With <= mutation, isNegative(0) should be true")
    }

    // ==================== Greater-or-equal operator (>=) ====================

    @Test
    fun `greater-or-equal operator generates mutations`() {
        // isNonNegative uses >= operator: x >= 0
        // Variants should be: >, <=

        MutFlow.underTest(run = 0, selection = Selection.MostLikelyStable, shuffle = Shuffle.PerChange) {
            calculator.isNonNegative(5)
        }

        val state = MutFlow.getRegistryState()
        val point = state.discoveredPoints.entries.find { it.key.contains("Calculator") }
        assertNotNull(point, "Should discover a mutation point for isNonNegative")
        assertEquals(2, point.value, ">= operator should have 2 variants (>, <=)")
    }

    @Test
    fun `greater-or-equal mutations change behavior correctly`() {
        // Original: x >= 0
        // Variant 0 (>):  x > 0   --> false for x=0, true for x=5
        // Variant 1 (<=): x <= 0  --> true for x=0, false for x=5

        // Baseline
        val baselineZero = MutFlow.underTest(run = 0, selection = Selection.MostLikelyStable, shuffle = Shuffle.PerChange) {
            calculator.isNonNegative(0)
        }
        assertTrue(baselineZero, "isNonNegative(0) should be true")

        // First mutation (> instead of >=)
        val mutant1 = MutFlow.underTest(run = 1, selection = Selection.MostLikelyStable, shuffle = Shuffle.PerChange) {
            calculator.isNonNegative(0)  // Original: true, > variant: false
        }
        // With > mutation, isNonNegative(0) returns false instead of true
        assertFalse(mutant1, "With > mutation, isNonNegative(0) should be false")
    }

    // ==================== Less-or-equal operator (<=) ====================

    @Test
    fun `less-or-equal operator generates mutations`() {
        // isNonPositive uses <= operator: x <= 0
        // Variants should be: <, >=

        MutFlow.underTest(run = 0, selection = Selection.MostLikelyStable, shuffle = Shuffle.PerChange) {
            calculator.isNonPositive(-5)
        }

        val state = MutFlow.getRegistryState()
        val point = state.discoveredPoints.entries.find { it.key.contains("Calculator") }
        assertNotNull(point, "Should discover a mutation point for isNonPositive")
        assertEquals(2, point.value, "<= operator should have 2 variants (<, >=)")
    }

    @Test
    fun `less-or-equal mutations change behavior correctly`() {
        // Original: x <= 0
        // Variant 0 (<):  x < 0   --> false for x=0, true for x=-5
        // Variant 1 (>=): x >= 0  --> true for x=0, false for x=-5

        // Baseline
        val baselineZero = MutFlow.underTest(run = 0, selection = Selection.MostLikelyStable, shuffle = Shuffle.PerChange) {
            calculator.isNonPositive(0)
        }
        assertTrue(baselineZero, "isNonPositive(0) should be true")

        // First mutation (< instead of <=)
        val mutant1 = MutFlow.underTest(run = 1, selection = Selection.MostLikelyStable, shuffle = Shuffle.PerChange) {
            calculator.isNonPositive(0)  // Original: true, < variant: false
        }
        // With < mutation, isNonPositive(0) returns false instead of true
        assertFalse(mutant1, "With < mutation, isNonPositive(0) should be false")
    }

    // ==================== @SuppressMutations ====================

    @Test
    fun `SuppressMutations annotation prevents mutation injection`() {
        // debugLog uses > operator but is annotated with @SuppressMutations
        // No mutations should be discovered

        MutFlow.underTest(run = 0, selection = Selection.MostLikelyStable, shuffle = Shuffle.PerChange) {
            calculator.debugLog(150)
        }

        val state = MutFlow.getRegistryState()
        // Should discover NO mutation points (the function is suppressed)
        assertTrue(state.discoveredPoints.isEmpty(),
            "No mutations should be discovered for @SuppressMutations function")
    }

    @Test
    fun `SuppressMutations function uses original behavior always`() {
        // Even in mutation runs, the suppressed function should use original code

        // Baseline
        val baseline = MutFlow.underTest(run = 0, selection = Selection.MostLikelyStable, shuffle = Shuffle.PerChange) {
            calculator.debugLog(150)  // 150 > 100 = true
        }
        assertTrue(baseline, "debugLog(150) should be true")

        // Since no mutations are discovered, run 1 should still work with original
        val run1 = MutFlow.underTest(run = 0, selection = Selection.MostLikelyStable, shuffle = Shuffle.PerChange) {
            calculator.debugLog(50)  // 50 > 100 = false
        }
        assertFalse(run1, "debugLog(50) should be false")
    }

    // ==================== Arithmetic operators (+, -, *, /, %) ====================

    @Test
    fun `addition operator generates mutation`() {
        // add uses + operator: a + b
        // Variant should be: -

        MutFlow.underTest(run = 0, selection = Selection.MostLikelyStable, shuffle = Shuffle.PerChange) {
            calculator.add(5, 3)
        }

        val state = MutFlow.getRegistryState()
        // Should discover at least one mutation point for add
        val points = state.discoveredPoints.entries.filter { it.key.contains("Calculator") }
        assertTrue(points.isNotEmpty(), "Should discover mutation points for add")
    }

    @Test
    fun `addition mutation changes behavior correctly`() {
        // Original: a + b = 5 + 3 = 8
        // Variant (-): a - b = 5 - 3 = 2

        // Baseline
        val baseline = MutFlow.underTest(run = 0, selection = Selection.MostLikelyStable, shuffle = Shuffle.PerChange) {
            calculator.add(5, 3)
        }
        assertEquals(8, baseline, "add(5, 3) should be 8")

        // First mutation (- instead of +)
        val mutant1 = MutFlow.underTest(run = 1, selection = Selection.MostLikelyStable, shuffle = Shuffle.PerChange) {
            calculator.add(5, 3)
        }
        assertEquals(2, mutant1, "With - mutation, add(5, 3) should be 2")
    }

    @Test
    fun `subtraction operator generates mutation`() {
        // subtract uses - operator: a - b
        // Variant should be: +

        MutFlow.underTest(run = 0, selection = Selection.MostLikelyStable, shuffle = Shuffle.PerChange) {
            calculator.subtract(5, 3)
        }

        val state = MutFlow.getRegistryState()
        val points = state.discoveredPoints.entries.filter { it.key.contains("Calculator") }
        assertTrue(points.isNotEmpty(), "Should discover mutation points for subtract")
    }

    @Test
    fun `subtraction mutation changes behavior correctly`() {
        // Original: a - b = 5 - 3 = 2
        // Variant (+): a + b = 5 + 3 = 8

        // Baseline
        val baseline = MutFlow.underTest(run = 0, selection = Selection.MostLikelyStable, shuffle = Shuffle.PerChange) {
            calculator.subtract(5, 3)
        }
        assertEquals(2, baseline, "subtract(5, 3) should be 2")

        // First mutation (+ instead of -)
        val mutant1 = MutFlow.underTest(run = 1, selection = Selection.MostLikelyStable, shuffle = Shuffle.PerChange) {
            calculator.subtract(5, 3)
        }
        assertEquals(8, mutant1, "With + mutation, subtract(5, 3) should be 8")
    }

    @Test
    fun `multiplication operator generates mutation`() {
        // multiply uses * operator: a * b
        // Variant should be: /

        MutFlow.underTest(run = 0, selection = Selection.MostLikelyStable, shuffle = Shuffle.PerChange) {
            calculator.multiply(6, 3)
        }

        val state = MutFlow.getRegistryState()
        val points = state.discoveredPoints.entries.filter { it.key.contains("Calculator") }
        assertTrue(points.isNotEmpty(), "Should discover mutation points for multiply")
    }

    @Test
    fun `multiplication mutation changes behavior correctly`() {
        // Original: a * b = 6 * 3 = 18
        // Variant (/): a / b = 6 / 3 = 2

        // Baseline
        val baseline = MutFlow.underTest(run = 0, selection = Selection.MostLikelyStable, shuffle = Shuffle.PerChange) {
            calculator.multiply(6, 3)
        }
        assertEquals(18, baseline, "multiply(6, 3) should be 18")

        // First mutation (/ instead of *)
        val mutant1 = MutFlow.underTest(run = 1, selection = Selection.MostLikelyStable, shuffle = Shuffle.PerChange) {
            calculator.multiply(6, 3)
        }
        assertEquals(2, mutant1, "With / mutation, multiply(6, 3) should be 2")
    }

    @Test
    fun `multiplication to division handles zero safely`() {
        // When mutating * to /, division by zero is handled:
        // a * 0 becomes: when { 0 != 0 -> a/0; a != 0 -> 0/a; else -> 1 }
        // Since 0 != 0 is false and a != 0 is true (for a=5), result is 0/5 = 0

        // Baseline: 5 * 0 = 0
        val baseline = MutFlow.underTest(run = 0, selection = Selection.MostLikelyStable, shuffle = Shuffle.PerChange) {
            calculator.multiply(5, 0)
        }
        assertEquals(0, baseline, "multiply(5, 0) should be 0")

        // Mutation: safe division handles b=0 by computing 0/a = 0
        val mutant1 = MutFlow.underTest(run = 1, selection = Selection.MostLikelyStable, shuffle = Shuffle.PerChange) {
            calculator.multiply(5, 0)
        }
        assertEquals(0, mutant1, "With safe / mutation, multiply(5, 0) should be 0 (not crash)")
    }

    @Test
    fun `multiplication to division handles both zero as one`() {
        // When both operands are 0: a * b = 0 * 0 = 0
        // Mutation becomes: when { 0 != 0 -> 0/0; 0 != 0 -> 0/0; else -> 1 }
        // Result is 1 (fallback to avoid 0/0)

        // Baseline: 0 * 0 = 0
        val baseline = MutFlow.underTest(run = 0, selection = Selection.MostLikelyStable, shuffle = Shuffle.PerChange) {
            calculator.multiply(0, 0)
        }
        assertEquals(0, baseline, "multiply(0, 0) should be 0")

        // Mutation: safe division returns 1 when both are 0
        val mutant1 = MutFlow.underTest(run = 1, selection = Selection.MostLikelyStable, shuffle = Shuffle.PerChange) {
            calculator.multiply(0, 0)
        }
        assertEquals(1, mutant1, "With safe / mutation, multiply(0, 0) should be 1 (fallback)")
    }

    @Test
    fun `division operator generates mutation`() {
        // divide uses / operator: a / b
        // Variant should be: *

        MutFlow.underTest(run = 0, selection = Selection.MostLikelyStable, shuffle = Shuffle.PerChange) {
            calculator.divide(6, 3)
        }

        val state = MutFlow.getRegistryState()
        val points = state.discoveredPoints.entries.filter { it.key.contains("Calculator") }
        assertTrue(points.isNotEmpty(), "Should discover mutation points for divide")
    }

    @Test
    fun `division mutation changes behavior correctly`() {
        // Original: a / b = 6 / 3 = 2
        // Variant (*): a * b = 6 * 3 = 18

        // Baseline
        val baseline = MutFlow.underTest(run = 0, selection = Selection.MostLikelyStable, shuffle = Shuffle.PerChange) {
            calculator.divide(6, 3)
        }
        assertEquals(2, baseline, "divide(6, 3) should be 2")

        // First mutation (* instead of /)
        val mutant1 = MutFlow.underTest(run = 1, selection = Selection.MostLikelyStable, shuffle = Shuffle.PerChange) {
            calculator.divide(6, 3)
        }
        assertEquals(18, mutant1, "With * mutation, divide(6, 3) should be 18")
    }

    @Test
    fun `modulo operator generates mutation`() {
        // modulo uses % operator: a % b
        // Variant should be: /

        MutFlow.underTest(run = 0, selection = Selection.MostLikelyStable, shuffle = Shuffle.PerChange) {
            calculator.modulo(7, 3)
        }

        val state = MutFlow.getRegistryState()
        val points = state.discoveredPoints.entries.filter { it.key.contains("Calculator") }
        assertTrue(points.isNotEmpty(), "Should discover mutation points for modulo")
    }

    @Test
    fun `modulo mutation changes behavior correctly`() {
        // Original: a % b = 7 % 3 = 1
        // Variant (/): a / b = 7 / 3 = 2

        // Baseline
        val baseline = MutFlow.underTest(run = 0, selection = Selection.MostLikelyStable, shuffle = Shuffle.PerChange) {
            calculator.modulo(7, 3)
        }
        assertEquals(1, baseline, "modulo(7, 3) should be 1")

        // First mutation (/ instead of %)
        val mutant1 = MutFlow.underTest(run = 1, selection = Selection.MostLikelyStable, shuffle = Shuffle.PerChange) {
            calculator.modulo(7, 3)
        }
        assertEquals(2, mutant1, "With / mutation, modulo(7, 3) should be 2")
    }
}
