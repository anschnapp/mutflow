package sample

import io.github.anschnapp.mutflow.MutFlow
import io.github.anschnapp.mutflow.Selection
import io.github.anschnapp.mutflow.Shuffle
import io.github.anschnapp.mutflow.junit.MutFlowTest
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Comprehensive @MutFlowTest covering all Calculator methods.
 *
 * With @MutFlowTest, the test class runs multiple times:
 * - Run 0 (Baseline): All tests run, mutation points discovered
 * - Run 1+ (Mutation runs): All tests run with one mutation active
 *
 * Tests include boundary values to kill ConstantBoundary (+1/-1) mutations.
 */
@MutFlowTest(maxRuns = 500, selection = Selection.MostLikelyStable, shuffle = Shuffle.PerChange)
class CalculatorJUnitTest {

    private val calculator = Calculator()

    // ==================== isPositive: x > 0 ====================
    // Mutations: > → >=, > → <, 0 → 1, 0 → -1

    @Test
    fun `isPositive returns true for positive numbers`() {
        val result = MutFlow.underTest { calculator.isPositive(5) }
        assertTrue(result, "isPositive(5) should be true")
    }

    @Test
    fun `isPositive returns false for negative numbers`() {
        val result = MutFlow.underTest { calculator.isPositive(-5) }
        assertFalse(result, "isPositive(-5) should be false")
    }

    @Test
    fun `isPositive returns false for zero`() {
        // Kills: > → >= (0 >= 0 = true), 0 → -1 (0 > -1 = true)
        val result = MutFlow.underTest { calculator.isPositive(0) }
        assertFalse(result, "isPositive(0) should be false")
    }

    @Test
    fun `isPositive returns true at boundary`() {
        // Kills: 0 → 1 (1 > 1 = false)
        val result = MutFlow.underTest { calculator.isPositive(1) }
        assertTrue(result, "isPositive(1) should be true")
    }

    // ==================== isNegative: x < 0 ====================
    // Mutations: < → <=, < → >, 0 → 1, 0 → -1

    @Test
    fun `isNegative returns true for negative numbers`() {
        val result = MutFlow.underTest { calculator.isNegative(-5) }
        assertTrue(result, "isNegative(-5) should be true")
    }

    @Test
    fun `isNegative returns false for positive numbers`() {
        val result = MutFlow.underTest { calculator.isNegative(5) }
        assertFalse(result, "isNegative(5) should be false")
    }

    @Test
    fun `isNegative returns false for zero`() {
        // Kills: < → <= (0 <= 0 = true), 0 → 1 (0 < 1 = true)
        val result = MutFlow.underTest { calculator.isNegative(0) }
        assertFalse(result, "isNegative(0) should be false")
    }

    @Test
    fun `isNegative returns true at boundary`() {
        // Kills: 0 → -1 (-1 < -1 = false)
        val result = MutFlow.underTest { calculator.isNegative(-1) }
        assertTrue(result, "isNegative(-1) should be true")
    }

    // ==================== isZero: x == 0 ====================
    // Mutations: == → !=

    @Test
    fun `isZero returns true for zero`() {
        val result = MutFlow.underTest { calculator.isZero(0) }
        assertTrue(result, "isZero(0) should be true")
    }

    @Test
    fun `isZero returns false for non-zero`() {
        val result = MutFlow.underTest { calculator.isZero(5) }
        assertFalse(result, "isZero(5) should be false")
    }

    // ==================== isNonNegative: x >= 0 ====================
    // Mutations: >= → >, >= → <=, 0 → 1, 0 → -1

    @Test
    fun `isNonNegative returns true for positive numbers`() {
        val result = MutFlow.underTest { calculator.isNonNegative(5) }
        assertTrue(result, "isNonNegative(5) should be true")
    }

    @Test
    fun `isNonNegative returns false for negative numbers`() {
        val result = MutFlow.underTest { calculator.isNonNegative(-5) }
        assertFalse(result, "isNonNegative(-5) should be false")
    }

