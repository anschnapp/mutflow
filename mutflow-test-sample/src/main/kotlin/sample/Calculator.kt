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
}
