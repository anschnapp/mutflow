package io.github.anschnapp.mutflow.compiler

import org.jetbrains.kotlin.ir.builders.irCall
import org.jetbrains.kotlin.ir.expressions.IrCall
import org.jetbrains.kotlin.ir.expressions.IrConst
import org.jetbrains.kotlin.ir.expressions.IrStatementOrigin
import org.jetbrains.kotlin.ir.symbols.UnsafeDuringIrConstructionAPI
import org.jetbrains.kotlin.ir.util.deepCopyWithSymbols

/**
 * Mutation operator for equality swaps: == ↔ !=
 *
 * IR representation:
 * - `==` is a single `EQEQ` intrinsic call with origin `EQEQ`
 * - `!=` is `not(EQEQ(a, b))` - two calls, both with origin `EXCLEQ`:
 *     - inner: EQEQ intrinsic (symbol name "EQEQ", origin EXCLEQ)
 *     - outer: Boolean.not() (symbol name "not", origin EXCLEQ)
 *
 * Mutation approach:
 * - `== → !=`: wrap the EQEQ call with `Boolean.not()`
 * - `!= → ==`: unwrap - return the dispatch receiver (the inner EQEQ call)
 *
 * Important: We match the outer `not()` call for `!=`, NOT the inner EQEQ.
 * Matching the inner EQEQ would create a duplicate/spurious mutation point.
 *
 * Null comparisons are deliberately skipped. Kotlin's null-safety operators
 * (`?:`, `?.`) desugar to a compiler-synthesized `x == null` check in IR, so
 * without this guard we would mint `== ↔ !=` mutations on code where the
 * developer never wrote an equality operator - a misleading display and, for
 * safe-calls, an always-crash mutant. We also skip explicit `x == null` /
 * `x != null`: inverting a null check is typically an equivalent mutant or
 * produces a downstream NPE, so it carries little signal.
 */
@OptIn(UnsafeDuringIrConstructionAPI::class)
class EqualitySwapOperator : MutationOperator {

    override fun matches(call: IrCall): Boolean {
        return when {
            // == : EQEQ intrinsic with EQEQ origin (but not a null comparison)
            call.origin == IrStatementOrigin.EQEQ
                    && call.symbol.owner.name.asString() == "EQEQ" -> !isNullComparison(call)
            // != : outer not() wrapper with EXCLEQ origin (but not a null comparison)
            call.origin == IrStatementOrigin.EXCLEQ
                    && call.symbol.owner.name.asString() == "not" -> {
                val innerEqEq = call.dispatchReceiver as? IrCall
                innerEqEq != null && !isNullComparison(innerEqEq)
            }
            else -> false
        }
    }

    /**
     * True if the EQEQ call has a `null` literal as one of its operands
     * (i.e. an `x == null` / `null == x` comparison, whether hand-written or
     * synthesized by a null-safety operator).
     */
    private fun isNullComparison(eqeqCall: IrCall): Boolean {
        return eqeqCall.arguments.any { it is IrConst && it.value == null }
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
