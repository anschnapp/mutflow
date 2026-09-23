package io.github.anschnapp.mutflow.compiler

import org.jetbrains.kotlin.ir.UNDEFINED_OFFSET
import org.jetbrains.kotlin.ir.builders.*
import org.jetbrains.kotlin.ir.expressions.IrCall
import org.jetbrains.kotlin.ir.expressions.IrExpression
import org.jetbrains.kotlin.ir.expressions.IrStatementOrigin
import org.jetbrains.kotlin.ir.expressions.impl.IrBranchImpl
import org.jetbrains.kotlin.ir.expressions.impl.IrConstImpl
import org.jetbrains.kotlin.ir.expressions.impl.IrElseBranchImpl
import org.jetbrains.kotlin.ir.expressions.impl.IrWhenImpl
import org.jetbrains.kotlin.ir.symbols.IrSimpleFunctionSymbol
import org.jetbrains.kotlin.ir.symbols.UnsafeDuringIrConstructionAPI
import org.jetbrains.kotlin.ir.types.*
import org.jetbrains.kotlin.ir.util.classId
import org.jetbrains.kotlin.ir.util.deepCopyWithSymbols
import org.jetbrains.kotlin.name.CallableId
import org.jetbrains.kotlin.name.Name

/**
 * Mutation operator for arithmetic operations: +, -, *, /, %
 *
 * Each operator gets 1 variant (simple swap):
 * - + → - (and vice versa)
 * - * → / (and vice versa)
 * - % → /
 *
 * Special case: * → / generates safe division code that avoids div-by-zero:
 * ```
 * when {
 *     b != 0 -> a / b
 *     a != 0 -> b / a   // b is 0, result is 0
 *     else -> 1         // both are 0, return 1 to avoid 0/0
 * }
 * ```
 */
@OptIn(UnsafeDuringIrConstructionAPI::class)
class ArithmeticOperator : MutationOperator<IrCall> {

    companion object {
        private val SUPPORTED_ORIGINS = setOf(
            IrStatementOrigin.PLUS,
            IrStatementOrigin.MINUS,
            IrStatementOrigin.MUL,
            IrStatementOrigin.DIV,
            IrStatementOrigin.PERC  // modulo (%)
        )
    }

    override fun matches(node: IrCall): Boolean {
        return node.origin in SUPPORTED_ORIGINS
    }

    private fun originalDescription(call: IrCall): String {
        return when (call.origin) {
            IrStatementOrigin.PLUS -> "+"
            IrStatementOrigin.MINUS -> "-"
            IrStatementOrigin.MUL -> "*"
            IrStatementOrigin.DIV -> "/"
            IrStatementOrigin.PERC -> "%"
            else -> "?"
        }
    }

    /**
     * Mutates over the operands: `a + b + c` is `(a + b) + c`, so a variant that copied its
     * left operand would copy the whole instrumented chain beneath it, doubling the code per
     * term. Both operands are always evaluated, so they can be hoisted instead.
     */
    override fun mutation(node: IrCall, context: MutationContext): Mutation? {
        val left = node.arguments[0] ?: return null
        val right = node.arguments[1] ?: return null
        val resultType = node.type

        // For arithmetic operations, we can look up the replacement function
        // by examining the original call's symbol and finding the equivalent
        // function with a different name but same signature pattern.
        //
        // The original call symbol tells us which overload is being used,
        // so we find the same overload for the replacement operator.
        val originalSymbol = node.symbol
        val originalFunctionName = originalSymbol.owner.name.asString()

        // Get the class that declares this function
        val declaringClassId = originalSymbol.owner.parent.let { parent ->
            (parent as? org.jetbrains.kotlin.ir.declarations.IrClass)?.classId
        } ?: return null

        // Find the replacement function with same parameter signature
        fun findFunction(name: String): IrSimpleFunctionSymbol? {
            if (name == originalFunctionName) return originalSymbol // same function
            val callableId = CallableId(declaringClassId, Name.identifier(name))
            // For primitives, just get the first function - the type checker
            // already resolved the correct overload for the original call
            return context.pluginContext.referenceFunctions(callableId).firstOrNull()
        }

        fun swapTo(description: String, fn: IrSimpleFunctionSymbol) =
            Mutation.OverOperands.Variant(description) { operands ->
                context.builder.irCall(fn).also {
                    it.arguments[0] = operands[0]
                    it.arguments[1] = operands[1]
                }
            }

        val variant = when (node.origin) {
            // + → -
            IrStatementOrigin.PLUS -> findFunction("minus")?.let { swapTo("-", it) }
            // - → +
            IrStatementOrigin.MINUS -> findFunction("plus")?.let { swapTo("+", it) }
            // * → / with safe division to avoid div-by-zero
            IrStatementOrigin.MUL -> findFunction("div")?.let { fn ->
                Mutation.OverOperands.Variant("/") { operands ->
                    createSafeDivision(node, operands, fn, resultType, context)
                }
            }
            // / → *
            IrStatementOrigin.DIV -> findFunction("times")?.let { swapTo("*", it) }
            // % → /
            IrStatementOrigin.PERC -> findFunction("div")?.let { swapTo("/", it) }
            else -> null
        } ?: return null

        return Mutation.OverOperands(
            originalDescription = originalDescription(node),
            operands = listOf(left, right),
            original = { operands ->
                node.arguments[0] = operands[0]
                node.arguments[1] = operands[1]
                node
            },
            variants = listOf(variant)
        )
    }

