package io.github.anschnapp.mutflow.compiler

import org.jetbrains.kotlin.ir.expressions.IrConstructorCall

/**
 * Abstraction for mutation operators that match on [IrConstructorCall] nodes.
 *
 * In Kotlin 2.4.0 `IrConstructorCall` is a sibling of [org.jetbrains.kotlin.ir.expressions.IrCall]
 * (both extend [org.jetbrains.kotlin.ir.expressions.IrFunctionAccessExpression]), so
 * constructor calls never reach [MutationOperator] and need their own visitor path
 * in the transformer.
 */
interface ConstructorCallMutationOperator {

    /** Declarative metadata for this operator (stable id, group, status). */
    val descriptor: MutatorDescriptor

    /** Returns true if this operator can generate mutations for the given constructor call. */
    fun matches(call: IrConstructorCall): Boolean

    /** Generates mutation variants for the given constructor call. */
    fun variants(call: IrConstructorCall, context: MutationContext): List<MutationOperator.Variant>

    /** Returns a description of the original constructor for display. */
    fun originalDescription(call: IrConstructorCall): String
}
