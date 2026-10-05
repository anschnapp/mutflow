package io.github.anschnapp.mutflow.compiler

import org.jetbrains.kotlin.ir.builders.irCall
import org.jetbrains.kotlin.ir.builders.irNotEquals
import org.jetbrains.kotlin.ir.builders.irTrue
import org.jetbrains.kotlin.ir.expressions.IrExpression
import org.jetbrains.kotlin.ir.expressions.IrStatementOrigin
import org.jetbrains.kotlin.ir.expressions.IrWhen
import org.jetbrains.kotlin.ir.expressions.impl.IrBranchImpl
import org.jetbrains.kotlin.ir.expressions.impl.IrElseBranchImpl
import org.jetbrains.kotlin.ir.expressions.impl.IrWhenImpl
import org.jetbrains.kotlin.ir.symbols.UnsafeDuringIrConstructionAPI

/**
 * Mutation operator for boolean logic swaps: && ↔ ||
 *
 * In Kotlin K2 IR (2.3.0+), boolean operators are lowered to IrWhen expressions:
 * - `a && b` → IrWhen(origin=ANDAND): when { a -> b; else -> false }
 * - `a || b` → IrWhen(origin=OROR):   when { a -> true; else -> b }
 *
 * The mutation is fused: `&&` and `||` evaluate their right operand lazily, so it cannot be
 * hoisted, and a variant that restated both operands next to the untouched original would
 * carry a second copy of everything beneath it, doubling the code per level of a chain.
 * [mutation] instead builds one expression that is the original or the mutant depending on
 * the mutation flag, with every operand appearing exactly once.
 */
@OptIn(UnsafeDuringIrConstructionAPI::class)
class BooleanLogicOperator : MutationOperator<IrWhen> {

    override fun matches(node: IrWhen): Boolean {
        return node.origin == IrStatementOrigin.ANDAND ||
            node.origin == IrStatementOrigin.OROR
    }

    /**
     * Fused form, which mutates without duplicating either operand.
     *
     * Both operators are the same shape, differing only in the polarity of the condition and in
     * the value the else branch yields:
     *
     * ```
     * a && b  ==  when { a  -> b; else -> false }
     * a || b  ==  when { !a -> b; else -> true  }
     * ```
     *
     * Those two differences are the same bit, so one boolean `selectsOr` selects the operator:
     *
     * ```
     * when { (a != selectsOr) -> b; else -> selectsOr }
     * ```
     *
     * | selectsOr | condition | else  | meaning |
     * |-----------|-----------|-------|---------|
     * | false     | a         | false | a && b  |
     * | true      | !a        | true  | a \|\| b  |
     *
     * `selectsOr` is the mutation flag for an `&&` original and its negation for an `||` one.
     * `a` is evaluated first and once, as in the original, and `b` is moved into the single
     * branch that can reach it, so it is still only evaluated when the operator says so:
     * short-circuiting holds for the mutant exactly as it does for the original.
     *
     * `a` is emitted as `a!!`. For Kotlin code that changes nothing, but a Java method returning
     * `Boolean` can return null, and `!=` accepts null where the original `a && b` throws. The
     * `!!` restores that NullPointerException.
     */
    override fun mutation(node: IrWhen, context: MutationContext): Mutation? {
        if (node.branches.size != 2) return null
        val isAnd = node.origin == IrStatementOrigin.ANDAND

        val firstOperand = node.branches[0].condition
        // `b` in `a && b` / `a || b`: the branch result for &&, the else result for ||.
        val secondOperand = if (isAnd) node.branches[0].result else node.branches[1].result

        return Mutation.Fused(
            originalDescription = if (isAnd) "&&" else "||",
            variantDescription = if (isAnd) "||" else "&&"
        ) { active ->
            val builder = context.builder
            val booleanType = context.pluginContext.irBuiltIns.booleanType

            // An && original turns into || exactly when the mutation is active; an || original
            // turns into && exactly then. The two cases differ by a negation, nothing else.
            fun selectsOr(): IrExpression = if (isAnd) {
                active()
            } else {
                builder.irCall(context.pluginContext.irBuiltIns.booleanNotSymbol).also {
                    it.dispatchReceiver = active()
                }
            }

            // `a!!`: keeps the NullPointerException of the original for a null Java Boolean.
            fun notNull(operand: IrExpression): IrExpression =
                builder.irCall(context.pluginContext.irBuiltIns.checkNotNullSymbol, booleanType).also {
                    it.typeArguments[0] = booleanType
                    it.arguments[0] = operand
                }

            IrWhenImpl(
                startOffset = node.startOffset,
                endOffset = node.endOffset,
                type = booleanType,
                origin = null
            ).apply {
                branches += IrBranchImpl(
                    startOffset = node.startOffset,
                    endOffset = node.endOffset,
                    condition = builder.irNotEquals(notNull(firstOperand), selectsOr()),
                    result = secondOperand
                )
                branches += IrElseBranchImpl(
                    startOffset = node.startOffset,
                    endOffset = node.endOffset,
                    condition = builder.irTrue(),
                    result = selectsOr()
                )
            }
        }
    }
}
