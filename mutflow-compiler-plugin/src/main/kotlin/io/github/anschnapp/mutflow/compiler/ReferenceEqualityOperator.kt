package io.github.anschnapp.mutflow.compiler

import org.jetbrains.kotlin.ir.builders.irCall
import org.jetbrains.kotlin.ir.expressions.IrCall
import org.jetbrains.kotlin.ir.expressions.IrStatementOrigin
import org.jetbrains.kotlin.ir.symbols.UnsafeDuringIrConstructionAPI
import org.jetbrains.kotlin.ir.util.deepCopyWithSymbols

/**
 * Flips `===` ↔ `!==`.
 *
 * Mirrors [EqualitySwapOperator]'s IR shapes: `===` is an `EQEQEQ` intrinsic,
 * and `!==` is that wrapped in `Boolean.not()`. We match the outer `not()` for
 * `!==` (not the inner intrinsic) so each one yields exactly one point.
 */
@OptIn(UnsafeDuringIrConstructionAPI::class)
class ReferenceEqualityOperator : MutationOperator {

    override val descriptor = MutatorDescriptor(
        id = "REFERENCE_EQUALITY_SWAP",
        name = "ReferenceEqualitySwap",
        description = "Swap === ↔ !==",
        group = MutatorGroup.RELATIONAL,
        status = MutatorStatus.STABLE
    )

    override fun matches(call: IrCall): Boolean {
        return when {
            // a === b
            call.origin == IrStatementOrigin.EQEQEQ &&
                    call.symbol.owner.name.asString() == "EQEQEQ" -> true
            // a !== b : not(EQEQEQ(a, b))
            call.origin == IrStatementOrigin.EXCLEQEQ &&
                    call.symbol.owner.name.asString() == "not" -> {
                call.dispatchReceiver is IrCall
            }
            else -> false
        }
    }

    override fun originalDescription(call: IrCall): String {
        return when (call.origin) {
            IrStatementOrigin.EQEQEQ -> "==="
            IrStatementOrigin.EXCLEQEQ -> "!=="
            else -> "?"
        }
    }

    override fun variants(call: IrCall, context: MutationContext): List<MutationOperator.Variant> {
        return when (call.origin) {
            // === → !== : wrap in not()
            IrStatementOrigin.EQEQEQ -> listOf(
                MutationOperator.Variant("!==") {
                    val booleanNotSymbol = context.pluginContext.irBuiltIns.booleanNotSymbol
                    context.builder.irCall(booleanNotSymbol).also {
                        it.dispatchReceiver = call.deepCopyWithSymbols()
                    }
                }
            )
            // !== → === : unwrap the not()
            IrStatementOrigin.EXCLEQEQ -> listOf(
                MutationOperator.Variant("===") {
                    call.dispatchReceiver!!.deepCopyWithSymbols()
                }
            )
            else -> emptyList()
        }
    }
}
