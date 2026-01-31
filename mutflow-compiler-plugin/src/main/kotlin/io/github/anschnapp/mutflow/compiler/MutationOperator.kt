package io.github.anschnapp.mutflow.compiler

import org.jetbrains.kotlin.backend.common.extensions.IrPluginContext
import org.jetbrains.kotlin.ir.builders.IrBuilderWithScope
import org.jetbrains.kotlin.ir.expressions.IrCall
import org.jetbrains.kotlin.ir.expressions.IrExpression

/**
 * Abstraction for mutation operators.
 *
 * Each implementation handles a specific category of mutations
 * (e.g., relational comparisons, arithmetic operations, boolean logic).
 */
interface MutationOperator {

    /**
     * Returns true if this operator can generate mutations for the given call.
     */
    fun matches(call: IrCall): Boolean

    /**
     * Generates mutation variants for the given call.
     *
     * @param call The original IR call expression
     * @param context Context providing access to plugin context and IR builder
     * @return List of variants (not including the original)
     */
    fun variants(call: IrCall, context: MutationContext): List<Variant>

    /**
     * Returns a description of the original operator for display.
     * Example: ">" for greater-than comparisons.
     */
    fun originalDescription(call: IrCall): String

    /**
     * A mutation variant with its description and expression generator.
     */
    data class Variant(
        /** Description of the variant for display (e.g., ">=", "<") */
        val description: String,
        /** Creates the IR expression for this variant */
        val createExpression: () -> IrExpression
    )
}

/**
 * Context passed to mutation operators during variant generation.
 */
data class MutationContext(
    val pluginContext: IrPluginContext,
    val builder: IrBuilderWithScope
)
