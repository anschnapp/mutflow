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
}
