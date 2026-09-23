package io.github.anschnapp.mutflow.compiler

import org.jetbrains.kotlin.ir.builders.irCall
import org.jetbrains.kotlin.ir.expressions.IrCall
import org.jetbrains.kotlin.ir.expressions.IrConst
import org.jetbrains.kotlin.ir.expressions.IrStatementOrigin
import org.jetbrains.kotlin.ir.symbols.UnsafeDuringIrConstructionAPI
import org.jetbrains.kotlin.ir.expressions.IrExpression

/**
 * Mutation operator for equality swaps: == ↔ !=
 *
 * IR representation:
 * - `==` is a single `EQEQ` intrinsic call with origin `EQEQ`
 * - `!=` is `not(EQEQ(a, b))` - two calls, both with origin `EXCLEQ`:
 *     - inner: EQEQ intrinsic (symbol name "EQEQ", origin EXCLEQ)
 *     - outer: Boolean.not() (symbol name "not", origin EXCLEQ)
 *
 * Mutation approach: both directions are a negation of the EQEQ result, so the EQEQ call is
 * the single operand, evaluated once, and the variant negates it or strips the negation:
 * - `== → !=`: original `v`, variant `!v`
 * - `!= → ==`: original `!v`, variant `v`
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
class EqualitySwapOperator : MutationOperator<IrCall> {

    override fun matches(node: IrCall): Boolean {
        return when {
            // == : EQEQ intrinsic with EQEQ origin (but not a null comparison)
            node.origin == IrStatementOrigin.EQEQ
                    && node.symbol.owner.name.asString() == "EQEQ" -> !isNullComparison(node)
            // != : outer not() wrapper with EXCLEQ origin (but not a null comparison)
            node.origin == IrStatementOrigin.EXCLEQ
                    && node.symbol.owner.name.asString() == "not" -> {
                val innerEqEq = node.dispatchReceiver as? IrCall
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

    override fun mutation(node: IrCall, context: MutationContext): Mutation? {
        val booleanNotSymbol = context.pluginContext.irBuiltIns.booleanNotSymbol
        fun not(value: IrExpression) = context.builder.irCall(booleanNotSymbol).also {
            it.dispatchReceiver = value
        }

        return when (node.origin) {
            // == → != : the EQEQ call itself is the operand
            IrStatementOrigin.EQEQ -> Mutation.OverOperands(
                originalDescription = "==",
                operands = listOf(node),
                original = { operands -> operands[0] },
                variants = listOf(Mutation.OverOperands.Variant("!=") { operands -> not(operands[0]) })
            )
            // != → == : the EQEQ call inside not() is the operand
            IrStatementOrigin.EXCLEQ -> {
                val eqeq = node.dispatchReceiver ?: return null
                Mutation.OverOperands(
                    originalDescription = "!=",
                    operands = listOf(eqeq),
                    original = { operands -> node.also { it.dispatchReceiver = operands[0] } },
                    variants = listOf(Mutation.OverOperands.Variant("==") { operands -> operands[0] })
                )
            }
            else -> null
        }
    }
}
