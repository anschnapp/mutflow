package io.github.anschnapp.mutflow.compiler

import org.jetbrains.kotlin.ir.UNDEFINED_OFFSET
import org.jetbrains.kotlin.ir.declarations.IrFunction
import org.jetbrains.kotlin.ir.expressions.IrConst
import org.jetbrains.kotlin.ir.expressions.IrReturn
import org.jetbrains.kotlin.ir.expressions.impl.IrConstImpl
import org.jetbrains.kotlin.ir.symbols.UnsafeDuringIrConstructionAPI
import org.jetbrains.kotlin.ir.types.isNullable

/**
 * Mutation operator for nullable return statements.
 *
 * Replaces the return value with null to detect tests that don't properly
 * verify actual return values (only checking for non-null).
 *
 * Example:
 * ```
 * // Original
 * fun findUser(id: Int): User? {
 *     return database.query(id)
 * }
 *
 * // Variant: return null
 * ```
 *
 * If a test passes when the return is mutated to null, it means the test
 * either doesn't check the returned value properly, or the caller doesn't
 * handle the null case correctly.
 *
 * Note: This operator only matches explicit return statements from source code,
 * not synthetic returns generated for expression-bodied functions. It also
 * skips returns that are already null (mutating null to null is pointless).
 */
@OptIn(UnsafeDuringIrConstructionAPI::class)
class NullableReturnOperator : ReturnMutationOperator {

    override fun matches(ret: IrReturn): Boolean {
        val value = ret.value

        // Skip synthetic returns (expression-bodied functions get synthetic IrReturn nodes)
        // These can be detected by:
        // 1. UNDEFINED_OFFSET (-1) or negative offsets
        // 2. Zero-width span (startOffset == endOffset) - synthetic returns don't span the "return" keyword
        if (ret.startOffset == UNDEFINED_OFFSET || ret.startOffset < 0) {
            return false
        }
        if (ret.startOffset == ret.endOffset) {
            // Zero-width return is synthetic (from expression-bodied function)
            return false
        }

        // Get the function's return type (not the expression type, which may be non-null)
        // For example: fun foo(): Int? { return x } - x has type Int, but function returns Int?
        val returnTarget = ret.returnTargetSymbol.owner
        val functionReturnType = when (returnTarget) {
            is IrFunction -> returnTarget.returnType
            else -> value.type
        }

        // Must return to a function with nullable return type
        if (!functionReturnType.isNullable()) {
            return false
        }

        // Skip if already returning null (mutating null to null is pointless)
        if (value is IrConst && value.value == null) {
            return false
        }

        return true
    }

    override fun originalDescription(ret: IrReturn): String {
        return "return ..."
    }

    @OptIn(UnsafeDuringIrConstructionAPI::class)
    override fun variants(ret: IrReturn, context: MutationContext): List<MutationOperator.Variant> {
        val value = ret.value

        // Get the function's return type for the null constant
        val returnTarget = ret.returnTargetSymbol.owner
        val functionReturnType = when (returnTarget) {
            is IrFunction -> returnTarget.returnType
            else -> value.type
        }

        return listOf(
            MutationOperator.Variant("null") {
                IrConstImpl.constNull(
                    value.startOffset,
                    value.endOffset,
                    functionReturnType
                )
            }
        )
    }
}