    /**
     * Creates a safe division expression that avoids division by zero.
     *
     * The operands are already evaluated once, so reading them repeatedly is free.
     * Generates:
     * ```
     * when {
     *     b != 0 -> a / b
     *     a != 0 -> b / a   // b is 0, so result is 0
     *     else -> 1         // both are 0, return 1
     * }
     * ```
     */
    private fun createSafeDivision(
        original: IrCall,
        operands: Operands,
        divFn: IrSimpleFunctionSymbol,
        resultType: IrType,
        context: MutationContext
    ): IrExpression {
        val builder = context.builder

        val zero = createZeroConstant(resultType)
        val one = createOneConstant(resultType)

        fun divide(dividend: Int, divisor: Int) = builder.irCall(divFn).also {
            it.arguments[0] = operands[dividend]
            it.arguments[1] = operands[divisor]
        }

        if (zero == null || one == null) {
            // Fallback: just do the division (shouldn't happen for numeric types)
            return divide(0, 1)
        }

        return IrWhenImpl(
            startOffset = original.startOffset,
            endOffset = original.endOffset,
            type = resultType,
            origin = null
        ).apply {
            // Branch 1: b != 0 -> a / b
            branches += IrBranchImpl(
                startOffset = original.startOffset,
                endOffset = original.endOffset,
                condition = builder.irNotEquals(operands[1], zero.deepCopyWithSymbols()),
                result = divide(0, 1)
            )
            // Branch 2: a != 0 -> b / a (b is 0, so 0 / a = 0)
            branches += IrBranchImpl(
                startOffset = original.startOffset,
                endOffset = original.endOffset,
                condition = builder.irNotEquals(operands[0], zero.deepCopyWithSymbols()),
                result = divide(1, 0)
            )
            // Else: both are 0 -> return 1
            branches += IrElseBranchImpl(
                startOffset = original.startOffset,
                endOffset = original.endOffset,
                condition = builder.irTrue(),
                result = one
            )
        }
    }

    /**
     * Creates a zero constant for the given numeric type.
     */
    private fun createZeroConstant(type: IrType): IrExpression? {
        return when {
            type.isInt() -> IrConstImpl.int(UNDEFINED_OFFSET, UNDEFINED_OFFSET, type, 0)
            type.isLong() -> IrConstImpl.long(UNDEFINED_OFFSET, UNDEFINED_OFFSET, type, 0L)
            type.isFloat() -> IrConstImpl.float(UNDEFINED_OFFSET, UNDEFINED_OFFSET, type, 0.0f)
            type.isDouble() -> IrConstImpl.double(UNDEFINED_OFFSET, UNDEFINED_OFFSET, type, 0.0)
            type.isShort() -> IrConstImpl.short(UNDEFINED_OFFSET, UNDEFINED_OFFSET, type, 0)
            type.isByte() -> IrConstImpl.byte(UNDEFINED_OFFSET, UNDEFINED_OFFSET, type, 0)
            else -> null
        }
    }

    /**
     * Creates a one constant for the given numeric type.
     */
    private fun createOneConstant(type: IrType): IrExpression? {
        return when {
            type.isInt() -> IrConstImpl.int(UNDEFINED_OFFSET, UNDEFINED_OFFSET, type, 1)
            type.isLong() -> IrConstImpl.long(UNDEFINED_OFFSET, UNDEFINED_OFFSET, type, 1L)
            type.isFloat() -> IrConstImpl.float(UNDEFINED_OFFSET, UNDEFINED_OFFSET, type, 1.0f)
            type.isDouble() -> IrConstImpl.double(UNDEFINED_OFFSET, UNDEFINED_OFFSET, type, 1.0)
            type.isShort() -> IrConstImpl.short(UNDEFINED_OFFSET, UNDEFINED_OFFSET, type, 1)
            type.isByte() -> IrConstImpl.byte(UNDEFINED_OFFSET, UNDEFINED_OFFSET, type, 1)
            else -> null
        }
    }
}
