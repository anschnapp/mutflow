package io.github.anschnapp.mutflow.compiler

import org.jetbrains.kotlin.ir.expressions.IrThrow

/**
 * Abstraction for mutation operators that target throw statements.
 *
 * Each implementation handles a specific category of mutations
 * on thrown values (e.g., exception type swaps).
 *
 * The interface operates directly on [IrThrow] so that the dispatch point
 * (`visitThrow` in [MutflowIrTransformer]) and the operator contract agree on
 * the node shape.
 */
interface ThrowMutationOperator {

    /**
     * Returns true if the throw expression has the node shape this operator handles.
     *
     * This is a shape check only. Whether a mutation is actually available
     * (for example, whether a swap pair exists for the thrown type) is decided
     * in [variants], which returns an empty list when there is nothing to mutate.
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
