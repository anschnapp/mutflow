package io.github.anschnapp.mutflow.compiler

import org.jetbrains.kotlin.ir.expressions.IrWhen

/**
 * Abstraction for mutation operators that target when expressions.
 *
 * In Kotlin K2 IR, boolean logic operators (&& and ||) are lowered to
 * IrWhen expressions with specific origins (ANDAND and OROR), not IrCall
 * nodes. This interface handles mutations on those constructs.
 */
interface WhenMutationOperator {

    /**
     * Returns true if this operator can generate mutations for the given when expression.
     */
    fun matches(whenExpr: IrWhen): Boolean

    /**
     * Generates mutation variants for the given when expression.
     *
     * @param whenExpr The original IR when expression
     * @param context Context providing access to plugin context and IR builder
     * @return List of variants (not including the original)
     */
    fun variants(whenExpr: IrWhen, context: MutationContext): List<MutationOperator.Variant>

    /**
     * Returns a description of the original operator for display.
     * Example: "&&" for logical AND.
     */
    fun originalDescription(whenExpr: IrWhen): String
}
