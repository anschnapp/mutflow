package io.github.anschnapp.mutflow.compiler

import org.jetbrains.kotlin.ir.expressions.IrExpression
import org.jetbrains.kotlin.ir.types.IrType

/**
 * Mutation operators that target assignment nodes — [org.jetbrains.kotlin.ir.expressions.IrSetValue]
 * and [org.jetbrains.kotlin.ir.expressions.IrSetField] — which are distinct IR
 * node types from [IrCall], so they get their own visitor path in the transformer.
 */
interface AssignmentMutationOperator {

    /** Declarative metadata for this operator (stable id, group, status). */
    val descriptor: MutatorDescriptor

    /** Whether this operator can mutate an assignment to [targetType] of [assignedValue]. */
    fun matches(targetType: IrType, assignedValue: IrExpression): Boolean

    /** Generates mutation variants for the given assignment. */
    fun variants(
        targetType: IrType,
        assignedValue: IrExpression,
        context: MutationContext
    ): List<MutationOperator.Variant>

    /** Returns a description of the original assignment for display. */
    fun originalDescription(assignedValue: IrExpression): String
}
