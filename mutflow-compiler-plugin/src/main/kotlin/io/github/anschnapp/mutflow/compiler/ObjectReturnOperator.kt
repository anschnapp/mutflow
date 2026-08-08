package io.github.anschnapp.mutflow.compiler

import org.jetbrains.kotlin.ir.UNDEFINED_OFFSET
import org.jetbrains.kotlin.ir.declarations.IrFunction
import org.jetbrains.kotlin.ir.expressions.IrConst
import org.jetbrains.kotlin.ir.expressions.IrReturn
import org.jetbrains.kotlin.ir.expressions.impl.IrConstImpl
import org.jetbrains.kotlin.ir.symbols.UnsafeDuringIrConstructionAPI
import org.jetbrains.kotlin.ir.types.isNullable
import org.jetbrains.kotlin.ir.types.isPrimitiveType
import org.jetbrains.kotlin.ir.types.makeNullable

/**
 * Mutation operator for object return statements.
 *
 * Replaces the return value of a non-nullable object type (String, List, custom
 * classes, ...) with `null` to detect tests that don't verify the actual
 * returned object. Mirrors pitest's `RETURNS` mutator (object → null).
 *
 * Example:
 * ```
 * // Original
 * fun name(): String = user.name
 *
 * // Variant: return null
 * ```
 *
 * Skips primitive/boolean/char returns (handled by [PrimitiveReturnOperator] /
 * [BooleanReturnOperator]), nullable returns (handled by
 * [NullableReturnOperator]), and returns that are already null.
 */
@OptIn(UnsafeDuringIrConstructionAPI::class)
class ObjectReturnOperator : ReturnMutationOperator {

    override val descriptor = MutatorDescriptor(
        id = "RETURN_OBJECT",
        name = "ObjectReturn",
        description = "Replace object return with null",
        group = MutatorGroup.RETURN,
        status = MutatorStatus.STABLE
    )

    override fun matches(ret: IrReturn): Boolean {
        val value = ret.value

        // Skip synthetic returns (expression-bodied functions get synthetic IrReturn nodes)
        if (ret.startOffset == UNDEFINED_OFFSET || ret.startOffset < 0) return false
        if (ret.startOffset == ret.endOffset) return false

        // Get the function's return type (not the expression type, which may differ)
        val returnTarget = ret.returnTargetSymbol.owner
        val functionReturnType = when (returnTarget) {
            is IrFunction -> returnTarget.returnType
            else -> value.type
        }

        // Must be a non-nullable object type
        if (functionReturnType.isNullable()) return false
        if (functionReturnType.isPrimitiveType()) return false

        // Skip if already returning null (mutating null to null is pointless)
        if (value is IrConst && value.value == null) return false

        return true
    }

    override fun originalDescription(ret: IrReturn): String = "return ..."

    override fun variants(ret: IrReturn, context: MutationContext): List<MutationOperator.Variant> {
        val value = ret.value

        // The null constant must have a nullable type (e.g. String? for a String return).
        val returnTarget = ret.returnTargetSymbol.owner
        val functionReturnType = when (returnTarget) {
            is IrFunction -> returnTarget.returnType
            else -> value.type
        }
        val nullableType = functionReturnType.makeNullable()

        return listOf(
            MutationOperator.Variant("null") {
                IrConstImpl.constNull(
                    value.startOffset,
                    value.endOffset,
                    nullableType
                )
            }
        )
    }
}
