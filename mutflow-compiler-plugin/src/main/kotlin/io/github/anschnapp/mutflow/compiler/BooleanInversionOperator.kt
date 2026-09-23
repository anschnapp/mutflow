package io.github.anschnapp.mutflow.compiler

import org.jetbrains.kotlin.ir.builders.irCall
import org.jetbrains.kotlin.ir.expressions.IrCall
import org.jetbrains.kotlin.ir.expressions.IrStatementOrigin
import org.jetbrains.kotlin.ir.symbols.UnsafeDuringIrConstructionAPI
import org.jetbrains.kotlin.ir.types.isBoolean

/**
 * Mutation operator for boolean inversion: expr → !expr
 *
 * Matches boolean-returning IrCall nodes (plain function calls and property
 * accesses) and wraps them in `Boolean.not()`. The "remove negation" case
 * (!expr → expr) is implicitly covered because adding `!` to the inner
 * expression of `!expr` produces `!(!expr)` which evaluates to `expr`.
 *
 * Excludes:
 * - `not()` calls (would create redundant double-negation mutation points)
 * - Calls with EXCLEQ origin (handled by EqualitySwapOperator)
 * - Calls whose result is discarded (`list.add(x)` as a statement): inverting
 *   an unused value changes nothing observable, so the mutant would be equivalent
 *
 * The call itself is the single operand: it is evaluated once and the variant negates the
 * result, so the call (with its receiver and arguments) is never duplicated.
 */
@OptIn(UnsafeDuringIrConstructionAPI::class)
class BooleanInversionOperator : MutationOperator<IrCall> {

    override fun matches(node: IrCall): Boolean {
        val name = node.symbol.owner.name.asString()
        if (name == "not") return false

        return node.type.isBoolean()
                && (node.origin == null || node.origin == IrStatementOrigin.GET_PROPERTY)
    }

    override fun mutation(node: IrCall, context: MutationContext): Mutation? {
        if (!context.resultUsed) return null
        val name = node.symbol.owner.name.asString()
        val booleanNotSymbol = context.pluginContext.irBuiltIns.booleanNotSymbol

        return Mutation.OverOperands(
            originalDescription = "${name}()",
            operands = listOf(node),
            original = { operands -> operands[0] },
            variants = listOf(
                Mutation.OverOperands.Variant("!${name}()") { operands ->
                    context.builder.irCall(booleanNotSymbol).also { it.dispatchReceiver = operands[0] }
                }
            )
        )
    }
}
