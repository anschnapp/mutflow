package io.github.anschnapp.mutflow.compiler

import org.jetbrains.kotlin.ir.builders.irCall
import org.jetbrains.kotlin.ir.expressions.IrCall
import org.jetbrains.kotlin.ir.expressions.IrStatementOrigin
import org.jetbrains.kotlin.ir.symbols.UnsafeDuringIrConstructionAPI
import org.jetbrains.kotlin.ir.util.deepCopyWithSymbols

/**
 * Mutation operator for equality swaps: == ↔ !=
 *
 * IR representation:
 * - `==` is a single `EQEQ` intrinsic call with origin `EQEQ`
 * - `!=` is `not(EQEQ(a, b))` — two calls, both with origin `EXCLEQ`:
 *     - inner: EQEQ intrinsic (symbol name "EQEQ", origin EXCLEQ)
 *     - outer: Boolean.not() (symbol name "not", origin EXCLEQ)
 *
 * Mutation approach:
 * - `== → !=`: wrap the EQEQ call with `Boolean.not()`
 * - `!= → ==`: unwrap — return the dispatch receiver (the inner EQEQ call)
 *
 * Important: We match the outer `not()` call for `!=`, NOT the inner EQEQ.
 * Matching the inner EQEQ would create a duplicate/spurious mutation point.
 */
@OptIn(UnsafeDuringIrConstructionAPI::class)
class EqualitySwapOperator : MutationOperator {

    override fun matches(call: IrCall): Boolean {
        return when {
            // == : EQEQ intrinsic with EQEQ origin
            call.origin == IrStatementOrigin.EQEQ
                    && call.symbol.owner.name.asString() == "EQEQ" -> true
            // != : outer not() wrapper with EXCLEQ origin
            call.origin == IrStatementOrigin.EXCLEQ
                    && call.symbol.owner.name.asString() == "not" -> true
            else -> false
        }
    }

    override fun originalDescription(call: IrCall): String {
        return when (call.origin) {
            IrStatementOrigin.EQEQ -> "=="
            IrStatementOrigin.EXCLEQ -> "!="
            else -> "?"
        }
    }

    override fun variants(call: IrCall, context: MutationContext): List<MutationOperator.Variant> {
        return when (call.origin) {
            // == → != : create not() call with the EQEQ call as its receiver
            IrStatementOrigin.EQEQ -> listOf(
                MutationOperator.Variant("!=") {
                    val booleanNotSymbol = context.pluginContext.irBuiltIns.booleanNotSymbol
                    context.builder.irCall(booleanNotSymbol).also {
                        it.dispatchReceiver = call.deepCopyWithSymbols()
                    }
                }
            )
            // != → == : unwrap not(), return the inner EQEQ call
            IrStatementOrigin.EXCLEQ -> listOf(
                MutationOperator.Variant("==") {
                    call.dispatchReceiver!!.deepCopyWithSymbols()
                }
            )
            else -> emptyList()
        }
    }
}
