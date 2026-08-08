package io.github.anschnapp.mutflow.compiler

import org.jetbrains.kotlin.ir.expressions.IrConst
import org.jetbrains.kotlin.ir.expressions.IrExpression
import org.jetbrains.kotlin.ir.expressions.impl.IrConstImpl
import org.jetbrains.kotlin.ir.symbols.UnsafeDuringIrConstructionAPI
import org.jetbrains.kotlin.ir.types.*

/**
 * Swaps the right-hand side of an assignment for a default constant.
 *
 * Roughly Mull's `cxx_assign_const`/`cxx_init_const`: `var a = x` becomes
 * `var a = 0`, `field = s` becomes `field = ""`, etc. Catches tests that don't
 * actually check what got stored. The constant follows the assigned type
 * (numeric 0, `'a'`, `""`, `false`, or `null`). Assignments that already hold
 * a constant are skipped — no point turning `0` into `0`.
 */
@OptIn(UnsafeDuringIrConstructionAPI::class)
class AssignConstOperator : AssignmentMutationOperator {

    override val descriptor = MutatorDescriptor(
        id = "ASSIGN_CONST",
        name = "AssignConst",
        description = "Replace assigned value with default constant",
        group = MutatorGroup.ARITHMETIC,
        status = MutatorStatus.STABLE
    )

    override fun matches(targetType: IrType, assignedValue: IrExpression): Boolean =
        assignedValue !is IrConst

    override fun originalDescription(assignedValue: IrExpression): String = "="

    override fun variants(
        targetType: IrType,
        assignedValue: IrExpression,
        context: MutationContext
    ): List<MutationOperator.Variant> {
        val defaultValue = defaultForType(targetType, assignedValue.startOffset, assignedValue.endOffset)
            ?: return emptyList()

        return listOf(
            MutationOperator.Variant(describeValue(defaultValue)) {
                defaultValue
            }
        )
    }

    private fun describeValue(value: IrExpression): String = when (value) {
        is IrConst -> value.value.toString()
        else -> "null"
    }

    private fun defaultForType(type: IrType, startOffset: Int, endOffset: Int): IrExpression? {
        return when {
            type.isInt() -> IrConstImpl.int(startOffset, endOffset, type, 0)
            type.isLong() -> IrConstImpl.long(startOffset, endOffset, type, 0L)
            type.isShort() -> IrConstImpl.short(startOffset, endOffset, type, 0)
            type.isByte() -> IrConstImpl.byte(startOffset, endOffset, type, 0)
            type.isFloat() -> IrConstImpl.float(startOffset, endOffset, type, 0.0f)
            type.isDouble() -> IrConstImpl.double(startOffset, endOffset, type, 0.0)
            type.isChar() -> IrConstImpl.char(startOffset, endOffset, type, 'a')
            type.isBoolean() -> IrConstImpl.boolean(startOffset, endOffset, type, false)
            type.isString() -> IrConstImpl.string(startOffset, endOffset, type, "")
            else -> IrConstImpl.constNull(startOffset, endOffset, type.makeNullable())
        }
    }
}
