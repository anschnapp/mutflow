package io.github.anschnapp.mutflow.compiler

import org.jetbrains.kotlin.ir.expressions.IrConst

/**
 * Abstraction for mutation operators that target constant expressions
 * (e.g. string literals).
 *
 * Constants are leaf nodes in the IR tree, so unlike [MutationOperator] there
 * is no recursion concern — each constant is a single mutation point.
 */
interface ConstMutationOperator {

    /**
     * Declarative metadata for this operator (stable id, group, status).
     */
    val descriptor: MutatorDescriptor

    /**
     * Returns true if this operator can generate mutations for the given constant.
     */
    fun matches(const: IrConst): Boolean

    /**
     * Generates mutation variants for the given constant.
     *
     * @param const The original IR constant
     * @param context Context providing access to plugin context and IR builder
     * @return List of variants (not including the original)
     */
    fun variants(const: IrConst, context: MutationContext): List<MutationOperator.Variant>

    /**
     * Returns a description of the original operator for display.
     * Example: `"foo"` for a string literal.
     */
    fun originalDescription(const: IrConst): String
}
