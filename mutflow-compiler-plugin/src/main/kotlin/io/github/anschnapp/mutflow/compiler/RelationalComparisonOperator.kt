package io.github.anschnapp.mutflow.compiler

import org.jetbrains.kotlin.backend.common.extensions.IrPluginContext
import org.jetbrains.kotlin.ir.builders.IrBuilderWithScope
import org.jetbrains.kotlin.ir.builders.irCall
import org.jetbrains.kotlin.ir.expressions.IrCall
import org.jetbrains.kotlin.ir.expressions.IrExpression
import org.jetbrains.kotlin.ir.expressions.IrStatementOrigin
import org.jetbrains.kotlin.ir.symbols.IrSimpleFunctionSymbol
import org.jetbrains.kotlin.ir.symbols.UnsafeDuringIrConstructionAPI
import org.jetbrains.kotlin.ir.types.classOrNull
import org.jetbrains.kotlin.ir.util.deepCopyWithSymbols

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
class RelationalComparisonOperator : MutationOperator {

    companion object {
        private val SUPPORTED_ORIGINS = setOf(
            IrStatementOrigin.GT,
            IrStatementOrigin.LT,
            IrStatementOrigin.GTEQ,
            IrStatementOrigin.LTEQ
        )
    }

    override fun matches(call: IrCall): Boolean {
        return call.origin in SUPPORTED_ORIGINS
    }

    override fun originalDescription(call: IrCall): String {
        return when (call.origin) {
            IrStatementOrigin.GT -> ">"
            IrStatementOrigin.LT -> "<"
            IrStatementOrigin.GTEQ -> ">="
            IrStatementOrigin.LTEQ -> "<="
            else -> "?"
        }
    }

    override fun variants(call: IrCall, context: MutationContext): List<MutationOperator.Variant> {
        val left = call.arguments[0] ?: return emptyList()
        val right = call.arguments[1] ?: return emptyList()

        val operandType = left.type.classOrNull ?: return emptyList()
        val builtIns = context.pluginContext.irBuiltIns

        // Get comparison functions for this operand type
        val greaterFn = builtIns.greaterFunByOperandType[operandType]
        val lessFn = builtIns.lessFunByOperandType[operandType]
        val greaterOrEqualFn = builtIns.greaterOrEqualFunByOperandType[operandType]
        val lessOrEqualFn = builtIns.lessOrEqualFunByOperandType[operandType]

        // If any comparison function is missing, skip this mutation
        if (greaterFn == null || lessFn == null || greaterOrEqualFn == null || lessOrEqualFn == null) {
            return emptyList()
        }

        return when (call.origin) {
            IrStatementOrigin.GT -> listOf(
                // > → >= (boundary: include equality)
                createVariant(">=", left, right, greaterOrEqualFn, context.builder),
                // > → < (flip direction)
                createVariant("<", left, right, lessFn, context.builder)
            )
            IrStatementOrigin.LT -> listOf(
                // < → <= (boundary: include equality)
                createVariant("<=", left, right, lessOrEqualFn, context.builder),
                // < → > (flip direction)
                createVariant(">", left, right, greaterFn, context.builder)
            )
            IrStatementOrigin.GTEQ -> listOf(
                // >= → > (boundary: exclude equality)
                createVariant(">", left, right, greaterFn, context.builder),
                // >= → <= (flip direction)
                createVariant("<=", left, right, lessOrEqualFn, context.builder)
            )
            IrStatementOrigin.LTEQ -> listOf(
                // <= → < (boundary: exclude equality)
                createVariant("<", left, right, lessFn, context.builder),
                // <= → >= (flip direction)
                createVariant(">=", left, right, greaterOrEqualFn, context.builder)
            )
            else -> emptyList()
        }
    }

    private fun createVariant(
        description: String,
        left: IrExpression,
        right: IrExpression,
        comparisonFn: IrSimpleFunctionSymbol,
        builder: IrBuilderWithScope
    ): MutationOperator.Variant {
        return MutationOperator.Variant(description) {
            builder.irCall(comparisonFn).also {
                it.arguments[0] = left.deepCopyWithSymbols()
                it.arguments[1] = right.deepCopyWithSymbols()
            }
        }
    }
}