    @Test
    fun `isNonNegative returns true for zero`() {
        // Kills: >= → > (0 > 0 = false), 0 → 1 (0 >= 1 = false)
        val result = MutFlow.underTest { calculator.isNonNegative(0) }
        assertTrue(result, "isNonNegative(0) should be true")
    }

    @Test
    fun `isNonNegative returns false at boundary`() {
        // Kills: 0 → -1 (-1 >= -1 = true)
        val result = MutFlow.underTest { calculator.isNonNegative(-1) }
        assertFalse(result, "isNonNegative(-1) should be false")
    }

    // ==================== isNonPositive: x <= 0 ====================
    // Mutations: <= → <, <= → >=, 0 → 1, 0 → -1

    @Test
    fun `isNonPositive returns true for negative numbers`() {
        val result = MutFlow.underTest { calculator.isNonPositive(-5) }
        assertTrue(result, "isNonPositive(-5) should be true")
    }

    @Test
    fun `isNonPositive returns false for positive numbers`() {
        val result = MutFlow.underTest { calculator.isNonPositive(5) }
        assertFalse(result, "isNonPositive(5) should be false")
    }

    @Test
    fun `isNonPositive returns true for zero`() {
        // Kills: <= → < (0 < 0 = false), 0 → -1 (0 <= -1 = false)
        val result = MutFlow.underTest { calculator.isNonPositive(0) }
        assertTrue(result, "isNonPositive(0) should be true")
    }

    @Test
    fun `isNonPositive returns false at boundary`() {
        // Kills: 0 → 1 (1 <= 1 = true)
        val result = MutFlow.underTest { calculator.isNonPositive(1) }
        assertFalse(result, "isNonPositive(1) should be false")
    }

    // ==================== isInRange: x >= min && x <= max ====================
    // Mutations: >= → >, >= → <=, <= → <, <= → >=, return → true, return → false
    // (no ConstantBoundary — min/max are parameters, not literals)

    @Test
    fun `isInRange returns true for value in range`() {
        val result = MutFlow.underTest { calculator.isInRange(5, 0, 10) }
        assertTrue(result, "isInRange(5, 0, 10) should be true")
    }

    @Test
    fun `isInRange returns false for value below range`() {
        val result = MutFlow.underTest { calculator.isInRange(-1, 0, 10) }
        assertFalse(result, "isInRange(-1, 0, 10) should be false")
    }

    @Test
    fun `isInRange returns false for value above range`() {
        val result = MutFlow.underTest { calculator.isInRange(11, 0, 10) }
        assertFalse(result, "isInRange(11, 0, 10) should be false")
    }

    @Test
    fun `isInRange returns true at lower boundary`() {
        // Kills: >= → > (0 > 0 = false)
        val result = MutFlow.underTest { calculator.isInRange(0, 0, 10) }
        assertTrue(result, "isInRange(0, 0, 10) should be true")
    }

    @Test
    fun `isInRange returns true at upper boundary`() {
        // Kills: <= → < (10 < 10 = false)
        val result = MutFlow.underTest { calculator.isInRange(10, 0, 10) }
        assertTrue(result, "isInRange(10, 0, 10) should be true")
    }

    // ==================== getPositiveOrNull: if (x > 0) return x; return null ====================
    // Mutations: > → >=, > → <, 0 → 1, 0 → -1, return x → return null

    @Test
    fun `getPositiveOrNull returns value for positive`() {
        val result = MutFlow.underTest { calculator.getPositiveOrNull(5) }
        assertEquals(5, result, "getPositiveOrNull(5) should be 5")
    }

    @Test
    fun `getPositiveOrNull returns null for negative`() {
        val result = MutFlow.underTest { calculator.getPositiveOrNull(-5) }
        assertNull(result, "getPositiveOrNull(-5) should be null")
    }

