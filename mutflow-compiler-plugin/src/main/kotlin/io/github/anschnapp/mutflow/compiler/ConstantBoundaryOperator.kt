package io.github.anschnapp.mutflow.compiler

import org.jetbrains.kotlin.ir.builders.irCall
import org.jetbrains.kotlin.ir.expressions.IrCall
import org.jetbrains.kotlin.ir.expressions.IrConst
import org.jetbrains.kotlin.ir.expressions.IrExpression
import org.jetbrains.kotlin.ir.expressions.IrStatementOrigin
import org.jetbrains.kotlin.ir.expressions.impl.IrConstImpl
import org.jetbrains.kotlin.ir.symbols.UnsafeDuringIrConstructionAPI
import org.jetbrains.kotlin.ir.types.*
import org.jetbrains.kotlin.ir.util.deepCopyWithSymbols

/**
 * Mutation operator for constant boundary testing.
 *
 * Mutates numeric constants in comparison expressions by +1 and -1 to detect
 * poorly tested boundaries. For example, `x > 0` becomes:
 * - `x > 1` (would catch missing test for x=1)
 * - `x > -1` (would catch missing test for x=0)
 *
 * This complements RelationalComparisonOperator which tests operator choice,
 * while this tests boundary value choice.
 */
@OptIn(UnsafeDuringIrConstructionAPI::class)
class ConstantBoundaryOperator : MutationOperator {

    companion object {
        private val COMPARISON_ORIGINS = setOf(
            IrStatementOrigin.GT,
            IrStatementOrigin.LT,
            IrStatementOrigin.GTEQ,
            IrStatementOrigin.LTEQ
        )
    }

    override fun matches(call: IrCall): Boolean {
        if (call.origin !in COMPARISON_ORIGINS) return false

        // Check if either argument is a numeric constant
        val left = call.arguments[0]
        val right = call.arguments[1]

        return isNumericConstant(left) || isNumericConstant(right)
    }

    override fun originalDescription(call: IrCall): String {
        // Find the constant and return its value as the description
        val left = call.arguments[0]
        val right = call.arguments[1]

        return when {
            isNumericConstant(right) -> (right as IrConst).value.toString()
            isNumericConstant(left) -> (left as IrConst).value.toString()
            else -> "?"
        }
    }

    override fun variants(call: IrCall, context: MutationContext): List<MutationOperator.Variant> {
        val left = call.arguments[0] ?: return emptyList()
        val right = call.arguments[1] ?: return emptyList()

        // Prefer mutating the right side (more common: x > 0)
        // If right is constant, mutate it; otherwise try left
        return when {
            isNumericConstant(right) -> createVariantsForConstant(
                call, left, right as IrConst, isLeftConstant = false, context
            )
            isNumericConstant(left) -> createVariantsForConstant(
                call, right, left as IrConst, isLeftConstant = true, context
            )
            else -> emptyList()
        }
    }

    private fun isNumericConstant(expr: IrExpression?): Boolean {
        if (expr !is IrConst) return false
        return expr.type.isInt() || expr.type.isLong() || expr.type.isShort() ||
                expr.type.isByte() || expr.type.isFloat() || expr.type.isDouble() ||
                expr.type.isChar()
    }

    private fun createVariantsForConstant(
        originalCall: IrCall,
        otherOperand: IrExpression,
        constant: IrConst,
        isLeftConstant: Boolean,
        context: MutationContext
    ): List<MutationOperator.Variant> {
        val incremented = createIncrementedConstant(constant) ?: return emptyList()
        val decremented = createDecrementedConstant(constant) ?: return emptyList()

        val incrementedValue = getIncrementedValue(constant)
        val decrementedValue = getDecrementedValue(constant)

        return listOf(
            createVariant(originalCall, otherOperand, incremented, isLeftConstant, incrementedValue, context),
            createVariant(originalCall, otherOperand, decremented, isLeftConstant, decrementedValue, context)
        )
    }

    private fun createVariant(
        originalCall: IrCall,
        otherOperand: IrExpression,
        newConstant: IrConst,
        isLeftConstant: Boolean,
        description: String,
        context: MutationContext
    ): MutationOperator.Variant {
        return MutationOperator.Variant(description) {
            context.builder.irCall(originalCall.symbol).also { call ->
                call.origin = originalCall.origin
                if (isLeftConstant) {
                    call.arguments[0] = newConstant.deepCopyWithSymbols()
                    call.arguments[1] = otherOperand.deepCopyWithSymbols()
                } else {
                    call.arguments[0] = otherOperand.deepCopyWithSymbols()
                    call.arguments[1] = newConstant.deepCopyWithSymbols()
                }
            }
        }
    }

    private fun getIncrementedValue(constant: IrConst): String {
        return when (val value = constant.value) {
            is Int -> (value + 1).toString()
            is Long -> (value + 1).toString()
            is Short -> (value + 1).toShort().toString()
            is Byte -> (value + 1).toByte().toString()
            is Float -> (value + 1).toString()
            is Double -> (value + 1).toString()
            is Char -> (value.code + 1).toChar().toString()
            else -> "?"
        }
    }

    private fun getDecrementedValue(constant: IrConst): String {
        return when (val value = constant.value) {
            is Int -> (value - 1).toString()
            is Long -> (value - 1).toString()
            is Short -> (value - 1).toShort().toString()
            is Byte -> (value - 1).toByte().toString()
            is Float -> (value - 1).toString()
            is Double -> (value - 1).toString()
            is Char -> (value.code - 1).toChar().toString()
            else -> "?"
        }
    }

    private fun createIncrementedConstant(constant: IrConst): IrConst? {
        val startOffset = constant.startOffset
        val endOffset = constant.endOffset
        val type = constant.type

        return when (val value = constant.value) {
            is Int -> IrConstImpl.int(startOffset, endOffset, type, value + 1)
            is Long -> IrConstImpl.long(startOffset, endOffset, type, value + 1)
            is Short -> IrConstImpl.short(startOffset, endOffset, type, (value + 1).toShort())
            is Byte -> IrConstImpl.byte(startOffset, endOffset, type, (value + 1).toByte())
            is Float -> IrConstImpl.float(startOffset, endOffset, type, value + 1)
            is Double -> IrConstImpl.double(startOffset, endOffset, type, value + 1)
            is Char -> IrConstImpl.char(startOffset, endOffset, type, (value.code + 1).toChar())
            else -> null
        }
    }

    private fun createDecrementedConstant(constant: IrConst): IrConst? {
        val startOffset = constant.startOffset
        val endOffset = constant.endOffset
        val type = constant.type

        return when (val value = constant.value) {
            is Int -> IrConstImpl.int(startOffset, endOffset, type, value - 1)
            is Long -> IrConstImpl.long(startOffset, endOffset, type, value - 1)
            is Short -> IrConstImpl.short(startOffset, endOffset, type, (value - 1).toShort())
            is Byte -> IrConstImpl.byte(startOffset, endOffset, type, (value - 1).toByte())
            is Float -> IrConstImpl.float(startOffset, endOffset, type, value - 1)
            is Double -> IrConstImpl.double(startOffset, endOffset, type, value - 1)
            is Char -> IrConstImpl.char(startOffset, endOffset, type, (value.code - 1).toChar())
            else -> null
        }
    }
}
