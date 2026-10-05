package io.github.anschnapp.mutflow.compiler

import org.jetbrains.kotlin.ir.builders.irCall
import org.jetbrains.kotlin.ir.expressions.IrCall
import org.jetbrains.kotlin.ir.expressions.IrConst
import org.jetbrains.kotlin.ir.expressions.IrExpression
import org.jetbrains.kotlin.ir.expressions.IrStatementOrigin
import org.jetbrains.kotlin.ir.expressions.impl.IrConstImpl
import org.jetbrains.kotlin.ir.symbols.UnsafeDuringIrConstructionAPI
import org.jetbrains.kotlin.ir.types.*

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
class ConstantBoundaryOperator : MutationOperator<IrCall> {

    companion object {
        private val COMPARISON_ORIGINS = setOf(
            IrStatementOrigin.GT,
            IrStatementOrigin.LT,
            IrStatementOrigin.GTEQ,
            IrStatementOrigin.LTEQ
        )
    }

    override fun matches(node: IrCall): Boolean {
        if (node.origin !in COMPARISON_ORIGINS) return false

        // Check if either argument is a numeric constant
        val left = node.arguments[0]
        val right = node.arguments[1]

        return isNumericConstant(left) || isNumericConstant(right)
    }

    override fun mutation(node: IrCall, context: MutationContext): Mutation? {
        val left = node.arguments[0] ?: return null
        val right = node.arguments[1] ?: return null

        // Prefer mutating the right side (more common: x > 0)
        // If right is constant, mutate it; otherwise try left
        val (constant, isLeftConstant) = when {
            isNumericConstant(right) -> right as IrConst to false
            isNumericConstant(left) -> left as IrConst to true
            else -> return null
        }

        val incremented = createIncrementedConstant(constant) ?: return null
        val decremented = createDecrementedConstant(constant) ?: return null

        // Both arguments are listed, the constant included, so the operands are the same ones
        // RelationalComparisonOperator lists for this call and the two mutations share them.
        return Mutation.OverOperands(
            originalDescription = constant.value.toString(),
            operands = listOf(left, right),
            original = { operands ->
                node.arguments[0] = operands[0]
                node.arguments[1] = operands[1]
                node
            },
            variants = listOf(
                createVariant(node, incremented, isLeftConstant, getIncrementedValue(constant), context),
                createVariant(node, decremented, isLeftConstant, getDecrementedValue(constant), context)
            )
        )
    }

    private fun isNumericConstant(expr: IrExpression?): Boolean {
        if (expr !is IrConst) return false
        return expr.type.isInt() || expr.type.isLong() || expr.type.isShort() ||
                expr.type.isByte() || expr.type.isFloat() || expr.type.isDouble() ||
                expr.type.isChar()
    }

    private fun createVariant(
        originalCall: IrCall,
        newConstant: IrConst,
        isLeftConstant: Boolean,
        description: String,
        context: MutationContext
    ): Mutation.OverOperands.Variant {
        return Mutation.OverOperands.Variant(description) { operands ->
            context.builder.irCall(originalCall.symbol).also { call ->
                call.origin = originalCall.origin
                if (isLeftConstant) {
                    call.arguments[0] = newConstant
                    call.arguments[1] = operands[1]
                } else {
                    call.arguments[0] = operands[0]
                    call.arguments[1] = newConstant
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
