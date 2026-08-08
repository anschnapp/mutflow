package io.github.anschnapp.mutflow.compiler

import org.jetbrains.kotlin.ir.builders.irCall
import org.jetbrains.kotlin.ir.expressions.IrCall
import org.jetbrains.kotlin.ir.expressions.IrStatementOrigin
import org.jetbrains.kotlin.ir.symbols.UnsafeDuringIrConstructionAPI
import org.jetbrains.kotlin.ir.types.isBoolean
import org.jetbrains.kotlin.ir.util.deepCopyWithSymbols

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
 */
@OptIn(UnsafeDuringIrConstructionAPI::class)
class BooleanInversionOperator : MutationOperator {

    override val descriptor = MutatorDescriptor(
        id = "BOOLEAN_INVERSION",
        name = "BooleanInversion",
        description = "Invert boolean expression: expr → !expr",
        group = MutatorGroup.BOOLEAN,
        status = MutatorStatus.STABLE
    )

    override fun matches(call: IrCall): Boolean {
        val name = call.symbol.owner.name.asString()
        if (name == "not") return false

        return call.type.isBoolean()
                && (call.origin == null || call.origin == IrStatementOrigin.GET_PROPERTY)
    }

    override fun originalDescription(call: IrCall): String {
        return "${call.symbol.owner.name.asString()}()"
    }

    override fun variants(call: IrCall, context: MutationContext): List<MutationOperator.Variant> {
        val name = call.symbol.owner.name.asString()

        return listOf(
            MutationOperator.Variant("!${name}()") {
                val booleanNotSymbol = context.pluginContext.irBuiltIns.booleanNotSymbol
                context.builder.irCall(booleanNotSymbol).also {
                    it.dispatchReceiver = call.deepCopyWithSymbols()
                }
            }
        )
    }
}
