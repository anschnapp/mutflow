package io.github.anschnapp.mutflow.compiler

import org.jetbrains.kotlin.ir.builders.IrBuilderWithScope
import org.jetbrains.kotlin.ir.builders.irCall
import org.jetbrains.kotlin.ir.expressions.IrCall
import org.jetbrains.kotlin.ir.expressions.IrStatementOrigin
import org.jetbrains.kotlin.ir.symbols.IrSimpleFunctionSymbol
import org.jetbrains.kotlin.ir.symbols.UnsafeDuringIrConstructionAPI
import org.jetbrains.kotlin.ir.types.classOrNull

/**
 * Mutation operator for relational comparisons: >, <, >=, <=
 *
 * Each operator gets 2 variants:
 * - Boundary mutation (add/remove equality): > ↔ >=, < ↔ <=
 * - Direction flip: > ↔ <, >= ↔ <=
 *
 * This is type-agnostic and works with Int, Long, Double, Float, Short, Byte, Char.
 */
@OptIn(UnsafeDuringIrConstructionAPI::class)
class RelationalComparisonOperator : MutationOperator<IrCall> {

    companion object {
        private val SUPPORTED_ORIGINS = setOf(
            IrStatementOrigin.GT,
            IrStatementOrigin.LT,
            IrStatementOrigin.GTEQ,
            IrStatementOrigin.LTEQ
        )
    }

    override fun matches(node: IrCall): Boolean {
        return node.origin in SUPPORTED_ORIGINS
    }

    private fun originalDescription(call: IrCall): String {
        return when (call.origin) {
            IrStatementOrigin.GT -> ">"
            IrStatementOrigin.LT -> "<"
            IrStatementOrigin.GTEQ -> ">="
            IrStatementOrigin.LTEQ -> "<="
            else -> "?"
        }
    }

    override fun mutation(node: IrCall, context: MutationContext): Mutation? {
        val left = node.arguments[0] ?: return null
        val right = node.arguments[1] ?: return null

        val operandType = left.type.classOrNull ?: return null
        val builtIns = context.pluginContext.irBuiltIns

        // Get comparison functions for this operand type
        val greaterFn = builtIns.greaterFunByOperandType[operandType]
        val lessFn = builtIns.lessFunByOperandType[operandType]
        val greaterOrEqualFn = builtIns.greaterOrEqualFunByOperandType[operandType]
        val lessOrEqualFn = builtIns.lessOrEqualFunByOperandType[operandType]

        // If any comparison function is missing, skip this mutation
        if (greaterFn == null || lessFn == null || greaterOrEqualFn == null || lessOrEqualFn == null) {
            return null
        }

        val variants = when (node.origin) {
            IrStatementOrigin.GT -> listOf(
                // > → >= (boundary: include equality)
                createVariant(">=", greaterOrEqualFn, context.builder),
                // > → < (flip direction)
                createVariant("<", lessFn, context.builder)
            )
            IrStatementOrigin.LT -> listOf(
                // < → <= (boundary: include equality)
                createVariant("<=", lessOrEqualFn, context.builder),
                // < → > (flip direction)
                createVariant(">", greaterFn, context.builder)
            )
            IrStatementOrigin.GTEQ -> listOf(
                // >= → > (boundary: exclude equality)
                createVariant(">", greaterFn, context.builder),
                // >= → <= (flip direction)
                createVariant("<=", lessOrEqualFn, context.builder)
            )
            IrStatementOrigin.LTEQ -> listOf(
                // <= → < (boundary: exclude equality)
                createVariant("<", lessFn, context.builder),
                // <= → >= (flip direction)
                createVariant(">=", greaterOrEqualFn, context.builder)
            )
            else -> return null
        }

        // The operands are the call's own arguments, which ConstantBoundaryOperator lists as
        // well: both mutations of `x > 0` then share one evaluation of `x`.
        return Mutation.OverOperands(
            originalDescription = originalDescription(node),
            operands = listOf(left, right),
            original = { operands ->
                node.arguments[0] = operands[0]
                node.arguments[1] = operands[1]
                node
            },
            variants = variants
        )
    }

    private fun createVariant(
        description: String,
        comparisonFn: IrSimpleFunctionSymbol,
        builder: IrBuilderWithScope
    ): Mutation.OverOperands.Variant {
        return Mutation.OverOperands.Variant(description) { operands ->
            builder.irCall(comparisonFn).also {
                it.arguments[0] = operands[0]
                it.arguments[1] = operands[1]
            }
        }
    }
}
