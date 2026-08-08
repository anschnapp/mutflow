package io.github.anschnapp.mutflow.compiler

import org.jetbrains.kotlin.ir.expressions.IrCall
import org.jetbrains.kotlin.ir.expressions.IrStatementOrigin
import org.jetbrains.kotlin.ir.symbols.UnsafeDuringIrConstructionAPI
import org.jetbrains.kotlin.ir.util.deepCopyWithSymbols

/**
 * Mutation operator for unary minus removal: `-a` → `a`.
 *
 * In Kotlin IR, `-a` is a call to `unaryMinus()` with the operand as its dispatch
 * receiver. The call carries no [IrStatementOrigin] (origin is null), so it is
 * matched by function name. The mutation replaces the call with the operand itself.
 *
 * Mirrors pitest's `INVERT_NEGS` and Mull's `cxx_minus_to_noop`.
 *
 * Note: negative literals like `-5` are represented as a constant, not a
 * `unaryMinus` call, so they are not matched (mutating `-5` to `5` is covered by
 * the constant boundary operator instead).
 */
@OptIn(UnsafeDuringIrConstructionAPI::class)
class UnaryMinusOperator : MutationOperator {

    override val descriptor = MutatorDescriptor(
        id = "UNARY_MINUS",
        name = "UnaryMinus",
        description = "Remove unary minus: -a → a",
        group = MutatorGroup.ARITHMETIC,
        status = MutatorStatus.STABLE
    )

    override fun matches(call: IrCall): Boolean {
        return call.symbol.owner.name.asString() == "unaryMinus"
    }

    override fun originalDescription(call: IrCall): String = "-"

    override fun variants(call: IrCall, context: MutationContext): List<MutationOperator.Variant> {
        val operand = call.dispatchReceiver ?: return emptyList()
        return listOf(
            MutationOperator.Variant("noop") {
                operand.deepCopyWithSymbols()
            }
        )
    }
}
