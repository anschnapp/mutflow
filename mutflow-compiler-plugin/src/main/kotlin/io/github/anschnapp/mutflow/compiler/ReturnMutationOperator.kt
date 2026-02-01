package io.github.anschnapp.mutflow.compiler

import org.jetbrains.kotlin.ir.expressions.IrReturn

/**
 * Abstraction for mutation operators that target return statements.
 *
 * Each implementation handles a specific category of return mutations
 * (e.g., boolean return value replacement).
 */
interface ReturnMutationOperator {

    /**
     * Returns true if this operator can generate mutations for the given return statement.
     */
    fun matches(ret: IrReturn): Boolean

    /**
     * Generates mutation variants for the given return statement.
     *
     * @param ret The original IR return statement
     * @param context Context providing access to plugin context and IR builder
     * @return List of variants (not including the original)
     */
    fun variants(ret: IrReturn, context: MutationContext): List<MutationOperator.Variant>

    /**
     * Returns a description of the original return value for display.
     * Example: "return true" or "return <expr>".
     */
    fun originalDescription(ret: IrReturn): String
}
