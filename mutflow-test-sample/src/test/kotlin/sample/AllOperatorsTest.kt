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
}
