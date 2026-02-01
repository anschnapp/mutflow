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
class ArithmeticOperator : MutationOperator {

    companion object {
        private val SUPPORTED_ORIGINS = setOf(
            IrStatementOrigin.PLUS,
            IrStatementOrigin.MINUS,
            IrStatementOrigin.MUL,
            IrStatementOrigin.DIV,
            IrStatementOrigin.PERC  // modulo (%)
        )
    }

    override fun matches(call: IrCall): Boolean {
        return call.origin in SUPPORTED_ORIGINS
    }

    override fun originalDescription(call: IrCall): String {
        return when (call.origin) {
            IrStatementOrigin.PLUS -> "+"
            IrStatementOrigin.MINUS -> "-"
            IrStatementOrigin.MUL -> "*"
            IrStatementOrigin.DIV -> "/"
            IrStatementOrigin.PERC -> "%"
            else -> "?"
        }
    }

    override fun variants(call: IrCall, context: MutationContext): List<MutationOperator.Variant> {
        val left = call.arguments[0] ?: return emptyList()
        val right = call.arguments[1] ?: return emptyList()

        val leftType = left.type
        val rightType = right.type
        val resultType = call.type

        // For arithmetic operations, we can look up the replacement function
        // by examining the original call's symbol and finding the equivalent
        // function with a different name but same signature pattern.
        //
        // The original call symbol tells us which overload is being used,
        // so we find the same overload for the replacement operator.
        val originalSymbol = call.symbol
        val originalFunctionName = originalSymbol.owner.name.asString()
        val dispatchReceiver = call.dispatchReceiver

        // Get the class that declares this function
        val declaringClassId = originalSymbol.owner.parent.let { parent ->
            (parent as? org.jetbrains.kotlin.ir.declarations.IrClass)?.classId
        } ?: return emptyList()

        // Find the replacement function with same parameter signature
        fun findFunction(name: String): IrSimpleFunctionSymbol? {
            if (name == originalFunctionName) return originalSymbol // same function
            val callableId = CallableId(declaringClassId, Name.identifier(name))
            // For primitives, just get the first function - the type checker
            // already resolved the correct overload for the original call
            return context.pluginContext.referenceFunctions(callableId).firstOrNull()
        }

        val plusFn = findFunction("plus")
        val minusFn = findFunction("minus")
        val timesFn = findFunction("times")
        val divFn = findFunction("div")

        return when (call.origin) {
            IrStatementOrigin.PLUS -> {
                // + → -
                listOfNotNull(
                    minusFn?.let { fn ->
                        MutationOperator.Variant("-") {
                            context.builder.irCall(fn).also {
                                it.arguments[0] = left.deepCopyWithSymbols()
                                it.arguments[1] = right.deepCopyWithSymbols()
                            }
                        }
                    }
                )
            }
            IrStatementOrigin.MINUS -> {
                // - → +
                listOfNotNull(
                    plusFn?.let { fn ->
                        MutationOperator.Variant("+") {
                            context.builder.irCall(fn).also {
                                it.arguments[0] = left.deepCopyWithSymbols()
                                it.arguments[1] = right.deepCopyWithSymbols()
                            }
                        }
                    }
                )
            }
            IrStatementOrigin.MUL -> {
                // * → / with safe division to avoid div-by-zero
                listOfNotNull(
                    divFn?.let { fn ->
                        MutationOperator.Variant("/") {
                            createSafeDivision(left, right, fn, resultType, context)
                        }
                    }
                )
            }
            IrStatementOrigin.DIV -> {
                // / → *
                listOfNotNull(
                    timesFn?.let { fn ->
                        MutationOperator.Variant("*") {
                            context.builder.irCall(fn).also {
                                it.arguments[0] = left.deepCopyWithSymbols()
                                it.arguments[1] = right.deepCopyWithSymbols()
                            }
                        }
                    }
                )
            }
            IrStatementOrigin.PERC -> {
                // % → /
                listOfNotNull(
                    divFn?.let { fn ->
                        MutationOperator.Variant("/") {
                            context.builder.irCall(fn).also {
                                it.arguments[0] = left.deepCopyWithSymbols()
                                it.arguments[1] = right.deepCopyWithSymbols()
                            }
                        }
                    }
                )
            }
            else -> emptyList()
        }
    }

    /**
     * Creates a safe division expression that avoids division by zero.
     *
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
        left: IrExpression,
        right: IrExpression,
        divFn: IrSimpleFunctionSymbol,
        resultType: IrType,
        context: MutationContext
    ): IrExpression {
        val builder = context.builder

        return builder.irBlock(resultType = resultType) {
            // Evaluate left and right once, store in temporaries
            val leftVar = irTemporary(left.deepCopyWithSymbols(), nameHint = "left")
            val rightVar = irTemporary(right.deepCopyWithSymbols(), nameHint = "right")

            val zero = createZeroConstant(resultType)
            val one = createOneConstant(resultType)

            if (zero == null || one == null) {
                // Fallback: just do the division (shouldn't happen for numeric types)
                +builder.irCall(divFn).also {
                    it.arguments[0] = irGet(leftVar)
                    it.arguments[1] = irGet(rightVar)
                }
            } else {
                +IrWhenImpl(
                    startOffset = left.startOffset,
                    endOffset = right.endOffset,
                    type = resultType,
                    origin = null
                ).apply {
                    // Branch 1: b != 0 -> a / b
                    branches += IrBranchImpl(
                        startOffset = left.startOffset,
                        endOffset = right.endOffset,
                        condition = builder.irNotEquals(irGet(rightVar), zero.deepCopyWithSymbols()),
                        result = builder.irCall(divFn).also {
                            it.arguments[0] = irGet(leftVar)
                            it.arguments[1] = irGet(rightVar)
                        }
                    )
                    // Branch 2: a != 0 -> b / a (b is 0, so 0 / a = 0)
                    branches += IrBranchImpl(
                        startOffset = left.startOffset,
                        endOffset = right.endOffset,
                        condition = builder.irNotEquals(irGet(leftVar), zero.deepCopyWithSymbols()),
                        result = builder.irCall(divFn).also {
                            it.arguments[0] = irGet(rightVar)
                            it.arguments[1] = irGet(leftVar)
                        }
                    )
                    // Else: both are 0 -> return 1
                    branches += IrElseBranchImpl(
                        startOffset = left.startOffset,
                        endOffset = right.endOffset,
                        condition = builder.irTrue(),
                        result = one.deepCopyWithSymbols()
                    )
                }
            }
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
