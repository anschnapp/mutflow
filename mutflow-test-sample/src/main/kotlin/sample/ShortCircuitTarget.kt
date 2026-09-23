package sample

import io.github.anschnapp.mutflow.MutationTarget

/**
 * Counts how often the right operand of a boolean operator is evaluated, so a test can assert
 * that the mutant short-circuits exactly like the operator it stands for: `a && b` skips `b`
 * when `a` is false, `a || b` skips it when `a` is true.
 */
@MutationTarget
class ShortCircuitTarget {

    var rightOperandEvaluations = 0
        private set

    fun reset() {
        rightOperandEvaluations = 0
    }

    private fun rightOperand(result: Boolean): Boolean {
        rightOperandEvaluations++
        return result
    }

    /** `a && b`, mutated to `a || b`. */
    fun and(a: Boolean, b: Boolean): Boolean = a && rightOperand(b)

    /** `a || b`, mutated to `a && b`. */
    fun or(a: Boolean, b: Boolean): Boolean = a || rightOperand(b)
}
