package io.github.anschnapp.mutflow.compiler

import org.jetbrains.kotlin.ir.expressions.IrThrow

/**
 * Abstraction for mutation operators that target throw statements.
 *
 * Each implementation handles a specific category of mutations
 * on thrown values (e.g., exception type swaps).
 *
 * Unlike the previous constructor-based interface which operated on the inner
 * [IrConstructorCall], this interface operates directly on [IrThrow],
 * aligning the dispatch point (visitThrow) with the operator contract.
 *
 * Synthetic throws — from `!!`, exhaustive `when` without else, `TODO()`,
 * and inlined `require`/`check` — are filtered in [matches] using
 * offset-based heuristics, the same approach used by
 * [BooleanReturnOperator] for synthetic returns.
 */
interface ThrowMutationOperator {

    /**
     * Returns true if this operator can generate mutations for the given throw expression.
     *
     * Implementations should filter synthetic throws (offset-based check)
     * and return false for throws that don't match the operator's category.
     */
    fun matches(throwExpr: IrThrow): Boolean

    /**
     * Generates mutation variants for the given throw expression.
     *
     * @param throwExpr The original IR throw expression
     * @param context Context providing access to plugin context and IR builder
     * @return List of variants (not including the original)
     */
    fun variants(throwExpr: IrThrow, context: MutationContext): List<MutationOperator.Variant>

    /**
     * Returns a description of the original thrown value for display.
     * Example: "IllegalArgumentException" for `throw IllegalArgumentException()`.
     */
    fun originalDescription(throwExpr: IrThrow): String
}
