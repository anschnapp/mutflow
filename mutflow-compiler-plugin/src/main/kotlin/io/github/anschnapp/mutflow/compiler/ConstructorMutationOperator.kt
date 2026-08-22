package io.github.anschnapp.mutflow.compiler

import org.jetbrains.kotlin.ir.expressions.IrConstructorCall
import org.jetbrains.kotlin.ir.expressions.IrExpression

/**
 * Abstraction for mutation operators that target constructor calls.
 *
 * Each implementation handles a specific category of mutations
 * on constructor invocations (e.g., exception type swaps).
 */
interface ConstructorMutationOperator {

    /**
     * Returns true if this operator can generate mutations for the given constructor call.
     */
    fun matches(call: IrConstructorCall): Boolean

    /**
     * Generates mutation variants for the given constructor call.
     *
     * @param call The original IR constructor call expression
     * @param context Context providing access to plugin context and IR builder
     * @return List of variants (not including the original)
     */
    fun variants(call: IrConstructorCall, context: MutationContext): List<MutationOperator.Variant>

    /**
     * Returns a description of the original constructor for display.
     * Example: "IllegalArgumentException" for an exception constructor.
     */
    fun originalDescription(call: IrConstructorCall): String
}
