package io.github.anschnapp.mutflow.compiler

import org.jetbrains.kotlin.ir.UNDEFINED_OFFSET
import org.jetbrains.kotlin.ir.builders.*
import org.jetbrains.kotlin.ir.declarations.IrClass
import org.jetbrains.kotlin.ir.declarations.IrParameterKind
import org.jetbrains.kotlin.ir.declarations.IrSimpleFunction
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
     *
     * The two operands are the last two arguments of the call. An operator declared as an
     * extension inside a class or object (`with(Ops) { a + b }`) takes its dispatch receiver
     * before them; it is hoisted with them, in evaluation order, and passed on unchanged.
     */
    override fun mutation(node: IrCall, context: MutationContext): Mutation? {
        val arguments = node.arguments.map { it ?: return null }
        if (arguments.size < 2) return null
        val left = arguments.size - 2
        val right = arguments.size - 1
        val resultType = node.type

        val originalSymbol = node.symbol
        val originalFunction = originalSymbol.owner

        // Get the class that declares this function
        val declaringClassId = (originalFunction.parent as? IrClass)?.classId ?: return null

        // The replacement takes the same receivers and parameters as the original and returns
        // the same type: the declaring class can have several operators of that name, for other
        // operand types (`Int.plus(Byte)`, or an object's `A.plus(A)` beside its `B.plus(B)`).
        // Without such an overload, a call on the class itself keeps the first operator of that
        // name, as it always has (`Char - Char` has no `Char + Char` and becomes `Char.plus(Int)`),
        // while an extension operator is not mutated: another receiver would not compile.
        fun findFunction(name: String): IrSimpleFunctionSymbol? {
            if (name == originalFunction.name.asString()) return originalSymbol // same function
            val callableId = CallableId(declaringClassId, Name.identifier(name))
            val candidates = context.pluginContext.referenceFunctions(callableId)
            return candidates.firstOrNull { it.owner.hasSameSignatureAs(originalFunction) }
                ?: candidates.firstOrNull().takeUnless { originalFunction.hasExtensionReceiver() }
        }

        fun swapTo(description: String, fn: IrSimpleFunctionSymbol) =
            Mutation.OverOperands.Variant(description) { operands ->
                callOver(fn, operands, left, right, context)
            }

        val variant = when (node.origin) {
            // + → -
            IrStatementOrigin.PLUS -> findFunction("minus")?.let { swapTo("-", it) }
            // - → +
            IrStatementOrigin.MINUS -> findFunction("plus")?.let { swapTo("+", it) }
            // * → / with safe division to avoid div-by-zero
            IrStatementOrigin.MUL -> findFunction("div")?.let { fn ->
                Mutation.OverOperands.Variant("/") { operands ->
                    createSafeDivision(node, operands, left, right, fn, resultType, context)
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
            operands = arguments,
            original = { operands ->
                arguments.indices.forEach { node.arguments[it] = operands[it] }
                node
            },
            variants = listOf(variant)
        )
    }

    private fun IrSimpleFunction.hasSameSignatureAs(other: IrSimpleFunction): Boolean =
        returnType == other.returnType &&
            parameters.size == other.parameters.size &&
            parameters.zip(other.parameters).all { (a, b) -> a.kind == b.kind && a.type == b.type }

    private fun IrSimpleFunction.hasExtensionReceiver(): Boolean =
        parameters.any { it.kind == IrParameterKind.ExtensionReceiver }

    /**
     * Calls [fn] over the hoisted [operands], the ones at [first] and [second] as its two
     * operands. The operands before the last two (a dispatch receiver) are passed on first.
     */
    private fun callOver(
        fn: IrSimpleFunctionSymbol,
        operands: Operands,
        first: Int,
        second: Int,
        context: MutationContext
    ): IrExpression = context.builder.irCall(fn).also { call ->
        val receivers = operands.size - 2
        for (index in 0 until receivers) call.arguments[index] = operands[index]
        call.arguments[receivers] = operands[first]
        call.arguments[receivers + 1] = operands[second]
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
        left: Int,
        right: Int,
        divFn: IrSimpleFunctionSymbol,
        resultType: IrType,
        context: MutationContext
    ): IrExpression {
        val builder = context.builder

        val zero = createZeroConstant(resultType)
        val one = createOneConstant(resultType)

        fun divide(dividend: Int, divisor: Int) = callOver(divFn, operands, dividend, divisor, context)

        if (zero == null || one == null) {
            // Fallback: just do the division (shouldn't happen for numeric types)
            return divide(left, right)
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
                condition = builder.irNotEquals(operands[right], zero.deepCopyWithSymbols()),
                result = divide(left, right)
            )
            // Branch 2: a != 0 -> b / a (b is 0, so 0 / a = 0)
            branches += IrBranchImpl(
                startOffset = original.startOffset,
                endOffset = original.endOffset,
                condition = builder.irNotEquals(operands[left], zero.deepCopyWithSymbols()),
                result = divide(right, left)
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
