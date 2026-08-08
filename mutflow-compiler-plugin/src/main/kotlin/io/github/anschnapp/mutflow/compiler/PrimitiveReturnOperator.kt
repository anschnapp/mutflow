package io.github.anschnapp.mutflow.compiler

import org.jetbrains.kotlin.ir.UNDEFINED_OFFSET
import org.jetbrains.kotlin.ir.expressions.IrConst
import org.jetbrains.kotlin.ir.expressions.IrReturn
import org.jetbrains.kotlin.ir.expressions.impl.IrConstImpl
import org.jetbrains.kotlin.ir.types.*

/**
 * Mutation operator for primitive (numeric) return statements.
 *
 * Replaces the return value with `0` to detect tests that don't verify the
 * actual numeric result. Mirrors pitest's `PRIMITIVE_RETURNS` mutator.
 *
 * Example:
 * ```
 * // Original
 * fun balance(): Int = account.balance
 *
 * // Variant: return 0
 * ```
 *
 * Skips returns that are already a constant (mutating `return 0` to `return 0`
 * is pointless) and boolean returns (handled by [BooleanReturnOperator]).
 */
class PrimitiveReturnOperator : ReturnMutationOperator {

    override val descriptor = MutatorDescriptor(
        id = "RETURN_PRIMITIVE",
        name = "PrimitiveReturn",
        description = "Replace numeric return with 0",
        group = MutatorGroup.RETURN,
        status = MutatorStatus.STABLE
    )

    override fun matches(ret: IrReturn): Boolean {
        val value = ret.value

        // Skip synthetic returns (expression-bodied functions get synthetic IrReturn nodes)
        if (ret.startOffset == UNDEFINED_OFFSET || ret.startOffset < 0) return false
        if (ret.startOffset == ret.endOffset) return false
        // Skip returns that are already a constant
        if (value is IrConst) return false

        return value.type.isInt() || value.type.isLong() || value.type.isFloat() ||
            value.type.isDouble() || value.type.isShort() || value.type.isByte()
    }

    override fun originalDescription(ret: IrReturn): String = "return ..."

    override fun variants(ret: IrReturn, context: MutationContext): List<MutationOperator.Variant> {
        val value = ret.value
        val type = value.type
        return listOf(
            MutationOperator.Variant("0") {
                createZeroConstant(type, value.startOffset, value.endOffset)
            }
        )
    }

    private fun createZeroConstant(type: IrType, startOffset: Int, endOffset: Int): IrConstImpl {
        return when {
            type.isInt() -> IrConstImpl.int(startOffset, endOffset, type, 0)
            type.isLong() -> IrConstImpl.long(startOffset, endOffset, type, 0L)
            type.isFloat() -> IrConstImpl.float(startOffset, endOffset, type, 0.0f)
            type.isDouble() -> IrConstImpl.double(startOffset, endOffset, type, 0.0)
            type.isShort() -> IrConstImpl.short(startOffset, endOffset, type, 0)
            type.isByte() -> IrConstImpl.byte(startOffset, endOffset, type, 0)
            else -> error("Unsupported primitive return type: $type")
        }
    }
}
