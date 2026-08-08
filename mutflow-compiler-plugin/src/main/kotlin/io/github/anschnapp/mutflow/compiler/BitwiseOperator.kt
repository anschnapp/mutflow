package io.github.anschnapp.mutflow.compiler

import org.jetbrains.kotlin.ir.builders.irCall
import org.jetbrains.kotlin.ir.expressions.IrCall
import org.jetbrains.kotlin.ir.expressions.IrExpression
import org.jetbrains.kotlin.ir.symbols.IrSimpleFunctionSymbol
import org.jetbrains.kotlin.ir.symbols.UnsafeDuringIrConstructionAPI
import org.jetbrains.kotlin.ir.types.*
import org.jetbrains.kotlin.ir.util.classId
import org.jetbrains.kotlin.ir.util.deepCopyWithSymbols
import org.jetbrains.kotlin.name.CallableId
import org.jetbrains.kotlin.name.Name

/**
 * Mutation operator for bitwise operations on integer types.
 *
 * Mirrors pitest's `MATH` bitwise substitutions and Mull's `cxx_bitwise` group:
 * - `and` ↔ `or`
 * - `xor` → `and`
 * - `shl` ↔ `shr`
 * - `ushr` → `shl`
 *
 * In Kotlin IR these are infix function calls (`Int.and`, `Long.shl`, ...) with
 * no dedicated [org.jetbrains.kotlin.ir.expressions.IrStatementOrigin], so they
 * are matched by function name and receiver type. Boolean `and`/`or`/`xor`
 * (non-short-circuit logical operators) are deliberately excluded by requiring
 * an integer receiver type.
 */
@OptIn(UnsafeDuringIrConstructionAPI::class)
class BitwiseOperator : MutationOperator {

    override val descriptor = MutatorDescriptor(
        id = "BITWISE_SWAP",
        name = "BitwiseSwap",
        description = "Swap and↔or, xor→and, shl↔shr, ushr→shl",
        group = MutatorGroup.ARITHMETIC,
        status = MutatorStatus.STABLE
    )

    private companion object {
        private val BITWISE_NAMES = setOf("and", "or", "xor", "shl", "shr", "ushr")

        private val REPLACEMENTS = mapOf(
            "and" to "or",
            "or" to "and",
            "xor" to "and",
            "shl" to "shr",
            "shr" to "shl",
            "ushr" to "shl"
        )

        // Additional variants for a given name, produced alongside REPLACEMENTS.
        // xor → or (Mull cxx_xor_to_or) in addition to xor → and (pitest).
        private val ADDITIONAL_REPLACEMENTS = mapOf(
            "xor" to listOf("or")
        )
    }

    override fun matches(call: IrCall): Boolean {
        val name = call.symbol.owner.name.asString()
        if (name !in BITWISE_NAMES) return false
        // Only integer bitwise ops — exclude Boolean and/or/xor (logical, non-short-circuit)
        val receiverType = call.dispatchReceiver?.type ?: return false
        return receiverType.isInt() || receiverType.isLong() || receiverType.isShort() ||
            receiverType.isByte() || receiverType.isUInt() || receiverType.isULong()
    }

    override fun originalDescription(call: IrCall): String = call.symbol.owner.name.asString()

    override fun variants(call: IrCall, context: MutationContext): List<MutationOperator.Variant> {
        val name = call.symbol.owner.name.asString()
        val replacementName = REPLACEMENTS[name] ?: return emptyList()

        val left = call.dispatchReceiver ?: return emptyList()
        val right = call.arguments.getOrNull(0) ?: return emptyList()

        val replacementFn = findFunction(call, replacementName, context) ?: return emptyList()

        val variants = mutableListOf(
            MutationOperator.Variant(replacementName) {
                context.builder.irCall(replacementFn).also {
                    it.dispatchReceiver = left.deepCopyWithSymbols()
                    it.arguments[0] = right.deepCopyWithSymbols()
                }
            }
        )

        // Additional variants (e.g. xor → or alongside xor → and).
        for (additional in ADDITIONAL_REPLACEMENTS[name].orEmpty()) {
            val fn = findFunction(call, additional, context) ?: continue
            variants += MutationOperator.Variant(additional) {
                context.builder.irCall(fn).also {
                    it.dispatchReceiver = left.deepCopyWithSymbols()
                    it.arguments[0] = right.deepCopyWithSymbols()
                }
            }
        }

        return variants
    }

    /**
     * Finds the replacement function with the same signature as the original.
     * Matched by the original call's operand type (mirrors ArithmeticOperator's
     * overload matching): a class like `Int` has a single `and(Int)` overload,
     * but relying on `referenceFunctions(...).firstOrNull()` alone is fragile if
     * that ever changes, so pick the overload whose value parameter type equals
     * the original's.
     */
    private fun findFunction(
        original: IrCall,
        replacementName: String,
        context: MutationContext
    ): IrSimpleFunctionSymbol? {
        val declaringClassId = (original.symbol.owner.parent as? org.jetbrains.kotlin.ir.declarations.IrClass)?.classId
            ?: return null
        val callableId = CallableId(declaringClassId, Name.identifier(replacementName))
        val candidates = context.pluginContext.referenceFunctions(callableId)
        val originalParamType = original.symbol.owner.parameters.getOrNull(1)?.type
        return if (originalParamType != null) {
            candidates.firstOrNull { fn -> fn.owner.parameters.getOrNull(1)?.type == originalParamType }
                ?: candidates.firstOrNull()
        } else {
            candidates.firstOrNull()
        }
    }
}
