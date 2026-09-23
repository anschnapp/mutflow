package io.github.anschnapp.mutflow.compiler

import org.jetbrains.kotlin.backend.common.extensions.IrPluginContext
import org.jetbrains.kotlin.ir.IrElement
import org.jetbrains.kotlin.ir.builders.IrBuilderWithScope
import org.jetbrains.kotlin.ir.declarations.IrSimpleFunction

/**
 * Abstraction for mutation operators.
 *
 * Each implementation handles a specific category of mutations on one kind of IR node
 * (e.g. relational comparisons on [org.jetbrains.kotlin.ir.expressions.IrCall], boolean
 * logic on [org.jetbrains.kotlin.ir.expressions.IrWhen]). What it returns is a [Mutation],
 * whose kind decides how the transformer emits the original next to the variants.
 */
interface MutationOperator<in T : IrElement> {

    /**
     * Returns true if the node has the shape this operator handles.
     *
     * Whether a mutation is actually available (for example, whether a swap pair exists for a
     * thrown type) may still be decided in [mutation], which returns null when there is none.
     */
    fun matches(node: T): Boolean

    /**
     * Returns the mutation for the given node, or null if there is nothing to mutate.
     *
     * Only called for a node that [matches]. Nothing may be changed on the node here: the
     * transformer decides whether and when the builders inside the mutation run.
     */
    fun mutation(node: T, context: MutationContext): Mutation?
}

/**
 * Context passed to mutation operators while building a mutation.
 *
 * [resultUsed] is false when the call sits in statement position and its value is
 * discarded (`list.add(x)` on its own line). A mutation that only changes the
 * returned value of such a call is an equivalent mutant: the program behaves the
 * same and no test can kill it.
 */
data class MutationContext(
    val pluginContext: IrPluginContext,
    val builder: IrBuilderWithScope,
    val containingFunction: IrSimpleFunction,
    val resultUsed: Boolean = true
)
