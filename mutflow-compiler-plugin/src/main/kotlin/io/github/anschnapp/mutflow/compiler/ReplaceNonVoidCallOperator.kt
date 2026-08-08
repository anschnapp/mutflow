package io.github.anschnapp.mutflow.compiler

import org.jetbrains.kotlin.ir.expressions.IrCall
import org.jetbrains.kotlin.ir.expressions.IrConst
import org.jetbrains.kotlin.ir.expressions.IrExpression
import org.jetbrains.kotlin.ir.expressions.impl.IrConstImpl
import org.jetbrains.kotlin.ir.symbols.UnsafeDuringIrConstructionAPI
import org.jetbrains.kotlin.ir.types.*
import org.jetbrains.kotlin.ir.util.deepCopyWithSymbols

/**
 * Mutation operator that replaces a non-void method call with the default value
 * of its return type.
 *
 * Scoped to regular method calls (`origin == null`) so operator calls
 * (`a + b`, `a > b`, ...) are left to their dedicated operators. The variant
 * replaces the whole call with a default constant:
 * - numeric → `0`
 * - `Char` → `'a'`
 * - `String` → `""`
 * - object → `null`
 *
 * Mirrors pitest's `NON_VOID_METHOD_CALLS` and Mull's
 * `cxx_replace_scalar_call`.
 */
@OptIn(UnsafeDuringIrConstructionAPI::class)
class ReplaceNonVoidCallOperator : MutationOperator {

    override val descriptor = MutatorDescriptor(
        id = "NON_VOID_CALL",
        name = "ReplaceNonVoidCall",
        description = "Replace non-void call with default value",
        group = MutatorGroup.CALL,
        status = MutatorStatus.STABLE
    )

    override fun matches(call: IrCall): Boolean {
        // Only regular method calls (operator calls have non-null origins).
        if (call.origin != null) return false

        val returnType = call.type
        // Skip void and boolean returns (boolean handled by other operators).
        if (returnType.isUnit() || returnType.isBoolean()) return false

        return true
    }

    override fun originalDescription(call: IrCall): String =
        call.symbol.owner.name.asString()

    override fun variants(call: IrCall, context: MutationContext): List<MutationOperator.Variant> {
        val defaultValue = defaultForType(call.type, call.startOffset, call.endOffset)
            ?: return emptyList()

        return listOf(
            MutationOperator.Variant(defaultValue.description()) {
                defaultValue.deepCopyWithSymbols()
            }
        )
    }

    private fun IrExpression.description(): String = when (this) {
        is IrConst -> value.toString()
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
            type.isString() -> IrConstImpl.string(startOffset, endOffset, type, "")
            else -> IrConstImpl.constNull(startOffset, endOffset, type.makeNullable())
        }
    }
}
