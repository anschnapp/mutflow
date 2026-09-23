package sample

import io.github.anschnapp.mutflow.MutationTarget
import io.github.anschnapp.mutflow.SuppressMutations

/**
 * Operands with a visible side effect: every [next] call is recorded, so a test can assert that
 * a mutation evaluates each operand exactly once and in source order, for the original and for
 * every variant alike. The helpers themselves are not mutated, so the only points are the
 * operators under test.
 */
@MutationTarget
class OperandEvaluationTarget {

    val evaluated = mutableListOf<Int>()

    fun reset() {
        evaluated.clear()
    }

    @SuppressMutations
    private fun next(value: Int): Int {
        evaluated.add(value)
        return value
    }

    @SuppressMutations
    private fun isOdd(value: Int): Boolean {
        evaluated.add(value)
        return value % 2 == 1
    }

    @SuppressMutations
    private fun fail(): Int = throw UnsupportedOperationException("operand failed")

    /** `+`, mutated to `-`. */
    fun minus(a: Int, b: Int): Int = next(a) + next(b)

    /** `*`, mutated to a safe `/` that reads each operand up to twice. */
    fun times(a: Int, b: Int): Int = next(a) * next(b)

    /** `>` with a constant: a relational and a constant boundary mutation on one call. */
    fun positive(a: Int): Boolean = next(a) > 0

    /** `==`, mutated to `!=`. */
    fun same(a: Int, b: Int): Boolean = next(a) == next(b)

    /** A boolean call, mutated to its negation. */
    fun odd(a: Int): Boolean = isOdd(a)

    /** An operand that throws before the `+` can be computed. */
    fun failing(a: Int): Int = fail() + a

    /** An explicit return of a comparison, whose mutation is hoisted before the return's own. */
    fun explicitReturn(a: Int): Boolean {
        return next(a) > 0
    }
}