    @Test
    fun `getPositiveOrNull returns null for zero`() {
        // Kills: > → >= (0 >= 0 = true → returns 0), 0 → -1 (0 > -1 = true → returns 0)
        val result = MutFlow.underTest { calculator.getPositiveOrNull(0) }
        assertNull(result, "getPositiveOrNull(0) should be null")
    }

    @Test
    fun `getPositiveOrNull returns value at boundary`() {
        // Kills: 0 → 1 (1 > 1 = false → returns null)
        val result = MutFlow.underTest { calculator.getPositiveOrNull(1) }
        assertEquals(1, result, "getPositiveOrNull(1) should be 1")
    }

    // ==================== recordResult: void function body ====================
    // Mutations: void body removal (body skipped entirely)

    @Test
    fun `recordResult updates state`() {
        MutFlow.underTest { calculator.recordResult(42) }
        // Kills: void body removal (body skipped → lastResult stays 0)
        assertEquals(42, calculator.lastResult, "recordResult(42) should set lastResult to 42")
    }

    // ==================== Arithmetic operators ====================
    // add: + → -, subtract: - → +, multiply: * → /, divide: / → *, modulo: % → /

    @Test
    fun `add returns correct sum`() {
        val result = MutFlow.underTest { calculator.add(5, 3) }
        assertEquals(8, result, "add(5, 3) should be 8")
    }

    @Test
    fun `subtract returns correct difference`() {
        val result = MutFlow.underTest { calculator.subtract(5, 3) }
        assertEquals(2, result, "subtract(5, 3) should be 2")
    }

    @Test
    fun `multiply returns correct product`() {
        val result = MutFlow.underTest { calculator.multiply(6, 3) }
        assertEquals(18, result, "multiply(6, 3) should be 18")
    }

    @Test
    fun `divide returns correct quotient`() {
        val result = MutFlow.underTest { calculator.divide(6, 3) }
        assertEquals(2, result, "divide(6, 3) should be 2")
    }

    @Test
    fun `modulo returns correct remainder`() {
        val result = MutFlow.underTest { calculator.modulo(7, 3) }
        assertEquals(1, result, "modulo(7, 3) should be 1")
    }

    // ==================== Equality swap: == ↔ != ====================

    @Test
    fun `isNotZero returns true for non-zero`() {
        val result = MutFlow.underTest { calculator.isNotZero(5) }
        assertTrue(result, "isNotZero(5) should be true")
    }

    @Test
    fun `isNotZero returns false for zero`() {
        // Kills: != → == (0 == 0 = true)
        val result = MutFlow.underTest { calculator.isNotZero(0) }
        assertFalse(result, "isNotZero(0) should be false")
    }

    // ==================== mixedSuppression: a > 0 (suppressed) && b > 0 ====================
    // Mutations on b > 0: > → >=, > → <, 0 → 1, 0 → -1
    // Plus: return → true, return → false

    @Test
    fun `mixedSuppression returns true when both positive`() {
        val result = MutFlow.underTest { calculator.mixedSuppression(5, 5) }
        assertTrue(result, "mixedSuppression(5, 5) should be true")
    }

    @Test
    fun `mixedSuppression returns false when b is zero`() {
        // Kills: > → >= (0 >= 0 = true), 0 → -1 (0 > -1 = true)
        val result = MutFlow.underTest { calculator.mixedSuppression(5, 0) }
        assertFalse(result, "mixedSuppression(5, 0) should be false")
    }

    @Test
    fun `mixedSuppression returns true when b is at boundary`() {
        // Kills: 0 → 1 (1 > 1 = false)
        val result = MutFlow.underTest { calculator.mixedSuppression(5, 1) }
        assertTrue(result, "mixedSuppression(5, 1) should be true")
    }

    @Test
    fun `mixedSuppression returns false when a is negative`() {
        val result = MutFlow.underTest { calculator.mixedSuppression(-5, 5) }
        assertFalse(result, "mixedSuppression(-5, 5) should be false")
    }
}
