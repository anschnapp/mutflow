package sample

import io.github.anschnapp.mutflow.MutationTarget
import io.github.anschnapp.mutflow.SuppressMutations

@MutationTarget
class Calculator {
    fun isPositive(x: Int): Boolean = x > 0

    fun isNegative(x: Int): Boolean = x < 0

    fun isZero(x: Int): Boolean = x == 0

    fun isNonNegative(x: Int): Boolean = x >= 0

    fun isNonPositive(x: Int): Boolean = x <= 0

    @SuppressMutations
    fun debugLog(x: Int): Boolean {
        // This comparison should NOT be mutated
        return x > 100
    }

    /**
     * Function with explicit return statement (block body) for testing return mutations.
     * This will have:
     * - Operator mutations on >= and <=
     * - Constant mutations on min/max if they were literals
     * - Return value mutation (true/false)
     */
    fun isInRange(x: Int, min: Int, max: Int): Boolean {
        return x >= min && x <= max
    }

    /**
     * Function with nullable return type for testing null return mutations.
     * Returns the absolute value if positive, null otherwise.
     *
     * This will have:
     * - Operator mutation on >
     * - Constant boundary mutation on 0
     * - Null return mutation (return null instead of x)
     */
    fun getPositiveOrNull(x: Int): Int? {
        if (x > 0) {
            return x
        }
        return null
    }

    // --- Void function for testing void body removal mutation ---

    /** Mutable state to verify side effects. */
    var lastResult: Int = 0

    /**
     * Void function that performs a side effect (updates state).
     * The void body removal mutation will replace this with a no-op.
     */
    fun recordResult(value: Int) {
        lastResult = value
    }

    // --- Arithmetic operations for testing arithmetic mutations ---

    /**
     * Simple addition for testing + → - mutation.
     */
    fun add(a: Int, b: Int): Int = a + b

    /**
     * Simple subtraction for testing - → + mutation.
     */
    fun subtract(a: Int, b: Int): Int = a - b

    /**
     * Simple multiplication for testing * → / mutation.
     * Note: * → / uses safe division to avoid div-by-zero.
     */
    fun multiply(a: Int, b: Int): Int = a * b

    /**
     * Simple division for testing / → * mutation.
     */
    fun divide(a: Int, b: Int): Int = a / b

    /**
     * Simple modulo for testing % → / mutation.
     */
    fun modulo(a: Int, b: Int): Int = a % b

    // --- Comment-based line suppression tests ---

    /**
     * Inline mutflow:ignore comment — the comparison on this line should NOT be mutated.
     */
    fun isLargeIgnored(x: Int): Boolean = x > 100 // mutflow:ignore this is just a heuristic threshold

    /**
     * Standalone mutflow:falsePositive comment — the comparison on the next line should NOT be mutated.
     */
    fun isSmallFalsePositive(x: Int): Boolean {
        // mutflow:falsePositive equivalent mutant, boundary doesn't matter here
        return x < 10
    }

    /**
     * Mixed: one suppressed line and one normal line in the same function.
     * Only the non-suppressed comparison should produce mutations.
     */
    fun mixedSuppression(a: Int, b: Int): Boolean {
        val first = a > 0 // mutflow:ignore not relevant
        val second = b > 0
        return first && second
    }

    // --- Equality swap operations for testing == ↔ != mutations ---

    fun isNotZero(x: Int): Boolean = x != 0
}
